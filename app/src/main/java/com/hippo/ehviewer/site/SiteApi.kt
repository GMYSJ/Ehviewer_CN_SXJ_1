package com.hippo.ehviewer.site

import android.net.Uri
import com.hippo.ehviewer.util.KemonoHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
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

    /**
     * 从搜索页里解析出全部命中的创作者（去重，保持页面顺序）。
     *
     * <p>这是「用我自己输入的关键词」这条路的基础：详情页只能拿到 artist 标签 / uploader，
     * 而上传者往往是搬运号，跟真正作者对不上。
     */
    fun searchCreators(site: Site, keyword: String): List<Creator> {
        val normalized = KemonoHelper.normalize(keyword) ?: return emptyList()
        val response = Site.get(site.searchUrl(normalized)) ?: return emptyList()
        return try {
            response.use {
                if (!it.isSuccessful) return emptyList()
                val html = it.body()?.string() ?: return emptyList()
                parseCreators(html)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCreators(html: String): List<Creator> {
        val matcher = POST_LINK.matcher(html)
        val creators = LinkedHashSet<Creator>()
        while (matcher.find()) {
            creators.add(Creator(matcher.group(1), matcher.group(2)))
        }
        return creators.toList()
    }

    /**
     * 按关键词直接拿可下载的帖子：搜索页 → 命中的创作者 → 各自的帖子列表。
     *
     * <p>返回的 [Post] 自带 service / userId，所以后面的下载和导入不需要再知道创作者是谁。
     */
    fun fetchPostsByKeyword(site: Site, keyword: String): List<Post> {
        val creators = searchCreators(site, keyword)
        if (creators.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Post>()
        for (creator in creators) {
            val posts = fetchPosts(site, creator) ?: continue
            for (post in posts) {
                val key = post.service + "/" + post.userId + "/" + post.id
                if (seen.add(key)) {
                    result.add(post)
                }
            }
            // 站点有频率限制，多位作者之间稍微喘口气
            try {
                Thread.sleep(CREATOR_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // 新发布的排前面，勾选起来更顺手（published 是 ISO8601，字符串比较即可）
        return result.sortedByDescending { it.published ?: "" }
    }

    private const val CREATOR_INTERVAL_MS = 300L

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

    /**
     * 取一条帖子的可下载附件列表。
     *
     * <p><b>为什么不能直接用 API 返回的 attachments</b>：实测下来
     * <ul>
     *   <li>API 的 {@code attachments} 里只有 {@code name}/{@code path}/{@code node}，
     *       <b>没有</b> temp_url；</li>
     *   <li>按 Kemono 惯例拼的 {@code {base}/data<path>} 对 Pawchive 一律 404；</li>
     *   <li>真正能下载的 CDN 签名直链（{@code t1.pawchive.pw/f/<hash>/<文件名>?e=&s=}）
     *       <b>只出现在帖子页的 HTML 里</b>。</li>
     * </ul>
     * 所以这里先抓帖子页取直链；抓不到才退回 API 解析结果（此时多半下不动，但留个兜底）。
     */
    fun fetchDownloadAttachments(site: Site, post: Post): List<Attachment> {
        val fromHtml = fetchDirectAttachments(site, post)
        return fromHtml.ifEmpty { post.attachments }
    }

    /** 帖子详情页地址，形如 {@code {base}/patreon/user/<uid>/post/<pid>}。 */
    private fun postPageUrl(site: Site, post: Post): String =
        "${site.baseUrl}/${post.service}/user/${post.userId}/post/${post.id}"

    private fun fetchDirectAttachments(site: Site, post: Post): List<Attachment> {
        val response = Site.get(postPageUrl(site, post)) ?: return emptyList()
        val html = try {
            // OkHttp 3.x 是 Java 实现，这里是方法调用 body()，不能当 Kotlin 属性写
            response.use { it.body()?.string() }
        } catch (e: Exception) {
            null
        }
        if (html.isNullOrEmpty()) return emptyList()
        return parseDirectLinks(html)
    }

    /** CDN 签名直链，例如 {@code https://t1.pawchive.pw/f/<hash>/<文件名>?e=...&s=...}。 */
    private val DIRECT_LINK: Pattern =
        Pattern.compile("https?://t\\d+\\.[a-z0-9.-]+/f/[^\"'<>\\s]+")

    private fun parseDirectLinks(html: String): List<Attachment> {
        // 页面里链接是 HTML 转义过的，&amp; 必须还原，否则签名参数会被截断
        val text = html.replace("&amp;", "&")
        val matcher = DIRECT_LINK.matcher(text)
        val seen = LinkedHashSet<String>()
        val result = ArrayList<Attachment>()
        while (matcher.find()) {
            val link = matcher.group()
            if (!seen.add(link)) continue
            result.add(Attachment(name = nameFromDirectLink(link), path = link, tempUrl = link))
        }
        return result
    }

    /** 直链里的文件名是 URL 编码过的中文，解码后作为附件名（密码也可能写在里面）。 */
    private fun nameFromDirectLink(link: String): String {
        val segment = try {
            Uri.parse(link).lastPathSegment
        } catch (e: Exception) {
            null
        }
        if (segment.isNullOrEmpty()) return link
        return try {
            URLDecoder.decode(segment, "UTF-8")
        } catch (e: Exception) {
            segment
        }
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
