package com.hippo.ehviewer.site

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Kemono / Pawchive 资源下载：附件下载 -> 自动解压 -> 导入本地。
 *
 * 之所以自己走 OkHttp 而不是复用 EhViewer 的下载管理器，是因为后者是围绕 E-Hentai 的页码模型
 * 设计的，塞不进任意 URL 列表。这里只负责“把文件落地”，落地之后交给 [SiteImportHelper] 接手，
 * 最终在阅读器里的体验和正常下载完全一致。
 *
 * 进度有两层：[State.total]/[State.completed] 是文件级的，[State.bytesDone]/[State.bytesTotal]
 * 是当前文件内的字节级。只报文件级的话，一个几百 MB 的压缩包下载期间进度条会一直不动。
 */
class SiteDownloadService : Service() {

    /** 一次任务的整体进度，UI 通过轮询读取。所有字段跨线程写，必须 volatile。 */
    class State {
        @Volatile
        var total: Int = 0

        @Volatile
        var completed: Int = 0

        /** 当前帖子的标题 */
        @Volatile
        var currentName: String = ""

        /** 当前正在下载的文件名 */
        @Volatile
        var currentFile: String = ""

        /** 当前文件已落地的字节数（含断点续传的已有部分） */
        @Volatile
        var bytesDone: Long = 0L

        /** 当前文件的总字节数；0 表示服务端没给 Content-Length，只能转圈 */
        @Volatile
        var bytesTotal: Long = 0L

        /** 服务真的跑起来了吗。界面靠它区分「还没开始」和「正在忙」。 */
        @Volatile
        var started: Boolean = false

        @Volatile
        var done: Boolean = false

        @Volatile
        var cancelRequested: Boolean = false

        @Volatile
        var imported: Int = 0

        @Volatile
        var failed: Int = 0

        @Volatile
        var lastError: String = ""

        fun isRunning(): Boolean = started && !done

