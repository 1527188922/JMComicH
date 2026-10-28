package com.haruhi.jmcomic.feature.ui

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.BuildConfig
import com.haruhi.jmcomic.core.download.AppDownloadGateway
import com.haruhi.jmcomic.feature.api.GiteeRelease
import com.haruhi.jmcomic.feature.api.ReleaseCheckResult
import com.haruhi.jmcomic.feature.api.ReleaseChecker
import com.haruhi.jmcomic.feature.data.AlbumUsage
import com.haruhi.jmcomic.feature.data.ExportManager
import com.haruhi.jmcomic.feature.data.Repository
import com.haruhi.jmcomic.feature.data.StorageKind
import com.haruhi.jmcomic.feature.data.StorageStats
import com.haruhi.jmcomic.feature.data.StorageStatsCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 检查更新 → 下载 → 安装的状态机（「关于」页）。
 *
 * ```
 * 点击[检查更新]（每次都重新请求 Gitee）
 *   ├─ 接口失败 / 无新版 ──> 不进弹框，只给 Snackbar
 *   └─ 有新版 ──> 弹框
 *        ├─ 该版本有未完成下载 ──> Downloading（进度条）
 *        ├─ 该版本已下载且可读 ──> Available(downloadedId != null)（按钮[安装]）
 *        └─ 其余               ──> Available(downloadedId = null)（按钮[下载]）
 * ```
 *
 * 页面卡片**不展示**“发现新版本 / 已下载”，外部动作只有「检查更新」；
 * 已下载的安装包只在弹框里以「安装」按钮出现。
 *
 * 下载**不做本地持久化**：DownloadManager 的记录在系统侧跨进程存活，
 * `records(MARKER_APP_UPDATE)` 按用途标记找回（见 `AppDownloadGateway`）。
 * 进入页面时也不恢复上次记录——未安装的包等下一次「检查更新」时再按 tag 找回。
 */
sealed interface UpdateState {

    /** 没有进行中的更新事务。 */
    data object Idle : UpdateState

    /**
     * 有新版本，弹框数据齐了。
     *
     * [downloadedId] 非空表示该版本的安装包已下载且可读，弹框按钮显示「安装」；
     * 为空时显示「下载」（`apkUrl` 也为空则退回「打开发布页」）。
     */
    data class Available(
        val tag: String,
        val name: String,
        val body: String,
        val prerelease: Boolean,
        val apkUrl: String?,
        val downloadedId: Long? = null,
    ) : UpdateState

    /** APK 下载中（`percent` 为 null 表示还不知道总大小）。 */
    data class Downloading(
        val id: Long,
        val tag: String,
        val percent: Int?,
        val bytesSoFar: Long,
        val totalBytes: Long,
    ) : UpdateState
}

data class AboutUiState(
    /** null = 首次扫描中（页面结构先画、数字回来再填，不做整页 loading 交换）。 */
    val storage: StorageStats? = null,
    val storageFailed: Boolean = false,
    /** 选中的分段（null = 只看总量）；点同一个段再点一次收起。 */
    val selected: StorageKind? = null,
    val update: UpdateState = UpdateState.Idle,
    /** 正在请求 Gitee；页面只显示“正在检查…”，不改变当前 [update]。 */
    val checking: Boolean = false,
    /** 更新弹框是否展示（Downloading 时关掉 = 后台下载，页面卡片继续看进度）。 */
    val showUpdateDialog: Boolean = false,
    /** 一次性提示（Snackbar，走 `MessageEffect`）。 */
    val message: String? = null,
)

