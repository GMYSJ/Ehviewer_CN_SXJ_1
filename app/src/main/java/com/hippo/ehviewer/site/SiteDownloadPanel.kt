package com.hippo.ehviewer.site

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.hippo.ehviewer.R
import java.lang.ref.WeakReference

/**
 * 「下载」页顶部那条站外（Kemono / Pawchive）下载条目。
 *
 * <p>站外任务没有走 EhViewer 的 DownloadInfo 体系：那套是围绕画廊页码建的，
 * 每一条都要有 gid、页码、缩略图，还要进数据库，硬塞进去会污染正常下载的恢复/清理逻辑。
 * 所以改成在下载页顶部单独开一条，让用户在平时看下载进度的同一个地方也能看到它，
 * 点一下进详情页。
 *
 * <p>由 [com.hippo.ehviewer.ui.scene.download.DownloadsScene] 在视图创建时 bind、
 * 销毁时 stop。用弱引用持有根视图，避免页面销毁后 ticker 把整个 View 树泄漏掉。
 */
object SiteDownloadPanel {

    private const val TAG = "SiteDownloadPanel"
    private const val INTERVAL_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private var rootRef: WeakReference<View>? = null
    private var ticker: Runnable? = null

    @JvmStatic
    fun bind(root: View) {
        rootRef = WeakReference(root)
        val panel = root.findViewById<View>(R.id.site_download_panel) ?: return
        panel.setOnClickListener {
            val context = it.context
            try {
                context.startActivity(SiteDownloadActivity.newResumeIntent(context))
            } catch (t: Throwable) {
                Log.e(TAG, "打开站外下载页失败", t)
            }
        }
        refresh(root)
        startTicker()
    }

    @JvmStatic
    fun stop() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        rootRef = null
    }

    private fun startTicker() {
        if (ticker != null) return
        val runnable = object : Runnable {
            override fun run() {
                val root = rootRef?.get()
                if (root == null) {
                    ticker = null
                    return
                }
                refresh(root)
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        ticker = runnable
        handler.postDelayed(runnable, INTERVAL_MS)
    }

    /**
     * 按当前状态刷新这一条。没有任务在跑就隐藏——跑完的结果留在通知栏，不在这里常驻。
     */
    @JvmStatic
    fun refresh(root: View) {
        val panel = root.findViewById<View>(R.id.site_download_panel) ?: return
        val state = SiteDownloadService.STATE
        if (!state.isRunning()) {
            if (panel.visibility != View.GONE) {
                panel.visibility = View.GONE
            }
            return
        }
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
        }
        val context = root.context
        root.findViewById<TextView>(R.id.site_download_panel_title)?.text =
            if (state.currentName.isNotEmpty()) {
                state.currentName
            } else {
                context.getString(R.string.site_download_title)
            }
        root.findViewById<TextView>(R.id.site_download_panel_progress)?.text =
            SiteDownloadService.describe(context)
        root.findViewById<ProgressBar>(R.id.site_download_panel_bar)?.let { bar ->
            if (state.total > 0) {
                bar.isIndeterminate = false
                bar.max = 1000
                bar.progress = state.permille()
            } else if (!bar.isIndeterminate) {
                bar.isIndeterminate = true
            }
        }
    }
}
