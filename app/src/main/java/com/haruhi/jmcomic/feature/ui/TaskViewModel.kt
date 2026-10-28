package com.haruhi.jmcomic.feature.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.feature.data.DownloadManager
import com.haruhi.jmcomic.feature.data.ExportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** 任务页里的一行。 */
data class TaskRow(
    val task: DownloadTaskEntity,
    /** 只有展开的那一条才会加载章节明细（几百话的任务不必整表观察）。 */
    val chapters: List<DownloadTaskChapterEntity> = emptyList(),
    val expanded: Boolean = false,
) {
    val displayName: String
        get() = task.displayName?.takeIf { it.isNotBlank() }
            ?: if (task.type.name.startsWith("JM")) "JM${task.bizKey}" else task.bizKey

    /** 只有漫画任务有封面（`bizKey` 就是 JM 号）；导出任务也是，**生成成品的那本**。 */
    val albumId: Long?
        get() = when (task.type) {
            DownloadType.JM_ALBUM,
            DownloadType.JM_OFFICIAL_ZIP,
            DownloadType.JM_EXPORT_ZIP,
            DownloadType.JM_EXPORT_PDF -> task.bizKey.toLongOrNull()
        }

    /** 导出任务：**不能续做**，只能取消后重新生成（计划 §9 第 9 条），所以不给「重试」与「暂停」。 */
    val isExport: Boolean get() = task.isExportTask

    /**
     * 任务类型标签（用户要求第 4 点）：导出任务要一眼看出是在生成 zip 还是 pdf。
     *
     * 普通下载任务不标——任务名本身（本子标题）已经说明了一切。
     */
    val typeLabel: String?
        get() = when (task.type) {
            DownloadType.JM_OFFICIAL_ZIP -> "官方 ZIP"
            DownloadType.JM_EXPORT_ZIP -> "生成 ZIP"
            DownloadType.JM_EXPORT_PDF -> "生成 PDF"
            else -> null
        }

    /** 导出完成的成品文件名（"打开至 / 发送至 / 删除文件"要用）。 */
    val outputName: String?
        get() = if (isExport && task.status == DownloadStatus.SUCCESS) task.displayName else null

    val statusText: String get() = taskStatusLabel(task)
    val progressText: String get() = taskProgressText(task)
    val detailText: String? get() = taskDetailText(task)

    val canPause: Boolean
        get() = !isExport && (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED)
    val canResume: Boolean get() = !isExport && task.status == DownloadStatus.PAUSED
    val canCancel: Boolean get() = task.status.isActive
    val canRetry: Boolean
        get() = !isExport && (task.status == DownloadStatus.FAIL || task.status == DownloadStatus.CANCELLED)

    val isOfficialZip: Boolean get() = task.type == DownloadType.JM_OFFICIAL_ZIP

    /** 完成/取消后允许"删除官方 ZIP"（删文件 + 任务记录，需二次确认）。 */
    val canDeleteOfficialZip: Boolean
        get() = isOfficialZip &&
            (task.status == DownloadStatus.SUCCESS || task.status == DownloadStatus.CANCELLED)
}

/** 任务页类型筛选。 */
enum class TaskFilter(val label: String) {
    ALL("全部"),
    ALBUM("下载漫画"),
    EXPORT_ZIP("导出 ZIP"),
    EXPORT_PDF("导出 PDF"),
    OFFICIAL_ZIP("下载官方 ZIP"),
    ;

    fun matches(type: DownloadType): Boolean = when (this) {
        ALL -> true
        ALBUM -> type == DownloadType.JM_ALBUM
        EXPORT_ZIP -> type == DownloadType.JM_EXPORT_ZIP
        EXPORT_PDF -> type == DownloadType.JM_EXPORT_PDF
        OFFICIAL_ZIP -> type == DownloadType.JM_OFFICIAL_ZIP
    }
}

/**
 * 任务页状态（计划 §9 第 9 条）。
 *
 * 分组规则：**失败不算"正在运行"**，所以"进行中"只含
 * `QUEUED/RUNNING/PAUSED/WAITING_NETWORK`；失败与取消进"已完成"区并单独提示。
 */
