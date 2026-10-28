package com.hippo.ehviewer.site

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.ToolbarActivity

/**
 * 列出某个创作者在 Kemono / Pawchive 上的帖子，勾选后批量下载。
 *
 * 下载过程由 [SiteDownloadService] 在后台完成，这里只负责展示题目和进度。
 * 从通知栏或「下载」页顶部那条进来时带 [EXTRA_RESUME]，此时不去联网重拉帖子，直接显示正在跑的进度。
 */
class SiteDownloadActivity : ToolbarActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val posts = ArrayList<Post>()

    /** 去重用：同一个帖子在翻页和单帖补齐里可能被碰到两次 */
    private val postKeys = HashSet<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var pollCount = 0
    private var polling = false

    private lateinit var site: Site
    private lateinit var creator: Creator

    /** 非空表示这次是从「我输入的关键词」进来的，而不是从详情页的 artist 标签 */
    private var keyword: String? = null

    /** true 表示从通知栏点进来，只看进度 */
    private var resumeMode = false
    private lateinit var listView: ListView
    private lateinit var progressView: TextView
    private lateinit var downloadButton: View
    private lateinit var cancelButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_download)

        val siteName = intent.getStringExtra(EXTRA_SITE)
        site = Site.values().firstOrNull { it.name == siteName } ?: Site.PAWCHIVE
        creator = Creator(
            intent.getStringExtra(EXTRA_SERVICE) ?: "",
            intent.getStringExtra(EXTRA_USER) ?: "",
        )
        keyword = intent.getStringExtra(EXTRA_KEYWORD)?.takeIf { it.isNotEmpty() }
        resumeMode = intent.getBooleanExtra(EXTRA_RESUME, false)

        listView = findViewById(R.id.post_list)
        progressView = findViewById(R.id.progress)
        downloadButton = findViewById(R.id.download)
        cancelButton = findViewById(R.id.cancel)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            ArrayList<String>(),
        )
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        downloadButton.isEnabled = false
        downloadButton.setOnClickListener { startDownload() }
        cancelButton.setOnClickListener {
            SiteDownloadService.cancel()
            if (resumeMode) finish()
        }

        if (resumeMode) {
            // 从通知栏进来的，列表没有数据源，直接进入进度视图
            setTitle(R.string.site_download_resume_title)
            listView.visibility = View.GONE
            downloadButton.visibility = View.GONE
            progressView.text = SiteDownloadService.describe(this)
            cancelButton.isEnabled = SiteDownloadService.STATE.isRunning()
            if (SiteDownloadService.STATE.isRunning()) {
                startPolling()
            }
            return
        }

        setTitle(site.label + " - " + (keyword ?: creator.userId))
        if (keyword != null) {
            loadByKeyword()
        } else {
            loadPosts()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            setIntent(intent)
            resumeMode = true
            listView.visibility = View.GONE
            downloadButton.visibility = View.GONE
            setTitle(R.string.site_download_resume_title)
            cancelButton.isEnabled = SiteDownloadService.STATE.isRunning()
            startPolling()
        }
    }

    override fun onResume() {
        super.onResume()
        // 退出页面时轮询就停了；重新进来要接着显示，否则用户以为下载没了
        val state = SiteDownloadService.STATE
        if (state.isRunning() || resumeMode) {
            downloadButton.isEnabled = false
            startPolling()
        }
    }

    private fun loadPosts() {
        progressView.setText(R.string.site_download_loading)
        Thread {
            val fetched = try {
                SiteApi.fetchPosts(site, creator) ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            handler.post {
                posts.clear()
                postKeys.clear()
                fetched.forEach { addPost(it) }
                onListReady()
            }
        }.start()
    }

    /**
     * 关键词模式：边解析边显示。
     *
     * <p>整轮解析要受站点 1 请求/秒的限流约束，几十秒很正常。如果等全部收齐再显示，
     * 用户看到的是一直空着的列表，会以为卡死——所以解析出一条就立刻加进列表。
     */
    private fun loadByKeyword() {
        val query = keyword ?: return
        progressView.text = getString(R.string.site_download_searching, query)
        Thread {
            try {
                SiteApi.fetchPostsByKeyword(
                    site,
                    query,
                    onProgress = { done, total ->
                        handler.post {
                            progressView.text =
                                getString(R.string.site_download_parsing, done, total)
                        }
                    },
                    onPost = { post ->
                        handler.post {
                            if (addPost(post)) {
                                downloadButton.isEnabled = true
                            }
                        }
                    },
                )
            } catch (t: Throwable) {
                // 网络异常不往外抛，界面统一按“拿到多少算多少”处理
            }
            handler.post { onListReady() }
        }.start()
    }

    /**
     * @return true 表示是新的帖子，已经加进列表
     */
    private fun addPost(post: Post): Boolean {
        val key = post.service + "/" + post.userId + "/" + post.id
        if (!postKeys.add(key)) return false
        posts.add(post)
        adapter.add((post.title ?: post.id) + "  (" + post.attachments.size + ")")
        return true
    }

    private fun onListReady() {
        downloadButton.isEnabled = posts.isNotEmpty()
        progressView.text = if (posts.isEmpty()) {
            getString(R.string.site_download_empty)
        } else {
            getString(R.string.site_download_found, posts.size)
        }
    }

    private fun startDownload() {
        val checked = listView.checkedItemPositions
        val selected = ArrayList<Post>()
        for (i in posts.indices) {
            if (checked.get(i)) {
                selected.add(posts[i])
            }
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.site_download_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        if (!SiteDownloadService.start(this, site, creator, selected)) {
            Toast.makeText(this, R.string.site_download_busy, Toast.LENGTH_SHORT).show()
            return
        }
        downloadButton.isEnabled = false
        progressView.text = getString(R.string.site_download_preparing)
        startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollCount = 0
        pollProgress()
    }

    private fun pollProgress() {
        handler.postDelayed({
            if (isFinishing || isDestroyed) {
                polling = false
                return@postDelayed
            }
            val state = SiteDownloadService.STATE
            progressView.text = SiteDownloadService.describe(this)
            cancelButton.isEnabled = state.isRunning()
            if (state.done) {
                polling = false
                downloadButton.isEnabled = posts.isNotEmpty()
                val message = if (state.failed > 0) {
                    getString(R.string.site_download_done_with_failed, state.imported, state.failed)
                } else {
                    getString(R.string.site_download_done, state.imported)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else if (!state.started && ++pollCount > POLL_TIMEOUT_COUNT) {
                // 服务始终没进入运行状态（被系统拒绝启动等），别让界面一直卡着。
                // 注意不能用 completed==0 判断：第一个附件可能是几百 MB，下十分钟也还是 0。
                polling = false
                downloadButton.isEnabled = posts.isNotEmpty()
                val reason = state.lastError
                Toast.makeText(
                    this,
                    if (reason.isEmpty()) getString(R.string.site_download_not_started) else reason,
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                pollProgress()
            }
        }, POLL_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        polling = false
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SITE = "site"
        private const val EXTRA_SERVICE = "service"
        private const val EXTRA_USER = "user"
        private const val EXTRA_KEYWORD = "keyword"

        /** 从通知栏点进来，只看进度不重新联网 */
        const val EXTRA_RESUME = "resume"

        private const val POLL_INTERVAL_MS = 500L

        /** 15 秒内服务都没起来，认为启动失败 */
        private const val POLL_TIMEOUT_COUNT = 30

        fun newIntent(context: Context, site: Site, creator: Creator): Intent {
            val intent = Intent(context, SiteDownloadActivity::class.java)
            intent.putExtra(EXTRA_SITE, site.name)
            intent.putExtra(EXTRA_SERVICE, creator.service)
            intent.putExtra(EXTRA_USER, creator.userId)
            return intent
        }

        /**
         * 用用户自己输入的关键词进站内下载（不是 artist / uploader 标签）。
         */
        fun newIntentForKeyword(context: Context, site: Site, keyword: String): Intent {
            val intent = Intent(context, SiteDownloadActivity::class.java)
            intent.putExtra(EXTRA_SITE, site.name)
            intent.putExtra(EXTRA_KEYWORD, keyword)
            return intent
        }

        /**
         * 只看进度的入口：从通知栏或「下载」页顶部那条进来，不重新联网拉帖子。
         */
        fun newResumeIntent(context: Context): Intent {
            val intent = Intent(context, SiteDownloadActivity::class.java)
            intent.putExtra(EXTRA_RESUME, true)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            return intent
        }
    }
}
