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

    /** 附件静态资源的挂载前缀。 */
    public static final String DATA_PATH = "/data";

    /**
     * 站点对非浏览器 UA 较为敏感（Cloudflare），这里统一伪装成 Chrome。
     */
    public static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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
