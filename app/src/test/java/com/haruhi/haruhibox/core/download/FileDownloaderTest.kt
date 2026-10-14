package com.haruhi.haruhibox.core.download

import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.network.NetworkError
import com.haruhi.haruhibox.core.storage.LibraryDirs
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载引擎验收（计划 §11.2「下载引擎：本地起一个 HTTP 服务模拟 Range 分片、超时、断流」，
 * 以及 §D.1「仅凭文件存在认定成功、Range 条件不完整」的补强项）。
 *
 * 关键用例对应计划里点名的风险：
 * - B站 CDN 是否支持 Range → [does not support range - server ignores Range and we restart from zero]；
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
        val target = File(dirs.bili, "video.mp4")

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 中转文件必须被清理
        assertThat(partOf(target).exists()).isFalse()
    }

    @Test
    fun `resumes from the existing part file and only fetches the missing bytes`() = runTest {
        val target = File(dirs.bili, "video.mp4")
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
        val target = File(dirs.bili, "video.mp4")
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
        val target = File(dirs.bili, "video.mp4")
        target.parentFile!!.mkdirs()
        target.writeBytes(payload)

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Skipped::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `truncated download is rejected when the caller knows the expected size`() = runTest {
        val target = File(dirs.bili, "video.mp4")
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
            // B站接口会给出 durl 的 size，传进来就能识别断流。
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
        val target = File(dirs.bili, "video.mp4")
        val half = payload.copyOfRange(0, payload.size / 2)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .chunkedBody(Buffer().write(half), payload.size)
                .build(),
        )

        val outcome = downloader.download({ server.url("/video.mp4").toString() }, target)

        // 文档化这个约束：既没有 Content-Length 也没有调用方给的 size 时，
        // "完整"的判定只能退化为"文件非空"。调用方（B站/JM 仓库）必须尽量给出 size。
        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.length()).isEqualTo(half.size.toLong())
    }

    @Test
    fun `http error code fails fast without publishing`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = downloader.download({ server.url("/missing.mp4").toString() }, target)

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).error).isInstanceOf(NetworkError.HttpStatus::class.java)
        assertThat(target.exists()).isFalse()
        // 4xx 不重试
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `server error is retried and url is re-resolved each attempt`() = runTest {
        val target = File(dirs.bili, "video.mp4")
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(bodyResponse(200, payload))
        var resolveCount = 0

        val outcome = downloader.download(
            urlProvider = {
                resolveCount++
                server.url("/video.mp4").toString()
            },
            target = target,
        )

        assertThat(outcome).isInstanceOf(DownloadOutcome.Downloaded::class.java)
        assertThat(target.readBytes()).isEqualTo(payload)
        // 每次尝试都重新解析直链（B站直链会过期）
        assertThat(resolveCount).isEqualTo(2)
    }

    @Test
    fun `range 416 drops the local offset and restarts from scratch`() = runTest {
        val target = File(dirs.bili, "video.mp4")
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
        val target = File(dirs.bili, "video.mp4")
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
        val target = File(dirs.bili, "video.mp4")
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
        val target = File(dirs.bili, "00001.webp")
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
        val target = File(dirs.bili, "00001.webp")
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
        val target = File(dirs.bili, "00001.webp")

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
