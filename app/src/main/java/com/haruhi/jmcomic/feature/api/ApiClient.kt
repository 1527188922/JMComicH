package com.haruhi.jmcomic.feature.api

import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.core.protocol.Crypto
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.protocol.SearchSort
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.core.network.isNetworkTimeout
import com.haruhi.jmcomic.core.network.isRetryableApiError
import com.haruhi.jmcomic.core.network.withTimeoutRetry
import com.haruhi.jmcomic.core.settings.ResolvedNetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder

/**
 * JM 接口客户端。
 *
 * 覆盖计划 §10 P2 要求的四个接口 + 一个模板接口：
 * `/search`、`/album`、`/chapter`、`/chapter_view_template`；
 * 详情页「评论」tab 用的 `/forum`，以及分类页用的 `/categories` + `/categories/filter`
 * 也在这里。
 * **`/login` 不实现**（已确认不做 JM 账号）。
 *
 * 契约细节全部对齐参考实现 `JmcomicService`：
 * - 每次请求新建签名（`ts` 为秒级时间戳）；**重试时沿用第一次的 `ts`**，
 *   否则响应无法用同一个 key 解密（参考实现 `executeGet` 的注释明确写了这一点）；
 * - 只有**可重试的异常**才重试（网络超时，或响应空体 / 非 JSON 信封这类**内容异常**），
 *   次数来自 `jm.request.{album,search,chapter}` 与单独一项「获取分类」；
 * - 响应 JSON 的 `data` 字段是密文，用 `ts` 派生 AES-256 密钥解密。
 *
 * `ts` 的作用域刻意放在**每次请求**（而不是每次尝试）里：
 * 这样重试复用的是同一个 `ts`，满足上面第 1 条契约。
 */
