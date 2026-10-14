package com.haruhi.haruhibox.core.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * 任务仓库验收：去重、删除语义、**杀进程后按磁盘现状恢复进度**。
 *
 * 恢复语义（用户拍板"退出 = 下载暂停、导出删除"）：
 * 「杀掉进程再启动，任务列表和进度能从数据库恢复，但 **RUNNING 任务按磁盘现状
 * 重算进度后置为 PAUSED、不自动续跑**（等用户点"继续"）；中断的导出任务直接删除」，
 * 与 §D.1「恢复时协调文件与数据库；文件被删或损坏时进度应允许纠正」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadTaskRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: HaruhiBoxDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var repository: DownloadTaskRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaruhiBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        repository = DownloadTaskRepository(db.downloadTaskDao(), dirs)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `submitting the same album twice reuses the running task`() = runTest {
        val first = repository.submit(DownloadType.JM_ALBUM, "480854", "标题", payload = null)
        val second = repository.submit(DownloadType.JM_ALBUM, "480854", "标题", payload = null)

        assertThat(first).isInstanceOf(DownloadTaskRepository.SubmitResult.Created::class.java)
        assertThat(second).isInstanceOf(DownloadTaskRepository.SubmitResult.AlreadyRunning::class.java)
        assertThat((second as DownloadTaskRepository.SubmitResult.AlreadyRunning).taskId)
            .isEqualTo((first as DownloadTaskRepository.SubmitResult.Created).taskId)
    }

    @Test
    fun `after a task finishes a new submission creates a fresh task`() = runTest {
        val first = repository.submit(DownloadType.JM_ALBUM, "480854", "标题", null)
        repository.markSuccess((first as DownloadTaskRepository.SubmitResult.Created).taskId)

        val second = repository.submit(DownloadType.JM_ALBUM, "480854", "标题", null)
        assertThat(second).isInstanceOf(DownloadTaskRepository.SubmitResult.Created::class.java)
        assertThat((second as DownloadTaskRepository.SubmitResult.Created).taskId)
            .isNotEqualTo(first.taskId)
    }

    @Test
    fun `chapter progress never regresses during normal updates`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(taskId, listOf(spec(10, "第1话", 1, totalImages = 20)))

        repository.updateChapterProgress(taskId, 10, doneImages = 12, status = DownloadStatus.RUNNING)
        repository.updateChapterProgress(taskId, 10, doneImages = 5, status = DownloadStatus.RUNNING)

        val chapter = repository.observeChapters(taskId).first().first { it.chapterId == 10L }
        assertThat(chapter.doneImages).isEqualTo(12)
    }

    @Test
    fun `finished chapter status is not downgraded by a late running update`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(taskId, listOf(spec(10, "第1话", 1, totalImages = 20)))
        repository.updateChapterProgress(taskId, 10, doneImages = 20, status = DownloadStatus.SUCCESS)
        repository.updateChapterProgress(taskId, 10, doneImages = 20, status = DownloadStatus.RUNNING)

        val chapters = repository.observeChapters(taskId).first()
        assertThat(chapters.first().status).isEqualTo(DownloadStatus.SUCCESS)
    }

    @Test
    fun `restart recomputes chapter progress from disk and pauses the task`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(
            taskId,
            listOf(
                spec(1, "第1话", 1, totalImages = 3),
                spec(2, "第2话", 2, totalImages = 4),
            ),
        )
        repository.markRunning(taskId)

        // 模拟"下载到一半被杀进程"：第 1 话下完 3 张里的 2 张，第 2 话一张没下
        val chapter1Dir = File(dirs.jm, "标题_JM480854/第1话").apply { mkdirs() }
        File(chapter1Dir, "00001.webp").writeBytes(ByteArray(16))
        File(chapter1Dir, "00002.webp").writeBytes(ByteArray(16))
        val chapter2Dir = File(dirs.jm, "标题_JM480854/第2话").apply { mkdirs() }

        val recovered = repository.recoverInterrupted { task ->
            assertThat(task.bizKey).isEqualTo("480854")
            listOf(
                DownloadTaskRepository.ChapterRediscovery(
                    chapterId = 1,
                    chapterDir = chapter1Dir,
                    imageFiles = listOf("00001.webp", "00002.webp", "00003.webp"),
                ),
                DownloadTaskRepository.ChapterRediscovery(
                    chapterId = 2,
                    chapterDir = chapter2Dir,
                    imageFiles = listOf("00001.webp", "00002.webp", "00003.webp", "00004.webp"),
                ),
            )
        }

        assertThat(recovered).hasSize(1)
        val task = recovered.first()
        // RUNNING 恢复为 PAUSED：不自动续跑，等用户在任务页点"继续"
        assertThat(task.status).isEqualTo(DownloadStatus.PAUSED)
        // 进度按磁盘重算：第 1 话 2/3，第 2 话 0/4
        assertThat(task.doneImages).isEqualTo(2)
        assertThat(task.totalImages).isEqualTo(7)
        assertThat(task.doneChapters).isEqualTo(0)

        val chapters = repository.observeChapters(taskId).first()
        assertThat(chapters.first { it.chapterId == 1L }.doneImages).isEqualTo(2)
        assertThat(chapters.first { it.chapterId == 1L }.status).isEqualTo(DownloadStatus.PAUSED)
        assertThat(chapters.first { it.chapterId == 2L }.doneImages).isEqualTo(0)
    }

    @Test
    fun `restart marks chapters that are fully on disk as success`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(taskId, listOf(spec(1, "第1话", 1, totalImages = 2)))
        repository.markRunning(taskId)

        val chapterDir = File(dirs.jm, "标题_JM480854/第1话").apply { mkdirs() }
        File(chapterDir, "00001.webp").writeBytes(ByteArray(8))
        File(chapterDir, "00002.webp").writeBytes(ByteArray(8))

        val recovered = repository.recoverInterrupted {
            listOf(
                DownloadTaskRepository.ChapterRediscovery(
                    chapterId = 1,
                    chapterDir = chapterDir,
                    imageFiles = listOf("00001.webp", "00002.webp"),
                ),
            )
        }

        assertThat(recovered.first().doneChapters).isEqualTo(1)
        val chapters = repository.observeChapters(taskId).first()
        assertThat(chapters.first().status).isEqualTo(DownloadStatus.SUCCESS)
        assertThat(chapters.first().doneImages).isEqualTo(2)
    }

    @Test
    fun `zero byte files do not count as downloaded`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(taskId, listOf(spec(1, "第1话", 1, totalImages = 2)))
        repository.markRunning(taskId)

        // 上次下载被中断留下了 0 字节的残file：必须重新下载
        val chapterDir = File(dirs.jm, "标题_JM480854/第1话").apply { mkdirs() }
        File(chapterDir, "00001.webp").writeBytes(ByteArray(0))
        File(chapterDir, "00002.webp").writeBytes(ByteArray(8))

        repository.recoverInterrupted {
            listOf(
                DownloadTaskRepository.ChapterRediscovery(
                    chapterId = 1,
                    chapterDir = chapterDir,
                    imageFiles = listOf("00001.webp", "00002.webp"),
                ),
            )
        }

        val chapters = repository.observeChapters(taskId).first()
        assertThat(chapters.first().doneImages).isEqualTo(1)
        assertThat(chapters.first().status).isEqualTo(DownloadStatus.PAUSED)
    }

    @Test
    fun `recovery never touches paused or cancelled tasks`() = runTest {
        val paused = createdId("1", "暂停的")
        val cancelled = createdId("2", "取消的")
        val running = createdId("3", "运行中的")

        repository.markPaused(paused)
        repository.markCancelled(cancelled)
        repository.markRunning(running)

        val recovered = repository.recoverInterrupted { emptyList() }

        assertThat(recovered.map { it.id }).containsExactly(running)
        assertThat(repository.findById(paused)!!.status).isEqualTo(DownloadStatus.PAUSED)
        assertThat(repository.findById(cancelled)!!.status).isEqualTo(DownloadStatus.CANCELLED)
        assertThat(repository.findById(running)!!.status).isEqualTo(DownloadStatus.PAUSED)
    }

    @Test
    fun `recovery is a no-op when nothing was interrupted`() = runTest {
        repository.submit(DownloadType.JM_ALBUM, "1", "排队中", null)
        val recovered = repository.recoverInterrupted { error("不应该被调用") }
        assertThat(recovered).isEmpty()
    }

    @Test
    fun `waiting for network is a distinct state and is recoverable`() = runTest {
        val taskId = newTask()
        repository.markRunning(taskId)
        repository.markWaitingNetwork(taskId, "等待 Wi-Fi")

        val task = repository.findById(taskId)!!
        assertThat(task.status).isEqualTo(DownloadStatus.WAITING_NETWORK)
        assertThat(task.stageText).isEqualTo(DownloadTaskRepository.STAGE_WAITING_NETWORK)
        // 等待网络算活跃任务（占用 bizKey），但不算"正在运行"
        assertThat(task.status.isActive).isTrue()
        assertThat(repository.activeTask(DownloadType.JM_ALBUM, "480854")).isNotNull()

        val recovered = repository.recoverInterrupted { emptyList() }
        assertThat(recovered.map { it.id }).containsExactly(taskId)
        assertThat(repository.findById(taskId)!!.status).isEqualTo(DownloadStatus.PAUSED)
    }

    @Test
    fun `interrupted export tasks are deleted together with their partial file`() = runTest {
        // 导出任务：RUNNING + 私有目录里留着一份没写完的中转文件
        val submitted = repository.submit(
            type = DownloadType.JM_EXPORT_ZIP,
            bizKey = "480854",
            displayName = "标题_JM480854.zip",
            payload = "{}",
        )
        val exportId = (submitted as DownloadTaskRepository.SubmitResult.Created).taskId
        repository.markRunning(exportId)
        val partial = File(dirs.export, "标题_JM480854.zip").apply { writeBytes(ByteArray(8)) }
        // 同一本还有一条被打断的下载任务：它应该被暂停而不是删除
        val albumTask = newTask()
        repository.markRunning(albumTask)

        val recovered = repository.recoverInterrupted { emptyList() }

        // 导出任务被删干净：记录、中转文件都不留，同 (type, bizKey) 的去重名额也释放了
        assertThat(recovered.map { it.id }).containsExactly(albumTask)
        assertThat(repository.findById(exportId)).isNull()
        assertThat(partial.exists()).isFalse()
        assertThat(repository.activeTask(DownloadType.JM_EXPORT_ZIP, "480854")).isNull()
        assertThat(repository.findById(albumTask)!!.status).isEqualTo(DownloadStatus.PAUSED)
    }

    @Test
    fun `exit pause marks active downloads paused and requeues running chapters`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(taskId, listOf(spec(1, "第1话", 1, totalImages = 2)))
        repository.markRunning(taskId)
        repository.updateChapterProgress(taskId, 1, doneImages = 1, status = DownloadStatus.RUNNING)

        repository.pauseActive(listOf(DownloadType.JM_ALBUM, DownloadType.BILI_VIDEO))

        assertThat(repository.findById(taskId)!!.status).isEqualTo(DownloadStatus.PAUSED)
        val chapter = repository.observeChapters(taskId).first().first { it.chapterId == 1L }
        assertThat(chapter.status).isEqualTo(DownloadStatus.QUEUED)
        // 已下载张数不回退：点"继续"后按磁盘续传，不必从零重下
        assertThat(chapter.doneImages).isEqualTo(1)
    }

    @Test
    fun `delete removes task and its chapter details`() = runTest {
        val taskId = newTask()
        repository.upsertChapters(taskId, listOf(spec(1, "第1话", 1, totalImages = 2)))

        repository.delete(taskId)

        assertThat(repository.findById(taskId)).isNull()
        assertThat(db.downloadTaskDao().chapters(taskId)).isEmpty()
    }

    private suspend fun newTask(): Long = createdId("480854", "标题")

    private suspend fun createdId(bizKey: String, name: String): Long {
        val result = repository.submit(DownloadType.JM_ALBUM, bizKey, name, payload = "{}")
        return (result as DownloadTaskRepository.SubmitResult.Created).taskId
    }

    private fun spec(
        chapterId: Long,
        title: String,
        sort: Int,
        totalImages: Int,
    ) = DownloadTaskRepository.ChapterSpec(
        chapterId = chapterId,
        chapterTitle = title,
        sort = sort,
        totalImages = totalImages,
    )
}
