package com.haruhi.jmcomic.feature.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.codec.ArchiveWriter
import com.haruhi.jmcomic.core.codec.PdfWriter
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
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
 * 两个入口的差别只有**删不删数据库记录**，所以这里把各处痕迹（图片 / 导出成品 / 封面缓存 /
 * 数据库表）分开断言，任何一处漏删都会挂。
 *
 * **"从书库移除"不删最近浏览**（用户要求第 5 点）：移出书库影响的是书库与本地文件，
 * "刚看过什么"是另一件事，保留后最近列表里还能看到这本。
 *
 * 另外验证"从书库移除"会先取消还在跑的任务——否则下载会把文件又写回刚删掉的目录。
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
    private lateinit var covers: RecordingCoverCache
    private lateinit var cleaner: AlbumCleaner
    private lateinit var scope: CoroutineScope

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
        covers = RecordingCoverCache()
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
            coverCache = covers,
            dirs = dirs,
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
        // 封面**不删**：只删文件时列表里还要显示封面
        assertThat(covers.evicted).isEmpty()
    }

    @Test
    fun `removing from library wipes records favorites progress but keeps recent and therefore the cover`() {
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
        assertThat(result.removedExports).isEqualTo(2)
        assertThat(dirs.albumDir(folderName).exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.zip").exists()).isFalse()
        assertThat(File(publisher.dir, "$folderName.pdf").exists()).isFalse()

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
        // 既然它还在最近页上露面，封面就不能删（用户第九轮要求第 3 点）
        assertThat(covers.evicted).isEmpty()
    }

    @Test
    fun `removing from library clears the cover when the album is not in recent anymore`() {
        seedAlbum()
        seedChapter()

        runBlocking { cleaner.removeFromLibrary(listOf(album())) }

        // 书库与最近浏览里都没有这本了 -> 封面缓存没人用，清掉
        assertThat(runBlocking { repo.findRecent(albumId) }).isNull()
        assertThat(covers.evicted).containsExactly(albumId)
    }

    @Test
    fun `clearing recent keeps the cover of albums that are still in the library`() {
        seedAlbum()
        seedChapter()
        runBlocking { repo.touchRecent(albumId) }

        val removed = runBlocking { cleaner.clearRecent() }

        assertThat(removed).isEqualTo(1)
        assertThat(runBlocking { repo.findRecent(albumId) }).isNull()
        // 书库里还有这本，书库页还要显示它的封面
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNotNull()
        assertThat(covers.evicted).isEmpty()
    }

    @Test
    fun `clearing recent drops covers that the library no longer needs`() {
        // 只在最近浏览里、不在书库里：正是"移出书库之后"的那种状态
        runBlocking { repo.touchRecent(albumId) }

        runBlocking { cleaner.clearRecent() }

        assertThat(runBlocking { repo.findRecent(albumId) }).isNull()
        assertThat(runBlocking { repo.findAlbum(albumId) }).isNull()
        assertThat(covers.evicted).containsExactly(albumId)
    }

    @Test
    fun `removing one recent entry only drops its cover when the library does not have it`() {
        seedAlbum()
        seedChapter()
        runBlocking { repo.touchRecent(albumId) }

        runBlocking { cleaner.removeRecent(albumId) }

        assertThat(runBlocking { repo.findRecent(albumId) }).isNull()
        assertThat(covers.evicted).isEmpty()
    }

    @Test
    fun `removing from library cancels the running download task first`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_ALBUM, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.removeFromLibrary(listOf(album())) }

        // 任务被打成已取消（不是删掉记录：任务页还要能说明"为什么停了"）
        assertThat(runBlocking { tasks.findById(taskId) }!!.status).isEqualTo(DownloadStatus.CANCELLED)
    }

    @Test
    fun `deleting files leaves a running task alone`() {
        seedAlbum()
        seedChapter()
        val taskId = runBlocking {
            val id = (tasks.submit(DownloadType.JM_ALBUM, albumId.toString(), "测试本子", null)
                as DownloadTaskRepository.SubmitResult.Created).taskId
            tasks.markRunning(id)
            id
        }

        runBlocking { cleaner.deleteFiles(listOf(album())) }

        // "删除本地文件"不删记录：任务照跑，用户可能是想重下这一本
        assertThat(runBlocking { tasks.findById(taskId) }!!.status).isEqualTo(DownloadStatus.RUNNING)
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

    private class RecordingCoverCache : CoverCache {
        val evicted = mutableListOf<Long>()
        override fun evict(albumId: Long) {
            evicted += albumId
        }
    }
}
