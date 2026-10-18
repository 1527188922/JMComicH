package com.haruhi.jmcomic.feature.api

import com.haruhi.jmcomic.core.network.NetworkClientFactory
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.protocol.Defaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * 图片线路测速。
 *
 * **官方 App 有图片测速**，做法在 `utils/speedTestUtil.ts` 的 `testImageLoadTime` 里：
 * 用 `<img>` 标签去加载 `{img_host}/media/users/nopic-.gif?v=0`（一张 1～4 KB 的占位头像，
 * 一定存在、不需要登录），`onload` 到了就算量到「图源顺畅度」，`onerror` 或 10 秒超时
 * 算「无法载入」（对应文案 `member.speed_test_image_ready_time` /
 * `speed_test_image_load_fail`）。它只对**当前接口线路**测：官方 App 的 `img_host` 是从
 * 那条线路的 `/setting` 里拿的，官方根本没有「图片线路清单」这回事，所以没有现成的
 * 「13 条图片线路各测一遍」可以直接抄。
 *
 * 本工程的图片线路是用户自己在清单里选的（`LineCatalog.imageLines`），所以这里对每条
 * 线路**直接**取同一张探针图，量到的毫秒数就等于官方那个「图片就绪时间」的语义。
 * 灯号沿用 [ApiLineProbe] 的三档与官方阈值（600 / 1200 ms）——两列灯号放在一起才有可比性。
 *
 * 与接口探针的三处刻意差异：
 * - **不带签名**：图片 CDN 不校验 `Token` / `Tokenparam`（章节图片下载本来就不带），
 *   只有 UA 要带对；
 * - **量"整张图收完"而不是首字节**：探针图只有 1～4 KB，收完的那一刻就是官方 App
 *   里 `img.onload` 的时刻；
 * - **每次都换一个空连接池**：官方源码注释写明「每次測速都是對海外線路發出全新請求
 *   （含 DNS + TLS 交握，無連線重用）」。不这样做的话，正在使用的图片线路会命中
 *   `imageHttpClient` 里已有的连接，量出几十毫秒的假绿灯，另外 12 条却是冷连接。
 */
