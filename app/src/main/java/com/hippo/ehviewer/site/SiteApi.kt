package com.hippo.ehviewer.site

import com.hippo.ehviewer.util.KemonoHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/** 搜索结果里出现的帖子链接，形如 {@code /patreon/user/30500811/post/173566354}。 */
private val POST_LINK: Pattern = Pattern.compile("/([a-z0-9_]+)/user/(\\d+)/post/(\\d+)")

/** Pawchive 搜索无结果时的占位文案。 */
private const val EMPTY_MARKER = "Nobody here but us chickens!"

/** 某个站点上的创作者。 */
data class Creator(
    val service: String,
    val userId: String,
)

/** 帖子里的一个可下载附件。 */
data class Attachment(
    val name: String,
    val path: String,
    val tempUrl: String?,
) {
    /**
     * 优先用 CDN 签名直链（不经过站点主服务器，不会被 Cloudflare 拦），
     * 拿不到再退回站点上的静态路径。
     */
    fun preferredUrl(site: Site): String? = tempUrl ?: KemonoHelper.buildDataUrl(site.baseUrl, path)

    val isArchive: Boolean
        get() {
            val lower = name.lowercase()
            return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")
        }
}

/** 一条帖子。 */
data class Post(
    val id: String,
    val userId: String,
    val service: String,
    val title: String?,
    val content: String?,
    val published: String?,
    val attachments: List<Attachment>,
)

/**
 * 搜索结果。
 *
 * @param count     解析到的结果条数
 * @param creator   第一个命中的创作者（可用于继续拉取其帖子列表）
 * @param reliable  结果是否可信。Kemono 是单页应用、服务端不渲染结果，
 *                  这时拿不到任何信息，返回 false，调用方应按“未知”处理而不是“不存在”
 */
data class SearchResult(
    val count: Int,
    val creator: Creator?,
    val reliable: Boolean,
)

object SiteApi {

    /**
     * 在指定站点按关键词搜索。
     *
     * 失败一律返回 null，由调用方静默处理。
     */
    fun search(site: Site, keyword: String): SearchResult? {
        val normalized = KemonoHelper.normalize(keyword) ?: return null
        val response = Site.get(site.searchUrl(normalized)) ?: return null
        return try {
            response.use {
                if (!it.isSuccessful) return null
                val html = it.body()?.string() ?: return null
                parseSearchResult(site, html)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSearchResult(site: Site, html: String): SearchResult {
        val matcher = POST_LINK.matcher(html)
        val creators = LinkedHashSet<Creator>()
        val posts = HashSet<String>()
        while (matcher.find()) {
            creators.add(Creator(matcher.group(1), matcher.group(2)))
            posts.add(matcher.group(3))
        }
        if (creators.isNotEmpty()) {
            return SearchResult(posts.size, creators.first(), true)
        }
        val empty = html.contains(EMPTY_MARKER)
        // 服务端渲染的站点能确定“没有结果”；单页应用拿不到信息，标记为不可信
        val reliable = empty || site.searchableHtml
        return SearchResult(0, null, reliable)
    }

    /**
     * 拉取某个创作者的全部帖子（含附件清单）。
     */
    fun fetchPosts(site: Site, creator: Creator): List<Post>? {
        val response = Site.get(site.postsApiUrl(creator.service, creator.userId)) ?: return null
        return try {
            response.use {
                if (!it.isSuccessful) return null
                val body = it.body()?.string() ?: return null
                parsePosts(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePosts(body: String): List<Post> {
        val array = JSONArray(body)
        val result = ArrayList<Post>(array.length())
        for (i in 0 until array.length()) {
            val item: JSONObject = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNullOrEmpty()) continue
            result.add(
                Post(
                    id = id,
                    userId = item.optString("user"),
                    service = item.optString("service"),
                    title = item.optString("title").let { if (it.isNullOrEmpty()) null else it },
                    content = item.optString("content").let { if (it.isNullOrEmpty()) null else it },
                    published = item.optString("published").let { if (it.isNullOrEmpty()) null else it },
                    attachments = parseAttachments(item),
                )
            )
        }
        return result
    }

    private fun parseAttachments(post: JSONObject): List<Attachment> {
        val result = ArrayList<Attachment>()
        post.optJSONObject("file")?.let { file ->
            parseAttachment(file)?.let { result.add(it) }
        }
        val array: JSONArray? = post.optJSONArray("attachments")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parseAttachment(item)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun parseAttachment(item: JSONObject): Attachment? {
        val path = item.optString("path")
        if (path.isNullOrEmpty()) return null
        val name = item.optString("name").let { if (it.isNullOrEmpty()) guessName(path) else it }
        val tempUrl = item.optString("temp_url").let { if (it.isNullOrEmpty()) null else it }
        return Attachment(name = name, path = path, tempUrl = tempUrl)
    }

    private fun guessName(path: String): String = path.substringAfterLast('/', path)
}
