package com.haruhi.jmcomic.core.protocol

/**
 * JM 接口的默认常量与 URL 规则。
 *
 * 移植自参考实现 `JmcomicService`（常量在 84～99 行，URL 规则在 1478～1483 行）。
 * 这些值全部可以在 JM 模式设置里被用户覆盖（接口线路/图片线路）。
 */
object Defaults {

    /** 默认 API 域名，参考实现 `jm.api_domain`。 */
    const val DEFAULT_API_DOMAIN = "www.cdnbea.net"

    /** 默认图片 CDN 域名，参考实现 `JmcomicService.IMAGE_DOMAIN`。 */
    const val IMAGE_DOMAIN = "cdn-msp2.jmapiproxy2.cc"

    /** 封面兜底域名，参考实现 `JmcomicService.COVER_DOMAIN`（主域名失败后重试）。 */
    const val COVER_DOMAIN = "cdn-msp3.18comic.vip"

    /**
     * 参考实现里的 zip/pdf 默认密码 `JM_DEFAULT_PASSWORD`。
     *
     * **只作参考实现的对齐记录用，不再当"用户没设过密码"时的预填兜底**
     * （用户 2026-10-15 反馈：设置里留空就该预填空，不该冒出 1234）。
     * 常量本身留着，供协议常量对照测试（`CryptoGoldenTest`）核对。
     */
    const val DEFAULT_EXPORT_PASSWORD = "1234"

    /** JM /search 每页固定返回 80 条。 */
    const val SEARCH_PAGE_SIZE = 80

    /** 在线搜索历史条数上限，参考实现 `jm.search.history.limit`。 */
    const val SEARCH_HISTORY_LIMIT = 20

    /**
     * 参考实现 `JmcomicService` 第 91 行的固定 UA，**必须带上**：
     * JM 接口对 UA 有校验，用默认的 OkHttp UA 会被拒。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    /** 参考实现 `getScrambleId` 取不到 scramble_id 时的兜底值（第 1467 行）。 */
    const val FALLBACK_SCRAMBLE_ID = 220_980L

    /** 请求超时（参考实现硬编码 10 秒）与图片下载超时（连接 4 秒 / 读 10 秒）。 */
    const val REQUEST_TIMEOUT_MS = 10_000L
    const val IMAGE_CONNECT_TIMEOUT_MS = 4_000L
    const val IMAGE_READ_TIMEOUT_MS = 10_000L

    /** 网络超时重试的基础休眠时长，指数退避 1s/2s/4s…，参考实现 `TIMEOUT_RETRY_SLEEP_MILLIS`。 */
    const val TIMEOUT_RETRY_SLEEP_MS = 1_000L

    /**
     * 各接口的默认重试次数（参考实现 `jm.request.*.retry=0`）。
     * 0 的含义是"不自动重试"，不是"请求 0 次"（计划 §9.3 明确要求 UI 这么写）。
     */
    const val RETRY_DEFAULT = 0

    /** 封面 URL：`https://{domain}/media/albums/{aid}.jpg`。 */
    fun buildCoverUrl(jmId: Long, domain: String? = null): String {
        val host = if (domain.isNullOrBlank()) IMAGE_DOMAIN else domain
        return "https://$host/media/albums/$jmId.jpg"
    }

    /** 章节图片 URL：`https://{imageDomain}/media/photos/{chapterId}/{filename}`。 */
    fun buildImgUrl(chapterId: Long, filename: String, imageDomain: String = IMAGE_DOMAIN): String =
        "https://$imageDomain/media/photos/$chapterId/$filename"

    /** 本子详情 URL。 */
    fun albumUrl(apiDomain: String = DEFAULT_API_DOMAIN): String = "https://$apiDomain/album"

    /** 搜索 URL。 */
    fun searchUrl(apiDomain: String = DEFAULT_API_DOMAIN): String = "https://$apiDomain/search"

    /** 章节详情（图片清单）URL。 */
    fun chapterUrl(apiDomain: String = DEFAULT_API_DOMAIN): String = "https://$apiDomain/chapter"

    /** 章节阅读页模板 URL，用于取 `scramble_id`。 */
    fun chapterViewTemplateUrl(apiDomain: String = DEFAULT_API_DOMAIN): String =
        "https://$apiDomain/chapter_view_template"
}

/**
 * JM `/search` 的排序方式，对应请求参数 `o`。
 *
 * 移植自参考实现 `JmSearchSortEnum`，默认排序是"最新"。
 */
enum class SearchSort(val code: String, val label: String) {
    LATEST("mr", "最新"),
    VIEW("mv", "最多观看"),
    PICTURE("mp", "最多图片"),
    LIKE("tf", "最多喜欢"),
    ;

    companion object {
        val DEFAULT = LATEST

        fun fromCode(code: String?): SearchSort =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