        /**
         * 千分比进度（0..1000）。
         *
         * 用千分比而不是百分比，是因为通知栏的 setProgress 吃整数；
         * 百分比粒度下大文件每跳一格要 2.5MB，看起来像卡住了。
         */
        fun permille(): Int {
            if (total <= 0) return 0
            val part = if (bytesTotal > 0) {
                (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val overall = (completed + part) / total
            return (overall * 1000).toInt().coerceIn(0, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var lastNotifyAt: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_CANCEL == intent.action) {
            cancel()
            return START_NOT_STICKY
        }
        // 被系统重启时 intent 为 null，此时任务列表早就没了，别挂着空服务
        if (intent == null) {
            if (!STATE.isRunning()) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Android 12+ 对后台启动前台服务有限制，个别机型/时机下会抛异常。
        // 通知栏只是锦上添花，不能因为挂不上通知就把整个 App 带崩。
        try {
            ensureChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground 失败", e)
        }

        // 重复 startService 会再次进到这里；已有线程在跑就别再起一个，
        // 否则两个线程会同时往同一个 STATE 里写，进度直接乱掉
        if (worker?.isAlive != true) {
            worker = Thread { runDownload() }.apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        STATE.cancelRequested = true
    }

    @Suppress("DEPRECATION")
    private fun runDownload() {
        try {
            runDownloadInner()
        } catch (t: Throwable) {
            // 后台线程里的未捕获 Throwable 会直接被系统当成崩溃，整个进程被杀。
            // 下载失败顶多是没有文件，绝不能连累 App 本身。
            Log.e(TAG, "下载任务异常终止", t)
            STATE.lastError = t.message ?: t.javaClass.simpleName
        } finally {
            STATE.done = true
            STATE.started = true
            STATE.currentName = ""
            STATE.currentFile = ""
            STATE.bytesDone = 0L
            STATE.bytesTotal = 0L
            try {
                publish(force = true)
                // detach 而不是 remove：保留下载结果，用户点一下还能回来看到导入了多少
                stopForeground(false)
            } catch (t: Throwable) {
                Log.w(TAG, "收尾失败", t)
            }
            markIdle()
            stopSelf()
        }
    }

    private fun runDownloadInner() {
        val tasks = synchronized(pendingTasks) { pendingTasks.toList() }
        STATE.total = tasks.sumOf { it.post.attachments.size }
        STATE.completed = 0
        STATE.imported = 0
        STATE.failed = 0
        STATE.started = true
        publish(force = true)

        for (task in tasks) {
            if (STATE.cancelRequested) break
            STATE.currentName = task.post.title ?: task.post.id
            publish(force = true)
            try {
                downloadPost(task)
            } catch (t: Throwable) {
                // 单条帖子失败（含 OOM、解压库异常）只记一次失败，继续下一条
                Log.e(TAG, "帖子下载失败: " + task.post.id, t)
                STATE.failed++
                STATE.lastError = t.message ?: t.javaClass.simpleName
            }
            publish(force = true)
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
        if (attachments.isEmpty()) {
            STATE.failed++
            STATE.lastError = getString(R.string.site_download_no_attachment)
            return
        }
        val downloaded = ArrayList<File>()
        for (attachment in attachments) {
            if (STATE.cancelRequested) break
            val url = attachment.preferredUrl(site) ?: continue
            val target = File(dir, sanitize(attachment.name))
            STATE.currentFile = target.name
            STATE.bytesDone = 0L
            STATE.bytesTotal = 0L
            publish(force = true)
            if (downloadFile(url, target)) {
                downloaded.add(target)
            }
            STATE.currentFile = ""
            STATE.bytesDone = 0L
            STATE.bytesTotal = 0L
            STATE.completed++
            publish(force = true)
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
            STATE.currentFile = archives[0].name
            publish(force = true)
            for (archive in archives) {
                val out = File(dir, archive.nameWithoutExtension + "_extracted")
                val extracted = SiteExtractor.extract(archive, out, passwords)
                if (extracted != null) {
                    imageRoot = extracted
                    break
                }
            }
            STATE.currentFile = ""
            publish(force = true)
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
        STATE.bytesDone = existing
        STATE.bytesTotal = 0L
        return try {
            Site.newResumableCall(url, existing).execute().use { response ->
                if (response.code() == 416) {
                    // 服务端不接受这个 Range，重头来过
                    target.delete()
                    STATE.bytesDone = 0L
                    return false
                }
                if (!response.isSuccessful) {
                    STATE.lastError = "HTTP " + response.code()
                    return false
                }
                val body = response.body() ?: return false
                val length = body.contentLength()
                // 断点续传时服务端只给剩余长度，总量要加上已有部分
                STATE.bytesTotal = if (length > 0) existing + length else 0L
                publish(force = true)
                val append = existing > 0 && response.code() == 206
                FileOutputStream(target, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!STATE.cancelRequested) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            STATE.bytesDone += read
                            // 字节级进度要高频刷新，但 notify 不能跟着每个 buffer 走
                            publish(force = false)
                        }
                    }
                }
                true
            }
        } catch (e: IOException) {
            STATE.lastError = e.message ?: "IOException"
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
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /**
     * @param force true 表示状态跃变（换文件、文件完成、任务结束），必须立刻推给通知栏；
     *              false 用于字节级刷新，按 [NOTIFY_INTERVAL_MS] 节流，否则 notify 调用会把主线程压垮。
     */
    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "更新通知失败", t)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val state = STATE
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(!state.done)
            .setAutoCancel(state.done)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 没有 contentIntent 的话点通知栏毫无反应，用户根本不知道怎么回到下载页
        contentIntent()?.let { builder.setContentIntent(it) }

        if (state.done) {
            builder.setContentTitle(getString(R.string.site_download_title_done))
            builder.setContentText(progressLine(this))
            if (state.failed > 0 && state.lastError.isNotEmpty()) {
                builder.setSubText(state.lastError)
            }
            builder.setProgress(0, 0, false)
        } else {
            // 标题放当前帖子，正文放进度数字，副标题放文件名——通知栏折叠时也能一眼看到在干什么
            builder.setContentTitle(
                if (state.currentName.isNotEmpty()) {
                    state.currentName
                } else {
                    getString(R.string.site_download_title)
                }
            )
            builder.setContentText(progressLine(this))
            if (state.currentFile.isNotEmpty()) {
                builder.setSubText(state.currentFile)
            }
            if (state.total > 0) {
                builder.setProgress(1000, state.permille(), false)
            } else {
                // 还不知道要下多少东西，先转圈，别显示一条不动的空进度条
                builder.setProgress(0, 0, true)
            }
            cancelIntent()?.let {
                builder.addAction(0, getString(R.string.site_download_cancel_action), it)
            }
        }
        return builder.build()
    }

    /** 点通知回到下载页；带上 resume 标记，页面只显示进度而不是重新联网拉一遍帖子。 */
    private fun contentIntent(): PendingIntent? {
        return try {
            // Service 里启动 Activity 必须带 NEW_TASK
            val intent = SiteDownloadActivity.newResumeIntent(this)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(this, REQUEST_CONTENT, intent, pendingIntentFlags())
        } catch (t: Throwable) {
            Log.w(TAG, "构造通知点击意图失败", t)
            null
        }
    }

    private fun cancelIntent(): PendingIntent? {
        return try {
            val intent = Intent(this, SiteDownloadService::class.java).setAction(ACTION_CANCEL)
            PendingIntent.getService(this, REQUEST_CANCEL, intent, pendingIntentFlags())
        } catch (t: Throwable) {
            Log.w(TAG, "构造取消意图失败", t)
            null
        }
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private class Task(val site: Site, val post: Post)

    companion object {
        private const val TAG = "SiteDownloadService"
        private const val ACTION_CANCEL = "cancel"
        private const val NOTIFICATION_ID = 20261028
        private const val CHANNEL_ID = "site_download"
        private const val MAX_RETRY = 3
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_CANCEL = 2

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

        /** 通知刷新间隔，太密会拖慢下载线程 */
        private const val NOTIFY_INTERVAL_MS = 400L

        private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")

        @Volatile
        var STATE: State = State()
            private set

        private val pendingTasks: ArrayList<Task> = ArrayList()

        /** 防止连点两次「下载所选」起两个任务，两个线程会互相踩 STATE */
        @Volatile
        private var busy = false

        /**
         * 启动下载。
         *
         * @return false 表示已有任务在跑，本次没有启动
         */
        fun start(context: Context, site: Site, creator: Creator, posts: List<Post>): Boolean {
            synchronized(pendingTasks) {
                if (busy) return false
                busy = true
                STATE = State()
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
                STATE.lastError = t.message ?: t.javaClass.simpleName
                STATE.done = true
                busy = false
                return false
            }
            return true
        }

        fun cancel() {
            STATE.cancelRequested = true
        }

        private fun markIdle() {
            busy = false
        }

        /**
         * 一句人能看懂的进度：文件数 + 百分比 + 当前文件的字节进度。
         *
         * 通知栏和界面共用，免得两边算法不一致、显示的数字对不上。
         */
        fun describe(context: Context): String {
            val s = STATE
            if (s.done) return doneText(context, s)
            val line = progressLine(context)
            return if (s.currentFile.isNotEmpty()) line + "\n" + s.currentFile else line
        }

        private fun doneText(context: Context, s: State): String =
            if (s.failed > 0) {
                context.getString(R.string.site_download_done_with_failed, s.imported, s.failed)
            } else {
                context.getString(R.string.site_download_done, s.imported)
            }

        /** 只要进度数字那部分，通知栏折叠态用。 */
        fun progressLine(context: Context): String {
            val s = STATE
            if (s.done) return doneText(context, s)
            if (s.total <= 0) return context.getString(R.string.site_download_preparing)
            val sb = StringBuilder()
            sb.append(
                context.getString(
                    R.string.site_download_notif_progress,
                    s.completed,
                    s.total,
                    s.permille() / 10,
                )
            )
            if (s.bytesTotal > 0) {
                sb.append("  ")
                    .append(formatSize(s.bytesDone))
                    .append(" / ")
                    .append(formatSize(s.bytesTotal))
            }
            return sb.toString()
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }
}
