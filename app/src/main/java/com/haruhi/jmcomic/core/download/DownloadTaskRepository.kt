package com.haruhi.jmcomic.core.download

import com.haruhi.jmcomic.core.database.DownloadTaskDao
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.storage.LibraryDirs
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import timber.log.Timber
import java.io.File

/**
 * 持久化下载任务的仓库。
 *
 * 计划 §4.5 / §3.3 的核心约束都在这里落地：
 * 1. **同一 `(type, bizKey)` 只允许一个活跃任务**（照抄参考实现的去重规则）；
 * 2. 进程被杀后重启：`RUNNING`/`WAITING_NETWORK` 任务按磁盘现状重算进度后置为 `PAUSED`
 *    （**不自动续跑**，等用户点"继续"）；导出任务（zip/pdf）直接删除——它不能续做；
 *    `PAUSED` 保持、`CANCELLED` 不复活；
 * 3. "是否已下载"**始终以磁盘文件是否存在为准**，不以任务状态为准（§3.3）；
 * 4. 完成历史只保留最近 [FINISHED_HISTORY_LIMIT] 条。
 *
 * 这一层不做实际传输，只负责"任务事实"的读写与恢复编排；
 * 执行由 [DownloadTaskExecutor] 驱动。
 */
class DownloadTaskRepository(
    private val dao: DownloadTaskDao,
    private val dirs: LibraryDirs,
) {

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    fun observeActive(): Flow<List<DownloadTaskEntity>> = dao.observeActive()

    fun observeFinished(limit: Int = FINISHED_HISTORY_LIMIT): Flow<List<DownloadTaskEntity>> =
        dao.observeFinished(limit)

    fun observeById(id: Long): Flow<DownloadTaskEntity?> = dao.observeById(id)

    fun observeChapters(taskId: Long) = dao.observeChapters(taskId)

    fun observeBadgeCount(): Flow<Int> = dao.observeBadgeCount()

    fun observeFailedCount(): Flow<Int> = dao.observeFailedCount()

    suspend fun findById(id: Long): DownloadTaskEntity? = dao.findById(id)

    /** 所有活跃任务（任务页"进行中"分组、退出收尾都从这里拿）。 */
    suspend fun activeTasks(): List<DownloadTaskEntity> = dao.activeTasks()

    suspend fun activeTask(type: DownloadType, bizKey: String): DownloadTaskEntity? =
        dao.activeTask(type, bizKey)

    /**
     * 观察某个业务对象的活跃任务（同一个 `(type, bizKey)` 最多一条）。
     *
     * 详情页靠它显示"正在导出…"：导出任务不入章节明细，只看这一条就够；
     * **任务从有变无**就表示这一轮跑完了（成功/失败/取消），界面据此立刻重算成品列表。
     */
    fun observeActiveTask(type: DownloadType, bizKey: String): Flow<DownloadTaskEntity?> =
        dao.observeActiveTask(type, bizKey)

    /**
     * 某个本子当前活跃任务里**所有章节的 id**。
     *
     * 详情页靠它把"已经在下载队列里"的章节标成另一种颜色并且不允许再选
     * （用户要求第 3 点）。没有活跃任务时发空集合，调用方不必区分
     * "没有任务"和"任务里没有章节"。
     */
    fun observeQueuedChapters(albumId: Long): Flow<Set<Long>> =
        observeQueuedChapters(DownloadType.JM_ALBUM, albumId)

    /** 官方 ZIP 队列里某本的章节（与普通图片下载队列分开）。 */
    fun observeOfficialZipQueuedChapters(albumId: Long): Flow<Set<Long>> =
        observeQueuedChapters(DownloadType.JM_OFFICIAL_ZIP, albumId)

    private fun observeQueuedChapters(type: DownloadType, albumId: Long): Flow<Set<Long>> =
        dao.observeActiveTask(type, albumId.toString())
            .flatMapLatest { task ->
                if (task == null) {
                    flowOf(emptySet())
                } else {
                    dao.observeChapters(task.id).map { rows -> rows.map { it.chapterId }.toSet() }
                }
            }

    /**
     * 某个本子**下载进度发生过变化**的信号流。
     *
     * 详情页靠它把"部分下载 x/y 张"和本子占用体积做成实时的：执行器每下完一张图就
     * 写一次 `t_download_task_chapter`（见 `DownloadManager` 的 `onFileDone`），
     * 这里跟着发信号，调用方再去重算本地状态。
     *
     * 发的是**信号**而不是具体进度数字：调用方真正要的答案（磁盘上有几张、占了多大）
     * 只存在于文件系统里，任务表里的 `doneImages` 只是个"该重算了"的提醒，
     * 两者混用会在取消 / 重试 / 恢复之后对不上。
     *
     * 两种信号：
     * - [ActiveTaskSignal.Progress]：下载进行中，**按 [PROGRESS_SAMPLE_MS] 周期取样**。
     *   这里必须用 `sample` 而不是逐条转发：一话 100 张图就是 100 次写入，
     *   逐条转发等于让界面在整个下载过程中不停扫盘；`sample` 才是"每 600ms 看一眼当前值"。
     *   也**不能**让调用方自己去抖（debounce）：下载期间事件是连续的，
     *   去抖窗口会被一直推迟、永远不触发，界面反而整个下载过程都不动。
     * - [ActiveTaskSignal.Ended]：任务结束（成功 / 失败 / 取消）时发一次，
     *   调用方据此按磁盘现状做最终重算，否则最后一话会停在"部分下载 99/100"。
     *
     * `sample` 目前还带 `@FlowPreview`，所以这里显式 opt-in（只有这一个函数用到）。
     */
    @OptIn(FlowPreview::class)
    fun observeChapterProgressEvents(albumId: Long): Flow<ActiveTaskSignal> =
        dao.observeActiveTask(DownloadType.JM_ALBUM, albumId.toString())
            // ⚠ 必须先 `map` 成 **id** 再去重，不能直接对实体去重：
            // 执行器每张图都会 `updateProgress` 改写整行任务（updateTime 也变），
            // 实体每次都"不相等" → `flatMapLatest` 每次都被重启 → 下面的取样流
            // 永远等不到 600ms 就被换掉，结果是整个下载过程中一次都不发信号。
            // 只认"任务出现 / 消失"这两个变化，取样流才能安安静静跑完一个周期。
            .map { task -> task?.id }
            .distinctUntilChanged()
            .flatMapLatest { taskId ->
                if (taskId == null) {
                    // 任务消失 = 这一轮跑完了（成功 / 失败 / 取消）：立刻做最终重算
                    flowOf(ActiveTaskSignal.Ended)
                } else {
                    dao.observeChapters(taskId)
                        .map { ActiveTaskSignal.Progress }
                        .sample(PROGRESS_SAMPLE_MS)
                }
            }

    /** [observeChapterProgressEvents] 的信号类型。 */
    enum class ActiveTaskSignal {
        /** 有活跃任务，进度可能变了（会按周期取样，不是每条都转发）。 */
        Progress,

        /** 活跃任务结束了（成功 / 失败 / 取消），要按磁盘现状做最终重算。 */
        Ended,
    }

    // ------------------------------------------------------------------
    // 提交（含去重）
    // ------------------------------------------------------------------

    /** 提交结果。重复提交**不是错误**，UI 只需要提示"已有任务"。 */
    sealed interface SubmitResult {
        data class Created(val taskId: Long) : SubmitResult

        /** 已有同 bizKey 的活跃任务，直接复用。 */
        data class AlreadyRunning(val taskId: Long, val status: DownloadStatus) : SubmitResult
    }

    /**
     * 提交一个下载任务；同一 `(type, bizKey)` 已有活跃任务时**复用而不是新建**
     * （§9 第 7 条「重复提交显示已有任务」）。
     */
    suspend fun submit(
        type: DownloadType,
        bizKey: String,
        displayName: String?,
        payload: String?,
        totalChapters: Int = 0,
    ): SubmitResult {
        dao.activeTask(type, bizKey)?.let { existing ->
            Timber.tag("task").d("已有活跃任务，复用：%s %s", type, bizKey)
            return SubmitResult.AlreadyRunning(existing.id, existing.status)
        }
        // 能走到这里说明当前没有活跃任务，同 (type, bizKey) 的旧行都已是结束态（成功/失败/取消）。
        // 建新任务前把它们（连同章节明细）清掉，同一对象同类型只留最新一条；
        // 导出任务的半成品中转文件一并删，下载任务**不删图片文件**——用户可能只是想重新下，
        // 已下好的图要保留（"是否已下载"始终以磁盘为准）。
        dao.tasksByTypeAndKey(type, bizKey)
            .filter { it.type.isExport }
            .forEach { stale ->
                runCatching { stale.displayName?.let { File(dirs.export, it).delete() } }
                    .onFailure { Timber.tag("task").w(it, "清理旧导出中转文件失败：%s", stale.displayName) }
            }
        val id = dao.replaceByTypeAndKey(
            type = type,
            bizKey = bizKey,
            task = DownloadTaskEntity(
                type = type,
                bizKey = bizKey,
                status = DownloadStatus.QUEUED,
                displayName = displayName,
                payload = payload,
                totalChapters = totalChapters,
            ),
        )
        return SubmitResult.Created(id)
    }

    /** 某个业务对象的**全部任务行**（不分类型 / 状态），移出书库时清理用。 */
    suspend fun tasksByBizKey(bizKey: String): List<DownloadTaskEntity> = dao.tasksByBizKey(bizKey)

    // ------------------------------------------------------------------
    // 状态迁移
    // ------------------------------------------------------------------

    suspend fun markRunning(id: Long) {
        dao.markRunning(id, System.currentTimeMillis())
    }

    /**
     * 上报任务级进度。**只写进度列**（传 null 的项不动）：
     * 下载期间进度心跳会高频重算任务行，整行读改写会把心跳/暂停刚写入的值覆盖掉。
     */
    suspend fun updateProgress(
        id: Long,
        stageText: String? = null,
        doneChapters: Int? = null,
        totalChapters: Int? = null,
        doneImages: Int? = null,
        totalImages: Int? = null,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
    ) {
        dao.updateProgress(
            id = id,
            stageText = stageText,
            doneChapters = doneChapters,
            totalChapters = totalChapters,
            doneImages = doneImages,
            totalImages = totalImages,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            now = System.currentTimeMillis(),
        )
    }

    suspend fun markPaused(id: Long) {
        dao.updateStatus(id, DownloadStatus.PAUSED, System.currentTimeMillis())
        // 暂停时正在跑的那一章会卡在 RUNNING，立刻打回队列，界面上不留"假下载中"
        dao.requeueUnfinishedChapters(id, System.currentTimeMillis())
    }

    /**
     * 退出应用时把指定类型的活跃任务**批量暂停**（漫画图片下载用）。
     *
     * 与单任务 [markPaused] 是同一条规则，只是批量口径：任务行改 `PAUSED`，
     * 再把卡在 `RUNNING` 的章节打回队列。**先落库，协程取消由调用方随后做**——
     * 执行器每个安全检查点都会读任务状态，状态落库之后"暂停"一定赢过正在跑的那一轮。
     */
    suspend fun pauseActive(types: List<DownloadType>) {
        val now = System.currentTimeMillis()
        dao.pauseActive(types, now)
        dao.requeueRunningChapters(types, now)
    }

    /** 等待网络：不算失败，也不算"正在运行"（§9 第 9 条）。 */
    suspend fun markWaitingNetwork(id: Long, reason: String?) {
        dao.markWaitingNetwork(id, reason, STAGE_WAITING_NETWORK, System.currentTimeMillis())
    }

    suspend fun markQueued(id: Long) = dao.updateStatus(id, DownloadStatus.QUEUED, System.currentTimeMillis())

    suspend fun markSuccess(id: Long) {
        dao.finish(id, DownloadStatus.SUCCESS, null, System.currentTimeMillis())
        dao.trimFinished(FINISHED_HISTORY_LIMIT)
    }

    suspend fun markFailed(id: Long, message: String) {
        dao.finish(id, DownloadStatus.FAIL, message, System.currentTimeMillis())
        dao.trimFinished(FINISHED_HISTORY_LIMIT)
    }

    /** 取消：**不自动复活**（§D.3）。已经写下的 `.part` 由调用方决定是否清理。 */
    suspend fun markCancelled(id: Long) {
        dao.finish(id, DownloadStatus.CANCELLED, null, System.currentTimeMillis())
        dao.requeueUnfinishedChapters(id, System.currentTimeMillis())
        dao.trimFinished(FINISHED_HISTORY_LIMIT)
    }

    /** 清空完成历史（§9 第 9 条「完成区可清空历史」）。 */
    suspend fun clearFinished(): Int = dao.clearFinished()

    suspend fun delete(id: Long) {
        dao.deleteChapters(id)
        dao.deleteById(id)
    }

    // ------------------------------------------------------------------
    // 章节明细
    // ------------------------------------------------------------------

    suspend fun upsertChapters(taskId: Long, chapters: List<ChapterSpec>) {
        dao.upsertChapters(chapters.map { it.toEntity(taskId) })
    }

    suspend fun chapterList(taskId: Long): List<DownloadTaskChapterEntity> = dao.chapters(taskId)

    /**
     * 写入某章应有的图片数。拿到图片清单之后调用。
     *
     * 与 [updateChapterProgress] 分开是因为它不改状态、不改 done：
     * 提交任务时还不知道每章有多少张图，`totalImages` 先写 0。
     */
    suspend fun setChapterTotal(taskId: Long, chapterId: Long, totalImages: Int) {
        dao.setChapterTotal(taskId, chapterId, totalImages, System.currentTimeMillis())
    }

    /**
     * 把任务级进度按章节明细重算一遍（图片总数 / 已完成图片 / 已完成章节）。
     *
     * **任务级数字永远由章节明细推导**，不单独累加：这样取消、重试、恢复
     * 都不会让任务进度和章节进度对不上。
     *
     * 实现是**一条 SQL 的子查询聚合**（见 [DownloadTaskDao.recomputeTotalsFromChapters]），
     * 不是"读整行再写回"：下载期间进度心跳每 600ms 调它一次，
     * 读改写会与 pause / 单张图进度写入互相覆盖。
     */
    suspend fun recomputeTaskTotals(taskId: Long) {
        dao.recomputeTotalsFromChapters(taskId, System.currentTimeMillis())
    }

    /**
     * 重试 / 恢复前把没跑完的章节打回队列，并返回刷新后的章节明细。
     *
     * 只重置状态，不重置已下载计数：磁盘上已有的图片会在执行时被跳过，
     * 计数由执行器按磁盘现状重新上报。
     */
    suspend fun requeueUnfinishedChapters(taskId: Long): List<DownloadTaskChapterEntity> {
        dao.requeueUnfinishedChapters(taskId, System.currentTimeMillis())
        return dao.chapters(taskId)
    }

    /**
     * 更新某章的图片进度。
     *
     * **【关键】进度只允许前进**：新的 done 小于已记录的 done 时忽略（除非显式
     * `allowRegression = true`）。这样"恢复时按磁盘重算"不会让进度回退，
     * 也不会因为重复回调而重复累加（§4.5）。
     *
     * 但 §D.1 也指出"文件被删或损坏时进度应允许纠正，不能绝对要求不回退"——
     * 所以提供了重算入口 [recomputeChapterProgress]，它显式传 `allowRegression = true`。
     */
    suspend fun updateChapterProgress(
        taskId: Long,
        chapterId: Long,
        doneImages: Int,
        status: DownloadStatus,
        message: String? = null,
        allowRegression: Boolean = false,
    ) {
        val current = dao.chapters(taskId).firstOrNull { it.chapterId == chapterId } ?: return
        val effectiveDone = when {
            allowRegression -> doneImages
            doneImages > current.doneImages -> doneImages
            else -> current.doneImages
        }
        // 状态也一样：已经 SUCCESS 的章节不被 RUNNING 覆盖（除非在重算）
        val effectiveStatus = when {
            allowRegression -> status
            current.status == DownloadStatus.SUCCESS && status != DownloadStatus.SUCCESS -> current.status
            else -> status
        }
        dao.updateChapterProgress(
            taskId = taskId,
            chapterId = chapterId,
            doneImages = effectiveDone,
            status = effectiveStatus,
            message = message,
            now = System.currentTimeMillis(),
        )
    }

    /**
     * 按磁盘现状重算某章的进度（恢复时调用）。
     *
     * "磁盘现状"= `chapterDir` 下**存在且非空**的图片数除以该章应有的图片数。
     * 与参考实现 `downloadChapter` 里 `images.size - downloadParams.size` 的算法等价，
     * 但更稳：它是按文件名逐个核对，而不是靠"待下载数量差"。
     */
    suspend fun recomputeChapterProgress(
        taskId: Long,
        chapterId: Long,
        chapterDir: File,
        imageFiles: List<String>,
    ): Int {
        val done = imageFiles.count { name ->
            val file = File(chapterDir, name)
            file.isFile && file.length() > 0
        }
        val status = when {
            imageFiles.isEmpty() -> DownloadStatus.SUCCESS
            done >= imageFiles.size -> DownloadStatus.SUCCESS
            done > 0 -> DownloadStatus.PAUSED
            else -> DownloadStatus.QUEUED
        }
        dao.updateChapterProgress(
            taskId = taskId,
            chapterId = chapterId,
            doneImages = done,
            status = status,
            message = null,
            now = System.currentTimeMillis(),
        )
        return done
    }

    // ------------------------------------------------------------------
    // 进程重启恢复
    // ------------------------------------------------------------------

    /**
     * 启动时恢复：把被中断的任务收尾，并**按磁盘现状重算进度**。
     *
     * 返回**恢复后还在的任务**（被删掉的导出任务不在里面），调用方目前只用它记日志。
     *
     * 恢复语义（用户拍板"退出应用 = 下载暂停、导出删除"，这里是杀进程没跑到
     * 退出钩子时的兜底路径）：
     * - **下载任务**：`RUNNING` / `WAITING_NETWORK` 按磁盘现状重算进度后置为 `PAUSED`，
     *   **不自动续跑**，等用户在任务页点"继续"（点继续后按磁盘跳过已下载的图片，续传）；
     * - **导出任务**（zip/pdf）：**直接删除**——导出不能续做，留着的任务行只会占住
     *   同 `(type, bizKey)` 的去重名额（旧实现把它打回"排队中"却没人继续执行，
     *   用户从详情页再点导出时反被挡住）；同一份未完成的中转文件一并删掉；
     * - `CANCELLED` 不复活；`PAUSED` 保持。
     */
    suspend fun recoverInterrupted(
        chapterResolver: suspend (DownloadTaskEntity) -> List<ChapterRediscovery>,
    ): List<DownloadTaskEntity> {
        val interrupted = dao.activeTasks().filter {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.WAITING_NETWORK
        }
        if (interrupted.isEmpty()) return emptyList()

        // 导出任务先分流：不能续做，收尾方式就是"删干净"，不需要章节重算
        val (exportTasks, downloadTasks) = interrupted.partition { it.type.isExport }
        for (task in exportTasks) {
            runCatching {
                delete(task.id)
                // 中转文件写在私有目录（files/library/export/），成品在系统目录、不受影响
                task.displayName?.let { File(dirs.export, it).delete() }
                Timber.tag("task").i("清理被中断的导出任务：%d %s", task.id, task.displayName)
            }.onFailure { Timber.tag("task").w(it, "清理导出任务失败：%d", task.id) }
        }

        for (task in downloadTasks) {
            runCatching {
                // 章节明细里卡在 RUNNING 的那一条也要打回队列，否则界面永远显示"下载中"
                dao.requeueUnfinishedChapters(task.id, System.currentTimeMillis())
                val discovered = chapterResolver(task)
                for (chapter in discovered) {
                    recomputeChapterProgress(
                        taskId = task.id,
                        chapterId = chapter.chapterId,
                        chapterDir = chapter.chapterDir,
                        imageFiles = chapter.imageFiles,
                    )
                }
                val doneChapters = discovered.count { it.isCompleteOnDisk }
                val doneImages = discovered.sumOf { chapter ->
                    chapter.imageFiles.count { name ->
                        val file = File(chapter.chapterDir, name)
                        file.isFile && file.length() > 0
                    }
                }
                dao.update(
                    task.copy(
                        // 暂停而不是排队：再启动时**不自动下载**，等用户点"继续"
                        status = DownloadStatus.PAUSED,
                        doneChapters = doneChapters,
                        totalChapters = if (discovered.isNotEmpty()) discovered.size else task.totalChapters,
                        doneImages = doneImages,
                        totalImages = if (discovered.isNotEmpty()) {
                            discovered.sumOf { it.imageFiles.size }
                        } else {
                            task.totalImages
                        },
                        message = null,
                        stageText = STAGE_PAUSED_ON_RECOVERY,
                        updateTime = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { Timber.tag("task").w(it, "恢复任务失败：%d", task.id) }
        }

        // 兜底：只要还有 RUNNING/WAITING_NETWORK 没被上面处理掉（例如 chapterResolver 抛错），
        // 也统一置为 PAUSED，保证不存在"永远显示在运行中"或"自己跑起来"的僵尸任务。
        dao.pauseInterrupted(System.currentTimeMillis())

        return interrupted.mapNotNull { dao.findById(it.id) }
    }

    /** 恢复时需要的章节信息。 */
    data class ChapterRediscovery(
        val chapterId: Long,
        val chapterDir: File,
        val imageFiles: List<String>,
    ) {
        val isCompleteOnDisk: Boolean
            get() = imageFiles.isNotEmpty() && imageFiles.all { File(chapterDir, it).isFile }
    }

    /** 提交任务时的章节规格。 */
    data class ChapterSpec(
        val chapterId: Long,
        val chapterTitle: String?,
        val sort: Int,
        val totalImages: Int,
    ) {
        fun toEntity(taskId: Long) = DownloadTaskChapterEntity(
            taskId = taskId,
            chapterId = chapterId,
            chapterTitle = chapterTitle,
            sort = sort,
            totalImages = totalImages,
        )
    }

    companion object {
        /** 已完成任务的保留条数（参考实现 §3.3）。 */
        const val FINISHED_HISTORY_LIMIT = 20

        /** 被杀进程的任务恢复后的阶段文案：恢复 = 暂停，等用户点"继续"（不再显示"重新排队"）。 */
        const val STAGE_PAUSED_ON_RECOVERY = "已暂停"
        const val STAGE_WAITING_NETWORK = "等待网络"

        /**
         * 详情页在下载过程中重算本地状态的取样周期（毫秒）。
         *
         * 一次重算要逐话核对磁盘文件、再遍历整个本子目录算体积，所以不能每张图都做；
         * 600ms 对"进度条自己往上走"来说完全够用，扫盘次数却少了两个数量级。
         */
        const val PROGRESS_SAMPLE_MS = 600L
    }
}
