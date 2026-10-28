package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.download.DownloadOutcome
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.core.download.FileDownloader
import com.haruhi.jmcomic.core.download.ParallelImageDownloader
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.network.NetworkState
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.core.storage.OfficialZipPublisher
import com.haruhi.jmcomic.feature.api.AlbumDownloadDto
import com.haruhi.jmcomic.feature.api.ApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * 下载执行器需要的"环境"。
 *
 * 做成接口而不是直接读设置，有两个原因：
 * 1. **每次调用都重新读**——计划 §9.2 要求"线程数、线路、网络策略对新任务或下次重试生效"，
 *    所以这里全是 `suspend` 函数，而不是构造时的一张快照；
 * 2. 可以单测：注入一个假的网络状态就能验证"移动网络下进等待而不是失败"。
 */
interface DownloadEnvironment {
    /** 同时下载的图片数（已解析，保证 >= 1）。 */
    suspend fun concurrency(): Int

    /** 图片线路域名。 */
    suspend fun imageDomain(): String

    /**
     * 同时执行**官方 ZIP 章节**的数量（默认 2，范围 1～4）。
     *
     * 这是**全局**额度：不同本子的任务共用，同一本里多话并发也占额度。
     * 它是"并发下载几话 ZIP"，不是"同时几本"——同一本会按章节顺序依次启动新话。
     */
    suspend fun officialZipConcurrency(): Int = 2

    /**
     * 当前策略下，这个网络是否允许开始新的传输。
     *
     * @param metered 目标网络是否计费（移动网络、按流量计费的 Wi-Fi）
     */
    suspend fun allowsTransfer(metered: Boolean): Boolean

    /** 网络状况，用于等待恢复。 */
    val networkState: StateFlow<NetworkState>

    /**
     * 请求网络监视器**立即重新读一次**当前网络状况。
     *
     * 这是"漏掉系统回调"的兜底：等待网络期间 [DownloadManager] 会周期调用它，
     * 免得状态流长时间停在一次瞬时误判上、任务被永久挂在"等待网络"。
     * 默认空实现，测试用的假环境不必关心。
     */
    fun refreshNetwork() {}
}

/** 下载任务的参数快照（`t_download_task.payload`）。**不放凭据**（计划 §D.2 第 6 条）。 */
@Serializable
data class DownloadPayload(
    val albumId: Long,
    val chapterIds: List<Long> = emptyList(),
) {
    fun encode(): String = ApiJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String?): DownloadPayload? {
            if (json.isNullOrBlank()) return null
            return runCatching { ApiJson.decodeFromString(serializer(), json) }.getOrNull()
        }
    }
}

/**
 * JM 章节图片下载的编排器（计划 §10 P3 的核心）。
 *
 * ## 它负责什么
 *
 * - **任务生命周期**：提交（含去重）、开始、暂停、恢复、取消、重试；
 * - **执行顺序**：任务 → 章节 → 图片。每章先保证"图片清单已入库"
 *   （没有就现场拉一次 `/chapter` 并持久化，§9 第 7 条），然后走并发图片下载；
 *   下载产物是**站点原始字节**（不解扰），阅读 / 导出时再现场解扰；
 * - **进度持久化**：每张图完成写一次章节计数；任务级数字（任务卡进度条、通知栏都读它）
 *   由"进度心跳"按 [TASK_PROGRESS_REFRESH_MS] 周期从章节明细重算——每张图只写章节表，
 *   任务行如果只在整话结束时才更新，界面在整话下载期间就是静止的（用户反馈第 1、2 点）；
 * - **网络策略**：每个安全检查点问一次闸门，不允许时进 `WAITING_NETWORK`
 *   并**挂起等待**网络恢复（不是失败），恢复后自动继续（§9.5「不偷偷开始大下载」）；
 * - **恢复**：交给 [DownloadTaskRepository.recoverInterrupted]（恢复 = 暂停），
 *   本类只在用户点"继续"后从断点续做——磁盘上已有的图片会被跳过。
 *
 * ## 它不负责什么
 *
 * 不做解扰算法（阅读/导出时现场做）、不做 HTTP（`FileDownloader`）、
 * 不做通知（`DownloadForegroundService` 订阅任务表渲染）。
 * 这样"谁在下载"只有一个答案。
 *
 * ## 防死循环（对齐参考实现）
 *
 * 参考实现 `downloadChapter` 的规则是"**本次需下载数与上次相同就终止**"。
 * 这里的表达更直接，而且有两层：
 * - 章节内：每轮先数"磁盘上还缺几张"，**缺图数没有下降就结束**，最多 [MAX_CHAPTER_ROUNDS] 轮；
 * - 任务内：一轮下来没有任何章节完成（剩余章节数没减少）就整体停止。
 *
 * 所以不可能无限重试，也不会因为一次网络抖动把整本直接判死。
 */
