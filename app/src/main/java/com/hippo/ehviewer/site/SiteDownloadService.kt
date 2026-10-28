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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

        /** 当前下载速度（字节/秒），0 表示还没测出来 */
        @Volatile
        var speed: Long = 0L

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

    /** 分段下载连续失败的次数，超过阈值就整个任务都不用再试 */
    @Volatile
    private var segmentFailures: Int = 0

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
            SiteLog.d(this, "startForeground 失败：${e.javaClass.simpleName}: ${e.message}")
        }
        SiteLog.d(this, "服务启动 startId=$startId")

        // 重复 startService 会再次进到这里；已有线程在跑就别再起一个，
        // 否则两个线程会同时往同一个 STATE 里写，进度直接乱掉
        if (worker?.isAlive != true) {
            worker = Thread { runDownload() }.apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 这里绝对不能置 cancelRequested：系统回收/重建服务是常规操作，
        // 之前这么写会让下载在用户毫不知情的情况下被当成“已取消”结束，
        // 表现就是进度跑到一半连通知带面板一起消失。取消只由用户主动触发。
        Log.i(TAG, "服务销毁，任务状态: done=" + STATE.done + " completed=" + STATE.completed + "/" + STATE.total)
        SiteLog.d(this, "服务销毁：done=${STATE.done} ${STATE.completed}/${STATE.total}")
    }

    @Suppress("DEPRECATION")
    private fun runDownload() {
        try {
            runDownloadInner()
        } catch (t: Throwable) {
            // 后台线程里的未捕获 Throwable 会直接被系统当成崩溃，整个进程被杀。
            // 下载失败顶多是没有文件，绝不能连累 App 本身。
            Log.e(TAG, "下载任务异常终止", t)
            SiteLog.d(this, "任务异常终止：${t.javaClass.name}: ${t.message}")
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
        SiteLog.d(this, "任务开始：${tasks.size} 条帖子，${STATE.total} 个附件")
        publish(force = true)

        for (task in tasks) {
            if (STATE.cancelRequested) break
            STATE.currentName = task.post.title ?: task.post.id
            SiteLog.d(this, "帖子开始：${task.site.name}/${task.post.id} 附件=${task.post.attachments.size} 标题=${task.post.title}")
            publish(force = true)
            val completedBefore = STATE.completed
            try {
                downloadPost(task)
            } catch (t: Throwable) {
                // 单条帖子失败（含 OOM、解压库异常）只记一次失败，继续下一条
                Log.e(TAG, "帖子下载失败: " + task.post.id, t)
                SiteLog.d(this, "帖子异常：${task.post.id} ${t.javaClass.simpleName}: ${t.message}")
                STATE.failed++
                STATE.lastError = t.message ?: t.javaClass.simpleName
            }
            // 中途抛异常 / 提前 return 时，这条帖子后面的附件不会再被处理。
            // 不补齐的话 completed 永远差一截，进度条停在那儿然后突然“完成”。
            val expected = completedBefore + task.post.attachments.size
            if (STATE.completed < expected) {
                STATE.completed = expected
            }
            if (STATE.completed > STATE.total) {
                STATE.total = STATE.completed
            }
            SiteLog.d(this, "帖子结束：${task.post.id} 进度=${STATE.completed}/${STATE.total} 已导入=${STATE.imported} 失败=${STATE.failed}")
            publish(force = true)
        }
        SiteLog.d(this, "任务结束：导入=${STATE.imported} 失败=${STATE.failed} 取消=${STATE.cancelRequested}")
    }

    private fun downloadPost(task: Task) {
        val site = task.site
        val post = task.post
        val dir = File(filesDir, "site/${site.name}/${post.id}")
        if (!dir.exists() && !dir.mkdirs()) return

        // API 返回的 attachments 只有 path，拼出来的 {base}/data<path> 实测全部 404，
        // 因此换成从帖子页 HTML 提取的 CDN 签名直链（拿不到时由 SiteApi 兜底回退）
        waitForPageSlot()
        val attachments = SiteApi.fetchDownloadAttachments(site, post)
        if (attachments.isEmpty()) {
            SiteLog.d(this, "帖子没有可用附件：${post.id}")
            STATE.failed++
            STATE.lastError = getString(R.string.site_download_no_attachment)
            return
        }
        SiteLog.d(this, "帖子 ${post.id} 可用附件 ${attachments.size} 个")
        val downloaded = ArrayList<File>()
        for (attachment in attachments) {
            if (STATE.cancelRequested) break
            val url = attachment.preferredUrl(site)
            if (url == null) {
                // 拿不到地址也算一个“已处理”的附件，否则进度永远补不齐
                SiteLog.d(this, "附件无可用地址：${attachment.name}")
                STATE.failed++
            } else {
                val target = File(dir, sanitize(attachment.name))
                STATE.currentFile = target.name
                STATE.bytesDone = 0L
                STATE.bytesTotal = 0L
                STATE.speed = 0L
                publish(force = true)
                val ok = downloadFile(url, target)
                val size = if (target.exists()) target.length() else 0L
                SiteLog.d(
                    this,
                    if (ok) "附件完成：${target.name} ${size}B"
                    else "附件失败：${attachment.name} ${STATE.lastError}",
                )
                if (ok) {
                    downloaded.add(target)
                } else {
                    STATE.failed++
                }
                STATE.currentFile = ""
                STATE.bytesDone = 0L
                STATE.bytesTotal = 0L
                STATE.speed = 0L
            }
            STATE.completed++
            publish(force = true)
            // 注意：这里以前每次都 sleep 1.2 秒，纯属白等。
            // DDoS-Guard 的「1 请求/秒」限的是站点域名（页面/API），
            // 附件走的是 t1.pawchive.pw 这个 CDN 直链，不受那条限流约束。
            // 限流改由 waitForPageSlot() 在真正请求页面之前做。
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
     * 下载一个文件。
     *
     * <p>顺序是：先试多连接分段（大文件），不行再退回单连接断点续传。
     * 分段失败时速度最多回到原来的水平，不会更差。
     */
    private fun downloadFile(url: String, target: File): Boolean {
        if (trySegmented(url, target)) return true
        if (STATE.cancelRequested) return false
        for (attempt in 1..MAX_RETRY) {
            if (STATE.cancelRequested) return false
            if (attemptDownload(url, target)) {
                // 之前分段留下的 .part 文件没用了
                cleanParts(target)
                return true
            }
            try {
                Thread.sleep(RETRY_DELAY_MS * attempt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return false
    }

    /** 一段分段下载：对应一个独立的 .part 文件。 */
    private class Segment(val start: Long, val end: Long, val file: File)

    /**
     * 探测文件总大小，并确认服务端支持 Range。
     *
     * <p>用 {@code Range: bytes=0-0} 探一下：返回 206 说明支持分段，
     * 同时 Content-Range 里带着总长度；返回 200 说明不支持，只能单连接下。
     */
    private fun probeTotalSize(url: String): Long? {
        return try {
            Site.newRangeCall(url, 0L, 0L).execute().use { response ->
                if (response.code() != 206) return null
                val range = response.header("Content-Range") ?: return null
                // 形如 bytes 0-0/123456789
                val total = range.substringAfterLast('/', "").trim().toLongOrNull() ?: return null
                if (total > 0) total else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun trySegmented(url: String, target: File): Boolean {
        if (STATE.cancelRequested) return false
        // 连续几次分段都失败，说明这家的 CDN 不吃这套，本次任务别再浪费时间探测
        if (segmentFailures >= MAX_SEGMENT_FAILURES) return false
        val total = probeTotalSize(url) ?: return false
        if (total < SEGMENT_THRESHOLD) return false
        if (target.exists() && target.length() == total) {
            // 上次其实已经下完了，只是没并进去
            STATE.bytesTotal = total
            STATE.bytesDone = total
            STATE.speed = 0L
            publish(force = true)
            return true
        }
        return downloadSegments(url, target, total)
    }

    /**
     * 把文件切成几段并发下载，最后合并。
     *
     * <p>这类 CDN 通常按单连接限速——实测用户开不开代理速度都一样，甚至开代理更慢，
     * 说明瓶颈不在链路而在服务端对每条连接的配额。单连接下 250MB 的包只能跑出
     * 单连接的速度，切成 2~4 段并行才能把可用带宽用起来。
     *
     * <p>每段写到独立的 {@code .partN} 文件，中断后重试可以按段续传。
     */
    private fun downloadSegments(url: String, target: File, total: Long): Boolean {
        // 段数跟着文件大小走：太小分段没意义，太多又会撞上 CDN 的并发上限（5 个）
        val count = when {
            total >= 64L * 1024 * 1024 -> 4
            total >= 16L * 1024 * 1024 -> 3
            else -> 2
        }
        val segLength = (total + count - 1) / count
        val segments = ArrayList<Segment>(count)
        for (i in 0 until count) {
            val start = i * segLength
            if (start >= total) break
            val end = minOf(start + segLength - 1, total - 1)
            segments.add(Segment(start, end, File(target.parentFile, target.name + ".part" + i)))
        }
        if (segments.size <= 1) return false

        // 之前的整文件（单连接下的半成品）和分段文件不通用，删掉免得算错大小
        if (target.exists()) target.delete()

        val done = AtomicLong(0L)
        for (segment in segments) {
            if (segment.file.exists()) done.addAndGet(segment.file.length())
        }
        STATE.bytesTotal = total
        STATE.bytesDone = done.get()
        STATE.speed = 0L
        SiteLog.d(this, "分段下载：${target.name} 共 $total B，分 ${segments.size} 段")
        publish(force = true)

        // 段任务只累加字节，进度和速度由这个泵统一算，避免多线程抢着刷新通知
        val stopped = AtomicBoolean(false)
        val pump = Thread {
            var lastBytes = done.get()
            var lastAt = System.currentTimeMillis()
            while (!stopped.get()) {
                try {
                    Thread.sleep(SPEED_WINDOW_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                val now = System.currentTimeMillis()
                val current = done.get()
                STATE.bytesDone = current
                STATE.speed = (current - lastBytes) * 1000 / (now - lastAt).coerceAtLeast(1)
                lastBytes = current
                lastAt = now
                publish(force = false)
            }
        }
        pump.isDaemon = true
        pump.start()

        val pool = Executors.newFixedThreadPool(segments.size)
        var ok = true
        try {
            // 显式写成 Callable，避免 submit(Runnable) / submit(Callable) 重载歧义
            val futures = segments.map { segment ->
                pool.submit(Callable { downloadSegment(url, segment, done) })
            }
            for (future in futures) {
                if (!future.get()) ok = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "分段下载异常", t)
            ok = false
        } finally {
            stopped.set(true)
            pool.shutdownNow()
            pump.interrupt()
        }

        if (!ok || STATE.cancelRequested) {
            // 保留 .part：下次重试按段续传，不用从头再来
            if (!STATE.cancelRequested) {
                segmentFailures++
            }
            SiteLog.d(this, "分段未完成，保留分段文件（cancel=${STATE.cancelRequested}）")
            return false
        }

        return try {
            BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE * 8).use { out ->
                for (segment in segments) {
                    segment.file.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            segments.forEach { it.file.delete() }
            val size = target.length()
            // 泵已经停了，最后这几十毫秒的进度得手动补上，否则进度条差最后一格
            STATE.bytesDone = size
            STATE.bytesTotal = total
            STATE.speed = 0L
            SiteLog.d(this, "分段合并完成：${target.name} ${size}B / $total B")
            size == total
        } catch (t: Throwable) {
            Log.w(TAG, "合并分段文件失败", t)
            SiteLog.d(this, "合并分段失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun downloadSegment(url: String, segment: Segment, done: AtomicLong): Boolean {
        val existing = if (segment.file.exists()) segment.file.length() else 0L
        val from = segment.start + existing
        if (from > segment.end) return true
        return try {
            Site.newRangeCall(url, from, segment.end).execute().use { response ->
                // 服务端不认 Range（回了 200 全量）就不能分段，交回单连接逻辑
                if (response.code() != 206) {
                    SiteLog.d(this, "分段请求被拒：HTTP ${response.code()}，回退单连接")
                    return false
                }
                val body = response.body() ?: return false
                BufferedOutputStream(FileOutputStream(segment.file, existing > 0), BUFFER_SIZE * 8).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!STATE.cancelRequested) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            done.addAndGet(read.toLong())
                        }
                    }
                }
                !STATE.cancelRequested && segment.file.length() == segment.end - segment.start + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "分段下载失败", e)
            false
        }
    }

    /** 分段成功后清理残留的 .part 文件。 */
    private fun cleanParts(target: File) {
        try {
            target.parentFile?.listFiles()?.forEach { file ->
                if (file.name.startsWith(target.name + ".part")) file.delete()
            }
        } catch (t: Throwable) {
            // 清理失败无所谓
        }
    }

    /**
     * 站点页面/API 的限流闸门。
     *
     * <p>DDoS-Guard 拦的是站点域名（{@code pawchive.pw} 的页面和 /api 接口），
     * 要求不超过 1 请求/秒；附件走 {@code t1.pawchive.pw} 这个 CDN 直链，不受这条限制。
     * 所以只在真正要发页面请求之前等，下载过程一秒都不浪费。
     */
    private fun waitForPageSlot() {
        val wait = PAGE_INTERVAL_MS - (System.currentTimeMillis() - lastPageRequestAt)
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastPageRequestAt = System.currentTimeMillis()
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
                // 不套缓冲的话每 64KB 就是一次系统调用，慢速存储上会明显拖后腿
                BufferedOutputStream(FileOutputStream(target, append), BUFFER_SIZE * 8).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var windowStart = System.currentTimeMillis()
                        var windowBytes = 0L
                        while (!STATE.cancelRequested) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            STATE.bytesDone += read
                            windowBytes += read
                            // 每半秒测一次速度，顺便节流刷新通知
                            val now = System.currentTimeMillis()
                            val elapsed = now - windowStart
                            if (elapsed >= SPEED_WINDOW_MS) {
                                STATE.speed = windowBytes * 1000 / elapsed
                                windowStart = now
                                windowBytes = 0L
                                publish(force = false)
                            }
                        }
                        if (windowBytes > 0) {
                            val elapsed = (System.currentTimeMillis() - windowStart).coerceAtLeast(1)
                            STATE.speed = windowBytes * 1000 / elapsed
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
            // 有失败时别让通知被划走/自动消失，用户得能回来看原因
            .setAutoCancel(state.done && state.failed == 0)
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
         * 站点页面/API 的最小请求间隔。
         *
         * <p>Pawchive 的页面和 API 由 DDoS-Guard 保护，拦截页写明限制是
         * “no more than 1 request per second, and 5 active downloads”，超限直接封。
         * 这里取 1100ms 留足余量（实测 400ms 会被判定为滥用）。
         * <b>只约束页面请求</b>，CDN 直链下载不受影响。
         */
        private const val PAGE_INTERVAL_MS = 1100L
        private const val RETRY_DELAY_MS = 1200L
        private const val BUFFER_SIZE = 64 * 1024

        /** 速度采样窗口 */
        private const val SPEED_WINDOW_MS = 500L

        /** 小于这个大小就不分段：握手开销比省下的时间还多 */
        private const val SEGMENT_THRESHOLD = 4L * 1024 * 1024

        /** 分段连续失败这么多次，本次任务就不再尝试分段 */
        private const val MAX_SEGMENT_FAILURES = 2

        /** 上次发起页面请求的时间，用于 [waitForPageSlot] */
        @Volatile
        private var lastPageRequestAt = 0L

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
            if (s.done) {
                val text = doneText(context, s)
                // 失败时把最后一次错误原因一起显示，否则用户只看到“完成：导入 0 项”，
                // 完全不知道是网络拦了还是没找到附件
                return if (s.failed > 0 && s.lastError.isNotEmpty()) {
                    text + "\n" + s.lastError
                } else {
                    text
                }
            }
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
            if (s.speed > 0) {
                sb.append("  ").append(formatSize(s.speed)).append("/s")
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