class ApiClient(
    private val client: OkHttpClient,
    private val config: () -> ResolvedNetworkConfig,
    private val retryProvider: () -> RetrySettings = { RetrySettings() },
    /** 接口线路；默认取 [Defaults.DEFAULT_API_DOMAIN]。 */
    private val apiDomainProvider: () -> String = { Defaults.DEFAULT_API_DOMAIN },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** 各接口的重试次数（0 = 不自动重试）。对应 `jm.request.*.retry`。 */
    data class RetrySettings(
        val album: Int = Defaults.RETRY_DEFAULT,
        val search: Int = Defaults.RETRY_DEFAULT,
        val chapter: Int = Defaults.RETRY_DEFAULT,
        /** 「获取分类」（`/categories` + `/categories/filter`）单独一项。 */
        val category: Int = Defaults.RETRY_DEFAULT,
    )

    private val apiDomain: String get() = apiDomainProvider().ifBlank { Defaults.DEFAULT_API_DOMAIN }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** `/search`。`page` 从 1 开始，接口每页固定 80 条。 */
    suspend fun search(
        query: String,
        sort: SearchSort = SearchSort.DEFAULT,
        page: Int = 1,
    ): SearchRespDto {
        val keyword = query.trim()
        require(keyword.isNotEmpty()) { "请输入搜索关键字" }
        val safePage = if (page < 1) 1 else page

        return getDecrypted(
            path = "/search",
            params = listOf(
                "main_tag" to "0",
                "search_query" to keyword,
                "page" to safePage.toString(),
                "o" to sort.code,
            ),
            retry = retryProvider().search,
            decode = { json -> ApiJson.decodeFromString(SearchRespDto.serializer(), json) },
        )
    }

    /** `/album`：本子详情，含章节列表（`series`）。 */
    suspend fun album(aid: Long): AlbumDto = getDecrypted(
        path = "/album",
        params = listOf("id" to aid.toString()),
        retry = retryProvider().album,
        decode = { json -> ApiJson.decodeFromString(AlbumDto.serializer(), json) },
    )

    /**
     * `/categories`：分类树（主分类 + 二级分类）。
     *
     * 官方文档 §4.2：9 个主分类，其中「最新A漫」的 `slug` 是**空串**、没有二级分类；
     * 其余每个主分类带 2～6 个二级分类。真实响应里 `id` / `total_albums` 有数字与字符串
     * 混排，所以 [CategoriesRespDto] 干脆不声明它们（见该类的注释）。
     *
     * 重试次数用「获取分类」那一项（设置页「高级」里单独一项，默认 1 次）。
     */
    suspend fun categories(): CategoriesRespDto = getDecrypted(
        path = "/categories",
        params = listOf("lang" to Defaults.CATEGORY_LANG),
        retry = retryProvider().category,
        decode = { json -> ApiJson.decodeFromString(CategoriesRespDto.serializer(), json) },
    )

    /**
     * `/categories/filter`：分类内容列表。
     *
     * | 参数 | 说明 |
     * |---|---|
     * | `page` | **从 1 开始**，每页固定 80 条 |
     * | `c` | `slug` 或 `slug + "_" + 二级slug`；「最新A漫」传空串 → **不传** |
     * | `o` | 排序码（[CategorySort]）；「最新」是空串 → **不传** |
     * | `lang` | 固定 [Defaults.CATEGORY_LANG] |
     *
     * 空值参数按官方 `fetchGet` 的规则**直接丢掉**（文档 §2.4）：
     * 传 `o=` / `c=` 空串与不传在服务端语义相同，但少一个参数少一次歧义。
     */
    suspend fun categoryFilter(
        category: String,
        sort: CategorySort = CategorySort.DEFAULT,
        page: Int = 1,
    ): CategoryFilterRespDto = getDecrypted(
        path = "/categories/filter",
        params = buildList {
            add("page" to (if (page < 1) 1 else page).toString())
            if (sort.code.isNotBlank()) add("o" to sort.code)
            if (category.isNotBlank()) add("c" to category)
            add("lang" to Defaults.CATEGORY_LANG)
        },
        retry = retryProvider().category,
        decode = { json -> ApiJson.decodeFromString(CategoryFilterRespDto.serializer(), json) },
    )

    /**
     * `/forum`：漫画评论列表（详情页「评论」tab）。
     *
     * | 参数 | 说明 |
     * |---|---|
     * | `mode` | 固定 `all`（详情页看全部；`manhua` / `chat` 是官方 App 的其它场景） |
     * | `page` | **从 1 开始**，每页固定 **10** 条 |
     * | `aid` | 漫画 id |
     *
     * `total` 被服务端截断到 5000，所以最多 500 页。**匿名可读**（实测无登录态也返回 200），
     * 与本工程"不做 JM 账号"的定位一致。
     *
     * 重试次数沿用「漫画详情」那一项（`jm.request.album.retry`）：评论和详情同属详情页数据，
     * 不为它单开一个设置项。
     */
    suspend fun forum(aid: Long, page: Int = 1, mode: String = "all"): ForumRespDto = getDecrypted(
        path = "/forum",
        params = listOf(
            "mode" to mode,
            "page" to (if (page < 1) 1 else page).toString(),
            "aid" to aid.toString(),
        ),
        retry = retryProvider().album,
        decode = { json -> ApiJson.decodeFromString(ForumRespDto.serializer(), json) },
    )

    /** `/chapter`：章节详情，含图片文件名清单。 */
    suspend fun chapter(chapterId: Long): ChapterDto = getDecrypted(
        path = "/chapter",
        params = listOf("id" to chapterId.toString()),
        retry = retryProvider().chapter,
        decode = { json -> ApiJson.decodeFromString(ChapterDto.serializer(), json) },
    )

    /**
     * `/chapter_view_template`：取该章节的 `scramble_id`，用于计算解扰块数。
     *
     * 这个接口用的是**另一个 secret**（`18comicAPPContent`），且响应是 HTML 不是 JSON，
     * 所以不走 [getDecrypted]。
     *
     * **重试**：与「章节图片」（`/chapter`）共用一个配置项（`jm.request.chapter.retry`）——
     * 它同样是"打开章节就要打"的前置调用，可重试的异常（超时 / 空体 / 响应不含标记）与
     * 章节接口一致。重试次数耗尽后仍回落到兜底值 [Defaults.FALLBACK_SCRAMBLE_ID]，
     * 与参考实现 `getScrambleId` 的 catch 分支一致。
     */
    suspend fun scrambleId(chapterId: Long): Long {
        val ts = nowSeconds()
        val headers = Crypto.contentHeaderParams(ts)
        val url = buildUrl(
            Defaults.chapterViewTemplateUrl(apiDomain),
            listOf(
                "id" to chapterId.toString(),
                "v" to ts.toString(),
                "mode" to "vertical",
                "page" to "0",
                "app_img_shunt" to "1",
                "express" to "off",
            ),
        )
        return try {
            withTimeoutRetry(
                maxRetry = retryProvider().chapter,
                retryOn = { it.isRetryableApiError() },
                onRetry = { attempt, total, cause ->
                    Timber.tag("jm").d(
                        "第 %d/%d 次重试 /chapter_view_template（%s）",
                        attempt,
                        total,
                        cause.javaClass.simpleName,
                    )
                },
            ) {
                parseScrambleId(url, execute(url, headers.token, headers.tokenParam))
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.tag("jm").w(e, "取 scramble_id 失败，使用兜底值 chapterId=%d", chapterId)
            Defaults.FALLBACK_SCRAMBLE_ID
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 请求 → 取 `data` → AES 解密 → 反序列化。
     *
     * `ts` 在**进入重试之前**确定一次，重试闭包复用它 —— 这是能解密的前提。
     *
     * 「发请求 + 解析 JSON 信封」都在重试动作里：线路抖动时接口可能返回**空体**或
     * **非 JSON**（HTML 错误页），重发一次有机会拿到正确响应（见 [parseEnvelope]）。
     * 解密与字段解析留在循环外——那两类失败重试拿不到不同结果。
     */
    private suspend fun <T> getDecrypted(
        path: String,
        params: List<Pair<String, String>>,
        retry: Int,
        decode: (String) -> T,
    ): T {
        val ts = nowSeconds()
        val headers = Crypto.headerParams(ts)
        val url = buildUrl("https://$apiDomain$path", params)

        val encrypted = withTimeoutRetry(
            maxRetry = retry,
            retryOn = { it.isRetryableApiError() },
            onRetry = { attempt, total, cause ->
                Timber.tag("jm").d("第 %d/%d 次重试 %s（%s）", attempt, total, path, cause.javaClass.simpleName)
            },
        ) {
            parseEnvelope(url, execute(url, headers.token, headers.tokenParam))
        }

        if (encrypted.isNullOrBlank()) {
            throw NetworkError.Business("接口返回的 data 字段为空")
        }

        val plain = runCatching { Crypto.decryptData(ts, encrypted) }.getOrElse { e ->
            // 解密失败通常意味着 ts/密钥/接口版本不匹配，重试没有意义
            throw NetworkError.Business("响应解密失败，可能是接口版本已变更：${e.message}")
        }
        return runCatching { decode(plain) }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应字段与预期不符：${e.message}")
        }
    }

    /**
     * 响应体 → JSON 信封的 `data` 字段。
     *
     * 空体与非法 JSON 一律按**可重试**的内容异常抛（[NetworkError.Malformed.retryable]）：
     * 这两类多半是线路抖动时服务端回了个非预期响应，重发一次比直接把错误抛给用户划算。
     */
    private fun parseEnvelope(url: String, body: String): String? {
        if (body.isBlank()) {
            throw NetworkError.Malformed(url, "响应为空", retryable = true)
        }
        return runCatching {
            ApiJson.decodeFromString(ApiEnvelope.serializer(), body).data
        }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应不是合法 JSON：${e.message}", retryable = true)
        }
    }

    /** 发一次 GET 并读响应体。超时抛 [NetworkError.Timeout]，供 `withTimeoutRetry` 判定。 */
    private suspend fun execute(url: String, token: String, tokenParam: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("token", token)
                .header("tokenparam", tokenParam)
                // 参考实现第 91 行的固定 UA，必须带；OkHttp 的默认 UA 会被拒
                .header("user-agent", Defaults.USER_AGENT)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw NetworkError.HttpStatus(url, response.code)
                    }
                    response.body?.string() ?: throw NetworkError.Malformed(url, "响应体为空")
                }
            } catch (e: IOException) {
                // 先按"是否超时"分类：只有超时才允许重试（参考实现语义）
                throw if (e.isNetworkTimeout()) NetworkError.Timeout(url, e)
                else NetworkError.from(url, e)
            }
        }

    /**
     * `var scramble_id = 220980;` → 220980。
     *
     * 拿不到时抛**可重试**的内容异常（[NetworkError.Malformed.retryable]）——多半是线路
     * 抖动回了错误页；重试次数由调用方 [scrambleId] 控制，耗尽后由它兜底。
     */
    internal fun parseScrambleId(url: String, html: String): Long {
        val marker = "var scramble_id = "
        val start = html.indexOf(marker)
        if (start < 0) {
            throw NetworkError.Malformed(url, "响应里没有 $marker", retryable = true)
        }
        val value = html.substring(start + marker.length)
            .substringBefore(';')
            .trim()
        return value.toLongOrNull()
            ?: throw NetworkError.Malformed(url, "scramble_id 不是数字：$value", retryable = true)
    }

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        if (params.isEmpty()) return base
        val query = params.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return "$base?$query"
    }

    /**
     * 与 Hutool `HttpUtil.urlWithForm(..., false)` 一致的查询串编码：
     * 空格用 `%20`（不是 `+`）。JM 的搜索关键字里可能有空格与中文，必须一致。
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** 供 UI 展示的线路信息（设置页的"接口线路"）。 */
    fun currentApiDomain(): String = apiDomain
}
