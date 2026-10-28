package com.hippo.ehviewer.site

import android.net.Uri
import com.hippo.ehviewer.util.KemonoHelper
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Kemono / Pawchive 站点定义。
 *
 * 两个站点是同一套前端/后端的分支实现，因此 URL 规则基本一致：
 * - 搜索页:   {base}/posts?q=<keyword>          （注意 /artists?q= 会忽略 q，永远返回默认列表，不能用）
 * - 帖子 API: {base}/api/v1/{service}/user/{userId}/posts
 * - 附件:      {base}/data<path>，或直接取记录里的 temp_url（CDN 签名直链，不经过 Cloudflare）
 *
 * Kemono 是 Vite 单页应用，服务端不渲染任何结果，且其 api 接口常被 Cloudflare 拦下返回 403，
 * 因此凡是拿不到确定信息的场景，一律返回未知而不是错误。
 */
enum class Site(
    val label: String,
    val baseUrl: String,
    val searchableHtml: Boolean,
) {
    KEMONO("Kemono", KemonoHelper.KEMONO_BASE, false),
    PAWCHIVE("Pawchive", KemonoHelper.PAWCHIVE_BASE, true),
    ;

    fun searchUrl(keyword: String): String =
        Uri.parse(baseUrl).buildUpon()
            .appendPath("posts")
            .appendQueryParameter("q", keyword)
            .build()
            .toString()

    fun postsApiUrl(service: String, userId: String): String =
        "$baseUrl/api/v1/$service/user/$userId/posts"

    fun dataUrl(path: String): String =
        baseUrl + KemonoHelper.DATA_PATH + path

    companion object {
        /**
         * 独立 OkHttpClient：不复用 EhApplication 那份，避免把 E-Hentai 的 Cookie 带到站外。
         */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // 页面 / API 用，读超时给足：CDN 偶尔会卡住十几秒才继续吐数据
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }

        /**
         * 下载专用的连接池。
         *
         * <p>读超时压到 6 秒，用来抓“彻底卡死”的连接（CDN 有时回完响应头就不吐数据）。
         * <b>但它抓不住龟速</b>——数据哪怕每秒只流几 KB 也算有数据，永远不会超时。
         * 真正判断连接是否被限速要看平均速度，逻辑在
         * [com.hippo.ehviewer.site.SiteDownloadService] 的下载循环里。
         */
        private val downloadClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }

        /**
         * 带浏览器风格请求头的 GET。null 表示请求失败（调用方需静默处理）。
         */
        fun get(url: String): Response? {
            return try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", KemonoHelper.USER_AGENT)
                    .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7")
                    .build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                null
            }
        }

        fun newCall(url: String): Call {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", KemonoHelper.USER_AGENT)
                .build()
            return client.newCall(request)
        }

        /**
         * 支持断点续传的下载请求。
         *
         * <p><b>即使从 0 开始也一定带 Range 头</b>，这不是可选项。实测这条 CDN 对
         * 不带 Range 的完整 GET 只给 10 KB/s 左右、几十秒就断流；一旦带上
         * {@code Range: bytes=0-}（服务端回 206）立刻变成 1~2 MB/s。
         * 浏览器下载管理器之所以快，就是因为它默认就带这个头。
         *
         * @param startRange 已下载的字节数；为 0 时也照样发 {@code bytes=0-}
         */
        fun newResumableCall(url: String, startRange: Long): Call {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", KemonoHelper.USER_AGENT)
                .header("Range", "bytes=$startRange-")
            return downloadClient.newCall(builder.build())
        }
    }
}
