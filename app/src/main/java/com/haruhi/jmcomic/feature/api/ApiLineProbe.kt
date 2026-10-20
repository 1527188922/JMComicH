package com.haruhi.jmcomic.feature.api

import com.haruhi.jmcomic.core.network.NetworkClientFactory
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.protocol.ApiLanguage
import com.haruhi.jmcomic.core.protocol.Crypto
import com.haruhi.jmcomic.core.protocol.Defaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException

/**
 * 线路测速的灯号，阈值照官方 App `utils/speedTestUtil.ts` 的分档：
 * 绿 < 600 ms、橙 600～1200 ms、红 ≥ 1200 ms，连不上算红。
 */
enum class LineSpeedLevel {
    /** 还没测过（列表刚打开、或者这次测速还没轮到它）。 */
    UNKNOWN,

    /** 畅通（< [SMOOTH_UPPER_MS] ms）。 */
    SMOOTH,

    /** 一般（[SMOOTH_UPPER_MS] ~ [NORMAL_UPPER_MS] ms）。 */
    NORMAL,

    /** 很慢（≥ [NORMAL_UPPER_MS] ms）。 */
    LAGGY,

    /** 连不上 / 超时 / 返回内容不对。 */
    FAILED,
}

/**
 * 一条线路的测速结果。
 *
 * @property latencyMs 首字节耗时（毫秒）。失败时为 null。
 * @property error 失败原因（[toUserMessage] 出的中文文案，可直接展示）。
 */
data class LineProbeResult(
    val level: LineSpeedLevel = LineSpeedLevel.UNKNOWN,
    val latencyMs: Long? = null,
    val error: String? = null,
)

/** 绿灯上限（毫秒）。600 以下算畅通。 */
const val SMOOTH_UPPER_MS: Long = 600

/** 橙灯上限（毫秒）。1200 及以上算很慢。 */
const val NORMAL_UPPER_MS: Long = 1_200

/** 毫秒 → 灯号。抽成纯函数，单测直接打表验证阈值。 */
fun speedLevelOf(latencyMs: Long): LineSpeedLevel = when {
    latencyMs < SMOOTH_UPPER_MS -> LineSpeedLevel.SMOOTH
    latencyMs < NORMAL_UPPER_MS -> LineSpeedLevel.NORMAL
    else -> LineSpeedLevel.LAGGY
}

/**
 * 接口线路测速。
 *
 * 打法与官方 App 的「我的 → 测速」一致：对每条线路发一次真实请求
 * `GET https://{host}/setting?app_img_shunt=1&lang={当前语言}`（带 `Token` / `Tokenparam` 签名），
 * 量**首字节耗时**。这个接口**不需要登录**（签名只依赖时间戳），所以用户没登录也能测。
 *
 * 几个刻意的取舍：
 * - **超时固定 [timeoutSeconds]，不跟随设置里的「请求超时」**：那个默认只有 3 秒，
 *   测速时会把"慢但能用"的线路误判成红灯，也会让整轮测速更快失败；
 * - **只是探活**：能拿到 `data` 就说明这条线路签得过、解析得出，不再解密（测速不需要内容）；
 * - 客户端从当前的 [clientProvider] 现派生（见 [NetworkClientFactory.deriveWithTimeout]），
 *   所以代理 / UA 设置跟其它请求完全一致，只有超时不同。
 */
class ApiLineProbe(
    private val clientProvider: () -> OkHttpClient,
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /**
     * 探针请求里的 `lang`（设置页「语言 → 接口语言」，默认简中）。
     *
     * 官方测速打的就是带 `lang` 的 `/setting`（`speedTestUtil.ts` 用
     * `localStorage.getItem("lang")`），这里照样跟当前语言走，抓包对比时与 App 一致。
     */
    private val languageProvider: () -> ApiLanguage = { ApiLanguage.DEFAULT },
) {

    /**
     * 测一条线路。**不抛异常**：失败就是 [LineSpeedLevel.FAILED] + 一句能看懂的原因，
     * 让调用方能对每一条线路独立更新界面。
     */
    suspend fun probe(host: String): LineProbeResult = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val headers = Crypto.headerParams(ts)
        val url = "https://$host/setting?app_img_shunt=1&lang=${languageProvider().code}&t=$ts"
        val request = Request.Builder()
            .url(url)
            .header("token", headers.token)
            .header("tokenparam", headers.tokenParam)
            .header("user-agent", Defaults.USER_AGENT)
            .build()

        // 连接 / 读 / 写各自 8 秒还不够：OkHttp 的连接失败重试能把一次调用拖到 20 秒，
        // 那样"测速"就变成了"看它到底多久才放弃"。所以再给一个整次调用的总上限。
        val client = NetworkClientFactory.deriveWithTimeout(
            base = clientProvider(),
            timeoutSeconds = timeoutSeconds,
            callTimeoutSeconds = timeoutSeconds.toLong(),
        )
        // 计时从发请求前开始，到拿到响应头结束（= 首字节），不含读 body 的时间
        val started = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) {
                    return@withContext LineProbeResult(
                        level = LineSpeedLevel.FAILED,
                        latencyMs = elapsedMs,
                        error = "服务器返回 ${response.code}",
                    )
                }
                val body = response.body?.string().orEmpty()
                // 只认"看起来像 JM 信封"的响应：被劫持/广告页也会返回 200，但没有加密的 data
                val envelope = runCatching {
                    ApiJson.decodeFromString(ApiEnvelope.serializer(), body)
                }.getOrNull()
                if (envelope?.data.isNullOrBlank()) {
                    return@withContext LineProbeResult(
                        level = LineSpeedLevel.FAILED,
                        latencyMs = elapsedMs,
                        error = "返回内容异常",
                    )
                }
                LineProbeResult(level = speedLevelOf(elapsedMs), latencyMs = elapsedMs)
            }
        } catch (e: IOException) {
            Timber.tag("jm").d("线路测速失败 %s：%s", host, e.javaClass.simpleName)
            // 总时长上限（callTimeout）抛的是 InterruptedIOException，不在 NetworkError.from
            // 的"超时"分支里（那里只认 SocketTimeoutException），所以先把它单独归成超时，
            // 否则界面上会把"等太久"说成"连不上"。
            val error = if (e is java.io.InterruptedIOException) {
                NetworkError.Timeout(url, e)
            } else {
                NetworkError.from(url, e)
            }
            LineProbeResult(
                level = LineSpeedLevel.FAILED,
                latencyMs = null,
                error = error.toUserMessage(),
            )
        }
    }

    companion object {
        /**
         * 测速的固定超时（秒）：连接 / 读 / 写各自 8 秒，**整次调用也封顶 8 秒**。
         *
         * 8 秒比设置里默认的 3 秒宽：跨地区线路的首字节有时要 4～6 秒，
         * 太短会把"能用但慢"判成红，也测不出真实的慢。
         * 整次调用封顶是为了让 6 条线路的测速在一轮内结束——
         * 只设读 / 连超时的话，OkHttp 的连接重试能把一次调用拖到 20 秒以上
         * （实测重现过：默认线路 19.4 秒才回来）。
         */
        const val DEFAULT_TIMEOUT_SECONDS = 8
    }
}