class DownloadManager(
    private val repo: Repository,
    private val tasks: DownloadTaskRepository,
    private val downloader: ParallelImageDownloader,
    private val dirs: LibraryDirs,
    private val environment: DownloadEnvironment,
    private val scope: CoroutineScope,
    /** 单文件下载引擎（官方 ZIP 用；普通图片走 [downloader]）。 */
    private val fileDownloader: FileDownloader? = null,
    /** 官方 ZIP 链接入口；调用时必须带登录态（`ApiClient.authProvider` 已接账号）。 */
    private val officialZipApi: (suspend (Long) -> AlbumDownloadDto)? = null,
    /** 官方 ZIP 落盘器；null = 当前构建不支持官方 ZIP。 */
    private val officialZipPublisher: OfficialZipPublisher? = null,
    /** 有任务开始执行时回调（用来拉起前台服务与常驻通知）。 */
    private val onTaskStarted: () -> Unit = {},
) {

    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * 官方 ZIP **章节级**并发计数，所有本子共用（设置里的「同时下载官方 ZIP 数量」）。
     *
     * 用原子计数而不是 `Mutex` 名额，是因为释放发生在 `finally` 里：任务暂停/取消时
     * 协程已经处于取消状态，`withLock` 这类挂起函数会立刻抛 `CancellationException`，
     * 名额就永远还不回去（暂停几轮后额度掉到 0，官方 ZIP 再也启动不了）。
     */
    private val officialZipRunning = AtomicInteger(0)

    /**
     * 官方 ZIP 任务里**各话**的最新字节进度（taskId → chapterId → (已下, 总长)）。
     *
     * 任务级 `downloadedBytes/totalBytes` 用它们的和更新：多话并发时只写"当前这一话"
     * 会让任务卡数字来回跳。任务结束时整条移除；回调发现任务已移除就直接忽略。
     */
    private val officialZipBytes = ConcurrentHashMap<Long, ConcurrentHashMap<Long, Pair<Long, Long>>>()

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 提交一本漫画的章节下载。
     *
     * 同一本子已有活跃任务时**不新建**：把新选的章节**追加**进那个任务
     * （计划 §9 第 7 条要求"重复提交显示已有任务"，但"我选了第 5 话却没下"
     * 是更糟的体验），并唤醒它继续跑。
     */
    suspend fun submit(
        album: AlbumEntity,
        chapters: List<ChapterSummary>,
    ): DownloadTaskRepository.SubmitResult {
        require(chapters.isNotEmpty()) { "至少要选一章" }
        val bizKey = album.id.toString()
        val payload = DownloadPayload(album.id, chapters.map { it.chapterId })
        val specs = chapters.map { chapter ->
            DownloadTaskRepository.ChapterSpec(
                chapterId = chapter.chapterId,
                // 落盘目录名（唯一存储键）：重名章节会带 `-<chapterId>`。
                // 任务页/通知栏把它当这一话的标签显示；真正落盘前还会用
                // `Repository.chapterFolders` 再问一次当前值（见 downloadPendingChapters）。
                chapterTitle = repo.chapterFolderName(chapter),
                sort = chapter.sort?.toIntOrNull() ?: 0,
                totalImages = 0,
            )
        }
        val displayName = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"

        val existing = tasks.activeTask(DownloadType.JM_ALBUM, bizKey)
        if (existing != null) {
            appendChapters(existing.id, specs)
            // 用户此刻明确要求下载，所以暂停中的任务也接着跑
            if (existing.status == DownloadStatus.PAUSED) {
                tasks.markQueued(existing.id)
            }
            start(existing.id)
            return DownloadTaskRepository.SubmitResult.AlreadyRunning(existing.id, existing.status)
        }

        val result = tasks.submit(
            type = DownloadType.JM_ALBUM,
            bizKey = bizKey,
            displayName = displayName,
            payload = payload.encode(),
            totalChapters = chapters.size,
        )
        if (result is DownloadTaskRepository.SubmitResult.Created) {
            tasks.upsertChapters(result.taskId, specs)
            tasks.recomputeTaskTotals(result.taskId)
            start(result.taskId)
        }
        return result
    }

    /**
     * 提交官方 ZIP 下载：**一个本子一个任务**，任务内按章节顺序依次启动、可并发多话。
     *
     * 全局同名额度由 [officialZipRunning] 按设置里的“同时下载官方 ZIP 数量”限流，
     * 不同本子共用；同一本已有任务时把新选的章节追加进去（与普通下载一致）。
     */
    suspend fun submitOfficialZip(
        album: AlbumEntity,
        chapters: List<ChapterSummary>,
    ): DownloadTaskRepository.SubmitResult {
        require(chapters.isNotEmpty()) { "至少要选一章" }
        val bizKey = album.id.toString()
        val payload = DownloadPayload(album.id, chapters.map { it.chapterId })
        val specs = chapters.map { chapter ->
            DownloadTaskRepository.ChapterSpec(
                chapterId = chapter.chapterId,
                // 官方 ZIP 不落图片目录，这里只当"这一话的标签"用；
                // zip 文件名由 `officialZipName` 按 sort 生成（重名再追加 chapterId）
                chapterTitle = repo.chapterFolderName(chapter),
                sort = chapter.sort?.toIntOrNull() ?: 0,
                totalImages = 0,
            )
        }
        val displayName = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"

        val existing = tasks.activeTask(DownloadType.JM_OFFICIAL_ZIP, bizKey)
        if (existing != null) {
            appendChapters(existing.id, specs)
            if (existing.status == DownloadStatus.PAUSED) tasks.markQueued(existing.id)
            start(existing.id)
            return DownloadTaskRepository.SubmitResult.AlreadyRunning(existing.id, existing.status)
        }

        val result = tasks.submit(
            type = DownloadType.JM_OFFICIAL_ZIP,
            bizKey = bizKey,
            displayName = displayName,
            payload = payload.encode(),
            totalChapters = chapters.size,
        )
        if (result is DownloadTaskRepository.SubmitResult.Created) {
            tasks.upsertChapters(result.taskId, specs)
            tasks.recomputeTaskTotals(result.taskId)
            start(result.taskId)
        }
        return result
    }

    /**
     * 删除某本漫画在公共目录里的**全部官方 ZIP**（只删直接 `.zip`，解压文件夹不动）。
     * 返回释放字节数；任务记录由调用方删除。
     */
    suspend fun deleteOfficialZipFiles(taskId: Long): Long {
        val task = tasks.findById(taskId) ?: return 0L
        val payload = DownloadPayload.decode(task.payload)
        val albumId = payload?.albumId ?: task.bizKey.toLongOrNull() ?: return 0L
        val album = repo.findAlbum(albumId) ?: return 0L
        val folder = album.albumFolderName?.takeIf { it.isNotBlank() } ?: "JM$albumId"
        return officialZipPublisher?.deleteAlbum(folder) ?: 0L
    }

    /** 取消官方 ZIP 时清掉这一任务所有章节的临时 `.zip` / `.part`（暂停不调用）。 */
    private suspend fun cleanupOfficialZipTemp(task: DownloadTaskEntity) {
        val payload = DownloadPayload.decode(task.payload)
        val albumId = payload?.albumId ?: task.bizKey.toLongOrNull() ?: return
        val chapterIds = (payload?.chapterIds.orEmpty() +
            tasks.chapterList(task.id).map { it.chapterId }).distinct()
        chapterIds.forEach { chapterId ->
            val base = File(dirs.tmp, "official_zip_${albumId}_${chapterId}.zip")
            runCatching { base.delete() }
            runCatching { File(base.absolutePath + ".part").delete() }
        }
    }

    /** 追加章节到已有任务（已存在的章节不覆盖，避免把进度清零）。 */
    private suspend fun appendChapters(taskId: Long, specs: List<DownloadTaskRepository.ChapterSpec>) {
        val known = tasks.chapterList(taskId).map { it.chapterId }.toSet()
        val missing = specs.filterNot { it.chapterId in known }
        if (missing.isEmpty()) return
        tasks.upsertChapters(taskId, missing)
        tasks.recomputeTaskTotals(taskId)
        tasks.updateProgress(taskId, totalChapters = tasks.chapterList(taskId).size)
        Timber.tag("dl").i("任务 %d 追加 %d 个章节", taskId, missing.size)
    }

    // ------------------------------------------------------------------
    // 控制
    // ------------------------------------------------------------------

    fun isRunning(taskId: Long): Boolean = jobs[taskId]?.isActive == true

    /** 开始（或继续）执行一个任务。已经在跑就什么都不做。 */
    fun start(taskId: Long) {
        if (isRunning(taskId)) return
        onTaskStarted()
        jobs[taskId] = scope.launch {
            try {
                runTask(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("dl").e(e, "任务 %d 执行失败", taskId)
                runCatching { tasks.markFailed(taskId, e.toUserMessage()) }
            } finally {
                jobs.remove(taskId)
            }
        }
    }

    /** 暂停：先落库再取消协程，保证"暂停"一定赢过正在跑的那一轮。 */
    fun pause(taskId: Long) {
        scope.launch {
            runCatching { tasks.markPaused(taskId) }
            jobs[taskId]?.cancel()
        }
    }

    /**
     * 退出应用时**暂停所有**漫画/视频下载任务（用户拍板：退出 = 暂停，回来不自动跑）。
     *
     * 与单任务 [pause] 同一条规则，只是批量口径：
     * 1. 先把任务行批量置为 `PAUSED`（含"卡在 RUNNING 的章节"回队列），
     * 2. 再取消所有正在跑的 Job。
     *
     * 顺序不能反：先取消协程的话，正在跑的代码可能在"取消生效"之前又把状态写回运行中。
     * 已经下载的图片留在磁盘上，用户下次点"继续"时按"文件已存在即跳过"续做，
     * 不需要保存任何额外的断点信息。
     */
    suspend fun pauseAllForExit() {
        tasks.pauseActive(listOf(DownloadType.JM_ALBUM, DownloadType.JM_OFFICIAL_ZIP))
        jobs.values.forEach { it.cancel() }
    }

    /** 恢复暂停的任务。 */
    fun resume(taskId: Long) {
        scope.launch {
            val task = tasks.findById(taskId) ?: return@launch
            if (task.status != DownloadStatus.PAUSED) return@launch
            tasks.requeueUnfinishedChapters(taskId)
            tasks.markQueued(taskId)
            tasks.recomputeTaskTotals(taskId)
            start(taskId)
        }
    }

    /**
     * 取消：**不自动复活**（§D.3）。已下载的图片/ZIP 保留。
     *
     * 官方 ZIP 取消时额外清掉 `files/tmp/` 里这一任务所有章节的 `.part` 与中止文件；
     * **暂停不清**（暂停后还要续传）。
     */
    fun cancel(taskId: Long) {
        scope.launch {
            val task = tasks.findById(taskId)
            runCatching { tasks.markCancelled(taskId) }
            jobs[taskId]?.cancelAndJoin()
            if (task?.type == DownloadType.JM_OFFICIAL_ZIP) {
                cleanupOfficialZipTemp(task)
            }
        }
    }

    /** 重试失败/取消的任务：只重跑没跑完的部分。 */
    fun retry(taskId: Long) {
        scope.launch {
            val task = tasks.findById(taskId) ?: return@launch
            if (task.status.isActive) return@launch
            tasks.requeueUnfinishedChapters(taskId)
            tasks.markQueued(taskId)
            tasks.recomputeTaskTotals(taskId)
            start(taskId)
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private suspend fun runTask(taskId: Long) {
        val task = tasks.findById(taskId) ?: return
        if (!task.status.isRunnable) {
            Timber.tag("dl").d("任务 %d 状态为 %s，不执行", taskId, task.status)
            return
        }

        val payload = DownloadPayload.decode(task.payload)
        if (payload == null) {
            tasks.markFailed(taskId, "任务参数已损坏，请删除后重新下载")
            return
        }
        val album = repo.findAlbum(payload.albumId)
        if (album == null) {
            tasks.markFailed(taskId, "这本漫画不在书库，请重新加入书库后再下载")
            return
        }
        if (task.type == DownloadType.JM_OFFICIAL_ZIP) {
            runOfficialZipTask(taskId, album)
            return
        }
        val folderName = album.albumFolderName
        if (folderName.isNullOrBlank()) {
            tasks.markFailed(taskId, "这本漫画不在书库或目录名缺失，请重新加入书库后再下载")
            return
        }

        tasks.markRunning(taskId)
        tasks.updateProgress(taskId, stageText = STAGE_DOWNLOADING)
        tasks.recomputeTaskTotals(taskId)

        coroutineScope {
            val heartbeat = launch {
                while (true) {
                    delay(TASK_PROGRESS_REFRESH_MS)
                    val status = tasks.findById(taskId)?.status ?: break
                    // 只在真正下载时搬运；排队/等网络时数字不会变，不做无谓写入
                    if (status == DownloadStatus.RUNNING) tasks.recomputeTaskTotals(taskId)
                }
            }
            try {
                downloadPendingChapters(taskId, payload.albumId, folderName)
            } finally {
                heartbeat.cancel()
            }
        }

        if (!isRunnable(taskId)) return

        val rows = tasks.chapterList(taskId)
        val unfinished = rows.filter { it.status != DownloadStatus.SUCCESS }
        if (unfinished.isEmpty()) {
            tasks.markSuccess(taskId)
        } else {
            val doneImages = rows.sumOf { it.doneImages }
            val totalImages = rows.sumOf { it.totalImages }
            tasks.markFailed(
                taskId,
                "${unfinished.size} 话没有下完（$doneImages/$totalImages 张），可点重试补下",
            )
        }
    }

    // ------------------------------------------------------------------
    // 官方 ZIP
    // ------------------------------------------------------------------

    /**
     * 官方 ZIP 任务：一个本子一个任务，但**同一任务内也可以并发多话**。
     *
     * 并发额度是全局的（设置里的「同时下载官方 ZIP 数量」，默认 2），不同本子共用同一组
     * 名额。同一本内按章节顺序**依次启动**：1、2 先跑；1 完成就启动 3（此时 2、3 在跑），
     * 3 先于 2 完成就启动 4（2、4 在跑）；因此最终会自然形成 4、5 同时在跑。
     *
     * 实现用每任务一把 `order` 锁包住"拿全局名额"这一步：第 i 话必须等第 i-1 话已经
     * 拿到名额/启动后才去抢名额，保证启动顺序；全局计数 [officialZipRunning] 保证总并发数。
     */
    private suspend fun runOfficialZipTask(taskId: Long, album: AlbumEntity) {
        val api = officialZipApi
        val publisher = officialZipPublisher
        if (api == null || publisher == null || fileDownloader == null) {
            tasks.markFailed(taskId, "当前构建未接入官方 ZIP 下载")
            return
        }
        if (!isRunnable(taskId)) return
        tasks.markRunning(taskId)
        tasks.updateProgress(taskId, stageText = STAGE_OFFICIAL_ZIP)
        tasks.recomputeTaskTotals(taskId)

        coroutineScope {
            val pending = tasks.chapterList(taskId)
                .filter { it.status != DownloadStatus.SUCCESS }
            val order = Mutex()
            // 本任务各话的最新字节进度；任务级进度取它们的和，否则多话并发时
            // 任务卡会显示"最后报进度的那一话"的数字，看起来像进度倒退。
            val bytes = ConcurrentHashMap<Long, Pair<Long, Long>>()
            officialZipBytes[taskId] = bytes
            val jobs = pending.map { row ->
                // UNDISPATCHED：按列表顺序立刻进入 order 队列，避免调度器打乱启动顺序
                async(start = CoroutineStart.UNDISPATCHED) {
                    val acquired = order.withLock {
                        if (!isRunnable(taskId)) {
                            false
                        } else {
                            acquireOfficialZipPermit(taskId)
                        }
                    }
                    if (!acquired) return@async
                    try {
                        awaitNetworkPermission(taskId, STAGE_OFFICIAL_ZIP)
                        if (isRunnable(taskId)) {
                            downloadOfficialZipChapter(taskId, album, row, bytes)
                            tasks.recomputeTaskTotals(taskId)
                        }
                    } finally {
                        releaseOfficialZipPermit()
                    }
                }
            }
            try {
                jobs.awaitAll()
            } finally {
                officialZipBytes.remove(taskId)
            }
        }

        if (!isRunnable(taskId)) return
        val rows = tasks.chapterList(taskId)
        val unfinished = rows.filter { it.status != DownloadStatus.SUCCESS }
        if (unfinished.isEmpty()) {
            tasks.markSuccess(taskId)
            // 完成后清掉字节进度：任务卡只保留"N/N 话"，否则会停在
            // "3.9 MB/4.3 MB" 这种像没下完的数字（用户反馈）。
            tasks.updateProgress(taskId, downloadedBytes = 0, totalBytes = 0)
        } else {
            tasks.markFailed(
                taskId,
                "${unfinished.size} 话官方 ZIP 没有下完，可点重试补下",
            )
        }
    }

    private suspend fun downloadOfficialZipChapter(
        taskId: Long,
        album: AlbumEntity,
        row: DownloadTaskChapterEntity,
        bytes: ConcurrentHashMap<Long, Pair<Long, Long>>?,
    ) {
        val chapterId = row.chapterId
        val api = officialZipApi ?: return
        val publisher = officialZipPublisher ?: return
        val singleDownloader = fileDownloader ?: return
        val albumFolder = album.albumFolderName?.takeIf { it.isNotBlank() } ?: "JM${album.id}"
        val rows = tasks.chapterList(taskId)
        val zipName = officialZipName(row, rows)
        val stage = "$STAGE_OFFICIAL_ZIP · ${row.chapterTitle?.takeIf { it.isNotBlank() } ?: chapterId}"
        tasks.updateProgress(taskId, stageText = stage)
        tasks.updateChapterProgress(
            taskId = taskId,
            chapterId = chapterId,
            doneImages = 0,
            status = DownloadStatus.RUNNING,
            allowRegression = true,
        )

        if (publisher.exists(albumFolder, zipName)) {
            tasks.updateChapterProgress(
                taskId = taskId,
                chapterId = chapterId,
                doneImages = 0,
                status = DownloadStatus.SUCCESS,
                allowRegression = true,
            )
            return
        }

        val target = File(dirs.tmp, "official_zip_${album.id}_${chapterId}.zip")
        if (target.exists()) target.delete()
        var cachedUrl: String? = null
        try {
            val first = api(chapterId)
            if (first.status == "0" || first.downloadUrl.isNullOrBlank()) {
                throw OfficialZipLoginRequired(first.msg)
            }
            cachedUrl = first.downloadUrl
            var lastProgressAt = 0L
            val outcome = singleDownloader.download(
                urlProvider = {
                    val url = cachedUrl ?: api(chapterId).downloadUrl
                        ?: throw OfficialZipLoginRequired(null)
                    cachedUrl = null // 下一次重试必须重新取链（链接会过期）
                    url
                },
                target = target,
                onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= PROGRESS_UPDATE_MS || progress.totalBytes <= 0) {
                        lastProgressAt = now
                        // 汇总所有在跑的话：任务卡显示的是整本的总字节，不是当前这一话
                        bytes?.set(chapterId, progress.downloadedBytes to progress.totalBytes)
                        val done = bytes?.values?.sumOf { it.first } ?: progress.downloadedBytes
                        val total = bytes?.values?.sumOf { it.second } ?: progress.totalBytes
                        scope.launch {
                            // 任务收尾已经清过字节进度：迟到的回调不要再写回去
                            if (officialZipBytes[taskId] !== bytes) return@launch
                            runCatching {
                                tasks.updateProgress(
                                    taskId,
                                    downloadedBytes = done,
                                    totalBytes = total,
                                )
                            }
                        }
                    }
                },
                expectedTotal = 0L,
                // 官方 zip 不是"已存在即完整"的图片：target 只是本地中转，
                // 真正的去重看公共目录里的同名 zip。
                skipIfComplete = false,
            )
            when (outcome) {
                is DownloadOutcome.Cancelled -> return
                is DownloadOutcome.Failed -> throw outcome.error
                is DownloadOutcome.Downloaded, is DownloadOutcome.Skipped -> {
                    if (!isValidZip(target)) {
                        target.delete()
                        throw IllegalStateException("官方 ZIP 内容无效（可能链接已过期）")
                    }
                    publisher.publish(target, albumFolder, zipName)
                }
            }
            tasks.updateChapterProgress(
                taskId = taskId,
                chapterId = chapterId,
                doneImages = 0,
                status = DownloadStatus.SUCCESS,
                allowRegression = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            tasks.updateChapterProgress(
                taskId = taskId,
                chapterId = chapterId,
                doneImages = 0,
                status = DownloadStatus.FAIL,
                message = e.toUserMessage(),
                allowRegression = true,
            )
        } finally {
            // 只删已发布的 zip；`.part` 留着，下一次重试可以 Range 续传
            runCatching { target.delete() }
        }
    }

    private class OfficialZipLoginRequired(message: String?) : Exception(
        message?.takeIf { it.isNotBlank() } ?: "请先登录后再下载官方 ZIP",
    )

    private fun isValidZip(file: File): Boolean =
        file.isFile && file.length() > 0 && runCatching { ZipFile(file).use { true } }.getOrDefault(false)

    /**
     * `第{sort}话.zip`；没有 sort 时用章节标题。同一任务里重名（极少）追加章节 id。
     */
    private fun officialZipName(
        row: DownloadTaskChapterEntity,
        allRows: List<DownloadTaskChapterEntity>,
    ): String {
        val clean = zipBase(row)
        val duplicate = allRows.any { other ->
            other.chapterId != row.chapterId && zipBase(other) == clean
        }
        return if (duplicate) "$clean-${row.chapterId}.zip" else "$clean.zip"
    }

    private fun zipBase(row: DownloadTaskChapterEntity): String =
        (row.sort.takeIf { it > 0 }?.let { "第${it}话" }
            ?: row.chapterTitle?.takeIf { it.isNotBlank() }
            ?: row.chapterId.toString())
            .removeSuffix(".zip")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "第${row.chapterId}话" }
            .take(80)

    /**
     * 官方 ZIP 全局并发名额：每启动**一话**抢一个，所有本子共用。
     * 每次抢名额时现读设置，所以设置改数字影响之后启动的章节。
     * 等待用 200ms 轮询（任务/章节数量很少，不值得为它引入 Channel/信号量重建）。
     *
     * 返回 false = 等名额期间任务已暂停/取消，调用方不要再启动新章节。
     */
    private suspend fun acquireOfficialZipPermit(taskId: Long): Boolean {
        while (true) {
            if (!isRunnable(taskId)) return false
            val max = environment.officialZipConcurrency().coerceIn(1, 4)
            while (true) {
                val current = officialZipRunning.get()
                if (current >= max) break
                if (officialZipRunning.compareAndSet(current, current + 1)) return true
            }
            delay(200)
        }
    }

    /** 不挂起：可以在取消中的 `finally` 里安全调用（见 [officialZipRunning] 的说明）。 */
    private fun releaseOfficialZipPermit() {
        officialZipRunning.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * 逐话推进，直到没有未完成章节（或"剩下的话数不再减少"这种没有进展的情况）。
     *
     * 单独抽出来是为了让进度心跳有明确的作用域：心跳在 [runTask] 的
     * `coroutineScope` 里启动，这里所有 `return` 都只结束本方法，
     * 由 [runTask] 统一决定要不要走收尾分支。
     */
    private suspend fun downloadPendingChapters(taskId: Long, albumId: Long, folderName: String) {
        // 章节目录名以**当前**章节列表为准：任务行里的 `chapterTitle` 可能是升级前算的，
        // 那时重名章节还没带 `-<chapterId>`（会落进两话共享的老目录）。取不到就退回任务行。
        val chapterFolders = runCatching { repo.chapterFolders(albumId) }.getOrDefault(emptyMap())
        var previousRemaining = Int.MAX_VALUE
        while (true) {
            coroutineContext.ensureActive()
            if (!isRunnable(taskId)) return

            val pending = tasks.chapterList(taskId).filter { it.status != DownloadStatus.SUCCESS }
            if (pending.isEmpty()) break
            // 一轮下来没有任何章节完成 -> 再跑也是白跑（对齐参考实现的"需下载数不变就终止"）
            if (pending.size >= previousRemaining) {
                Timber.tag("dl").w("任务 %d 没有进展，停止重试（剩 %d 话）", taskId, pending.size)
                break
            }
            previousRemaining = pending.size

            for (row in pending) {
                coroutineContext.ensureActive()
                if (!isRunnable(taskId)) return
                downloadChapter(taskId, albumId, folderName, row, chapterFolders)
                tasks.recomputeTaskTotals(taskId)
            }
        }
    }

    private suspend fun downloadChapter(
        taskId: Long,
        albumId: Long,
        albumFolder: String,
        row: DownloadTaskChapterEntity,
        chapterFolders: Map<Long, String>,
    ) {
        val chapterId = row.chapterId
        awaitNetworkPermission(taskId)
        if (!isRunnable(taskId)) return

        // 任务页与通知栏都要看出"正在下第几话"，阶段文案在这一话开始时就写
        val stage = downloadStage(taskId, chapterId)
        tasks.updateProgress(taskId, stageText = stage)

        tasks.updateChapterProgress(taskId, chapterId, row.doneImages, DownloadStatus.RUNNING)

        val images = ensureImageList(albumId, chapterId)
        if (images.isEmpty()) {
            tasks.updateChapterProgress(
                taskId = taskId,
                chapterId = chapterId,
                doneImages = 0,
                status = DownloadStatus.FAIL,
                message = "没有拿到这一话的图片清单",
                allowRegression = true,
            )
            return
        }
        tasks.setChapterTotal(taskId, chapterId, images.size)
        // 任务行立刻知道"这一话有多少张"：否则新任务在整话下完之前 totalImages 一直是 0，
        // 任务卡按"totalImages > 0"判断要不要画进度条，就会整话都不显示（用户反馈第 1 点）
        tasks.recomputeTaskTotals(taskId)

        // 唯一存储键优先（重名章节带 -<chapterId>），任务行里的老名字只作兜底
        val folder = chapterFolders[chapterId]?.takeIf { it.isNotBlank() }
            ?: row.chapterTitle?.takeIf { it.isNotBlank() }
            ?: chapterId.toString()
        val chapterDir = dirs.chapterDir(albumFolder, folder)

        val concurrency = environment.concurrency()
        val missing = { images.filterNot { isUsable(File(chapterDir, it)) } }
        var previousMissing = Int.MAX_VALUE
        var round = 0

        while (round < MAX_CHAPTER_ROUNDS) {
            coroutineContext.ensureActive()
            if (!isRunnable(taskId)) return
            val pending = missing()
            if (pending.isEmpty()) break
            // 缺图数没有下降 -> 这一话下不动了，交给用户点重试
            if (pending.size >= previousMissing) break
            previousMissing = pending.size
            round++

            awaitNetworkPermission(taskId, stage)
            if (!isRunnable(taskId)) return

            val domain = environment.imageDomain()
            val providers: Map<String, suspend () -> String> = pending.associateWith { name ->
                suspend { Defaults.buildImgUrl(chapterId, name, domain) }
            }
            // 磁盘上已有的张数 + 本次成功的张数 = 这一话的完成数
            val doneCounter = AtomicInteger(images.size - pending.size)
            val result = downloader.downloadImages(
                images = providers,
                chapterDir = chapterDir,
                concurrency = concurrency,
                // 阅读器（旧版 reader-cache）里已经有这张图的原始字节？那就别再联网下一遍
                seedFor = { name -> readerRawSeed(albumId, chapterId, name) },
                // 下载完成的是站点原始字节：立刻在图片清单里标记 RAW（阅读/导出据此解扰）。
                // 只对真正发生了下载的文件调；已存在的旧成品保持原标记不动。
                onFileDownloaded = { name ->
                    try {
                        repo.markImageRaw(albumId, chapterId, name)
                    } catch (e: Exception) {
                        // 标记失败就别把"看起来已下载、实际会被当旧成品"的原始字节留在盘上：
                        // 删掉它，下一次重试会重新下载并重新标记。
                        File(chapterDir, name).delete()
                        throw e
                    }
                },
                onFileDone = { _, success ->
                    if (success) {
                        val done = doneCounter.incrementAndGet()
                        // 计划 §4.5：进度精确到"第 N 话 x/y 张"，每张图落库一次
                        tasks.updateChapterProgress(taskId, chapterId, done, DownloadStatus.RUNNING)
                    }
                },
            )
            if (result.cancelled) return
        }

        // 以磁盘现状为准做最终判定（允许回退：文件被删或损坏时进度要能纠正，§D.1）
        val remaining = missing().size
        val done = images.size - remaining
        tasks.updateChapterProgress(
            taskId = taskId,
            chapterId = chapterId,
            doneImages = done,
            status = if (remaining == 0) DownloadStatus.SUCCESS else DownloadStatus.FAIL,
            message = if (remaining == 0) null else "缺 $remaining 张，可点重试补下",
            allowRegression = true,
        )
    }

    /**
     * 这张图在**旧版阅读缓存**（`reader-cache/`）里的副本（有的话）。
     *
     * 新版本不再产生这个目录；这里只是让升级用户"边看边下"时不重复联网：
     * 旧缓存里存的也是站点原始字节，和新下载目标的内容一致。
     * 文件不在 / 长度为 0 就返回 null，下载走正常网络路径。
     */
    private fun readerRawSeed(albumId: Long, chapterId: Long, fileName: String): File? =
        File(dirs.readerCacheChapterDir(albumId, chapterId), fileName)
            .takeIf { it.isFile && it.length() > 0 }

    /** 图片清单：本地有就用本地的，没有就现场拉一次 `/chapter` 并入库。 */
    private suspend fun ensureImageList(albumId: Long, chapterId: Long): List<String> {        val local = repo.imagesOf(albumId, chapterId)
        if (local.isNotEmpty()) return local.map { it.imageFile }
        runCatching { repo.fetchAndSaveChapter(albumId, chapterId) }
            .onFailure { Timber.tag("dl").w(it, "拉取章节图片清单失败 chapterId=%d", chapterId) }
        return repo.imagesOf(albumId, chapterId).map { it.imageFile }
    }

    // ------------------------------------------------------------------
    // 网络闸门
    // ------------------------------------------------------------------

    /**
     * 等到网络条件允许为止。
     *
     * **不允许时进 `WAITING_NETWORK` 并挂起**，而不是抛错：
     * 计划 §9 第 9 条要求"等待网络"是一个独立状态，且不算失败。
     * 网络恢复（或用户在设置里放开策略）后这里自动返回，任务接着跑。
     *
     * @param stage 恢复后要写回的任务阶段文案（保持"第几话"这类上下文不丢）
     */
    private suspend fun awaitNetworkPermission(taskId: Long, stage: String = STAGE_DOWNLOADING) {
        if (isNetworkAllowed(environment.networkState.value)) return

        tasks.markWaitingNetwork(taskId, WAITING_REASON)
        while (true) {
            // 状态流发了"允许"的值就立刻继续；超时则兜底重读一次网络。
            // 这样即使漏掉一次系统回调（状态流停在一次瞬时误判上），任务也不会被永久挂住。
            val allowed = withTimeoutOrNull(NETWORK_RECHECK_MS) {
                environment.networkState.first { isNetworkAllowed(it) }
            }
            if (allowed != null) break
            environment.refreshNetwork()
        }
        if (!isRunnable(taskId)) return
        tasks.markQueued(taskId)
        tasks.markRunning(taskId)
        // 恢复后把阶段文案写回去（等待网络时被 STAGE_WAITING_NETWORK 覆盖过）
        tasks.updateProgress(taskId, stageText = stage)
    }

    /** 当前网络是否允许开始传输：在线且策略放行。 */
    private suspend fun isNetworkAllowed(state: NetworkState): Boolean =
        state.online && environment.allowsTransfer(state.metered)

    /**
     * 当前的阶段文案：`下载图片 · 第1话`。
     *
     * 话名优先用章节标题——就是章节列表、磁盘目录里显示的那个名字（JM 的标题
     * 通常形如"第1話"，正好回答了"在下第几话"）；标题缺失时退回"第 N/M 话"，
     * 序号按任务内章节顺序算（与 [DownloadTaskRepository.chapterList] 同序），
     * 不依赖 `sort` 字段——部分本子的 `sort` 拿不到，会退化成 0。
     */
    private suspend fun downloadStage(taskId: Long, chapterId: Long): String {
        val ordered = tasks.chapterList(taskId)
        val index = ordered.indexOfFirst { it.chapterId == chapterId }
        val chapter = ordered.getOrNull(index)?.chapterTitle?.takeIf { it.isNotBlank() }
            ?: if (index >= 0) "第 ${index + 1}/${ordered.size} 话" else null
        return if (chapter == null) STAGE_DOWNLOADING else "$STAGE_DOWNLOADING · $chapter"
    }

    /**
     * 任务是否还能继续跑。
     *
     * **`PAUSED` 不算**：暂停之后即使网络刚好恢复，也不能自己把状态改回运行中。
     */
    private suspend fun isRunnable(taskId: Long): Boolean =
        tasks.findById(taskId)?.status?.isRunnable == true

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0

    companion object {
        /** 同一话最多重跑几轮（每轮都必须有进展，否则立刻结束）。 */
        const val MAX_CHAPTER_ROUNDS = 3

        const val WAITING_REASON = "当前网络不允许下载，已暂停传输"

        /**
         * 「等待网络」时的兜底重读周期（毫秒）：等这么久还没等到状态流放行，
         * 就主动让 `NetworkMonitor` 重读一次当前网络，避免漏掉系统回调时一直被挂住。
         */
        const val NETWORK_RECHECK_MS = 1_000L

        /**
         * 进度心跳周期（毫秒）：把章节明细的聚合结果落一次任务行，
         * 让任务卡进度条 / 通知栏在整话下载期间也能动起来。
         * 节奏与详情页的 `PROGRESS_SAMPLE_MS`（600ms）一致。
         */
        const val TASK_PROGRESS_REFRESH_MS = 600L

        /** 任务的阶段文案，任务页与通知共用。 */
        const val STAGE_DOWNLOADING = "下载图片"

        /** 官方 ZIP 的阶段文案。 */
        const val STAGE_OFFICIAL_ZIP = "下载官方 ZIP"

        /** 单文件下载进度写库的最小间隔（毫秒）：避免每个 chunk 都写一次任务行。 */
        private const val PROGRESS_UPDATE_MS = 500L
    }
}
