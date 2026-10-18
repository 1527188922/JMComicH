package com.haruhi.jmcomic.feature.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 图片线路测速的验收测试。
 *
 * 守住三件事：
 * 1. **探针打的是官方 App 量「图源顺畅度」的那张图**
 *    （`{host}/media/users/nopic-.gif?v=0`，见 `docs/official-api-2.1.11.md` §3.6）——
 *    路径写错的话量到的就是别的东西；
 * 2. **只有"这个镜像上没有探针图"（404）才换备用图**，超时 / 5xx 不换（换路径也是白等）；
 * 3. **200 也可能是劫持页**（HTML / 空 body），那种必须判成不可用，
 *    不能因为状态码是 200 就点亮绿灯。
 */
class ImageLineProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var probe: ImageLineProbe

    /** 1×1 的 GIF89a 头 + 几十字节：足够让"首字节是不是 '<'"这类判断走真实路径。 */
    private val gifBytes = "GIF89a".toByteArray() + ByteArray(64) { (it % 97).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        // 生产走 https；这里用 http 才能连本地 MockWebServer
        probe = ImageLineProbe(clientProvider = { client }, scheme = "http")
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** `host:port` 形态，正好是 `urlOf` 拼 URL 时需要的那个"域名"。 */
    private val host: String get() = "${server.hostName}:${server.port}"

    // ---------------- 正常路径 ----------------

    @Test
    fun `probes the nopic gif the official app measures image ready time with`() = runTest {
        server.enqueue(imageResponse())

        val result = probe.probe(host)

        assertThat(result.level).isNotEqualTo(LineSpeedLevel.FAILED)
        assertThat(result.latencyMs).isNotNull()
        assertThat(result.error).isNull()

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/media/users/nopic-.gif")
        assertThat(request.url.queryParameter("v")).isEqualTo("0")
        // 图片 CDN 对 UA 有要求：用的是 Defaults.USER_AGENT
        assertThat(request.headers["User-Agent"]).contains("Mozilla/5.0")
        // 图片 CDN 不需要 JM 的签名，一个 Token 都不该带出去
        assertThat(request.headers["token"]).isNull()
    }

    // ---------------- 备用探针图 ----------------

    @Test
    fun `missing probe gif falls back to the avatar placeholder`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(imageResponse())

        val result = probe.probe(host)

        // 404 只说明"没这张图"，不等于线路不能用：换备用图之后应当测出结果
        assertThat(result.level).isNotEqualTo(LineSpeedLevel.FAILED)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/media/users/nopic-.gif")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/media/users/nopic-Male.gif")
    }

    @Test
    fun `a server error is not retried on the fallback path`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())

        val result = probe.probe(host)

        assertThat(result.level).isEqualTo(LineSpeedLevel.FAILED)
        assertThat(result.error).contains("500")
        // 只发了一次：5xx 换备用图也还是 5xx，白等一轮
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ---------------- 200 但内容不对 ----------------

    @Test
    fun `html hijack page is reported as unusable`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body>blocked</body></html>")
                .build(),
        )

        val result = probe.probe(host)

        assertThat(result.level).isEqualTo(LineSpeedLevel.FAILED)
        assertThat(result.error).contains("返回内容异常")
    }

    @Test
    fun `html body without a content type header is still reported as unusable`() = runTest {
        // 劫持页有时连 content-type 都不给，只能靠 body 判断
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().write("  \n<!DOCTYPE html><html></html>".toByteArray()))
                .build(),
        )

        val result = probe.probe(host)

        assertThat(result.level).isEqualTo(LineSpeedLevel.FAILED)
        assertThat(result.error).contains("返回内容异常")
    }

    @Test
    fun `empty body is reported as unusable`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = probe.probe(host)

        assertThat(result.level).isEqualTo(LineSpeedLevel.FAILED)
        assertThat(result.error).contains("返回内容异常")
    }

    // ---------------- 超时 ----------------

    @Test
    fun `a body that never arrives is reported as a timeout`() = runTest {
        val impatient = ImageLineProbe(
            clientProvider = { client },
            timeoutSeconds = 1,
            scheme = "http",
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "image/gif")
                .body(Buffer().write(gifBytes))
                .bodyDelay(3, TimeUnit.SECONDS)
                .build(),
        )

        val result = impatient.probe(host)

        assertThat(result.level).isEqualTo(LineSpeedLevel.FAILED)
        assertThat(result.error).contains("超时")
        // 超时不算"量到了"，不能给出一个毫秒数骗人
        assertThat(result.latencyMs).isNull()
    }

    // ---------------- 纯函数 ----------------

    @Test
    fun `html detection looks at both the content type and the first meaningful byte`() {
        assertThat(looksLikeHtml("image/gif", gifBytes)).isFalse()
        assertThat(looksLikeHtml(null, gifBytes)).isFalse()
        // 有 content-type 就够判了
        assertThat(looksLikeHtml("text/html", gifBytes)).isTrue()
        // 没 content-type 时看 body 的第一个有效字节
        assertThat(looksLikeHtml(null, "\n\t <html>".toByteArray())).isTrue()
        assertThat(looksLikeHtml(null, ByteArray(0))).isFalse()
    }

    @Test
    fun `probe url keeps the official path and the catalog host`() {
        assertThat(probe.urlOf("cdn-msp2.jmapiproxy2.cc", ImageLineProbe.PROBE_PATH))
            .isEqualTo("http://cdn-msp2.jmapiproxy2.cc/media/users/nopic-.gif?v=0")
        assertThat(ImageLineProbe.FALLBACK_PROBE_PATH).isEqualTo("/media/users/nopic-Male.gif")
    }

    private fun imageResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "image/gif")
        .body(Buffer().write(gifBytes))
        .build()
}
