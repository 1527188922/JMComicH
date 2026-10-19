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

    /**
     * JM `/categories/filter` 每页固定返回 **80** 条（官方逆向文档 §4.3）。
     *
     * 与 [SEARCH_PAGE_SIZE] 同值但**不复用常量**：两者是不同接口的契约，
     * 将来任一边变了都只该改自己那一行。
     */
    const val CATEGORY_PAGE_SIZE = 80

    /**
     * 分类接口的语言参数。
     *
     * 官方 App 的 `fetchGet` 会自动追加 `lang`（默认 `TW`，见文档 §2.4），
     * 本工程的 `/search` 没带它也一直正常；但分类的**展示名**（`同人` / `漢化` /
     * `最新A漫`）就是接口直接给的，带上 `TW` 才能拿到官方那套繁体文案。
     */
    const val CATEGORY_LANG = "TW"

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

    /**
     * 评论者头像 URL：`https://{imageDomain}/media/users/{photo}`。
     *
     * `photo` 是接口给的**文件名**（`nopic-Male.gif`、`22094869.jpg`）。
     * 和封面一样固定走 [IMAGE_DOMAIN]：头像与封面本来就是同一个图片 CDN，
     * 封面能显示就说明这条线路通。加载失败时界面显示昵称首字的占位圈，不会留空框。
     */
    fun buildAvatarUrl(photo: String, imageDomain: String = IMAGE_DOMAIN): String =
        "https://$imageDomain/media/users/${photo.trim().trimStart('/')}"

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

/**
 * 分类页的排序方式，对应 `/categories/filter` 的请求参数 `o`。
 *
 * 取值与文案逐条对齐官方 App 的 `assets/JsonData.ts` → `CatSortData`（文档 §4.3）。
 * 与 [SearchSort] **不能混用**：搜索接口没有 `mv_m` / `mp_w`，分类接口没有 `mr` / `mp`。
 */
enum class CategorySort(val code: String, val label: String) {
    /** 接口默认排序：`o` 直接**不传**（空串会被 `fetchGet` 丢掉）。 */
    LATEST("", "最新"),
    LIKE("tf", "最多愛心"),
    RANK_TOTAL("mv", "總排行"),
    RANK_MONTH("mv_m", "月排行"),
    RANK_WEEK("mp_w", "週排行"),
    ;

    companion object {
        val DEFAULT = LATEST

        fun fromCode(code: String?): CategorySort =
            entries.firstOrNull { it.code == code } ?: DEFAULT

        /**
         * 某个主分类可用的排序项。
         *
         * 官方 `Header.tsx` 的做法：`slug == ""`（最新A漫）时给**全部**排序，
         * 其它主分类把最后一项（週排行 `mp_w`）`slice(0, -1)` 掉。
         * 这里照抄，避免给分类页摆出一个点下去没结果的排序项。
         */
        fun optionsFor(mainSlug: String): List<CategorySort> =
            if (mainSlug.isBlank()) entries.toList() else entries.filter { it != RANK_WEEK }
    }
}
