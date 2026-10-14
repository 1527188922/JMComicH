package com.haruhi.haruhibox.feature.jm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.codec.ArchiveWriter
import com.haruhi.haruhibox.core.codec.PdfWriter
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.storage.LibraryDirs
import com.haruhi.haruhibox.feature.jm.api.JmApiClient
import com.haruhi.haruhibox.testing.FakeExportPublisher
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 导出编排的验收（计划 §10 P4）。
 *
 * 覆盖三件事：
 * 1. **缺图预检**：缺图时能说清"哪一话、缺多少张"，界面据此拦下导出；
 * 2. **跑通**：zip/pdf 都能生成、都经过"交付给系统目录"这一步，
 *    任务状态落到 SUCCESS（进度进任务页）；
 * 3. **重复提交不会排两份**（同一本 + 同一格式只允许一个活跃导出）。
 *
 * 加密相关的用例在 `ExportInstrumentedTest`（真机）——Robolectric 里
 * zip4j 的 AES 与 PDFBox 的 AES 都会撞上 JDK provider 验签问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JmExportManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: HaruhiBoxDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var repo: JmRepository
    private lateinit var tasks: DownloadTaskRepository
    private lateinit var manager: JmExportManager
    private lateinit var publisher: FakeExportPublisher
    private lateinit var scope: CoroutineScope

    private val albumId = 480854L
    private val folderName = "测试本子_JM480854"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        repo = JmRepository(
            api = JmApiClient(client = OkHttpClient(), config = { error("导出不联网") }),
            albumDao = db.jmAlbumDao(),
            chapterDao = db.jmChapterDao(),
            favoriteDao = db.jmFavoriteDao(),
            recentDao = db.jmRecentAlbumDao(),
            searchHistoryDao = db.jmSearchHistoryDao(),
            dirs = dirs,
        )
        tasks = DownloadTaskRepository(dao = db.downloadTaskDao(), dirs = dirs)
        publisher = FakeExportPublisher(temp.newFolder("downloads"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = JmExportManager(
            repo = repo,
            tasks = tasks,
            dirs = dirs,
            archiveWriter = ArchiveWriter(),
            pdfWriter = PdfWriter(),
            publisher = publisher,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // ------------------------------------------------------------------
    // 缺图预检
    // ------------------------------------------------------------------

    @Test
    fun `precheck reports chapters with missing images`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", "00002.webp", onDisk = 2)
        seedChapter(2L, "第2话", "00001.webp", "00002.webp", onDisk = 1)

        val precheck = runBlocking { manager.precheck(album()) }

        assertThat(precheck.totalImages).isEqualTo(4)
        assertThat(precheck.readyImages).isEqualTo(3)
        assertThat(precheck.isComplete).isFalse()
        assertThat(precheck.missingChapters).hasSize(1)
        assertThat(precheck.missingChapters[0].title).isEqualTo("第2话")
        assertThat(precheck.missingChapters[0].missing).isEqualTo(1)
    }

    @Test
    fun `precheck is complete when every image is on disk`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", "00002.webp", onDisk = 2)

        val precheck = runBlocking { manager.precheck(album()) }

        assertThat(precheck.isComplete).isTrue()
        assertThat(precheck.missingImages).isEqualTo(0)
    }

    /**
     * **一话都没下载过**的本子也要被预检拦下来（用户反馈的 bug）。
     *
     * 之前只有"下过一部分"的本子才会弹"先补下载"：这种本子有图片清单，
     * 能算出缺几张；而一话都没下过的本子连清单都没有，被当成"没有缺失"跳过了，
     * 于是静默导出缺页的包。现在它们以 `hasImageList = false` 列进缺失清单。
     */
    @Test
    fun `precheck reports chapters that were never downloaded`() {
        // 只有书库记录：没有 series、没有章节图片清单、磁盘上也没有本子目录
        seedAlbum()

        val precheck = runBlocking { manager.precheck(album()) }

        assertThat(precheck.isComplete).isFalse()
        assertThat(precheck.missingChapters).hasSize(1)
        assertThat(precheck.missingChapters[0].hasImageList).isFalse()
        // 说不出缺几张（清单还没拉过），所以这两个数字是 0
        assertThat(precheck.missingImages).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // zip
    // ------------------------------------------------------------------

    @Test
    fun `exporting a zip publishes it and marks the task successful`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", "00002.webp", onDisk = 2)
        seedChapter(2L, "第2话", "00001.webp", onDisk = 1)

        val taskId = exportAndWait(JmExportFormat.ZIP, password = null)

        val task = runBlocking { tasks.findById(taskId) }!!
        assertThat(task.status).isEqualTo(DownloadStatus.SUCCESS)
        assertThat(task.type).isEqualTo(DownloadType.JM_EXPORT_ZIP)
        assertThat(task.stageText).contains("下载")   // "已保存到 下载/HaruHiBox"

        // 成品交到了"系统目录"（这里是假的 publisher），且内容完整
        val published = File(publisher.dir, "$folderName.zip")
        assertThat(publisher.publishedNames).containsExactly("$folderName.zip")
        assertThat(published.exists()).isTrue()
        assertThat(ZipFile(published).fileHeaders.map { it.fileName })
            .containsExactly("第1话/00001.webp", "第1话/00002.webp", "第2话/00001.webp")

        // 私有目录里的中转文件要清理掉
        assertThat(dirs.export.listFiles().orEmpty().map { it.name }).isEmpty()
    }

    // ------------------------------------------------------------------
    // pdf
    // ------------------------------------------------------------------

    @Test
    fun `exporting a pdf publishes it with one page per image`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", "00002.webp", onDisk = 2)

        val taskId = exportAndWait(JmExportFormat.PDF, password = null)

        assertThat(runBlocking { tasks.findById(taskId) }!!.status).isEqualTo(DownloadStatus.SUCCESS)
        val published = File(publisher.dir, "$folderName.pdf")
        assertThat(published.exists()).isTrue()
        PDDocument.load(published).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(2)
            assertThat(doc.documentCatalog.documentOutline.firstChild.title).isEqualTo("目录")
        }
    }

    // ------------------------------------------------------------------
    // 去重
    // ------------------------------------------------------------------

    @Test
    fun `submitting twice reuses the running export`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)

        val first = runBlocking { manager.submit(album(), JmExportFormat.ZIP, null) }
        val second = runBlocking { manager.submit(album(), JmExportFormat.ZIP, null) }

        assertThat(second).isInstanceOf(DownloadTaskRepository.SubmitResult.AlreadyRunning::class.java)
        assertThat(runBlocking { db.downloadTaskDao().activeTasks() }).hasSize(1)
        waitUntil("导出没有结束") { runBlocking { tasks.findById((first as DownloadTaskRepository.SubmitResult.Created).taskId)!!.status.isFinishedOrFailed() } }
    }

    @Test
    fun `a different format is a different task`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)

        exportAndWait(JmExportFormat.ZIP, password = null)
        exportAndWait(JmExportFormat.PDF, password = null)

        // 两个都完成，各自留一条记录
        val finished = runBlocking { db.downloadTaskDao().activeTasks() }
        assertThat(finished).isEmpty()
        assertThat(publisher.publishedNames).containsExactly("$folderName.zip", "$folderName.pdf")
    }

    @Test
    fun `export fails with a readable reason when there are no local images`() {
        seedAlbum()
        // 完全不 seed 图片，也没有目录

        val taskId = exportAndWait(JmExportFormat.ZIP, password = null)

        val task = runBlocking { tasks.findById(taskId) }!!
        assertThat(task.status).isEqualTo(DownloadStatus.FAIL)
        assertThat(task.message).contains("请先下载")
        assertThat(publisher.publishedNames).isEmpty()
    }

    // ------------------------------------------------------------------
    // 成品查询 / 删除（"会覆盖"提示与"删除本地文件"都用得上，用户要求第 5、6、7 点）
    // ------------------------------------------------------------------

    @Test
    fun `outputExists tells whether that format is already in the download folder`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)

        assertThat(runBlocking { manager.outputExists(album(), JmExportFormat.ZIP) }).isFalse()

        exportAndWait(JmExportFormat.ZIP, password = null)

        assertThat(runBlocking { manager.outputExists(album(), JmExportFormat.ZIP) }).isTrue()
        // 另一种格式没导出过，不能跟着一起说"已存在"
        assertThat(runBlocking { manager.outputExists(album(), JmExportFormat.PDF) }).isFalse()
    }

    @Test
    fun `outputUri finds the published file by its display name`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)
        exportAndWait(JmExportFormat.ZIP, password = null)

        // 假 publisher 的 uriOf 恒为 null（见 FakePublisher 注释），这里只验证名字对得上：
        // 名字错了就不会去查（outputNameOf 返回 null 时直接给 null）
        assertThat(manager.outputNameOf(album(), JmExportFormat.ZIP)).isEqualTo("$folderName.zip")
        assertThat(runBlocking { manager.outputUri("$folderName.zip") }).isNull()
    }

    @Test
    fun `deleting an album wipes both exported formats`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)
        exportAndWait(JmExportFormat.ZIP, password = null)
        exportAndWait(JmExportFormat.PDF, password = null)

        val removed = runBlocking { manager.deleteOutputs(album()) }

        assertThat(removed).isEqualTo(2)
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.pdf").exists()).isFalse()
        // 已经删干净之后再删一次不该报错，也不该数成"删掉了"
        assertThat(runBlocking { manager.deleteOutputs(album()) }).isEqualTo(0)
    }

    @Test
    fun `deleting a single output keeps the other format`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)
        exportAndWait(JmExportFormat.ZIP, password = null)
        exportAndWait(JmExportFormat.PDF, password = null)

        val ok = runBlocking { manager.deleteOutput("$folderName.pdf") }

        assertThat(ok).isTrue()
        assertThat(File(publisher.dir, "$folderName.pdf").exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isTrue()
    }

    // ------------------------------------------------------------------
    // 成品信息与"按有没有 zip/pdf 筛选"（用户第九轮要求第 1、2 点）
    // ------------------------------------------------------------------

    @Test
    fun `exportsOf reports size and creation time of every format on disk`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)
        val generatedAt = 1_700_000_000_000L
        publisher.seed("$folderName.zip", bytes = 64, modifiedAt = generatedAt)

        val exports = runBlocking { manager.exportsOf(album()) }

        // 只有 zip：pdf 没导出过就不该出现在列表里
        assertThat(exports.map { it.format }).containsExactly(JmExportFormat.ZIP)
        assertThat(exports.single().file.displayName).isEqualTo("$folderName.zip")
        assertThat(exports.single().file.sizeBytes).isEqualTo(64L)
        assertThat(exports.single().file.createdAt).isEqualTo(generatedAt)
    }

    @Test
    fun `exportsOf is empty when nothing was exported yet`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)

        assertThat(runBlocking { manager.exportsOf(album()) }).isEmpty()
    }

    @Test
    fun `presentFormats maps each album to the formats sitting in the download folder`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)
        val other = JmAlbumEntity(id = 572339L, name = "另一本", albumFolderName = "另一本_JM572339")
        val untouched = JmAlbumEntity(id = 1L, name = "没导出过", albumFolderName = "没导出过_JM1")
        publisher.seed("$folderName.pdf")
        publisher.seed("另一本_JM572339.zip")
        publisher.seed("另一本_JM572339.pdf")
        // 别的 App 或用户自己放进去的同名文件不该算数（这里以"本子目录名"为准）
        publisher.seed("不相干的文件.zip")

        val formats = runBlocking { manager.presentFormats(listOf(album(), other, untouched)) }

        assertThat(formats[albumId]).containsExactly(JmExportFormat.PDF)
        assertThat(formats[572339L]).containsExactly(JmExportFormat.ZIP, JmExportFormat.PDF)
        // **每一本都要有条目**（空集合），界面才知道"查过了、确实没有"
        assertThat(formats).containsKey(1L)
        assertThat(formats[1L]).isEmpty()
    }

    @Test
    fun `presentFormats does one listing for the whole page`() {
        seedAlbum()
        seedChapter(1L, "第1话", "00001.webp", onDisk = 1)
        publisher.seed("$folderName.zip")
        val albums = (1L..60L).map { JmAlbumEntity(id = it, name = "本子 $it", albumFolderName = "本子_$it") }

        val formats = runBlocking { manager.presentFormats(albums) }

        assertThat(formats).hasSize(60)
        assertThat(formats.values.count { it.isNotEmpty() }).isEqualTo(0)
        // 60 本只查一次下载目录：逐本查的话一页就是 120 次 MediaStore 查询
        assertThat(publisher.listAllCalls).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun exportAndWait(format: JmExportFormat, password: String?): Long {
        val result = runBlocking { manager.submit(album(), format, password) }
        val taskId = when (result) {
            is DownloadTaskRepository.SubmitResult.Created -> result.taskId
            is DownloadTaskRepository.SubmitResult.AlreadyRunning -> result.taskId
        }
        waitUntil("导出任务没有结束：$format") {
            runBlocking { tasks.findById(taskId)!!.status.isFinishedOrFailed() }
        }
        return taskId
    }

    private fun DownloadStatus.isFinishedOrFailed() =
        this == DownloadStatus.SUCCESS || this == DownloadStatus.FAIL || this == DownloadStatus.CANCELLED

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("超时：$what")
    }

    private fun album() = JmAlbumEntity(id = albumId, name = "测试本子", albumFolderName = folderName)

    private fun seedAlbum() {
        runBlocking {
            db.jmAlbumDao().insertIgnore(
                com.haruhi.haruhibox.core.database.entity.JmAlbumBaseEntity(
                    id = albumId,
                    name = "测试本子",
                    albumFolderName = folderName,
                ),
            )
        }
    }

    /** 章节清单入库，并在磁盘上真的放 [onDisk] 张图。 */
    private fun seedChapter(chapterId: Long, title: String, vararg names: String, onDisk: Int) {
        runBlocking {
            db.jmChapterDao().replaceChapter(
                albumId,
                chapterId,
                names.mapIndexed { index, name ->
                    JmChapterImageEntity(
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
        val dir = dirs.chapterDir(folderName, title).apply { mkdirs() }
        names.take(onDisk).forEachIndexed { index, name ->
            writePng(File(dir, name), 24, 30 + index * 5)
        }
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(60, 90, 120))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
