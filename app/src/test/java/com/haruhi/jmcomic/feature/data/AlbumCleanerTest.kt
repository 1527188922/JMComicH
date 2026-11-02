package com.haruhi.jmcomic.feature.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.codec.ArchiveWriter
import com.haruhi.jmcomic.core.codec.PdfWriter
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.LibraryEntity
import com.haruhi.jmcomic.core.database.entity.PlaybackKind
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.ApiClient
import com.haruhi.jmcomic.testing.FakeExportPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * "删除一本漫画"的验收（用户要求第 6、7 点）。
 *
 * 两个入口的差别是**删不删数据库记录**、以及**系统下载目录里的成品保不保留**，
 * 所以这里把各处痕迹（图片 / 导出成品 / 官方 ZIP 中转文件 / 数据库表）分开断言，
 * 任何一处漏删都会挂。
 *
 * 封面缓存**不在这两个入口里删**：全应用只在「关于 → 存储占用 → 封面缓存」整体清除。
 *
 * **"从书库移除"不删最近浏览**（用户要求第 5 点）：移出书库影响的是书库与本地文件，
 * "刚看过什么"是另一件事，保留后最近列表里还能看到这本。
 *
 * 另外验证删除前会先停掉还在跑的任务——否则下载会把文件又写回刚删掉的目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumCleanerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: JmComicDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var repo: Repository
    private lateinit var reading: ReadingRepository
    private lateinit var tasks: DownloadTaskRepository
    private lateinit var publisher: FakeExportPublisher
    private lateinit var cleaner: AlbumCleaner
    private lateinit var scope: CoroutineScope
    private val stoppedOfficialZipTasks = mutableListOf<Long>()

    private val albumId = 480854L
    private val folderName = "测试本子_JM480854"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JmComicDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        repo = Repository(
            api = ApiClient(client = OkHttpClient(), config = { error("删除不联网") }),
            albumDao = db.albumDao(),
            chapterDao = db.chapterDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentAlbumDao(),
            searchHistoryDao = db.searchHistoryDao(),
            dirs = dirs,
            positionDao = db.playbackPositionDao(),
        )
        tasks = DownloadTaskRepository(dao = db.downloadTaskDao(), dirs = dirs)
        reading = ReadingRepository(
            positionDao = db.playbackPositionDao(),
            chapterDao = db.chapterDao(),
        )
        publisher = FakeExportPublisher(temp.newFolder("downloads"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleaner = AlbumCleaner(
            repo = repo,
            exports = ExportManager(
                repo = repo,
                tasks = tasks,
                dirs = dirs,
                archiveWriter = ArchiveWriter(),
                pdfWriter = PdfWriter(),
                publisher = publisher,
                scope = scope,
            ),
            tasks = tasks,
            dirs = dirs,
            stopOfficialZipTask = { taskId -> stoppedOfficialZipTasks += taskId },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `deleting files wipes images and exports but keeps the library record`() {
        seedAlbum()
        seedChapter()
        publish("$folderName.zip")

        val result = runBlocking { cleaner.deleteFiles(listOf(album())) }

        assertThat(File(dirs.albumDir(folderName), "第1话").exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isFalse()
        assertThat(result.removedExports).isEqualTo(1)
        assertThat(result.removedRecords).isFalse()
        assertThat(result.freedBytes).isGreaterThan(0L)
        // 书库记录、章节清单、最近浏览都要还在
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNotNull()
        assertThat(runBlocking { repo.chaptersOf(album()) }).hasSize(1)
        assertThat(runBlocking { repo.localAlbumDetail(albumId) }!!.fromLibrary).isTrue()
    }

    @Test
    fun `deleting files removes official zip temp files but not extracted folders`() {
        seedAlbum()
        seedChapter()
        val zip = File(dirs.tmp, "official_zip_${albumId}_1.zip").apply { writeBytes(ByteArray(8)) }
        val part = File(dirs.tmp, "official_zip_${albumId}_1.zip.part").apply { writeBytes(ByteArray(8)) }
        // 解压出来的文件夹在公共下载目录，不在 tmp，删除不应波及（这里只验证 tmp 被清）
        val otherAlbumTemp = File(dirs.tmp, "official_zip_999999_1.zip").apply { writeBytes(ByteArray(8)) }

        runBlocking { cleaner.deleteFiles(listOf(album())) }

        assertThat(zip.exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(otherAlbumTemp.exists()).isTrue()
    }

    @Test
    fun `deleting files stops the running official zip task`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_OFFICIAL_ZIP, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.deleteFiles(listOf(album())) }

        // 先同步停任务（生产环境等执行协程退出），再删任务行与中转文件
        assertThat(stoppedOfficialZipTasks).containsExactly(taskId)
        assertThat(runBlocking { tasks.findById(taskId) }).isNull()
    }

    @Test
    fun `removing from library wipes records favorites progress but keeps recent`() {
        seedAlbum()
        seedChapter()
        publish("$folderName.zip")
        publish("$folderName.pdf")
        runBlocking {
            repo.addToFavorite(albumId)
            repo.touchRecent(albumId)
            // 读到第 1 话第 3 张：移出书库后这条进度必须消失（用户第十一轮要求第 1 点）
            reading.savePosition(
                albumId = albumId,
                chapterId = 1L,
                imageFile = "00001.webp",
                imageIndex = 2,
                offsetRatio = 0.5f,
            )
        }
        assertThat(runBlocking { repo.favoriteIdsOf(albumId) }).isNotEmpty()
        assertThat(runBlocking { reading.positionOf(albumId) }).isNotNull()

        val result = runBlocking { cleaner.removeFromLibrary(listOf(album())) }

        assertThat(result.removedRecords).isTrue()
        // 导出成品保留（用户拍板：已落到系统下载目录的包不该被顺手删）
        assertThat(result.removedExports).isEqualTo(0)
        assertThat(dirs.albumDir(folderName).exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isTrue()
        assertThat(File(publisher.dir, "$folderName.pdf").exists()).isTrue()

        // 书库、章节清单、收藏关联、阅读位置都不能留痕
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNull()
        assertThat(runBlocking { db.chapterDao().chapterList(albumId) }).isEmpty()
        assertThat(runBlocking { repo.favoriteIdsOf(albumId) }).isEmpty()
        assertThat(runBlocking { reading.positionOf(albumId) }).isNull()
        assertThat(runBlocking { db.playbackPositionDao().find(PlaybackKind.JM, albumId.toString()) }).isNull()
        // 「继续阅读」指针正指向这本 -> 一并清掉（用户方案 B：卡片消失，不回退到旧书）
        assertThat(
            runBlocking {
                db.playbackPositionDao().find(ReadingRepository.CONTINUE_KIND, albumId.toString())
            },
        ).isNull()
        // 但最近浏览**必须保留**（用户第八轮要求第 5 点）：移出书库不影响"刚看过什么"
        assertThat(runBlocking { repo.findRecent(albumId) }).isNotNull()
        // 基础数据缓存也**必须保留**（本次要求：缓存表不因任何操作删除），离线仍能看详情
        assertThat(runBlocking { db.albumDao().findById(albumId) }).isNotNull()
        assertThat(runBlocking { repo.localAlbumDetail(albumId) }!!.fromLibrary).isFalse()
    }

    @Test
    fun `clearing recent only removes records and leaves files alone`() {
        seedAlbum()
        seedChapter()
        runBlocking { repo.touchRecent(albumId) }

        val removed = runBlocking { cleaner.clearRecent() }

        assertThat(removed).isEqualTo(1)
        assertThat(runBlocking { repo.findRecent(albumId) }).isNull()
        // 书库与图片都不受影响（封面缓存只在「关于」页整段清除，这两个入口一概不动）
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNotNull()
        assertThat(dirs.albumDir(folderName).exists()).isTrue()
    }

    @Test
    fun `removing one recent entry only removes the record`() {
        seedAlbum()
        seedChapter()
        runBlocking { repo.touchRecent(albumId) }

        runBlocking { cleaner.removeRecent(albumId) }

        assertThat(runBlocking { repo.findRecent(albumId) }).isNull()
        assertThat(dirs.albumDir(folderName).exists()).isTrue()
    }

    @Test
    fun `removing from library stops and deletes the running download task`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_ALBUM, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.removeFromLibrary(listOf(album())) }

        // 先落 PAUSED 停掉执行器，再删任务行
        assertThat(runBlocking { tasks.findById(taskId) }).isNull()
    }

    @Test
    fun `deleting files stops and deletes the running download task`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_ALBUM, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.deleteFiles(listOf(album())) }

        assertThat(runBlocking { tasks.findById(taskId) }).isNull()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun album() = AlbumEntity(id = albumId, name = "测试本子", albumFolderName = folderName)

    /** 基础数据 + 书库成员各写一行：模拟"已入库"的完整形态。 */
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

    private fun seedChapter() {
        runBlocking {
            db.chapterDao().replaceChapter(
                albumId,
                1L,
                listOf(
                    ChapterImageEntity(
                        albumId = albumId,
                        chapterId = 1L,
                        chapterTitle = "第1话",
                        chapterName = "第1话",
                        imageFile = "00001.webp",
                        imageSort = 1,
                    ),
                ),
            )
        }
        val dir = dirs.chapterDir(folderName, "第1话").apply { mkdirs() }
        File(dir, "00001.webp").writeBytes(ByteArray(64) { 7 })
    }

    /** 直接在"下载目录"里造一个成品，等价于"之前导出过"。 */
    private fun publish(name: String) {
        publisher.seed(name)
    }
}
