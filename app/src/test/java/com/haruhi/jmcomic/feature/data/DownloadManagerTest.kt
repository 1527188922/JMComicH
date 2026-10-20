package com.haruhi.jmcomic.feature.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.LibraryEntity
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.core.download.FileDownloader
import com.haruhi.jmcomic.core.download.ParallelImageDownloader
import com.haruhi.jmcomic.core.network.NetworkState
import com.haruhi.jmcomic.core.protocol.Crypto
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * JM 下载编排的验收（计划 §10 P3）：
 *
 * - 下载一整话，文件数 / 命名 / 内容正确；
 * - **`blockNum == 0` 的图片与源文件字节一致（未被重新编码）**——计划点名的验收项；
 * - 已存在的图片**不重复请求**（"文件存在即跳过"）；0 字节残留算缺失；
 * - 进度精确到"第 N 话 x/y 张"，任务级数字由章节明细推导；
 * - 中断后重新执行只补缺口（已有的不重下，`.part` 会被续传用掉）；
 * - "仅 Wi-Fi"策略下进 `WAITING_NETWORK` **而不是失败**，网络恢复后自动继续；
 * - 取消能停下且不复活；重复提交不新建任务而是把章节追加进去。
 *
 * 网络用 [MockWebServer]：把 API 域名指向它，再用一个拦截器把 https 改写成 http
 * （`Defaults` 的 URL 规则是写死的 https；为了测试去改生产代码不值得）。
 * 服务端返回的 `scramble_id` 远大于章节号，所以解扰块数为 0，图片**原样落盘**——
 * 这正是"未加扰图片不重新编码"的判定条件（计划 §0 的既定决策）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: JmComicDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var tasks: DownloadTaskRepository
    private lateinit var manager: DownloadManager
    private lateinit var scope: CoroutineScope
    private lateinit var environment: FakeEnvironment

    private val albumId = 480854L
    private val folderName = "测试本子_JM480854"
    private val chapterOne = 123456L
    private val chapterTwo = 123457L

    /** 每张图的字节内容；同时用于校验落盘结果逐字节一致。 */
    private val payloads = ConcurrentHashMap<String, ByteArray>()
    private val imageRequests = ConcurrentHashMap<String, AtomicInteger>()
    private val comicReadRequests = AtomicInteger()

    /** 固定时钟：签名与解密都由 `ts` 派生，测试里必须是同一个值。 */
    private val fixedTs = 1_700_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = manifestDispatcher()
        server.start()

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JmComicDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()

        val client = OkHttpClient.Builder()
            // https -> http：把 Defaults 里写死的域名改写到 MockWebServer
            .addInterceptor { chain ->
                val original = chain.request()
                val rewritten = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .build()

        val repo = Repository(
            api = ApiClient(
                client = client,
                config = { error("下载链路不读网络设置") },
                apiDomainProvider = { server.hostName },
                nowSeconds = { fixedTs },
            ),
            albumDao = db.albumDao(),
            chapterDao = db.chapterDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentAlbumDao(),
            searchHistoryDao = db.searchHistoryDao(),
            dirs = dirs,
        )
        tasks = DownloadTaskRepository(dao = db.downloadTaskDao(), dirs = dirs)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        environment = FakeEnvironment()
        manager = DownloadManager(
            repo = repo,
            tasks = tasks,
            downloader = ParallelImageDownloader(
                downloader = FileDownloader(
                    client = client,
                    dirs = dirs,
                    maxAttempts = 1,
                    retryDelayMs = 10L,
                ),
                defaultConcurrency = 2,
            ),
            dirs = dirs,
            environment = environment,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    // ------------------------------------------------------------------
    // 正常下载
    // ------------------------------------------------------------------

    @Test
    fun `downloads every image of a chapter with the api file names`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp", "00002.webp", "00003.webp")

        val taskId = downloadAndWait(chapterOne)

        assertThat(task(taskId).status).isEqualTo(DownloadStatus.SUCCESS)
        val chapterDir = dirs.chapterDir(folderName, "第1话")
        assertThat(chapterDir.listFiles()!!.map { it.name }.sorted())
            .containsExactly("00001.webp", "00002.webp", "00003.webp")
        // 内容逐字节一致：块数为 0 时**不允许重新编码**
        for (name in listOf("00001.webp", "00002.webp", "00003.webp")) {
            assertThat(File(chapterDir, name).readBytes()).isEqualTo(payloads[name])
        }
        // 中转文件必须被清理干净
        assertThat(chapterDir.listFiles()!!.map { it.name }.filter { it.endsWith(".part") }).isEmpty()
    }

    @Test
    fun `block num zero keeps the original bytes untouched`() {
        seedAlbum()
        // 故意用一段"不是图片"的字节：只要块数是 0，引擎就不该尝试解码它
        val raw = ByteArray(512) { (it % 97).toByte() }
        seedChapter(chapterOne, "第1话", "00001.webp", rawBytes = raw)

        downloadAndWait(chapterOne)

        val file = File(dirs.chapterDir(folderName, "第1话"), "00001.webp")
        assertThat(file.readBytes()).isEqualTo(raw)
    }

    @Test
    fun `progress is recorded per chapter and aggregated on the task`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp", "00002.webp")
        seedChapter(chapterTwo, "第2话", "00005.webp")

        val taskId = downloadAndWait(chapterOne, chapterTwo)

        val task = task(taskId)
        assertThat(task.status).isEqualTo(DownloadStatus.SUCCESS)
        assertThat(task.totalChapters).isEqualTo(2)
        assertThat(task.doneChapters).isEqualTo(2)
        assertThat(task.totalImages).isEqualTo(3)
        assertThat(task.doneImages).isEqualTo(3)

        val rows = runBlocking { tasks.chapterList(taskId) }
        assertThat(rows.map { it.status }).containsExactly(DownloadStatus.SUCCESS, DownloadStatus.SUCCESS)
        assertThat(rows.first { it.chapterId == chapterOne }.doneImages).isEqualTo(2)
        assertThat(rows.first { it.chapterId == chapterOne }.totalImages).isEqualTo(2)
    }

    // ------------------------------------------------------------------
    // 跳过已下载
    // ------------------------------------------------------------------

    @Test
    fun `images already on disk are not requested again`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp", "00002.webp")
        val chapterDir = dirs.chapterDir(folderName, "第1话")
        chapterDir.mkdirs()
        File(chapterDir, "00001.webp").writeBytes(payloads.getValue("00001.webp"))

        downloadAndWait(chapterOne)

        assertThat(imageRequests.keys).containsExactly("00002.webp")
        // 已存在的那张不能被覆盖
        assertThat(File(chapterDir, "00001.webp").readBytes()).isEqualTo(payloads.getValue("00001.webp"))
    }

    @Test
    fun `a zero byte leftover is treated as missing and re-downloaded`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp")
        val chapterDir = dirs.chapterDir(folderName, "第1话")
        chapterDir.mkdirs()
        File(chapterDir, "00001.webp").writeBytes(ByteArray(0))

        downloadAndWait(chapterOne)

        assertThat(File(chapterDir, "00001.webp").readBytes()).isEqualTo(payloads.getValue("00001.webp"))
    }

    // ------------------------------------------------------------------
    // 中断与恢复
    // ------------------------------------------------------------------

    @Test
    fun `resuming after a kill only downloads the missing images`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp", "00002.webp", "00003.webp")
        // 模拟"上次下到一半被杀"：一张已完整落盘，另一张只剩 .part
        val chapterDir = dirs.chapterDir(folderName, "第1话")
        chapterDir.mkdirs()
        File(chapterDir, "00001.webp").writeBytes(payloads.getValue("00001.webp"))
        File(chapterDir, "00002.webp.part").writeBytes(ByteArray(16))

        val taskId = downloadAndWait(chapterOne)

        // 已完整的那张一个字节都不再请求
        assertThat(imageRequests.keys).containsExactly("00002.webp", "00003.webp")
        // 残留的 .part 被续传用掉后清理
        assertThat(File(chapterDir, "00002.webp.part").exists()).isFalse()
        assertThat(task(taskId).doneImages).isEqualTo(3)
    }

    // ------------------------------------------------------------------
    // 网络策略
    // ------------------------------------------------------------------

    @Test
    fun `blocked network puts the task in waiting state instead of failing it`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp")
        environment.blockMetered = true
        environment.state.value = NetworkState(online = true, metered = true)

        val taskId = submit(chapterOne)

        waitUntil("任务没有进入等待网络状态") {
            task(taskId).status == DownloadStatus.WAITING_NETWORK
        }
        // 等待网络**不是失败**（§9 第 9 条），而且一个请求都没发出去
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(task(taskId).stageText).isEqualTo(DownloadTaskRepository.STAGE_WAITING_NETWORK)

        // 切回 Wi-Fi：状态流发新值，任务必须自己接着跑
        environment.state.value = NetworkState(online = true, metered = false)
        waitUntil("网络恢复后任务没有继续") { task(taskId).status == DownloadStatus.SUCCESS }

        assertThat(File(dirs.chapterDir(folderName, "第1话"), "00001.webp").exists()).isTrue()
    }

    // ------------------------------------------------------------------
    // 取消
    // ------------------------------------------------------------------

    @Test
    fun `cancelling stops the task and it does not come back`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp", "00002.webp", "00003.webp", "00004.webp")
        // 一次只跑一张 + 每张慢 150ms，保证取消发生在传输途中
        environment.concurrency = 1
        server.dispatcher = slowDispatcher()

        val taskId = submit(chapterOne)
        waitUntil("任务没有开始") { task(taskId).status == DownloadStatus.RUNNING }
        manager.cancel(taskId)

        waitUntil("取消没有生效") { task(taskId).status == DownloadStatus.CANCELLED }
        Thread.sleep(300)
        // 取消之后不能自己复活（§D.3），章节也不能卡在"下载中"
        assertThat(task(taskId).status).isEqualTo(DownloadStatus.CANCELLED)
        assertThat(runBlocking { tasks.chapterList(taskId) }.map { it.status })
            .doesNotContain(DownloadStatus.RUNNING)
    }

    // ------------------------------------------------------------------
    // 提交语义
    // ------------------------------------------------------------------

    @Test
    fun `submitting again appends chapters instead of creating a second task`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp")
        seedChapter(chapterTwo, "第2话", "00005.webp")
        environment.blockMetered = true
        environment.state.value = NetworkState(online = true, metered = true)

        val first = runBlocking { manager.submit(album(), listOf(summary(chapterOne))) }
        val second = runBlocking { manager.submit(album(), listOf(summary(chapterTwo))) }

        assertThat(second).isInstanceOf(DownloadTaskRepository.SubmitResult.AlreadyRunning::class.java)
        val taskId = (first as DownloadTaskRepository.SubmitResult.Created).taskId
        assertThat(runBlocking { tasks.chapterList(taskId) }.map { it.chapterId })
            .containsExactly(chapterOne, chapterTwo)
        assertThat(runBlocking { db.downloadTaskDao().activeTasks() }).hasSize(1)

        environment.state.value = NetworkState(online = true, metered = false)
        waitUntil("追加的章节没有被下载") { task(taskId).status == DownloadStatus.SUCCESS }
        assertThat(task(taskId).doneChapters).isEqualTo(2)
    }

    @Test
    fun `payload round trips and survives a broken body`() {
        val payload = DownloadPayload(albumId, listOf(chapterOne, chapterTwo))
        assertThat(DownloadPayload.decode(payload.encode())).isEqualTo(payload)
        assertThat(DownloadPayload.decode(null)).isNull()
        assertThat(DownloadPayload.decode("not json")).isNull()
    }

    @Test
    fun `scramble id is requested once per chapter download`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp", "00002.webp")

        downloadAndWait(chapterOne)

        assertThat(comicReadRequests.get()).isEqualTo(1)
    }

    @Test
    fun `a chapter without an image manifest fails with a readable reason`() {
        seedAlbum()
        // 没有入库章节清单，MockWebServer 的 /comic_read 也不返回任何图片 -> 拿不到清单
        val taskId = downloadAndWait(chapterOne)

        val failed = task(taskId)
        assertThat(failed.status).isEqualTo(DownloadStatus.FAIL)
        assertThat(failed.message).contains("没有下完")
        val row = runBlocking { tasks.chapterList(taskId) }.single()
        assertThat(row.status).isEqualTo(DownloadStatus.FAIL)
        assertThat(row.message).isEqualTo("没有拿到这一话的图片清单")
    }

    @Test
    fun `retry only re-runs the chapters that are still incomplete`() {
        seedAlbum()
        seedChapter(chapterOne, "第1话", "00001.webp")
        seedChapter(chapterTwo, "第2话", "00005.webp")
        // 第 2 话的文件先不放出来 -> 第一轮只有第 1 话能成功
        val secondChapterBytes = payloads.remove("00005.webp")!!

        val taskId = downloadAndWait(chapterOne, chapterTwo)
        assertThat(task(taskId).status).isEqualTo(DownloadStatus.FAIL)
        assertThat(runBlocking { tasks.chapterList(taskId) }.first { it.chapterId == chapterOne }.status)
            .isEqualTo(DownloadStatus.SUCCESS)

        // 服务端恢复之后重试：已经下好的那一话不会再请求一次
        val firstChapterRequests = imageRequests.getValue("00001.webp").get()
        payloads["00005.webp"] = secondChapterBytes
        manager.retry(taskId)

        waitUntil("重试后任务没有完成") { task(taskId).status == DownloadStatus.SUCCESS }
        assertThat(imageRequests.getValue("00001.webp").get()).isEqualTo(firstChapterRequests)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 正常放行的服务端。 */
    private fun manifestDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            // 默认机制（ChapterSource.COMIC_READ）：`/comic_read` 一次给出图片清单 + scramble_id
            if (path.endsWith("/comic_read")) {
                comicReadRequests.incrementAndGet()
                val encrypted = Crypto.encryptDataForTest(fixedTs, """{"scramble_id":"999999999"}""")
                return MockResponse.Builder()
                    .code(200)
                    .body(Buffer().writeUtf8("""{"data":"$encrypted"}"""))
                    .build()
            }
            val name = path.substringAfterLast('/')
            val bytes = payloads[name] ?: return MockResponse.Builder().code(404).build()
            imageRequests.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            return MockResponse.Builder()
                .code(200)
                .setHeader("Content-Length", bytes.size.toString())
                .body(Buffer().write(bytes))
                .build()
        }
    }

    /** 每张图慢 150ms，用来构造"传输途中取消"。 */
    private fun slowDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val name = request.url.encodedPath.substringAfterLast('/')
            val bytes = payloads[name] ?: return MockResponse.Builder().code(404).build()
            imageRequests.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            Thread.sleep(150)
            return MockResponse.Builder()
                .code(200)
                .setHeader("Content-Length", bytes.size.toString())
                .body(Buffer().write(bytes))
                .build()
        }
    }

    /** 提交任务，不等它结束。 */
    private fun submit(vararg chapterIds: Long): Long {
        val selected = chapterIds.map { summary(it) }
        return when (val result = runBlocking { manager.submit(album(), selected) }) {
            is DownloadTaskRepository.SubmitResult.Created -> result.taskId
            is DownloadTaskRepository.SubmitResult.AlreadyRunning -> result.taskId
        }
    }

    /**
     * 提交并等它跑到终态。
     *
     * 执行器跑在 [scope]（真实线程池）上，所以这里用轮询而不是 `runTest` 的虚拟时间。
     */
    private fun downloadAndWait(vararg chapterIds: Long): Long {
        val taskId = submit(*chapterIds)
        waitUntil("任务没有结束") { completed(taskId) }
        return taskId
    }

    private fun completed(taskId: Long): Boolean = when (task(taskId).status) {
        DownloadStatus.SUCCESS, DownloadStatus.FAIL, DownloadStatus.CANCELLED -> true
        else -> false
    }

    private fun task(taskId: Long) = runBlocking { tasks.findById(taskId)!! }

    private fun summary(chapterId: Long) = ChapterSummary(
        chapterId = chapterId,
        sort = if (chapterId == chapterOne) "1" else "2",
        name = null,
        title = if (chapterId == chapterOne) "第1话" else "第2话",
    )

    private fun album() = AlbumEntity(id = albumId, name = "测试本子", albumFolderName = folderName)

    /** 基础数据 + 书库成员各写一行：下载执行器要求本子在书库里（`repo.findAlbum` 走成员表）。 */
    private fun seedAlbum() {
        runBlocking {
            db.albumDao().insertIgnore(
                AlbumBaseEntity(id = albumId, name = "测试本子", albumFolderName = folderName),
            )
            db.albumDao().insertLibraryRow(
                LibraryEntity(
                    albumId = albumId,
                    createTime = "2026-01-01 00:00:00",
                ),
            )
        }
    }

    /** 章节清单入库；[rawBytes] 用于给某一张图指定任意字节（验证"不重新编码"）。 */
    private fun seedChapter(
        chapterId: Long,
        title: String,
        vararg names: String,
        rawBytes: ByteArray? = null,
    ) {
        names.forEach { name ->
            payloads[name] = rawBytes ?: ByteArray(256) { offset -> (name.hashCode() + offset).toByte() }
        }
        runBlocking {
            db.chapterDao().replaceChapter(
                albumId,
                chapterId,
                names.mapIndexed { index, name ->
                    ChapterImageEntity(
                        albumId = albumId,
                        chapterId = chapterId,
                        chapterTitle = title,
                        chapterName = title,
                        imageFile = name,
                        imageSort = index + 1,
                    )
                },
            )
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("超时：$what")
    }

    /**
     * 可控的下载环境。
     *
     * 用"是否计费 + 只允许 Wi-Fi"表达网络策略，而不是一个布尔开关——
     * 因为 `StateFlow` 会把**相等的新值**吞掉，测试必须真的切换网络类型才能触发恢复。
     */
    private class FakeEnvironment : DownloadEnvironment {
        val state = MutableStateFlow(NetworkState(online = true, metered = false))
        var blockMetered = false
        var concurrency = 2

        override suspend fun concurrency(): Int = concurrency

        override suspend fun imageDomain(): String = "cdn.test"

        override suspend fun allowsTransfer(metered: Boolean): Boolean = !(blockMetered && metered)

        override val networkState: StateFlow<NetworkState> = state
    }
}