class ImageLineProbe(
    private val clientProvider: () -> OkHttpClient,
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /**
     * 探测 URL 的 scheme。生产固定 `https`；单测传 `http` 才能连上本地 `MockWebServer`。
     */
    private val scheme: String = "https",
) {

    /**
     * 测一条图片线路。**不抛异常**：失败就是 [LineSpeedLevel.FAILED] + 一句能看懂的原因，
     * 让调用方能对每一条线路独立更新界面。
     */
    suspend fun probe(host: String): LineProbeResult = withContext(Dispatchers.IO) {
        val primary = attempt(urlOf(host, PROBE_PATH))
        // 404 = 这个镜像上没有这张探针图，不等于"这条线路不能用"（图片照样可能没问题），
        // 换成官方文档里的头像兜底图再量一次。超时 / 连不上不换路径：换了也没用，
        // 只会让一轮测速慢一倍。
        if (primary.httpStatus == HTTP_NOT_FOUND) {
            attempt(urlOf(host, FALLBACK_PROBE_PATH)).result
        } else {
            primary.result
        }
    }

    /** 拼探测 URL。抽出来是为了单测能直接核对路径（官方那两个探针图路径不能写错）。 */
    internal fun urlOf(host: String, path: String): String = "$scheme://$host$path"

    /**
     * 发一次真实的取图请求，量"从发出请求到整张图收完"的耗时。
     *
     * 和接口探针一样**不解密、不重试**：能拿到 2xx + 一份不像 HTML 的内容，
     * 就说明这条 CDN 现在确实在发图。
     */
    private fun attempt(url: String): ProbeAttempt {
        val request = Request.Builder()
            .url(url)
            .header("user-agent", Defaults.USER_AGENT)
            .build()

        val started = System.nanoTime()
        return try {
            probeClient().newCall(request).execute().use { response ->
                val status = response.code
                if (!response.isSuccessful) {
                    return ProbeAttempt(
                        result = LineProbeResult(
                            level = LineSpeedLevel.FAILED,
                            latencyMs = elapsedMs(started),
                            error = "服务器返回 $status",
                        ),
                        httpStatus = status,
                    )
                }
                // 全量读完：探针图只有 1～4 KB，读完的耗时就是官方说的「图片就绪时间」
                val body = response.body?.bytes() ?: ByteArray(0)
                val elapsed = elapsedMs(started)
                val broken = body.isEmpty() || looksLikeHtml(response.header("content-type"), body)
                ProbeAttempt(
                    result = if (broken) {
                        // 200 也可能是劫持页 / 错误页，跟接口探针的"返回内容异常"同一套判断
                        LineProbeResult(
                            level = LineSpeedLevel.FAILED,
                            latencyMs = elapsed,
                            error = "返回内容异常",
                        )
                    } else {
                        LineProbeResult(level = speedLevelOf(elapsed), latencyMs = elapsed)
                    },
                    httpStatus = status,
                )
            }
        } catch (e: IOException) {
            Timber.tag("jm").d("图片线路测速失败 %s：%s", url, e.javaClass.simpleName)
            // 同 ApiLineProbe：总时长上限（callTimeout）抛的是 InterruptedIOException，
            // 不在 NetworkError.from 的"超时"分支里，先单独归成超时，
            // 否则界面上会把"等太久"说成"连不上"。
            val error = if (e is InterruptedIOException) {
                NetworkError.Timeout(url, e)
            } else {
                NetworkError.from(url, e)
            }
            ProbeAttempt(
                result = LineProbeResult(
                    level = LineSpeedLevel.FAILED,
                    latencyMs = null,
                    error = error.toUserMessage(),
                ),
            )
        }
    }

    /**
     * 探测用的客户端。
     *
     * 从 [clientProvider] 现派生（代理 / UA / 拦截器与其它图片请求完全一致，只有超时不同），
     * 再换上一个**全新的空连接池**：每次探测都是冷启动的 DNS + TLS 握手，
     * 13 条线路量到的才是同一件事。
     */
    private fun probeClient(): OkHttpClient = NetworkClientFactory.deriveWithTimeout(
        base = clientProvider(),
        timeoutSeconds = timeoutSeconds,
        callTimeoutSeconds = timeoutSeconds.toLong(),
    ).newBuilder()
        // 1 个空闲位 + 1ms 保活 = 这条连接在测完的那一瞬间就作废，下一次一定是新连接
        .connectionPool(ConnectionPool(1, 1, TimeUnit.MILLISECONDS))
        .build()

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    companion object {
        /**
         * 探针图。官方 App 量「图源顺畅度」用的就是这张，路径与 `?v=0` 都与官方一致。
         *
         * **不要换成章节大图**：这里比的是"能不能马上拿到图"，探针图越小，
         * 量到的就越是线路本身的延迟，而不是某张图的体积。
         */
        internal const val PROBE_PATH = "/media/users/nopic-.gif?v=0"

        /** 备用探针图：官方文档 §8.2 里 `photo` 为空时的头像兜底图。 */
        internal const val FALLBACK_PROBE_PATH = "/media/users/nopic-Male.gif"

        /** 只有"文件不存在"才值得换备用探针图重试。 */
        private const val HTTP_NOT_FOUND = 404

        /**
         * 测速的固定超时（秒），与 [ApiLineProbe.DEFAULT_TIMEOUT_SECONDS] 一致：
         * 连接 / 读 / 写各自 8 秒，**整次调用也封顶 8 秒**。
         * 不跟随设置里的「图片下载超时」：那个是给单张图下载用的，测速要的是固定的可比标准。
         */
        const val DEFAULT_TIMEOUT_SECONDS = 8
    }
}

/**
 * 一次探测的中间结果。
 *
 * @property result 交给界面显示的结论。
 * @property httpStatus 服务端给的状态码；网络层就失败（超时 / 连不上）时为 null。
 *   只用来判断"要不要换备用探针图再试一次"。
 */
private data class ProbeAttempt(
    val result: LineProbeResult,
    val httpStatus: Int? = null,
)

/**
 * 响应像不像一个 HTML 页面。
 *
 * 被运营商 / 门户页劫持时是 `200 + HTML`，不是 2xx 以外的错误码，只看状态码会把它
 * 当成"畅通"。真图片的第一个有效字节不可能是 `<`。
 */
internal fun looksLikeHtml(contentType: String?, body: ByteArray): Boolean {
    if (contentType?.contains("text/html", ignoreCase = true) == true) return true
    val first = body.firstOrNull {
        it != ' '.code.toByte() &&
            it != '\n'.code.toByte() &&
            it != '\r'.code.toByte() &&
            it != '\t'.code.toByte()
    }
    return first == '<'.code.toByte()
}
