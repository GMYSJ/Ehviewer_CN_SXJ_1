package com.hippo.ehviewer.util;

import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Kemono / Pawchive 站点链接构造工具。
 *
 * <p>两个站点是同一套实现的分支，URL 规则基本一致。
 * 注意：只有 {@code /posts?q=<keyword>} 才是真搜索；
 * {@code /artists?q=} 会忽略查询词、始终返回默认作者列表，不要拿它做搜索跳转。
 */
public final class KemonoHelper {

    /**
     * Kemono 主站。历史上用过 kemono.party / kemono.su，当前（2026）为 kemono.cr，
     * 若日后迁移改这一行即可。
     */
    public static final String KEMONO_BASE = "https://kemono.cr";

    /** Pawchive 主站。 */
    public static final String PAWCHIVE_BASE = "https://pawchive.pw";

    /**
     * 附件静态资源的挂载前缀。
     *
     * <p>实测：{@code {base}/data<path>} 一律返回 404，不能用来下载附件。
     * 真实地址是帖子页 HTML 里的 CDN 签名直链，见 SiteApi 的直链提取。
     */
    public static final String DATA_PATH = "/data";

    /**
     * 统一使用的 User-Agent。
     *
     * <p><b>不要改成浏览器 UA。</b>Pawchive 的 CDN（t1.pawchive.pw）由 DDoS-Guard 保护，
     * 其拦截页原文写着 “pretending to be a browser will result in a block”。
     * 实测同一条签名直链：伪装 Chrome 返回 403，换成诚实可识别的 UA 返回 200。
     * 对方同时要求 “identifiable user agent, preferably with a way to contact you”，
     * 因此这里如实标识自身并附联系方式。
     */
    public static final String USER_AGENT =
            "EhViewer-SiteDownload/1.0 (Android; contact: github.com/GMYSJ/Ehviewer_CN_SXJ_1)";

    private static final String QUERY_KEYWORD = "q";
    private static final String PREFIX_ARTIST = "artist:";

    private KemonoHelper() {
    }

    /**
     * 拼接 {@code <base>/posts?q=<keyword>}。
     *
     * @return 关键词或 base 为空时返回 {@code null}
     */
    @Nullable
    public static String buildSearchUrl(@Nullable String base, @Nullable String artist) {
        String keyword = normalize(artist);
        if (null == base || null == keyword) {
            return null;
        }
        return Uri.parse(base).buildUpon()
                .appendPath("posts")
                .appendQueryParameter(QUERY_KEYWORD, keyword)
                .build()
                .toString();
    }

    /**
     * 拼接附件静态下载地址 {@code <base>/data<path>}。
     */
    @Nullable
    public static String buildDataUrl(@Nullable String base, @Nullable String path) {
        if (null == base || null == path || path.isEmpty()) {
            return null;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + DATA_PATH + path;
    }

    /**
     * 归一化 E-Hentai 侧的关键词：
     * <ol>
     *     <li>去掉可能带上的 {@code artist:} 命名空间前缀；</li>
     *     <li>E-Hentai 多作者用 {@code |} 分隔，取第一个（一次只搜一个词）；</li>
     *     <li>去掉标签搜索包裹的引号与 {@code $} 后缀；</li>
     *     <li>合并连续空白并去首尾空格。</li>
     * </ol>
     *
     * @return 归一化关键词，无有效内容时返回 {@code null}
     */
    @Nullable
    public static String normalize(@Nullable String artist) {
        if (null == artist) {
            return null;
        }
        String s = artist.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.regionMatches(true, 0, PREFIX_ARTIST, 0, PREFIX_ARTIST.length())) {
            s = s.substring(PREFIX_ARTIST.length());
        }
        int separator = s.indexOf('|');
        if (separator >= 0) {
            s = s.substring(0, separator);
        }
        // 标签搜索会把词包成 "xxx$"，这里还原成裸关键词
        while (s.startsWith("\"")) {
            s = s.substring(1);
        }
        while (s.endsWith("\"") || s.endsWith("$")) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }
}
