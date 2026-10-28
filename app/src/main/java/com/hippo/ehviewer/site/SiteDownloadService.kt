package com.hippo.ehviewer.site

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.R
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kemono / Pawchive 资源下载：附件下载 -> 自动解压 -> 导入本地。
 *
 * 之所以自己走 OkHttp 而不是复用 EhViewer 的下载管理器，是因为后者是围绕 E-Hentai 的页码模型
 * 设计的，塞不进任意 URL 列表。这里只负责“把文件落地”，落地之后交给 [SiteImportHelper] 接手，
 * 最终在阅读器里的体验和正常下载完全一致。
 */
class SiteDownloadService : Service() {

    /** 一次任务的整体进度，UI 通过轮询读取。 */
    class State {
        @Volatile
        var total: Int = 0

        @Volatile
        var completed: Int = 0

        @Volatile
        var currentName: String = ""

        @Volatile
        var done: Boolean = false

        @Volatile
        var cancelRequested: Boolean = false

        @Volatile
        var imported: Int = 0

        @Volatile
        var failed: Int = 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_CANCEL == intent.action) {
            cancel()
            return START_NOT_STICKY
        }
        // Android 12+ 对后台启动前台服务有限制，个别机型/时机下会抛异常。
        // 通知栏只是锦上添花，不能因为挂不上通知就把整个 App 带崩。
        try {
            ensureChannel()
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, ""))
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground 失败", e)
        }
        Thread { runDownload() }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        STATE.cancelRequested = true
    }

    private fun runDownload() {
        try {
            runDownloadInner()
        } catch (t: Throwable) {
            // 后台线程里的未捕获 Throwable 会直接被系统当成崩溃，整个进程被杀。
            // 下载失败顶多是没有文件，绝不能连累 App 本身。
            Log.e(TAG, "下载任务异常终止", t)
        } finally {
            STATE.done = true
            STATE.currentName = ""
            try {
                publish(STATE.completed)
                stopForeground(true)
            } catch (t: Throwable) {
                Log.w(TAG, "收尾失败", t)
            }
            stopSelf()
        }
    }

    private fun runDownloadInner() {
        val tasks = pendingTasks.toList()
        STATE.total = tasks.sumOf { it.post.attachments.size }
        STATE.completed = 0
        STATE.imported = 0
        STATE.failed = 0

        for (task in tasks) {
            if (STATE.cancelRequested) break
            STATE.currentName = task.post.title ?: task.post.id
            publish(STATE.completed)
            try {
                downloadPost(task)
            } catch (t: Throwable) {
                // 单条帖子失败（含 OOM、解压库异常）只记一次失败，继续下一条
                Log.e(TAG, "帖子下载失败: " + task.post.id, t)
                STATE.failed++
            }
        }
    }

    private fun downloadPost(task: Task) {
        val site = task.site
        val post = task.post
        val dir = File(filesDir, "site/${site.name}/${post.id}")
        if (!dir.exists() && !dir.mkdirs()) return

        // API 返回的 attachments 只有 path，拼出来的 {base}/data<path> 实测全部 404，
        // 因此换成从帖子页 HTML 提取的 CDN 签名直链（拿不到时由 SiteApi 兜底回退）
        val attachments = SiteApi.fetchDownloadAttachments(site, post)
        val downloaded = ArrayList<File>()
        for (attachment in attachments) {
            if (STATE.cancelRequested) break
            val url = attachment.preferredUrl(site) ?: continue
            val target = File(dir, sanitize(attachment.name))
            if (downloadFile(url, target)) {
                downloaded.add(target)
            }
            STATE.completed++
            publish(STATE.completed)
            // 站点有频率限制，稍微收着点，别把 IP 打进黑名单
            try {
                Thread.sleep(REQUEST_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (STATE.cancelRequested || downloaded.isEmpty()) return

        // 密码三个来源：正文、标题，以及压缩包文件名本身。
        // 后者非常常见，例如「清宵-被染的双修（解压密码Ryx8）.zip」，漏掉就完全解不开。
        val passwords = PasswordExtractor.extract(
            post.content,
            post.title,
            *downloaded.map { it.name }.toTypedArray(),
        )
        val archives = downloaded.filter { it.extension.lowercase() in ARCHIVE_EXTENSIONS }
        var imageRoot: File = dir
        if (archives.isNotEmpty()) {
            for (archive in archives) {
                val out = File(dir, archive.nameWithoutExtension + "_extracted")
                val extracted = SiteExtractor.extract(archive, out, passwords)
                if (extracted != null) {
                    imageRoot = extracted
                    break
                }
            }
        }

        val gid = SiteImportHelper.import(
            this,
            post.title ?: post.id,
            imageRoot,
            SiteImportHelper.URI_PREFIX + site.name + "/" + post.id,
        )
        if (gid > 0) {
            STATE.imported++
        } else {
            STATE.failed++
        }
    }

    /**
     * 带断点续传的文件下载，失败重试 MAX_RETRY 次。
     */
    private fun downloadFile(url: String, target: File): Boolean {
        for (attempt in 1..MAX_RETRY) {
            if (STATE.cancelRequested) return false
            if (attemptDownload(url, target)) return true
            try {
                Thread.sleep(RETRY_DELAY_MS * attempt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return false
    }

    private fun attemptDownload(url: String, target: File): Boolean {
        val existing = if (target.exists()) target.length() else 0L
        return try {
            Site.newResumableCall(url, existing).execute().use { response ->
                if (response.code() == 416) {
                    // 服务端不接受这个 Range，重头来过
                    target.delete()
                    return false
                }
                if (!response.isSuccessful) return false
                val body = response.body() ?: return false
                val append = existing > 0 && response.code() == 206
                FileOutputStream(target, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!STATE.cancelRequested) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun sanitize(name: String): String = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.site_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun publish(completed: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(STATE.total, completed, STATE.currentName))
    }

    private fun buildNotification(total: Int, completed: Int, name: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.site_download_title))
            .setContentText(if (STATE.done) getString(R.string.site_download_done, STATE.imported) else name)
            .setProgress(total.coerceAtLeast(1), completed, false)
            .setOngoing(!STATE.done)
            .build()
    }

    private class Task(val site: Site, val post: Post)

    companion object {
        private const val TAG = "SiteDownloadService"
        private const val ACTION_CANCEL = "cancel"
        private const val NOTIFICATION_ID = 20261028
        private const val CHANNEL_ID = "site_download"
        private const val MAX_RETRY = 3

        /**
         * 两次请求之间的最小间隔。
         *
         * <p>Pawchive 的 CDN 由 DDoS-Guard 保护，拦截页写明限制是
         * “no more than 1 request per second, and 5 active downloads”，超限直接封。
         * 这里取 1200ms 留足余量（实测 400ms 会被判定为滥用）。
         */
        private const val REQUEST_INTERVAL_MS = 1200L
        private const val RETRY_DELAY_MS = 1200L
        private const val BUFFER_SIZE = 32 * 1024

        private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")

        @Volatile
        var STATE: State = State()

        private val pendingTasks: ArrayList<Task> = ArrayList()

        fun start(context: Context, site: Site, creator: Creator, posts: List<Post>) {
            STATE = State()
            synchronized(pendingTasks) {
                pendingTasks.clear()
                posts.forEach { pendingTasks.add(Task(site, it)) }
            }
            val intent = Intent(context, SiteDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // 系统拒绝启动（后台限制 / 服务数超限）时，让用户看到失败而不是闪退
                Log.e(TAG, "启动下载服务失败", t)
            }
        }

        fun cancel() {
            STATE.cancelRequested = true
        }
    }
}
