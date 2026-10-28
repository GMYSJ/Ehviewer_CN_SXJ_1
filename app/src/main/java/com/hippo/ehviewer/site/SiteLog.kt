package com.hippo.ehviewer.site

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 站外下载的诊断日志。
 *
 * <p>下载在后台线程跑，出问题时用户只看到“进度没了”，拿不到任何线索。
 * 这里把关键事件（开始、每条帖子、每个文件的结果、异常、服务销毁）落到一个文件里，
 * 出问题后直接把文件发过来就能定位是“我们主动结束了任务”还是“进程被系统杀了”。
 *
 * <p>放在外部私有目录（Android/data/&lt;包名&gt;/files/logs），不需要额外权限。
 * 超过 128KB 自动清空重来，不会无限增长。
 */
internal object SiteLog {

    private const val MAX_BYTES = 128 * 1024L
    private const val FILE_NAME = "site_download.log"

    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun file(context: Context): File? = try {
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        if (!dir.exists() && !dir.mkdirs()) null else File(dir, FILE_NAME)
    } catch (t: Throwable) {
        null
    }

    @Synchronized
    fun d(context: Context, message: String) {
        try {
            val target = file(context) ?: return
            if (target.length() > MAX_BYTES) {
                target.delete()
            }
            target.appendText(format.format(Date()) + "  " + message + "\n")
        } catch (t: Throwable) {
            // 日志写不进去也绝不能影响下载本身
        }
    }
}