class AboutViewModel(
    private val repo: Repository,
    private val exports: ExportManager,
    private val collector: StorageStatsCollector,
    private val officialZips: com.haruhi.jmcomic.core.storage.OfficialZipPublisher,
    private val releaseChecker: ReleaseChecker,
    private val downloads: AppDownloadGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState())
    val state = _state.asStateFlow()

    /** 最近一次拉到的发行版信息（下载失败 / 取消后回到 Available 时复用）。 */
    private var lastRelease: GiteeRelease? = null
    private var lastApkUrl: String? = null

    private var scanJob: Job? = null
    private var watchJob: Job? = null

    init {
        rescan()
    }

    // ------------------------------------------------------------------
    // 存储统计
    // ------------------------------------------------------------------

    /** 进页面 / 回到页面重扫（从详情页删文件、下载返回时数字跟着变）。 */
    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            // 不清掉旧数据：重扫期间界面继续显示旧数字，新数据到了原位替换，
            // 不会每次回来都闪一下"统计中"（首次进入时 storage 本来就是 null）。
            _state.update { it.copy(storageFailed = false) }
            val stats = collector.collect()
            _state.update { state ->
                if (stats != null) state.copy(storage = stats) else state.copy(storageFailed = true)
            }
        }
    }

    /** 点同一个段再点一次 = 收起明细。 */
    fun select(kind: StorageKind) {
        _state.update { it.copy(selected = if (it.selected == kind) null else kind) }
    }

    // ------------------------------------------------------------------
    // 明细列表的操作
    // ------------------------------------------------------------------

    /**
     * 删除明细里某一行的内容。**按段语义**：每行只删它所代表的那一块——
     * 图片库行只删图片（`deleteAlbumFiles`，保留库记录与导出成品），
     * 阅读缓存行只删 `reader-cache` 目录，zip / pdf 行只删那一个成品文件。
     * 确认弹窗由界面负责，这里收到调用就是用户已确认。
     */
    fun deleteUsage(kind: StorageKind, usage: AlbumUsage) {
        when (kind) {
            StorageKind.LIBRARY -> usage.folderName?.let { folder ->
                viewModelScope.launch {
                    val freed = repo.deleteAlbumFiles(folder)
                    rescan()
                    _state.update { it.copy(message = "已删除本地图片（${formatBytes(freed)}）") }
                }
            }

            StorageKind.READER_CACHE -> usage.folderName?.let { folder ->
                viewModelScope.launch {
                    val freed = repo.deleteReaderCacheFiles(folder)
                    rescan()
                    _state.update { it.copy(message = "已删除阅读缓存（${formatBytes(freed)}）") }
                }
            }

            StorageKind.EXPORT_ZIP, StorageKind.EXPORT_PDF -> usage.detail?.let { fileName ->
                viewModelScope.launch {
                    val deleted = exports.deleteOutput(fileName)
                    rescan()
                    _state.update {
                        it.copy(
                            message = if (deleted) {
                                "已删除导出文件"
                            } else {
                                "删除失败，文件可能已经不在了"
                            },
                        )
                    }
                }
            }

            // 只删 `官方zip/<本子>/` 下的 .zip；解压出来的目录不动
            StorageKind.OFFICIAL_ZIP -> usage.folderName?.let { folder ->
                viewModelScope.launch {
                    val freed = officialZips.deleteAlbum(folder)
                    rescan()
                    _state.update {
                        it.copy(message = "已删除官方 ZIP（${formatBytes(freed)}）")
                    }
                }
            }

            // 封面缓存不支持按本钻取，不会走到这
            StorageKind.COVERS -> Unit
        }
    }

    /** 导出成品的内容 Uri（「打开至 / 发送至」发 Intent 用，与任务页/详情页同一模式）。 */
    suspend fun outputUri(displayName: String): android.net.Uri? = exports.outputUri(displayName)

    // ------------------------------------------------------------------
    // 检查更新
    // ------------------------------------------------------------------

    /**
     * 手动检查（页面唯一的触发点）。**每次点击都会重新请求 Gitee**，
     * 即使本地已有下载好的安装包也不例外（弹框里的按钮才据此切换「下载 / 安装」）。
     *
     * **debug 包直接无反应**（用户拍板）：debug 的 `VERSION_NAME` 带 `-debug` 后缀、
     * 也不该去比正式发行版。guard 放在 ViewModel 而不是 UI 层，
     * 将来若有别的入口复用这条逻辑也不会漏。
     */
    fun checkUpdate() {
        if (BuildConfig.DEBUG) return
        if (_state.value.checking) return
        // 不清掉 update：下载中点击检查时，进度要继续走，结果回来后再按记录校正
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            when (val result = releaseChecker.latest()) {
                is ReleaseCheckResult.Unavailable -> _state.update {
                    it.copy(checking = false, message = "检查更新失败，请稍后再试")
                }

                is ReleaseCheckResult.Ok -> onReleaseFetched(result.release)
            }
        }
    }

    private suspend fun onReleaseFetched(release: GiteeRelease) {
        lastRelease = release
        lastApkUrl = ReleaseChecker.apkUrlOf(release)
        when (ReleaseChecker.isNewer(release.tagName, BuildConfig.VERSION_NAME)) {
            true -> {
                // 清掉旧版本的下载记录（连旧 APK 文件一起删），再找当前 tag 的下载记录
                sweepStaleRecords(keepTag = release.tagName)
                val active = findActiveRecord(release.tagName)
                val ready = if (active == null) findReadyRecord(release.tagName) else null
                val update = when {
                    active != null -> UpdateState.Downloading(
                        id = active.id,
                        tag = release.tagName,
                        percent = percentOf(active),
                        bytesSoFar = active.bytesSoFar,
                        totalBytes = active.totalBytes,
                    )

                    else -> UpdateState.Available(
                        tag = release.tagName,
                        name = release.name,
                        body = release.body,
                        prerelease = release.prerelease,
                        apkUrl = lastApkUrl,
                        downloadedId = ready?.id,
                    )
                }
                _state.update {
                    it.copy(checking = false, update = update, showUpdateDialog = true)
                }
                if (active != null) {
                    watchDownload(active.id, release.tagName)
                }
            }

            false -> {
                // 已是最新：本地的更新包记录都不再有价值，连文件一起清掉
                sweepStaleRecords(keepTag = null)
                clearReleaseCache()
                _state.update {
                    it.copy(
                        checking = false,
                        update = UpdateState.Idle,
                        message = "已是最新版本（v${BuildConfig.VERSION_NAME}）",
                    )
                }
            }

            null -> {
                // tag 不符合 v*.*.*：按"无法判断"处理，绝不误报新版本
                clearReleaseCache()
                _state.update {
                    it.copy(
                        checking = false,
                        message = "无法识别最新版本号（${release.tagName}）",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 下载 / 安装
    // ------------------------------------------------------------------

    /** 弹框的「下载」：交给 DownloadManager（系统级后台，App 进程死了也继续）。 */
    fun downloadUpdate() {
        // 状态机保证只有 Available 时才可能有「下载」按钮，这里再收一次口
        val available = _state.value.update as? UpdateState.Available ?: return
        if (available.downloadedId != null) return
        val url = available.apkUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val id = runCatching {
                withContext(Dispatchers.IO) {
                    downloads.enqueue(
                        url = url,
                        subPath = "apk/JMComicH-${available.tag}.apk",
                        title = ReleaseChecker.downloadTitleOf(available.tag),
                        marker = AppDownloadGateway.MARKER_APP_UPDATE,
                        mimeType = AppDownloadGateway.APK_MIME,
                    )
                }
            }.getOrElse {
                // 部分定制 ROM 禁用了系统下载服务
                Timber.tag("update").w(it, "提交下载失败")
                _state.update { it.copy(message = "无法开始下载，请稍后再试") }
                return@launch
            }
            _state.update {
                it.copy(update = UpdateState.Downloading(id, available.tag, null, 0L, 0L))
            }
            watchDownload(id, available.tag)
        }
    }

    /** 下载中「取消」：中止并清半成品（`remove` 自动删文件），弹框回到「下载」。 */
    fun cancelUpdateDownload() {
        val downloading = _state.value.update as? UpdateState.Downloading ?: return
        backToAvailable()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { downloads.remove(downloading.id) }
        }
    }

    /** 下载中「后台」：关弹框，进度挪到「关于」页的应用信息卡上继续看。 */
    fun updateToBackground() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    /** 弹框「以后再说」。下载中等于后台下载，其余只是收起弹框。 */
    fun dismissUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 下载失败 / 取消后回到 Available（弹框数据还在，用户可直接重点「下载」）。 */
    private fun backToAvailable() {
        val release = lastRelease
        if (release == null) {
            _state.update { it.copy(update = UpdateState.Idle, showUpdateDialog = false) }
            return
        }
        _state.update {
            it.copy(
                update = UpdateState.Available(
                    tag = release.tagName,
                    name = release.name,
                    body = release.body,
                    prerelease = release.prerelease,
                    apkUrl = lastApkUrl,
                ),
            )
        }
    }

    private fun clearReleaseCache() {
        lastRelease = null
        lastApkUrl = null
    }

    /**
     * 轮询下载进度直到终态。
     *
     * 终止条件除记录本身的 SUCCESSFUL / FAILED 外，还有"状态已不再是这条下载"——
     * 取消路径是**先改状态、再删记录**，轮询发现状态对不上就退出，
     * 不会拿旧进度把新状态覆盖掉。
     */
    private fun watchDownload(id: Long, tag: String) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (isActive) {
                val stillWatching = _state.value.update.let { it is UpdateState.Downloading && it.id == id }
                if (!stillWatching) break

                val record = withContext(Dispatchers.IO) {
                    runCatching {
                        downloads.records(AppDownloadGateway.MARKER_APP_UPDATE).firstOrNull { it.id == id }
                    }.getOrNull()
                }
                when {
                    // 记录没了（被取消 / 清理），状态已由取消方写好
                    record == null -> break

                    record.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        // 下载完成不自动唤起安装器：弹框里的按钮从「下载」变成「安装」，
                        // 由用户自己决定什么时候装
                        val release = lastRelease
                        _state.update {
                            it.copy(
                                update = UpdateState.Available(
                                    tag = tag,
                                    name = release?.name.orEmpty(),
                                    body = release?.body.orEmpty(),
                                    prerelease = release?.prerelease ?: false,
                                    apkUrl = lastApkUrl,
                                    downloadedId = id,
                                ),
                            )
                        }
                        break
                    }

                    record.status == DownloadManager.STATUS_FAILED -> {
                        withContext(Dispatchers.IO) { downloads.remove(id) }
                        backToAvailable()
                        _state.update { it.copy(message = "下载失败，请重试") }
                        break
                    }

                    else -> {
                        val percent = percentOf(record)
                        _state.update {
                            it.copy(
                                update = UpdateState.Downloading(
                                    id = id,
                                    tag = tag,
                                    percent = percent,
                                    bytesSoFar = record.bytesSoFar,
                                    totalBytes = record.totalBytes,
                                ),
                            )
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 清掉与目标版本无关的记录（`keepTag = null` 表示全清；连旧 APK 文件一起删）。 */
    private suspend fun sweepStaleRecords(keepTag: String?) {
        withContext(Dispatchers.IO) {
            runCatching {
                val stale = downloads.records(AppDownloadGateway.MARKER_APP_UPDATE).filter { record ->
                    // 失败记录即使 tag 相同也不保留：重新下载会撞上残留文件
                    val tag = ReleaseChecker.tagFromTitle(record.title)
                    tag != keepTag || (record.finished && !record.successful)
                }
                if (stale.isNotEmpty()) {
                    downloads.remove(*stale.map { it.id }.toLongArray())
                }
            }
        }
    }

    /** 当前版本 tag 的未完成下载记录（进程重启 / 后台下载继续时找回进度）。 */
    private suspend fun findActiveRecord(tag: String): AppDownloadGateway.Record? =
        withContext(Dispatchers.IO) {
            runCatching {
                downloads.records(AppDownloadGateway.MARKER_APP_UPDATE)
                    .firstOrNull { !it.finished && ReleaseChecker.tagFromTitle(it.title) == tag }
            }.getOrNull()
        }

    /**
     * 当前版本 tag 的已完成且**仍可读**的下载记录（有就显示「安装」）。
     * 记录成功但文件被删 / 清数据后 URI 失效的，一并清掉，按未下载处理。
     */
    private suspend fun findReadyRecord(tag: String): AppDownloadGateway.Record? =
        withContext(Dispatchers.IO) {
            runCatching {
                val records = downloads.records(AppDownloadGateway.MARKER_APP_UPDATE).filter {
                    it.successful && ReleaseChecker.tagFromTitle(it.title) == tag
                }
                val ready = records.firstOrNull { downloads.isDownloadedFileReadable(it.id) }
                val broken = records.filter { it.id != ready?.id }
                if (broken.isNotEmpty()) {
                    downloads.remove(*broken.map { it.id }.toLongArray())
                }
                ready
            }.getOrNull()
        }

    private fun percentOf(record: AppDownloadGateway.Record): Int? =
        if (record.totalBytes > 0) {
            (record.bytesSoFar * 100 / record.totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }

    private companion object {
        /** 进度轮询间隔：ContentProvider 查询很轻，800ms 足够跟手。 */
        const val POLL_INTERVAL_MS = 800L
    }
}
