package com.haruhi.jmcomic.feature.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.download.FileDownloader
import com.haruhi.jmcomic.core.download.ParallelImageDownloader
import com.haruhi.jmcomic.core.network.NetworkState
import com.haruhi.jmcomic.core.protocol.Crypto
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.ApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * "本地优先 + 取回来就缓存"的验收（用户要求第 2、4 点）：
 *
 * - 本地已有 → **一个网络请求都不发**；
 * - 本地没有 → 取回来、落盘，**第二次不再请求**；
 * - 删掉本地文件后 → 重新取（不再显示"图片缺失"）；
 * - 取回来的图与下载走的是同一个目录/同一套解扰规则，所以"一边看一边下载"不冲突。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: JmComicDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var fileDownloader: FileDownloader
    private lateinit var store: ImageStore

    private val album = AlbumEntity(
        id = 480854,
        name = "测试本子",
        albumFolderName = "测试本子_JM480854",
    )
    private val chapterId = 123456L
    private val folder = "第1话"
    private val imageFile = "00001.webp"

    private val payload = ByteArray(512) { (it % 97).toByte() }
    private val requests = ConcurrentHashMap<String, AtomicInteger>()

    /** 固定时钟：签名与解密都由 `ts` 派生，测试里必须是同一个值。 */
    private val fixedTs = 1_700_000_000L

    /**
     * `/comic_read` 返回的 `scramble_id`。
     *
     * 默认远大于章节号 → 块数 0 → 旧管线"不重新编码、原样落盘"（其余用例依赖这一点）。
     * 验证"新管线一行处理都不做"时改成 `1`：让这一话**真的需要解扰**。
     */
    private var scrambleId = "999999999"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                // 默认机制（ChapterSource.COMIC_READ）：`/comic_read` 一次给出 scramble_id
                if (path.endsWith("/comic_read")) {
                    requests.computeIfAbsent("comic_read") { AtomicInteger() }.incrementAndGet()
                    // 默认远大于章节号 -> 块数 0 -> 图片原样落盘（不重新编码）
                    return comicReadEnvelope()
                }
                val name = path.substringAfterLast('/')
                requests.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
                return MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Length", payload.size.toString())
                    .body(Buffer().write(payload))
                    .build()
            }
        }
        server.start()

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JmComicDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()

        val client = OkHttpClient.Builder()
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
                config = { error("取图链路不读网络设置") },
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
        fileDownloader = FileDownloader(client = client, dirs = dirs, maxAttempts = 1, retryDelayMs = 10L)
        store = ImageStore(
            repo = repo,
            downloader = fileDownloader,
            dirs = dirs,
            environment = FakeEnvironment(),
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `a file that is not on disk is not a cache hit`() {
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()

        // 0 字节残留不算命中（与下载层的判定一致）
        val target = store.targetFile(album, folder, imageFile)
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray(0))
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()
    }

    @Test
    fun `reading a page on demand downloads it once and caches it`() {
        val first = runBlocking { store.file(album, chapterId, folder, imageFile) }

        assertThat(first.readBytes()).isEqualTo(payload)
        assertThat(store.cachedFile(album, folder, imageFile)).isEqualTo(first)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)

        // 第二次（例如退出阅读器再进来）**一个请求都不发**
        val second = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(second).isEqualTo(first)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)
    }

    @Test
    fun `a cached page never touches the network`() {
        val target = store.targetFile(album, folder, imageFile)
        target.parentFile!!.mkdirs()
        target.writeBytes(payload)

        val file = runBlocking { store.file(album, chapterId, folder, imageFile) }

        assertThat(file).isEqualTo(target)
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** 用户要求第 4 点：删掉本地文件后再看，应该重新取回来而不是报"图片缺失"。 */
    @Test
    fun `deleting the local file makes the next read fetch it again`() {
        val target = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(target.exists()).isTrue()

        target.delete()
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()

        val again = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(again.readBytes()).isEqualTo(payload)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(2)
    }

    /** 块数为 0 时原样保留（下载与阅读走同一套规则，验收项）。 */
    @Test
    fun `block num zero keeps the downloaded bytes untouched`() {
        val file = runBlocking { store.file(album, chapterId, folder, imageFile) }
        assertThat(file.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `scramble id is fetched once per chapter`() {
        runBlocking {
            store.file(album, chapterId, folder, "00001.webp")
            store.file(album, chapterId, folder, "00002.webp")
        }
        // `/comic_read` 一次同时给出图片清单与 scramble_id，所以这一话只打一次接口
        assertThat(requests.getValue("comic_read").get()).isEqualTo(1)
    }

    @Test
    fun `the chapter directory matches the download layout`() {
        val dir = store.chapterDir(album, folder)
        assertThat(dir.name).isEqualTo(folder)
        assertThat(dir.parentFile!!.name).isEqualTo("测试本子_JM480854")
        assertThat(File(dirs.library, "测试本子_JM480854")).isEqualTo(dir.parentFile)
    }

    // ------------------------------------------------------------------
    // 原图缓存（新管线，ReaderCacheMode.RAW）
    // ------------------------------------------------------------------

    /**
     * 新管线的核心承诺：**下载只存原始字节**——不解扰、不解码、不重编码。
     *
     * 所以这里断言三件事：
     * 1. 落盘字节与响应逐字节相同（没有经过任何处理）；
     * 2. 落点在 `reader-cache/`，**不是** `library/`（不污染"是否已下载"与导出源）；
     * 3. 第二次取不再发请求。
     */
    @Test
    fun `fetchRawFile stores the untouched bytes in the reader cache`() = runBlocking {
        val file = store.fetchRawFile(album.id, chapterId, imageFile)

        assertThat(file.readBytes()).isEqualTo(payload)
        assertThat(file.length()).isEqualTo(payload.size.toLong())
        assertThat(store.cachedRawFile(album.id, chapterId, imageFile)).isEqualTo(file)

        // 落在 reader-cache/<aid>/<cid>/ 下，且不是 library 里的成品
        assertThat(file.parentFile).isEqualTo(dirs.readerCacheChapterDir(album.id, chapterId))
        assertThat(file.parentFile!!.parentFile).isEqualTo(dirs.readerCacheAlbumDir(album.id))
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()

        // 第二次（例如翻回来重新解扰）**一个请求都不发**
        val again = store.fetchRawFile(album.id, chapterId, imageFile)
        assertThat(again).isEqualTo(file)
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)
    }

    /**
     * 新管线连**需要解扰**的章节也不做任何处理：字节原样落盘，解扰推迟到显示时。
     *
     * 把 `scramble_id` 设成 1（远小于章节号）→ 这一话每张图都要搬块。旧管线在这个前提下
     * 会解码 + 无损重编码（测试里的字节根本不是合法 webp，Robolectric 下必然解不出来），
     * 所以"文件字节仍与响应逐字节一致"就证明新管线没走那条路。
     */
    @Test
    fun `fetchRawFile never descrambles even when the chapter is scrambled`() = runBlocking {
        scrambleId = "1"

        val file = store.fetchRawFile(album.id, chapterId, imageFile)

        assertThat(file.readBytes()).isEqualTo(payload)
        assertThat(store.cachedFile(album, folder, imageFile)).isNull()
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)
    }

    /** 原图缓存与"下载成品"是两套目录，互不影响：清掉缓存不会动已下载的图。 */
    @Test
    fun `raw cache and downloaded files live in different places`() = runBlocking {        val raw = store.fetchRawFile(album.id, chapterId, imageFile)
        val downloaded = store.file(album, chapterId, folder, imageFile)

        assertThat(raw.absolutePath).isNotEqualTo(downloaded.absolutePath)
        assertThat(downloaded.parentFile).isEqualTo(store.chapterDir(album, folder))
        assertThat(raw.parentFile).isEqualTo(dirs.readerCacheChapterDir(album.id, chapterId))
        // 成品在 library 里，删本子目录不会碰原图缓存，反之亦然
        assertThat(dirs.sizeOf(dirs.readerCache)).isGreaterThan(0L)
    }

    /** `clearCache()` 会把原图缓存一起清掉（它是纯缓存，不是用户资产）。 */
    @Test
    fun `clearing the cache frees the raw reader cache too`() = runBlocking {
        store.fetchRawFile(album.id, chapterId, imageFile)
        assertThat(dirs.readerCache.exists()).isTrue()

        val freed = dirs.clearCache()

        assertThat(freed).isGreaterThan(0L)
        assertThat(dirs.readerCache.exists()).isFalse()
        // 库里（用户资产）不受影响
        assertThat(dirs.library.exists()).isTrue()
    }

    /**
     * 用户实际问过的场景：**一边下载、一边看漫画**。
     *
     * 阅读器与下载任务同时要同一张图时，两边抢的是**同一个 [FileDownloader]** 的 per-file 锁，
     * 所以这张图只会真正下载一次，也不会出现"后一次下载覆盖前一次"（后到者拿到锁时看到的
     * 已经是解扰完的成品，于是安全地走 `Skipped`），后处理也只会对真正下载过的那一次执行。
     */
    @Test
    fun `reader and download task racing for the same image download it once`() = runTest {
        val chapterDir = store.chapterDir(album, folder)
        val parallel = ParallelImageDownloader(downloader = fileDownloader, defaultConcurrency = 2)
        val taskTransforms = AtomicInteger()

        val reader = async { store.file(album, chapterId, folder, imageFile) }
        val task = async {
            parallel.downloadImages(
                images = mapOf(imageFile to { server.url("/$imageFile").toString() }),
                chapterDir = chapterDir,
                transform = { _, _ ->
                    taskTransforms.incrementAndGet()
                    false
                },
            )
        }
        val published = reader.await()
        task.await()

        // 只下载一次：两边不会各下一份、也不会互相覆盖
        assertThat(requests.getValue(imageFile).get()).isEqualTo(1)
        // 后处理只对真正下载过的那一次执行（解扰不可逆，跑两遍会毁图）
        assertThat(taskTransforms.get()).isAtMost(1)
        // 两边看到的是同一个文件，且后续读取直接命中缓存
        assertThat(published).isEqualTo(File(chapterDir, imageFile))
        assertThat(store.cachedFile(album, folder, imageFile)).isEqualTo(published)
    }

    /**
     * `/comic_read` 的加密信封：`{"data":"<密文>"}`，明文是 `{"scramble_id":"999999999"}`。
     *
     * 用 [Crypto.encryptDataForTest] 按固定 `ts` 加密，客户端就能用同一个 `ts` 解出来；
     * `scramble_id` 远大于章节号，所以解扰块数为 0、图片原样落盘。
     */
    private fun comicReadEnvelope(): MockResponse {
        val encrypted = Crypto.encryptDataForTest(fixedTs, """{"scramble_id":"$scrambleId"}""")
        return MockResponse.Builder()
            .code(200)
            .body(Buffer().writeUtf8("""{"data":"$encrypted"}"""))
            .build()
    }

    private class FakeEnvironment : DownloadEnvironment {
        override suspend fun concurrency(): Int = 2
        override suspend fun imageDomain(): String = "cdn.test"
        override suspend fun allowsTransfer(metered: Boolean): Boolean = true
        override val networkState: StateFlow<NetworkState> =
            MutableStateFlow(NetworkState(online = true, metered = false))
    }
}