data class TaskUiState(
    val active: List<TaskRow> = emptyList(),
    val finished: List<TaskRow> = emptyList(),
    val badgeCount: Int = 0,
    val failedCount: Int = 0,
    val loading: Boolean = true,
    val message: String? = null,
    val filter: TaskFilter = TaskFilter.ALL,
) {
    val isEmpty: Boolean get() = !loading && active.isEmpty() && finished.isEmpty()
}

/**
 * 任务页的 ViewModel。
 *
 * 所有动作都转发给 [DownloadManager]——**页面不直接改任务状态**，
 * 这样"谁能改状态"只有执行器一个答案（否则界面和后台会互相覆盖）。
 */
class TaskViewModel(
    private val tasks: DownloadTaskRepository,
    private val downloads: DownloadManager,
    private val exports: ExportManager,
) : ViewModel() {

    private val expanded = MutableStateFlow<Long?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(TaskFilter.ALL)

    private val chaptersOfExpanded = expanded.flatMapLatest { taskId ->
        if (taskId == null) flowOf(emptyList()) else tasks.observeChapters(taskId)
    }

    private data class Lists(
        val active: List<DownloadTaskEntity>,
        val finished: List<DownloadTaskEntity>,
        val badge: Int,
        val failed: Int,
    )

    // 先用一次 4 参数 combine 把"任务列表"收成一个流，再和展开状态/章节明细/提示合并。
    // 这样每一段都是强类型，不需要 `Array<Any?>` 那种会丢类型安全的写法。
    private val lists = combine(
        tasks.observeActive(),
        tasks.observeFinished(),
        tasks.observeBadgeCount(),
        tasks.observeFailedCount(),
    ) { active, finished, badge, failed -> Lists(active, finished, badge, failed) }

    val state: StateFlow<TaskUiState> = combine(
        lists,
        expanded,
        chaptersOfExpanded,
        message,
        filter,
    ) { current, expandedId, chapters, hint, selectedFilter ->
        val active = current.active
            .filter { selectedFilter.matches(it.type) }
            .map { it.toRow(expandedId, chapters) }
        val finished = current.finished
            .filter { selectedFilter.matches(it.type) }
            .map { it.toRow(expandedId, chapters) }
        TaskUiState(
            active = active,
            finished = finished,
            badgeCount = current.badge,
            failedCount = current.failed,
            loading = false,
            message = hint,
            filter = selectedFilter,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TaskUiState(),
    )

    private fun DownloadTaskEntity.toRow(
        expandedId: Long?,
        chapters: List<DownloadTaskChapterEntity>,
    ) = TaskRow(
        task = this,
        chapters = if (id == expandedId) chapters else emptyList(),
        expanded = id == expandedId,
    )

    fun toggleExpanded(taskId: Long) {
        expanded.update { if (it == taskId) null else taskId }
    }

    fun setFilter(value: TaskFilter) {
        filter.value = value
    }

    fun pause(taskId: Long) {
        downloads.pause(taskId)
        message.value = "已暂停，可随时继续"
    }

    fun resume(taskId: Long) {
        downloads.resume(taskId)
        message.value = "继续下载"
    }

    fun cancel(taskId: Long) {
        // 导出任务由导出管理器负责取消：两边各有一份"正在跑的 Job"，
        // 只标记状态而不取消协程的话，打包/生成还会继续跑下去。
        val row = state.value.let { current ->
            (current.active + current.finished).firstOrNull { it.task.id == taskId }
        }
        if (row?.isExport == true) {
            exports.cancel(taskId)
            message.value = "已取消导出（未完成的输出已清理）"
        } else {
            // DownloadManager 对官方 ZIP 会在取消后清掉 .part/临时 zip；暂停不清。
            downloads.cancel(taskId)
            message.value = if (row?.isOfficialZip == true) {
                "已取消官方 ZIP（未完成的临时文件已清理，已下载的 ZIP 保留）"
            } else {
                "已取消（已下载的图片保留）"
            }
        }
    }

    fun retry(taskId: Long) {
        downloads.retry(taskId)
        message.value = "重试未完成的部分"
    }

    fun remove(taskId: Long) {
        viewModelScope.launch {
            runCatching { tasks.delete(taskId) }
                .onFailure { Timber.tag("dl").w(it, "删除任务失败") }
            message.value = "已删除任务记录（本地文件未动）"
        }
    }

    // ------------------------------------------------------------------
    // 导出成品的"打开至 / 发送至 / 删除文件"（用户要求第 4 点）
    // ------------------------------------------------------------------

    /** 成品的内容 Uri；界面用它构造 ACTION_VIEW / ACTION_SEND 的 Intent。 */
    suspend fun outputUri(displayName: String): android.net.Uri? = exports.outputUri(displayName)

    /** 删除成品文件本身（记录保留）。 */
    fun deleteOutput(taskId: Long, displayName: String) {
        viewModelScope.launch {
            val ok = runCatching { exports.deleteOutput(displayName) }.getOrDefault(false)
            message.value = if (ok) "已删除文件" else "文件已经不在了"
        }
    }

    /**
     * 删除这本漫画的全部官方 ZIP（只删 `官方zip/<本子>/` 下的 .zip，
     * 解压出来的文件夹不动），随后移除任务记录。界面已二次确认。
     */
    fun deleteOfficialZip(taskId: Long) {
        viewModelScope.launch {
            val freed = runCatching { downloads.deleteOfficialZipFiles(taskId) }
                .getOrDefault(0L)
            runCatching { tasks.delete(taskId) }
                .onFailure { Timber.tag("dl").w(it, "删除官方 ZIP 任务记录失败") }
            message.value = "已删除官方 ZIP（${formatBytes(freed)}）"
        }
    }

    fun clearFinished() {
        viewModelScope.launch {
            val removed = runCatching { tasks.clearFinished() }.getOrDefault(0)
            message.value = if (removed > 0) "已清空完成记录" else "没有可清空的记录"
        }
    }

    fun consumeMessage() {
        message.value = null
    }
}

/**
 * 任务状态文案。
 *
 * 计划 §9 第 9 条：「明确区分排队、执行、暂停、等待网络及待处理失败，
 * **不将失败算作正在运行**」。所以这里每个状态都有独立文案，
 * 不合并成"处理中"这种含糊说法。
 */
fun taskStatusLabel(task: DownloadTaskEntity): String = when (task.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> task.stageText ?: DownloadManager.STAGE_DOWNLOADING
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.WAITING_NETWORK -> "等待网络"
    DownloadStatus.SUCCESS -> "已完成"
    DownloadStatus.FAIL -> "未完成"
    DownloadStatus.CANCELLED -> "已取消"
}

/**
 * 进度文案。
 *
 * 计划 §10 P3 的验收要求：进度显示能精确到"第 N 章 x/y 张"。
 * 所以有图片计数时**一定**同时给出章节数与图片数，而不是只给一个百分比。
 */
fun taskProgressText(task: DownloadTaskEntity): String {
    val images = if (task.totalImages > 0) "${task.doneImages}/${task.totalImages} 张" else null
    val chapters = if (task.totalChapters > 0) "${task.doneChapters}/${task.totalChapters} 话" else null
    // 官方 ZIP 完成后隐藏字节数：它的 totalBytes 只是"当前这一话"的长度，
    // 下载结束后再显示 3.9/4.3 MB 会让人以为任务还没完（用户反馈）。
    val bytes = if (task.totalBytes > 0 &&
        !(task.type == DownloadType.JM_OFFICIAL_ZIP && task.status.isFinished)
    ) {
        "${formatBytes(task.downloadedBytes)}/${formatBytes(task.totalBytes)}"
    } else {
        null
    }
    val parts = listOfNotNull(chapters, images, bytes)
    return parts.joinToString(" · ").ifEmpty { "准备中" }
}

/** 补充说明：等待网络的原因、失败原因。**只在有话说的时候返回内容**。 */
fun taskDetailText(task: DownloadTaskEntity): String? = when {
    task.status == DownloadStatus.WAITING_NETWORK ->
        task.message ?: "当前网络类型不允许下载，切换网络后自动继续"

    task.status == DownloadStatus.FAIL -> task.message

    // 导出任务（zip/pdf）的 doneImages 是"已写入的条目数"，不是下载的图片数，
    // 所以不能沿用"已下载 N 张"的说法（用户反馈的 pdf 任务文案错误）
    task.status == DownloadStatus.RUNNING && task.isExportTask ->
        "生成中"

    task.status == DownloadStatus.RUNNING && task.doneImages > 0 ->
        "已下载 ${task.doneImages} 张"

    else -> null
}

/** 导出任务（生成 ZIP / 生成 PDF）：**不能续做**，进度口径也不是"张"。 */
val DownloadTaskEntity.isExportTask: Boolean
    get() = type.isExport
