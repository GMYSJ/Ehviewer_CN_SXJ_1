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
 */
class SiteDownloadActivity : ToolbarActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val posts = ArrayList<Post>()
    private var pollCount = 0

    private lateinit var site: Site
    private lateinit var creator: Creator

    /** 非空表示这次是从「我输入的关键词」进来的，而不是从详情页的 artist 标签 */
    private var keyword: String? = null
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

        listView = findViewById(R.id.post_list)
        progressView = findViewById(R.id.progress)
        downloadButton = findViewById(R.id.download)
        cancelButton = findViewById(R.id.cancel)

        setTitle(site.label + " - " + (keyword ?: creator.userId))
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        downloadButton.isEnabled = false
        downloadButton.setOnClickListener { startDownload() }
        cancelButton.setOnClickListener {
            SiteDownloadService.cancel()
            finish()
        }

        if (keyword != null) {
            loadByKeyword()
        } else {
            loadPosts()
        }
    }

    private fun loadPosts() {
        progressView.setText(R.string.site_download_loading)
        Thread {
            val fetched = SiteApi.fetchPosts(site, creator) ?: emptyList()
            handler.post {
                posts.clear()
                posts.addAll(fetched)
                renderPosts()
            }
        }.start()
    }

    /**
     * 关键词模式：先在站点上搜这个词，拿到命中的作者，再拉他们的帖子。
     *
     * <p>这样用的是用户自己输入的词，而不是详情页只能拿到的 artist / uploader 标签。
     */
    private fun loadByKeyword() {
        val query = keyword ?: return
        progressView.text = getString(R.string.site_download_searching, query)
        Thread {
            val fetched = try {
                SiteApi.fetchPostsByKeyword(site, query)
            } catch (t: Throwable) {
                emptyList()
            }
            handler.post {
                posts.clear()
                posts.addAll(fetched)
                renderPosts()
            }
        }.start()
    }

    private fun renderPosts() {
        val titles: List<String> = posts.map { post ->
            (post.title ?: post.id) + "  (" + post.attachments.size + ")"
        }
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            titles,
        )
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
        downloadButton.isEnabled = false
        pollCount = 0
        SiteDownloadService.start(this, site, creator, selected)
        pollProgress()
    }

    private fun pollProgress() {
        handler.postDelayed({
            val state = SiteDownloadService.STATE
            progressView.text = getString(
                R.string.site_download_progress,
                state.completed,
                state.total,
                state.imported,
            )
            if (state.done) {
                Toast.makeText(
                    this,
                    getString(R.string.site_download_done, state.imported),
                    Toast.LENGTH_SHORT,
                ).show()
                downloadButton.isEnabled = true
            } else if (state.completed == 0 && ++pollCount > POLL_TIMEOUT_COUNT) {
                // 服务没能跑起来（被系统拒绝启动等），别让界面一直卡在“下载中”
                Toast.makeText(this, R.string.site_download_empty, Toast.LENGTH_SHORT).show()
                downloadButton.isEnabled = true
            } else {
                pollProgress()
            }
        }, POLL_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SITE = "site"
        private const val EXTRA_SERVICE = "service"
        private const val EXTRA_USER = "user"
        private const val EXTRA_KEYWORD = "keyword"
        private const val POLL_INTERVAL_MS = 500L

        /** 30 秒内一点进展都没有，认为下载没跑起来 */
        private const val POLL_TIMEOUT_COUNT = 60

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
    }
}
