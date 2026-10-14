package com.haruhi.haruhibox.feature.jm.api

import com.haruhi.haruhibox.core.jm.JmCrypto
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.core.network.NetworkError
import com.haruhi.haruhibox.core.network.isNetworkTimeout
import com.haruhi.haruhibox.core.network.withTimeoutRetry
import com.haruhi.haruhibox.core.settings.ResolvedNetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder

/**
 * JM 接口客户端。
 *
 * 覆盖计划 §10 P2 要求的四个接口 + 一个模板接口：
 * `/search`、`/album`、`/chapter`、`/chapter_view_template`。
 * **`/login` 不实现**（已确认不做 JM 账号）。
 *
 * 契约细节全部对齐参考实现 `JmcomicService`：
 * - 每次请求新建签名（`ts` 为秒级时间戳）；**重试时沿用第一次的 `ts`**，
 *   否则响应无法用同一个 key 解密（参考实现 `executeGet` 的注释明确写了这一点）；
 * - 只有**网络超时**才重试，次数来自 `jm.request.{album,search,chapter}.retry`（默认 0）；
 * - 响应 JSON 的 `data` 字段是密文，用 `ts` 派生 AES-256 密钥解密。
 *
 * `ts` 的作用域刻意放在**每次请求**（而不是每次尝试）里：
 * 这样重试复用的是同一个 `ts`，满足上面第 1 条契约。
 */
class JmApiClient(
    private val client: OkHttpClient,
    private val config: () -> ResolvedNetworkConfig,
    private val retryProvider: () -> JmRetrySettings = { JmRetrySettings() },
    /** 接口线路；默认取 [JmDefaults.DEFAULT_API_DOMAIN]。 */
    private val apiDomainProvider: () -> String = { JmDefaults.DEFAULT_API_DOMAIN },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** 各接口的重试次数（0 = 不自动重试）。对应 `jm.request.*.retry`。 */
    data class JmRetrySettings(
        val album: Int = JmDefaults.RETRY_DEFAULT,
        val search: Int = JmDefaults.RETRY_DEFAULT,
        val chapter: Int = JmDefaults.RETRY_DEFAULT,
    )

    private val apiDomain: String get() = apiDomainProvider().ifBlank { JmDefaults.DEFAULT_API_DOMAIN }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** `/search`。`page` 从 1 开始，接口每页固定 80 条。 */
    suspend fun search(
        query: String,
        sort: JmSearchSort = JmSearchSort.DEFAULT,
        page: Int = 1,
    ): JmSearchRespDto {
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
            decode = { json -> JmJson.decodeFromString(JmSearchRespDto.serializer(), json) },
        )
    }

    /** `/album`：本子详情，含章节列表（`series`）。 */
    suspend fun album(aid: Long): JmAlbumDto = getDecrypted(
        path = "/album",
        params = listOf("id" to aid.toString()),
        retry = retryProvider().album,
        decode = { json -> JmJson.decodeFromString(JmAlbumDto.serializer(), json) },
    )

    /** `/chapter`：章节详情，含图片文件名清单。 */
    suspend fun chapter(chapterId: Long): JmChapterDto = getDecrypted(
        path = "/chapter",
        params = listOf("id" to chapterId.toString()),
        retry = retryProvider().chapter,
        decode = { json -> JmJson.decodeFromString(JmChapterDto.serializer(), json) },
    )

    /**
     * `/chapter_view_template`：取该章节的 `scramble_id`，用于计算解扰块数。
     *
     * 这个接口用的是**另一个 secret**（`18comicAPPContent`），且响应是 HTML 不是 JSON，
     * 所以不走 [getDecrypted]。
     *
     * 异常时返回兜底值 [JmDefaults.FALLBACK_SCRAMBLE_ID]（220980），
     * 与参考实现 `getScrambleId` 的 catch 分支一致。
     */
    suspend fun scrambleId(chapterId: Long): Long {
        val ts = nowSeconds()
        val headers = JmCrypto.contentHeaderParams(ts)
        val url = buildUrl(
            JmDefaults.chapterViewTemplateUrl(apiDomain),
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
            val body = execute(url, headers.token, headers.tokenParam)
            parseScrambleId(body)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.tag("jm").w(e, "取 scramble_id 失败，使用兜底值 chapterId=%d", chapterId)
            JmDefaults.FALLBACK_SCRAMBLE_ID
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 请求 → 取 `data` → AES 解密 → 反序列化。
     *
     * `ts` 在**进入重试之前**确定一次，重试闭包复用它 —— 这是能解密的前提。
     */
    private suspend fun <T> getDecrypted(
        path: String,
        params: List<Pair<String, String>>,
        retry: Int,
        decode: (String) -> T,
    ): T {
        val ts = nowSeconds()
        val headers = JmCrypto.headerParams(ts)
        val url = buildUrl("https://$apiDomain$path", params)

        val body = withTimeoutRetry(maxRetry = retry, onRetry = { attempt, total, cause ->
            Timber.tag("jm").d("第 %d/%d 次重试 %s（%s）", attempt, total, path, cause.javaClass.simpleName)
        }) {
            execute(url, headers.token, headers.tokenParam)
        }

        if (body.isBlank()) {
            throw NetworkError.Malformed(url, "响应为空")
        }
        val encrypted = runCatching {
            JmJson.decodeFromString(JmEnvelope.serializer(), body).data
        }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应不是合法 JSON：${e.message}")
        }
        if (encrypted.isNullOrBlank()) {
            throw NetworkError.Business("接口返回的 data 字段为空")
        }

        val plain = runCatching { JmCrypto.decryptData(ts, encrypted) }.getOrElse { e ->
            // 解密失败通常意味着 ts/密钥/接口版本不匹配，重试没有意义
            throw NetworkError.Business("响应解密失败，可能是接口版本已变更：${e.message}")
        }
        return runCatching { decode(plain) }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应字段与预期不符：${e.message}")
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
                .header("user-agent", JmDefaults.USER_AGENT)
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

    /** `var scramble_id = 220980;` → 220980。拿不到时抛异常，由调用方兜底。 */
    internal fun parseScrambleId(html: String): Long {
        val marker = "var scramble_id = "
        val start = html.indexOf(marker)
        require(start >= 0) { "响应里没有 $marker" }
        val value = html.substring(start + marker.length)
            .substringBefore(';')
            .trim()
        return value.toLongOrNull() ?: error("scramble_id 不是数字：$value")
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
