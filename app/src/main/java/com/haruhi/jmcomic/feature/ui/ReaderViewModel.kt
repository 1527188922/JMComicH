package com.haruhi.jmcomic.feature.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.settings.SettingDefaults
import com.haruhi.jmcomic.core.settings.SettingKeys
import com.haruhi.jmcomic.core.settings.SettingsRepository
import com.haruhi.jmcomic.feature.data.ChapterSummary
import com.haruhi.jmcomic.feature.data.DownloadManager
import com.haruhi.jmcomic.feature.data.ImageStore
import com.haruhi.jmcomic.feature.data.ReadingRepository
import com.haruhi.jmcomic.feature.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读器里的一页。
 *
 * [file] 为 null 表示**还没拿到图**：[state] 说明是正在联网取、还是取失败了。
 * 阅读器对没有缓存的页会**自动联网取回来并落盘**（用户要求第 2 点），
 * 所以正常情况下用户看不到"图片缺失"；只有真的取不到（离线、图床挂了）才提示并给重试。
 */
data class ReaderPage(
    val index: Int,
    val imageFile: String,
    val file: File? = null,
    /** 高/宽。没有图时用 [DEFAULT_ASPECT] 占位，保证长图占位稳定。 */
    val aspectRatio: Float = DEFAULT_ASPECT,
    val state: PageState = PageState.Pending,
    val error: String? = null,
) {
    val available: Boolean get() = file != null

    enum class PageState {
        /** 本地没有，等待联网取回。 */
        Pending,

        /** 正在下载。 */
        Loading,

        /** 已就绪（本地缓存或刚取回来）。 */
        Ready,

        /** 取失败（可重试）。 */
        Failed,
    }

    companion object {
        /** 缺图与还未测量时的占位比例（约 A4 竖版），避免布局跳动。 */
        const val DEFAULT_ASPECT = 1.4f
    }
}

data class ReaderUiState(
    val album: AlbumEntity? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val chapterIndex: Int = -1,
    val pages: List<ReaderPage> = emptyList(),
    /** 打开这一话时要跳到的页（来自上次的阅读位置）。 */
    val startPage: Int = 0,
    val startOffsetRatio: Float = 0f,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val controlsVisible: Boolean = true,
    val showChapterPicker: Boolean = false,
    val showSettings: Boolean = false,
    /** 当前停在第几页（0 起），底部进度条用。 */
    val currentPage: Int = 0,
    /** 本子是否已收藏（阅读器里的收藏按钮）。 */
    val collected: Boolean = false,
) {
    val currentChapter: ChapterSummary? get() = chapters.getOrNull(chapterIndex)
    val chapterTitle: String get() = currentChapter?.title ?: "阅读"
    val hasPreviousChapter: Boolean get() = chapterIndex > 0
    val hasNextChapter: Boolean get() = chapterIndex in 0 until chapters.lastIndex
    val totalPages: Int get() = pages.size

    /** 还等着联网取的页数。 */
    val pendingCount: Int get() = pages.count { it.state == ReaderPage.PageState.Pending || it.state == ReaderPage.PageState.Loading }

    /** 取失败的页数（只有这种才需要用户点重试）。 */
    val failedCount: Int get() = pages.count { it.state == ReaderPage.PageState.Failed }

    /** 已经在本地缓存里的页数。 */
    val cachedCount: Int get() = pages.count { it.available }

    /** 顶栏文案。 */
    val progressLabel: String
        get() = buildString {
            append("${pages.size} 页")
            if (cachedCount in 1 until pages.size) append(" · 已缓存 $cachedCount 页")
        }

    /** 底部进度条文案："12/34 页"。 */
    val pageLabel: String
        get() = if (pages.isEmpty()) "0/0 页" else "${(currentPage + 1).coerceIn(1, pages.size)}/${pages.size} 页"
}

