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

/** 搜索结果里命中的一条帖子（来自搜索页 HTML，不含正文）。 */
private data class SearchHit(
    val service: String,
    val userId: String,
    val postId: String,
)

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
     * 按关键词拿到「搜索结果里那些帖子的完整信息」。
     *
     * <p>做法分三步：
     * <ol>
     *   <li>搜索页 HTML 里拿到命中的 {@code (service, user, post)} 三元组；</li>
     *   <li>按作者翻页扫帖子列表（每页约 50 条），把命中项连同正文/附件一起捞出来。
     *       <b>只取命中项</b>，不要整个作者都展开——实测搜 “Ryx” 会命中 9 位作者，
     *       全展开会混进几千条无关内容；</li>
     *   <li>翻页翻不到的（帖子太老）逐条用单帖接口补齐，保证不漏。</li>
     * </ol>
     *
     * <p>返回顺序与搜索页一致。返回的 [Post] 自带 service / userId，后续下载不需要再知道作者是谁。
     *
     * @param onProgress 解析进度 (已解析, 总数)
     * @param onPost     每解析出一条就立刻回调一次。<b>这是界面能“出一条显示一条”的关键</b>：
     *                   整轮解析受站点 1 请求/秒的限流约束，可能要几十秒，全部收齐再显示
     *                   会让用户以为卡死。
     */
    fun fetchPostsByKeyword(
        site: Site,
        keyword: String,
        onProgress: ((Int, Int) -> Unit)? = null,
        onPost: ((Post) -> Unit)? = null,
    ): List<Post> {
        val normalized = KemonoHelper.normalize(keyword) ?: return emptyList()
        val html = fetchSearchHtml(site, normalized) ?: return emptyList()
        val hits = parseHits(html)
        if (hits.isEmpty()) return emptyList()

        val total = hits.size
        val collected = HashMap<String, Post>()
        onProgress?.invoke(0, total)

        // 按作者分组，只记需要的那几个 post id
        val hitsByCreator = LinkedHashMap<Creator, MutableSet<String>>()
        for (hit in hits) {
            hitsByCreator.getOrPut(Creator(hit.service, hit.userId)) { HashSet() }.add(hit.postId)
        }

        for ((creator, wanted) in hitsByCreator) {
            val missing = HashSet(wanted)
            var offset = 0
            var page = 0
            while (missing.isNotEmpty() && page < MAX_POST_PAGES) {
                val posts = fetchPostsPage(site, creator, offset) ?: break
                if (posts.isEmpty()) break
                for (post in posts) {
                    if (wanted.contains(post.id)) {
                        val key = postKey(post.service, post.userId, post.id)
                        if (collected.put(key, post) == null) {
                            onPost?.invoke(post)
                            onProgress?.invoke(collected.size, total)
                        }
                        missing.remove(post.id)
                    }
                }
                offset += posts.size
                page++
                sleepQuietly(POST_PAGE_INTERVAL_MS)
            }
            sleepQuietly(CREATOR_INTERVAL_MS)
        }

        // 翻页没覆盖到的，逐条补齐
        for (hit in hits) {
            if (collected.containsKey(postKey(hit.service, hit.userId, hit.postId))) continue
            val post = fetchSinglePost(site, hit) ?: continue
            val key = postKey(hit.service, hit.userId, hit.postId)
            if (collected.put(key, post) == null) {
                onPost?.invoke(post)
                onProgress?.invoke(collected.size, total)
            }
            sleepQuietly(SINGLE_POST_INTERVAL_MS)
        }

        // 按搜索页上的顺序输出，实在拿不到的跳过
        val result = ArrayList<Post>(hits.size)
        for (hit in hits) {
            collected[postKey(hit.service, hit.userId, hit.postId)]?.let { result.add(it) }
        }
        return result
    }

    /** 单帖接口：{@code {base}/api/v1/{service}/user/{uid}/post/{pid}}，返回单个对象。 */
    private fun fetchSinglePost(site: Site, hit: SearchHit): Post? {
        val url = "${site.baseUrl}/api/v1/${hit.service}/user/${hit.userId}/post/${hit.postId}"
        val response = Site.get(url) ?: return null
        return try {
            response.use {
                if (!it.isSuccessful) return null
                val body = it.body()?.string() ?: return null
                parsePost(JSONObject(body))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 搜索页 → HTML，失败返回 null（含被拦、超时）。 */
    private fun fetchSearchHtml(site: Site, keyword: String): String? {
        val response = Site.get(site.searchUrl(keyword)) ?: return null
        return try {
            response.use {
                if (!it.isSuccessful) return null
                it.body()?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 搜索页里命中的帖子三元组，按出现顺序去重。 */
    private fun parseHits(html: String): List<SearchHit> {
        val matcher = POST_LINK.matcher(html)
        val hits = LinkedHashSet<SearchHit>()
        while (matcher.find()) {
            hits.add(SearchHit(matcher.group(1), matcher.group(2), matcher.group(3)))
        }
        return hits.toList()
    }

    private fun postKey(service: String, userId: String, postId: String): String =
        "$service/$userId/$postId"

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 每位作者最多翻这么多页（每页约 50 条），防止在超高产作者身上无限翻页。 */
    private const val MAX_POST_PAGES = 4
    private const val POST_PAGE_INTERVAL_MS = 200L
    private const val CREATOR_INTERVAL_MS = 300L

    /** 单帖补齐请求之间的间隔，留足 DDoS-Guard 的余量。 */
    private const val SINGLE_POST_INTERVAL_MS = 700L

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
    fun fetchPosts(site: Site, creator: Creator): List<Post>? = fetchPostsPage(site, creator, 0)

    /**
     * 拉取一页帖子。接口默认每页 50 条，用 {@code ?o=<offset>} 往后翻，
     * 翻过头会返回空数组（实测）。
     */
    private fun fetchPostsPage(site: Site, creator: Creator, offset: Int): List<Post>? {
        val url = site.postsApiUrl(creator.service, creator.userId) +
            if (offset > 0) "?o=$offset" else ""
        val response = Site.get(url) ?: return null
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
            parsePost(item)?.let { result.add(it) }
        }
        return result
    }

    private fun parsePost(item: JSONObject): Post? {
        val id = item.optString("id")
        if (id.isNullOrEmpty()) return null
        return Post(
            id = id,
            userId = item.optString("user"),
            service = item.optString("service"),
            title = item.optString("title").let { if (it.isNullOrEmpty()) null else it },
            content = item.optString("content").let { if (it.isNullOrEmpty()) null else it },
            published = item.optString("published").let { if (it.isNullOrEmpty()) null else it },
            attachments = parseAttachments(item),
        )
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
