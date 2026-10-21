package com.haruhi.jmcomic.core.download

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.core.network.isTimeoutRetryable
import com.haruhi.jmcomic.core.storage.LibraryDirs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载引擎验收（计划 §11.2「下载引擎：本地起一个 HTTP 服务模拟 Range 分片、超时、断流」，
 * 以及 §D.1「仅凭文件存在认定成功、Range 条件不完整」的补强项）。
 *
 * 关键用例对应计划里点名的风险：
 * - 服务端不支持 Range（忽略 Range 直接返回 200）→ [does not support range - server ignores Range and we restart from zero]；
 * - 断点续传 → [resumes from the existing part file and only fetches the missing bytes]；
 * - 原子发布与损坏文件 → [incomplete download is reported as failure and part file is kept for resume]。
 */
class FileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dirs: LibraryDirs
    private lateinit var downloader: FileDownloader

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        downloader = FileDownloader(
            client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .build(),
            dirs = dirs,
            maxAttempts = 2,
            retryDelayMs = 10L,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `downloads a whole file and publishes it atomically`() = runTest {
        server.enqueue(bodyResponse(200, payload))
        val target = File(dirs.library, "video.mp4")

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 中转文件必须被清理
        assertThat(partOf(target).exists()).isFalse()
    }

    @Test
    fun `resumes from the existing part file and only fetches the missing bytes`() = runTest {
        val target = File(dirs.library, "video.mp4")
        val part = partOf(target)
        val alreadyDownloaded = 16 * 1024
        part.parentFile!!.mkdirs()
        part.writeBytes(payload.copyOfRange(0, alreadyDownloaded))

        // 服务端返回 206，并且只回剩下的一段
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes $alreadyDownloaded-${payload.size - 1}/${payload.size}")
                .body(Buffer().write(payload.copyOfRange(alreadyDownloaded, payload.size)))
                .build(),
        )

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        val request: RecordedRequest = server.takeRequest()
        assertThat(request.headers["Range"]).isEqualTo("bytes=$alreadyDownloaded-")

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        // 拼接后的字节必须与原始内容完全一致
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `does not support range - server ignores Range and we restart from zero`() = runTest {
        val target = File(dirs.library, "video.mp4")
        val part = partOf(target)
        // 本地已有半个文件，但服务端不支持 Range（返回 200 + 完整内容）
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(1024) { 0x7F })

        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        // 必须是从头写的完整文件，而不是"半个旧文件 + 完整新文件"
        assertThat(target.length()).isEqualTo(payload.size.toLong())
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `skips download when the target already exists and is non empty`() = runTest {
        val target = File(dirs.library, "video.mp4")
        target.parentFile!!.mkdirs()
        target.writeBytes(payload)

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Skipped::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    /**
     * 本地种子：阅读器的原图缓存已经有同一张图时，下载任务**不联网**，
     * 直接拿本地字节走「后处理 → 原子发布」，产出与网络下载一致的成品。
     *
     * 这就是"一边看一边下载"不再重复下载同一张图的机制（见 `ImageStore.fetchRawFile`）。
     */
    @Test
    fun `a local seed file is published without touching the network`() = runTest {
        val seed = File(dirs.readerCacheChapterDir(1L, 2L), "00001.webp")
        seed.parentFile!!.mkdirs()
        seed.writeBytes(payload)
        val target = File(dirs.chapterDir("测试本子_JM1", "第1话"), "00001.webp")

        val outcome = downloader.download(
            urlProvider = { server.url("/never-fetched.webp").toString() },
            target = target,
            seedFile = seed,
            // 模拟"解扰"：后处理必须真的作用在发布前的中转文件上
            beforePublish = { part -> part.writeBytes(part.readBytes().reversedArray()) },
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload.reversedArray())
        assertThat(server.requestCount).isEqualTo(0)
        // 中转文件不留、种子文件不动（阅读器还要继续用那份原图）
        assertThat(partOf(target).exists()).isFalse()
        assertThat(seed.readBytes()).isEqualTo(payload)
    }

    /** 种子文件读不动（这里用 0 字节模拟）时不能"发布一张空图"，要回落到正常网络下载。 */
    @Test
    fun `an unusable seed falls back to the network`() = runTest {
        val seed = File(dirs.readerCache, "empty.webp").apply { parentFile?.mkdirs() }
        val target = File(dirs.library, "fallback.webp")
        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download(
            urlProvider = { server.url("/fallback.webp").toString() },
            target = target,
            seedFile = seed,
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `truncated download is rejected when the caller knows the expected size`() = runTest {
        val target = File(dirs.library, "video.mp4")
        val half = payload.copyOfRange(0, payload.size / 2)
        // 服务端用 chunked 声明 64KB 却只给 32KB（模拟断流），两次尝试都断
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .chunkedBody(Buffer().write(half), payload.size)
                    .build(),
            )
        }

        val outcome = downloader.download(
            urlProvider = { server.url("/video.mp4").toString() },
            target = target,
            // 调用方已知总长时传进来就能识别断流（JM 接口会给出图片的 size）。
            // **这是防"静默截断"的唯一可靠手段**：服务端没给 Content-Length 时，
            // 客户端无法凭空判断少收的字节。
            expectedTotal = payload.size.toLong(),
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        // 不能把不完整的文件当成成品发布
        assertThat(target.exists()).isFalse()
        // 保留 .part 供下次续传
        val part = partOf(target)
        assertThat(part.exists()).isTrue()
        assertThat(part.length()).isLessThan(payload.size.toLong())
    }

    @Test
    fun `without any size information the engine accepts what the server sent`() = runTest {
        val target = File(dirs.library, "video.mp4")
        val half = payload.copyOfRange(0, payload.size / 2)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .chunkedBody(Buffer().write(half), payload.size)
                .build(),
        )

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        // 文档化这个约束：既没有 Content-Length 也没有调用方给的 size 时，
        // "完整"的判定只能退化为"文件非空"。调用方（Repository / ImageStore）必须尽量给出 size。
        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.length()).isEqualTo(half.size.toLong())
    }

    @Test
    fun `http error code fails fast without publishing`() = runTest {
        val target = File(dirs.library, "video.mp4")
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = downloader.download({ server.url("/missing.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).error).isInstanceOf(NetworkError.HttpStatus::class.java)
        assertThat(target.exists()).isFalse()
        // 4xx 不重试
        assertThat(server.requestCount).isEqualTo(1)
    }

    /**
     * 图片**只对网络超时重试**（用户 2026-10-20 要求）：5xx 直接判失败，不重发。
     *
     * 次数本身由设置页「高级 → 图片加载/下载失败重试次数」控制（引擎侧换算成
     * `maxAttempts`，setUp 里固定 2）；这里验证的是"哪些错误值得重发"。
     */
    @Test
    fun `server error is not retried because only timeouts are retryable`() = runTest {
        val target = File(dirs.library, "video.mp4")
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).error)
            .isInstanceOf(NetworkError.HttpStatus::class.java)
        // 5xx 不重试：只允许一次请求（备用响应还在队列里，没人取）
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(target.exists()).isFalse()
    }

    /**
     * 网络超时会按配置的次数重试，并且**每次尝试都重新解析直链**
     * （`urlProvider` 每轮都调一次——JM 图片线路会轮换）。
     *
     * 用拦截器在第一次尝试时抛 `SocketTimeoutException` 来模拟超时：
     * 比让服务器真的挂住快，也不会把测试拖到读超时。
     */
    @Test
    fun `timeout is retried and url is re-resolved each attempt`() = runTest {
        val attempts = AtomicInteger()
        val flaky = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (attempts.incrementAndGet() == 1) throw SocketTimeoutException("read timed out")
                chain.proceed(chain.request())
            }
            .build()
        val engine = FileDownloader(
            client = flaky,
            dirs = dirs,
            maxAttempts = 2,
            retryDelayMs = 10L,
        )
        server.enqueue(bodyResponse(200, payload))
        val target = File(dirs.library, "video.mp4")
        var resolveCount = 0

        val outcome = engine.download(
            urlProvider = {
                resolveCount++
                server.url("/video.mp4").toString()
            },
            target = target,
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(resolveCount).isEqualTo(2)
    }

    /** 重试判定表：图片下载**只有超时**值得重发。 */
    @Test
    fun `only timeouts are retryable for image downloads`() {
        assertThat(NetworkError.Timeout("u", SocketTimeoutException()).isTimeoutRetryable()).isTrue()
        assertThat(NetworkError.HttpStatus("u", 503).isTimeoutRetryable()).isFalse()
        assertThat(NetworkError.HttpStatus("u", 429).isTimeoutRetryable()).isFalse()
        assertThat(
            NetworkError.Unreachable("u", UnknownHostException("host")).isTimeoutRetryable(),
        ).isFalse()
        assertThat(NetworkError.Malformed("u", "bad json", retryable = true).isTimeoutRetryable()).isFalse()
        assertThat(NetworkError.Cancelled().isTimeoutRetryable()).isFalse()
    }

    @Test
    fun `range 416 drops the local offset and restarts from scratch`() = runTest {
        val target = File(dirs.library, "video.mp4")
        val part = partOf(target)
        // 本地偏移比资源还长（例如直链换成了另一个更短的资源）
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(payload.size + 4096))

        server.enqueue(
            MockResponse.Builder()
                .code(416)
                .setHeader("Content-Range", "bytes */${payload.size}")
                .build(),
        )
        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 第二次请求必须**不带** Range（已经放弃旧偏移）
        server.takeRequest()
        assertThat(server.takeRequest().headers["Range"]).isNull()
    }

    @Test
    fun `content range start mismatch triggers a clean restart`() = runTest {
        val target = File(dirs.library, "video.mp4")
        val part = partOf(target)
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(2048))

        // 请求 bytes=2048-，服务端却从 0 开始给（区间对不上）
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes 0-${payload.size - 1}/${payload.size}")
                .body(Buffer().write(payload))
                .build(),
        )
        server.enqueue(bodyResponse(200, payload))

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `content range parsing handles the documented forms`() {
        assertThat(FileDownloader.parseRangeStart("bytes 123-456/789")).isEqualTo(123L)
        assertThat(FileDownloader.parseRangeTotal("bytes 123-456/789")).isEqualTo(789L)
        assertThat(FileDownloader.parseRangeTotal("bytes 123-456/*")).isNull()
        assertThat(FileDownloader.parseRangeStart("garbage")).isNull()
        assertThat(FileDownloader.parseRangeTotal(null)).isNull()
    }

    @Test
    fun `completedBytes and isComplete reflect disk state for progress recovery`() {
        val target = File(dirs.library, "video.mp4")
        target.parentFile!!.mkdirs()
        assertThat(downloader.completedBytes(target)).isEqualTo(0L)
        assertThat(downloader.isComplete(target, payload.size.toLong())).isFalse()

        target.writeBytes(payload.copyOfRange(0, 100))
        assertThat(downloader.completedBytes(target)).isEqualTo(100L)

        target.writeBytes(payload)
        assertThat(downloader.isComplete(target, payload.size.toLong())).isTrue()
        // 长度未知时只要求"文件非空"
        assertThat(downloader.isComplete(target, 0L)).isTrue()
    }

    // ------------------------------------------------------------------
    // 发布前处理（JM 解扰走的就是这个钩子）
    // ------------------------------------------------------------------

    /**
     * 后处理必须在"图片对最终路径可见之前"完成。
     *
     * 调用方判断"这张图在不在"只看目标文件是否存在（`skipIfComplete`），所以**先发布再处理**
     * 会留下一个"已经在最终路径上、内容却还是加扰的"中间态：阅读器会把它当成成品图显示出来，
     * 而且之后永远不会再处理它（用户反馈的"快速退出阅读器再进来看到未解扰的图"）。
     */
    @Test
    fun `beforePublish runs on the part file while the target is still invisible`() = runTest {
        server.enqueue(bodyResponse(200, payload))
        val target = File(dirs.library, "00001.webp")
        // 解扰会重新编码，长度也会变——钩子改的就是随后被发布的那份文件
        val processed = "descrambled".toByteArray()
        val calls = AtomicInteger()
        val sawTarget = AtomicBoolean(false)
        val sawPartFile = AtomicBoolean(false)

        val outcome = downloader.download(
            urlProvider = { server.url("/00001.webp").toString() },
            target = target,
            beforePublish = { file ->
                calls.incrementAndGet()
                if (target.exists()) sawTarget.set(true)
                if (file.name.endsWith(FileDownloader.PART_SUFFIX)) sawPartFile.set(true)
                file.writeBytes(processed)
            },
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(calls.get()).isEqualTo(1)
        // 钩子跑在中转文件上，此时最终路径还不存在
        assertThat(sawPartFile.get()).isTrue()
        assertThat(sawTarget.get()).isFalse()
        // 发布出去的必须是处理后的内容，而且中转文件被清理干净
        assertThat(target.readBytes()).isEqualTo(processed)
        assertThat(partOf(target).exists()).isFalse()
    }

    /**
     * "一边下载、一边看漫画"时，同一张图会被两个调用方同时要（阅读器 + 下载任务）。
     * 后处理只能对**真正下载过的那一次**执行：解扰不可逆，跑两遍会把图毁掉。
     */
    @Test
    fun `concurrent callers for the same file process it exactly once`() = runTest {
        repeat(4) { server.enqueue(bodyResponse(200, payload)) }
        val target = File(dirs.library, "00001.webp")
        val calls = AtomicInteger()

        val outcomes = (1..4).map {
            async {
                downloader.download(
                    urlProvider = { server.url("/00001.webp").toString() },
                    target = target,
                    beforePublish = { calls.incrementAndGet() },
                )
            }
        }.awaitAll()

        assertThat(outcomes.count { it is DownloadOutcome.Downloaded }).isEqualTo(1)
        assertThat(outcomes.count { it is DownloadOutcome.Skipped }).isEqualTo(3)
        // 只下载一次、只处理一次
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(calls.get()).isEqualTo(1)
    }

    /**
     * 后处理失败时**宁可判失败**，也不能把未处理的字节发布到最终路径：
     * 调用方只按"文件在不在"判断完成，发出去的半成品会被永远当成成品图。
     */
    @Test
    fun `a failing beforePublish hook publishes nothing and does not retry`() = runTest {
        server.enqueue(bodyResponse(200, payload))
        val target = File(dirs.library, "00001.webp")

        val outcome = downloader.download(
            urlProvider = { server.url("/00001.webp").toString() },
            target = target,
            beforePublish = { error("解扰失败") },
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).error)
            .isInstanceOf(NetworkError.Malformed::class.java)
        // 最终路径上什么都不能留下
        assertThat(target.exists()).isFalse()
        // 半成品也不留：后处理可能已经把 .part 写坏，留着续传会把坏数据拼进来
        assertThat(partOf(target).exists()).isFalse()
        // 本地处理失败重试没有意义（引擎配的是 maxAttempts = 2，这里只允许一次请求）
        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun partOf(target: File) = File(target.parentFile, target.name + FileDownloader.PART_SUFFIX)

    /** 带显式 Content-Length 的响应，用于模拟"声明长度与实际长度不一致"。 */
    private fun bodyResponse(code: Int, bytes: ByteArray) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Length", bytes.size.toString())
        .body(Buffer().write(bytes))
        .build()
}