/**
 * 阅读器（计划 §9 第 8 条）。
 *
 * ## 能看就能看，不必先下载（用户要求第 2、4 点）
 *
 * 页面清单来自 `t_jm_chapter_image`（权威清单），**图不在本地就联网取回来并落盘**
 * （[ImageStore]）。所以：
 * - 删掉本地文件后再进阅读器，会自动重新取，不再显示"图片缺失"；
 * - 取回来的图就是缓存，下次再读不再请求；
 * - 用户点下载时，下载任务对每张图走的是同一个"文件在不在"的判断，
 *   所以"一边看一边下载"不冲突，也不会重复请求同一张图。
 *
 * 取图是**按页懒加载**的：只有滚动到附近的页才会发请求（LazyColumn / Pager 只组合可见项），
 * 不会一进阅读器就把整话几十张图全拉下来。
 *
 * ## 位置持久化
 *
 * 用 `本子 + 章节 + 图片文件名 + 页内比例` 记录（见 [ReadingRepository]）。
 * 翻页时先把当前页写进 UI 状态（进度条要立刻动），**落库去抖 400ms**：
 * 长图连续滚动时每帧都写库既浪费又会让 Room 排队。
 */
class ReaderViewModel(
    private val repo: Repository,
    private val reading: ReadingRepository,
    private val store: ImageStore,
    private val settings: SettingsRepository,
    private val downloads: DownloadManager,
    private val albumId: Long,
    private val startChapterId: Long?,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 正在取的页（按 imageFile 去重），避免滚动时同一页被重复请求。 */
    private val fetching = ConcurrentHashMap<String, Job>()

    private var loadJob: Job? = null
    private var positionJob: Job? = null
    private var scanJob: Job? = null

    init {
        // 进阅读器也算"看过这本"：刷新最近浏览时间（用户要求第 5 点）。
        // 详情页已经在进页时刷过一次，但用户可能从"继续阅读"卡片或章节直接进阅读器，
        // 那条路径也必须把浏览时间顶到最新。
        viewModelScope.launch {
            runCatching { repo.touchRecent(albumId) }
                .onFailure { Timber.tag("jm").w(it, "记录最近浏览失败") }
        }
        viewModelScope.launch {
            settings.observeReaderPreferences().collect { prefs ->
                _state.update { it.copy(preferences = prefs) }
            }
        }
        viewModelScope.launch {
            repo.observeAlbum(albumId).collect { album ->
                if (album != null) _state.update { it.copy(collected = album.collected == true) }
            }
        }
        viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        val detail = runCatching { repo.localAlbumDetail(albumId) }.getOrNull()
        val album = detail?.album
        if (album == null) {
            _state.update { it.copy(loading = false, error = "本地没有这本漫画的数据，请先联网打开详情页") }
            return
        }
        val chapters = runCatching { repo.chaptersOf(album) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            _state.update {
                it.copy(album = album, loading = false, error = "还没有章节数据，请在详情页刷新")
            }
            return
        }

        val position = runCatching { reading.positionOf(albumId) }.getOrNull()
        val index = when {
            startChapterId != null -> chapters.indexOfFirst { it.chapterId == startChapterId }
            position?.chapterId != null -> chapters.indexOfFirst { it.chapterId == position.chapterId }
            else -> -1
        }.let { if (it >= 0) it else 0 }

        _state.update { it.copy(album = album, chapters = chapters) }
        // 手动指定章节（从详情页点进来）时从头读；否则续读上次的位置
        openChapter(index, restorePosition = startChapterId == null)
    }

    /**
     * 打开某一话。
     *
     * @param restorePosition 是否把上次读到的页作为起始页；从章节列表手动切换时为 false
     */
    private fun openChapter(index: Int, restorePosition: Boolean) {
        val chapters = _state.value.chapters
        val chapter = chapters.getOrNull(index) ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, pages = emptyList()) }
            val album = _state.value.album ?: return@launch
            val position = if (restorePosition) {
                runCatching { reading.positionOf(albumId) }.getOrNull()
                    ?.takeIf { it.chapterId == chapter.chapterId }
            } else {
                null
            }

            val entities = runCatching { reading.imagesOf(albumId, chapter.chapterId) }
                .getOrDefault(emptyList())
            if (entities.isEmpty()) {
                // 没有图片清单就没法读：先联网补齐清单（`/chapter`），失败才提示
                val fetched = runCatching { repo.fetchAndSaveChapter(albumId, chapter.chapterId) }
                    .getOrDefault(0)
                if (fetched == 0) {
                    _state.update {
                        it.copy(
                            chapterIndex = index,
                            pages = emptyList(),
                            loading = false,
                            error = "这一话的图片清单还没拿到，请检查网络后重试",
                        )
                    }
                    return@launch
                }
            }

            val images = runCatching { reading.imagesOf(albumId, chapter.chapterId) }
                .getOrDefault(emptyList())
            val chapterFolder = repo.chapterFolderName(chapter)
            val pages = withContext(Dispatchers.IO) {
                images.mapIndexed { pageIndex, entity ->
                    // 本地有就立刻可用；没有就标记成待取，由界面滚动到附近时按需联网取
                    val cached = store.cachedFile(album, chapterFolder, entity.imageFile)
                    ReaderPage(
                        index = pageIndex,
                        imageFile = entity.imageFile,
                        file = cached,
                        aspectRatio = cached?.let { store.aspectRatioOf(it, ReaderPage.DEFAULT_ASPECT) }
                            ?: ReaderPage.DEFAULT_ASPECT,
                        state = if (cached != null) {
                            ReaderPage.PageState.Ready
                        } else {
                            ReaderPage.PageState.Pending
                        },
                    )
                }
            }
            val startPage = position?.imageFile
                ?.let { name -> pages.indexOfFirst { it.imageFile == name } }
                ?.takeIf { it >= 0 }
                ?: position?.imageIndex?.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                ?: 0

            _state.update {
                it.copy(
                    chapterIndex = index,
                    pages = pages,
                    startPage = if (restorePosition) startPage else 0,
                    startOffsetRatio = if (restorePosition) position?.offsetRatio ?: 0f else 0f,
                    currentPage = if (restorePosition) startPage else 0,
                    loading = false,
                    error = null,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 取图（本地优先，缺了就联网并缓存）
    // ------------------------------------------------------------------

    /**
     * 请求某一页的图。界面在页面进入可视范围时调用（同一页重复调用是安全的）。
     *
     * 本地已有、正在取、已经取失败过的都不再自动重发——失败要用户点「重试」，
     * 否则弱网下会在滚动时反复打请求。
     */
    fun requestPage(pageIndex: Int) {
        val current = _state.value
        val page = current.pages.getOrNull(pageIndex) ?: return
        if (page.available || page.state == ReaderPage.PageState.Failed) return
        val chapter = current.currentChapter ?: return
        val album = current.album ?: return
        val folder = repo.chapterFolderName(chapter)
        if (fetching.containsKey(page.imageFile)) return

        // 立刻标记"正在取"，避免同一页被连续几次布局回调重复排队
        updatePage(pageIndex) { it.copy(state = ReaderPage.PageState.Loading, error = null) }
        fetching[page.imageFile] = viewModelScope.launch {
            try {
                val file = store.file(album, chapter.chapterId, folder, page.imageFile)
                // 量比例也要读文件头，先在 IO 上算完再更新状态
                val aspect = store.aspectRatioOf(file, ReaderPage.DEFAULT_ASPECT)
                updatePage(pageIndex) {
                    it.copy(
                        file = file,
                        aspectRatio = aspect,
                        state = ReaderPage.PageState.Ready,
                        error = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("jm").w(e, "取图失败：%s", page.imageFile)
                updatePage(pageIndex) {
                    it.copy(state = ReaderPage.PageState.Failed, error = e.toUserMessage())
                }
            } finally {
                fetching.remove(page.imageFile)
            }
        }
    }

    /** 重试某一页：清掉失败标记后再取一次。 */
    fun retryPage(pageIndex: Int) {
        val page = _state.value.pages.getOrNull(pageIndex) ?: return
        if (page.available) {
            // 本地文件被删掉了：退回"待取"让它重新联网
            updatePage(pageIndex) { it.copy(file = null, state = ReaderPage.PageState.Pending, error = null) }
        } else {
            updatePage(pageIndex) { it.copy(state = ReaderPage.PageState.Pending, error = null) }
        }
        requestPage(pageIndex)
    }

    /** 重新扫一遍本地缓存（下载完成后回到阅读器，已下好的页要立刻显示）。 */
    fun refreshFromDisk() {
        val current = _state.value
        val album = current.album ?: return
        val chapter = current.currentChapter ?: return
        val folder = repo.chapterFolderName(chapter)
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            // 逐页核对磁盘：读文件头也算解码，全部放到 IO 上做（用户要求第 9 点）
            val scanned = withContext(Dispatchers.IO) {
                current.pages.map { page ->
                    val cached = store.cachedFile(album, folder, page.imageFile)
                    when {
                        cached != null -> page.copy(
                            file = cached,
                            aspectRatio = store.aspectRatioOf(cached, ReaderPage.DEFAULT_ASPECT),
                            state = ReaderPage.PageState.Ready,
                            error = null,
                        )
                        // 本地被删了且之前是就绪状态 -> 回到待取，滚动到时自动重新联网
                        page.available -> page.copy(file = null, state = ReaderPage.PageState.Pending)
                        else -> page
                    }
                }
            }
            _state.update { it.copy(pages = scanned) }
        }
    }

    private fun updatePage(index: Int, transform: (ReaderPage) -> ReaderPage) {
        _state.update { state ->
            val page = state.pages.getOrNull(index) ?: return@update state
            state.copy(pages = state.pages.toMutableList().also { it[index] = transform(page) })
        }
    }

    // ------------------------------------------------------------------
    // 导航
    // ------------------------------------------------------------------

    fun openChapterAt(index: Int) {
        _state.update { it.copy(showChapterPicker = false) }
        openChapter(index, restorePosition = false)
    }

    fun previousChapter() {
        val index = _state.value.chapterIndex
        if (index > 0) openChapter(index - 1, restorePosition = false)
    }

    fun nextChapter() {
        val index = _state.value.chapterIndex
        if (index in 0 until _state.value.chapters.lastIndex) openChapter(index + 1, restorePosition = false)
    }

    /**
     * 下载当前这一话（`下载` 按钮，用户要求第 3 点）。
     *
     * 会自动把本子加入书库——下载的前提是它有一条书库记录（目录名从那里来）。
     * 每张图落盘前都会先看缓存（[ImageStore]），所以**正在看的页不会被重复下载**。
     */
    fun downloadCurrentChapter() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val chapter = _state.value.currentChapter ?: return@launch
            runCatching {
                val library = repo.addToLibrary(album)
                downloads.submit(library, listOf(chapter))
            }.onSuccess { result ->
                val text = when (result) {
                    is com.haruhi.jmcomic.core.download.DownloadTaskRepository.SubmitResult.Created ->
                        "已加入下载队列：${chapter.title}"

                    is com.haruhi.jmcomic.core.download.DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已有下载任务，已把「${chapter.title}」加进去"
                }
                _state.update { it.copy(message = text) }
            }.onFailure { e ->
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 位置
    // ------------------------------------------------------------------

    /**
     * 上报当前位置（翻页/滚动时调用）。
     *
     * 分两步：**当前页立刻进 UI 状态**（底部进度条要跟着动），
     * **落库去抖 400ms**（快速滑动时不需要每一帧都写）。
     */
    fun onPositionChanged(pageIndex: Int, offsetRatio: Float) {
        val chapterId = _state.value.currentChapter?.chapterId ?: return
        val page = _state.value.pages.getOrNull(pageIndex) ?: return
        _state.update { it.copy(currentPage = pageIndex) }
        pendingPosition = PendingPosition(chapterId, page.imageFile, pageIndex, offsetRatio)
        if (positionJob?.isActive == true) return
        positionJob = viewModelScope.launch {
            delay(POSITION_DEBOUNCE_MS)
            val pending = pendingPosition ?: return@launch
            runCatching {
                reading.savePosition(
                    albumId = albumId,
                    chapterId = pending.chapterId,
                    imageFile = pending.imageFile,
                    imageIndex = pending.pageIndex,
                    offsetRatio = pending.offsetRatio,
                )
            }.onFailure { Timber.tag("jm").w(it, "保存阅读位置失败") }
        }
    }

    /** 立刻落库（退出阅读器时调用，避免去抖窗口里的位置丢掉）。 */
    fun flushPosition() {
        val pending = pendingPosition ?: return
        positionJob?.cancel()
        viewModelScope.launch {
            runCatching {
                reading.savePosition(
                    albumId = albumId,
                    chapterId = pending.chapterId,
                    imageFile = pending.imageFile,
                    imageIndex = pending.pageIndex,
                    offsetRatio = pending.offsetRatio,
                )
            }
        }
    }

    private var pendingPosition: PendingPosition? = null

    private data class PendingPosition(
        val chapterId: Long,
        val imageFile: String,
        val pageIndex: Int,
        val offsetRatio: Float,
    )

    // ------------------------------------------------------------------
    // 收藏（用户要求第 3 点：阅读器里要有收藏按钮）
    // ------------------------------------------------------------------

    /**
     * 收藏 / 取消收藏。
     *
     * 与详情页同一套语义：**收藏隐含入库**（目录名与书库记录都从书库来），
     * 所以未入库时先 `addToLibrary`。取消收藏只解除收藏关系，不动本地文件。
     */
    fun toggleCollected() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val currentlyCollected = _state.value.collected
            runCatching {
                if (currentlyCollected) {
                    repo.uncollect(albumId)
                } else {
                    repo.addToLibrary(album)
                    repo.addToFavorite(albumId)
                }
            }.onSuccess {
                _state.update { it.copy(collected = !currentlyCollected, message = if (currentlyCollected) "已取消收藏" else "已加入收藏") }
            }.onFailure { e ->
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 控件与偏好
    // ------------------------------------------------------------------

    fun toggleControls() = _state.update { it.copy(controlsVisible = !it.controlsVisible) }

    fun hideControls() = _state.update { it.copy(controlsVisible = false) }

    fun showChapterPicker() = _state.update { it.copy(showChapterPicker = true, controlsVisible = true) }

    fun showSettings() = _state.update { it.copy(showSettings = true, controlsVisible = true) }

    fun dismissSheets() = _state.update { it.copy(showChapterPicker = false, showSettings = false) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch { settings.putJmInt(SettingKeys.Jm.READING_MODE, mode.toCode()) }
    }

    fun setReadingDirection(direction: ReadingDirection) {
        viewModelScope.launch { settings.putJmInt(SettingKeys.Jm.READING_DIRECTION, direction.toCode()) }
    }

    fun setBackground(background: ReadingBackground) {
        viewModelScope.launch { settings.putJmInt(SettingKeys.Jm.READING_BACKGROUND, background.toCode()) }
    }

    fun setBrightnessFollowSystem(follow: Boolean) {
        viewModelScope.launch {
            settings.putJmInt(
                SettingKeys.Jm.BRIGHTNESS_MODE,
                if (follow) {
                    SettingDefaults.Jm.BRIGHTNESS_FOLLOW_SYSTEM
                } else {
                    SettingDefaults.Jm.BRIGHTNESS_CUSTOM
                },
            )
        }
    }

    fun setBrightness(value: Float) {
        viewModelScope.launch {
            settings.putJmInt(SettingKeys.Jm.BRIGHTNESS_VALUE, (value * 100).toInt().coerceIn(1, 100))
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settings.putJmBoolean(SettingKeys.Jm.KEEP_SCREEN_ON, enabled) }
    }

    companion object {
        /** 位置落库的去抖时长。 */
        const val POSITION_DEBOUNCE_MS = 400L
    }
}

/**
 * 阅读偏好流：任一设置变化都会重新发射。
 *
 * 用 `combine` 而不是逐项收集，是为了让"改亮度"和"改背景"走同一条路径，
 * 界面只订阅一个状态。
 */
fun SettingsRepository.observeReaderPreferences(): Flow<ReaderPreferences> {
    val mode = jmInt(SettingKeys.Jm.READING_MODE, SettingDefaults.Jm.READING_MODE_VERTICAL)
    val direction = jmInt(SettingKeys.Jm.READING_DIRECTION, SettingDefaults.Jm.READING_DIRECTION_LTR)
    val background = jmInt(SettingKeys.Jm.READING_BACKGROUND, SettingDefaults.Jm.READING_BACKGROUND_THEME)
    val brightnessMode = jmInt(SettingKeys.Jm.BRIGHTNESS_MODE, SettingDefaults.Jm.BRIGHTNESS_FOLLOW_SYSTEM)
    val brightnessValue = jmInt(SettingKeys.Jm.BRIGHTNESS_VALUE, SettingDefaults.Jm.BRIGHTNESS_VALUE_DEFAULT)
    val keepScreenOn = jmBoolean(SettingKeys.Jm.KEEP_SCREEN_ON, SettingDefaults.Jm.KEEP_SCREEN_ON)
    return combine(mode, direction, background, brightnessMode, brightnessValue) { m, d, b, bm, bv ->
        ReaderPreferences(
            mode = ReadingMode.fromCode(m),
            direction = ReadingDirection.fromCode(d),
            background = ReadingBackground.fromCode(b),
            brightnessFollowSystem = bm == SettingDefaults.Jm.BRIGHTNESS_FOLLOW_SYSTEM,
            brightnessValue = bv / 100f,
        )
    }.combine(keepScreenOn) { prefs, keep -> prefs.copy(keepScreenOn = keep) }
}
