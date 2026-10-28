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
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
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
         * @param startRange 已下载的字节数；为 0 时不带 Range 头
         */
        fun newResumableCall(url: String, startRange: Long): Call {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", KemonoHelper.USER_AGENT)
            if (startRange > 0) {
                builder.header("Range", "bytes=$startRange-")
            }
            return client.newCall(builder.build())
        }
    }
}
