package com.haruhi.jmcomic.feature.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.settings.ReaderCacheMode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 阅读器里的一页。
 *
 * 一页"能看"有两种来源（见 `ReaderCacheMode`）：
 * - [displayFile]：**解扰后的成品文件**（已下载的、或旧管线缓存下来的），交给 Coil 显示；
 * - [bitmap]：新管线在内存里就地解扰出来的位图，直接 `Image()` 显示。
 *
 * [rawFile] 只在**新管线**里出现：站点原始字节已经落盘，但还没解扰、**不能直接显示**。
 * 它的意义是"字节已经到手"，同时充当界面解扰的触发信号（`LaunchedEffect` 的 key 里有它）。
 */
data class ReaderPage(
    val index: Int,
    val imageFile: String,
    /** 解扰后的成品文件（旧管线缓存 / 已下载）；可直接显示。 */
    val displayFile: File? = null,
    /** 新管线就地解扰出的位图；可直接显示。 */
    val bitmap: Bitmap? = null,
    /** 新管线已落盘的原图（未解扰）；只表示"字节在本地"。 */
    val rawFile: File? = null,
    /** 高/宽。没有图时用 [DEFAULT_ASPECT] 占位，保证长图占位稳定。 */
    val aspectRatio: Float = DEFAULT_ASPECT,
    val state: PageState = PageState.Pending,
    val error: String? = null,
) {
    /** 能不能**立刻画出来**（有成品文件或位图，二者之一）。 */
    val available: Boolean get() = displayFile != null || bitmap != null

    /** 字节是否已在本地（有成品，或已经下好原图、只差解扰）。 */
    val hasBytes: Boolean get() = available || rawFile != null

    enum class PageState {
        /** 本地没有，等待联网取回（或已下好原图、等待解扰）。 */
        Pending,

        /** 正在下载 / 正在解扰。 */
        Loading,

        /** 已就绪。 */
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

    /**
     * 已经在本地有字节的页数（成品或原图）。
     *
     * 用 [ReaderPage.hasBytes] 而不是 [ReaderPage.available]：新管线的位图会被内存窗口回收，
     * 但**字节还在磁盘上**（再显示时不用联网）。按"能不能不再联网"来数才符合用户的理解，
     * 也避免顶栏的「已缓存 N 页」随着翻页来回跳。
     */
    val cachedCount: Int get() = pages.count { it.hasBytes }

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
 * ## 两套取图管线（设置页「阅读与显示 → 图片缓存方式」）
 *
 * - **原图缓存**（`ReaderCacheMode.RAW`，默认，2026-10-21 新增）：
 *   只把站点原始字节落盘（`reader-cache/<aid>/<cid>/`），**显示时**再解码 + `Canvas` 搬块。
 *   没有无损重编码、没有第二次解码、没有额外写盘——旧方案每页那 0.3～1.5 s 的本地成本
 *   基本被消掉（原因见 `docs/PROGRESS.md`「阅读器取图管线」）。
 * - **解扰后落盘**（`ReaderCacheMode.DESCRAMBLED`，旧行为，完整保留）：
 *   [ImageStore.file] 下载 → 解扰 → 无损 WEBP 重编码 → 与下载共用 `library` 目录。
 *
 * 两套可以随时切换，切完对**新取的页**立刻生效；已经在屏幕上的页不重建。
 * **下载任务永远产出解扰后的成品**，与本设置无关（导出要能用）。
 *
 * ## 预取（只有原图管线有）
 *
 * 一页只有几十 KB，慢的从来不是传输，而是"滚到才下、下完才算"这种**串行**。
 * 所以可见页一就绪，就顺手把后面 [PREFETCH_AHEAD] 页的**原始字节**取回来
 * （只取字节、不解码，占用的名额来自设置里的「同时下载图片数」）。
 * 名额用 `Semaphore` 实现：可见页 `acquire`（一定排得上），预取 `tryAcquire`
 * （抢不到就算了，绝不挡住用户正在看的那一页）。
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

    /**
     * 当前取图管线。设置一改就跟着变（只影响**新取的页**，不重建已经在屏幕上的页）。
     *
     * `@Volatile`：读写分别发生在 IO 协程（加载）与设置收集协程里。
     */
    @Volatile
    private var cacheMode: ReaderCacheMode = ReaderCacheMode.DEFAULT

    /**
     * 「同时下载图片数」= 在线取图的并发名额，**可见页与预取共用**（用户 2026-10-21 要求）。
     *
     * 存成 `@Volatile` + 可变引用：设置里的值一变就换一个新的信号量，
     * 正在排队的那几个任务会自然落到旧信号量上（最多瞬时多跑一两张，不影响正确性）。
     */
    @Volatile
    private var fetchSlots = Semaphore(SettingDefaults.Jm.DOWNLOAD_THREADS)

    /** 当前生效的并发名额（只在它变了的时候才换新的信号量，见 init 里的收集）。 */
    @Volatile
    private var fetchLimit = SettingDefaults.Jm.DOWNLOAD_THREADS

    /** 解扰解码的并发上限（固定 2）：一张长图光位图就几十 MB，开太多只会换页时抖动。 */
    private val decodeSlots = Semaphore(DECODE_CONCURRENCY)

    /** 已量过比例的图（新管线的位图按 imageFile 记，避免切章节来回重新解码）。 */
    private val bitmapAspects = ConcurrentHashMap<String, Float>()

    private var loadJob: Job? = null
    private var positionJob: Job? = null
    private var scanJob: Job? = null

    /** 窗口回收的去抖任务（滚动停稳后才真正回收远处位图，见 [scheduleBitmapTrim]）。 */
    private var trimJob: Job? = null

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
        // 取图管线：设置页改完立刻生效（对**新取的页**；已经在屏幕上的页不重建）
        viewModelScope.launch {
            settings.jmInt(SettingKeys.Jm.READER_CACHE_MODE, SettingDefaults.Jm.READER_CACHE_MODE)
                .collect { code -> cacheMode = ReaderCacheMode.fromCode(code) }
        }
        // 并发名额跟着「同时下载图片数」走（用户 2026-10-21 要求：直接用设置里那一项）
        viewModelScope.launch {
            settings.jmInt(SettingKeys.Jm.DOWNLOAD_THREADS, SettingDefaults.Jm.DOWNLOAD_THREADS)
                .collect { threads ->
                    val limit = SettingDefaults.Jm.normalizeDownloadThreads(threads)
                    // 只在**值真的变了**时换信号量：DataStore 每次写任何一项都会重发整张表，
                    // 按 availablePermits 判断会在有人占着名额时反复重建（等于把并发放开了）
                    if (limit != fetchLimit) {
                        fetchLimit = limit
                        fetchSlots = Semaphore(limit)
                    }
                }
        }
        viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        // 先确定这一章首次取图走哪条管线：设置收集协程第一项可能还没到，
        // 不能拿默认值去决定（用户可能早就切到了「解扰后落盘」）
        runCatching {
            ReaderCacheMode.fromCode(
                settings.jmInt(SettingKeys.Jm.READER_CACHE_MODE, SettingDefaults.Jm.READER_CACHE_MODE).first(),
            )
        }.onSuccess { cacheMode = it }
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
        // 换话时把上一话还在飞的取图/预取都掐掉：
        // 图片文件名在每一话里都叫 00001.webp…，不取消的话旧任务的"去重键"会挡住新话的同一页，
        // 而且它回来时会把旧话的文件写进新话的页里。
        fetching.values.forEach { it.cancel() }
        fetching.clear()
        // 文件名的比例缓存同理，跨话不通用
        bitmapAspects.clear()
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
                            error = "获取图片清单失败，请检查网络或线路后重试",
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
                    // 本地有就立刻可用；没有就标记成待取，由界面滚动到附近时按需联网取。
                    //
                    // "本地有"有两种：解扰后的成品（下载过 / 旧管线缓存）与原始字节（新管线缓存）。
                    // 成品直接显示；原图只让页面进入"字节已就绪、待解扰"，滚动到附近再解。
                    val finished = store.cachedFile(album, chapterFolder, entity.imageFile)
                    val raw = if (finished == null) {
                        store.cachedRawFile(albumId, chapter.chapterId, entity.imageFile)
                    } else {
                        null
                    }
                    ReaderPage(
                        index = pageIndex,
                        imageFile = entity.imageFile,
                        displayFile = finished,
                        rawFile = raw,
                        aspectRatio = finished?.let { store.aspectRatioOf(it, ReaderPage.DEFAULT_ASPECT) }
                            ?: raw?.let { store.aspectRatioOf(it, ReaderPage.DEFAULT_ASPECT) }
                            ?: ReaderPage.DEFAULT_ASPECT,
                        state = if (finished != null) {
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
        if (page.state == ReaderPage.PageState.Loading) return
        val chapter = current.currentChapter ?: return
        val album = current.album ?: return
        val folder = repo.chapterFolderName(chapter)

        // 这一页可能正在被**预取**取字节（预取不设 Loading、失败也不上报）。
        // 等它收尾再走一遍完整流程：成功就直接解扰，失败就当场按可见页重新取，
        // 否则用户会看到一个永远转圈的页（key 没变化，界面的 LaunchedEffect 不会再触发）。
        fetching[page.imageFile]?.let { inFlight ->
            viewModelScope.launch {
                inFlight.join()
                requestPage(pageIndex)
            }
            return
        }

        // 立刻标记"正在取"，避免同一页被连续几次布局回调重复排队
        updatePage(pageIndex) { it.copy(state = ReaderPage.PageState.Loading, error = null) }
        fetching[page.imageFile] = viewModelScope.launch {
            try {
                val loaded = loadPage(
                    album = album,
                    chapterId = chapter.chapterId,
                    chapterFolder = folder,
                    imageFile = page.imageFile,
                )
                // 等图的这段时间里用户可能已经换了话：别把上一话的图写进新话的页
                if (_state.value.currentChapter?.chapterId != chapter.chapterId) return@launch
                updatePage(pageIndex) {
                    it.copy(
                        displayFile = loaded.file,
                        bitmap = loaded.bitmap,
                        rawFile = null,
                        aspectRatio = loaded.aspect,
                        state = ReaderPage.PageState.Ready,
                        error = null,
                    )
                }
                // 这一页到手了，顺手把后面几页的**字节**取回来（只预取，不预解扰）
                prefetchAhead(pageIndex)
                // 新解出来的位图可能让内存超预算：把远处的位图收掉（当前页一定留）
                scheduleBitmapTrim()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("jm").w(e, "取图失败：%s", page.imageFile)
                if (_state.value.currentChapter?.chapterId == chapter.chapterId) {
                    updatePage(pageIndex) {
                        it.copy(state = ReaderPage.PageState.Failed, error = e.toUserMessage())
                    }
                }
            } finally {
                fetching.remove(page.imageFile)
            }
        }
    }

    /** 一次取图的结果：成品文件或位图（二者之一），外加算好的宽高比。 */
    private data class LoadedPage(val file: File?, val bitmap: Bitmap?, val aspect: Float)

    /**
     * 取一页并**准备好显示**。两条管线都在这里分派（见 [cacheMode]）。
     *
     * 优先级（两条管线相同）：**成品文件 > 现取**。所以"已经下载过"的章节在两种模式下
     * 都是零成本打开——成品就是解扰好的图，直接交给 Coil。
     *
     * 新管线只把字节落盘（`fetchRawFile`，占一个「同时下载图片数」名额），
     * 解扰在解扰名额内单独做，跑完直接把位图返回给界面。
     */
    private suspend fun loadPage(
        album: AlbumEntity,
        chapterId: Long,
        chapterFolder: String,
        imageFile: String,
    ): LoadedPage {
        // 1) 成品优先：下载过的、或旧管线缓存下来的
        store.cachedFile(album, chapterFolder, imageFile)?.let { finished ->
            return LoadedPage(
                file = finished,
                bitmap = null,
                aspect = store.aspectRatioOf(finished, ReaderPage.DEFAULT_ASPECT),
            )
        }

        // 2) 旧管线：下载 → 解扰 → 无损重编码 → 落盘（与 2026-10 之前完全一致）
        if (cacheMode == ReaderCacheMode.DESCRAMBLED) {
            val file = withFetchSlot { store.file(album, chapterId, chapterFolder, imageFile) }
            return LoadedPage(
                file = file,
                bitmap = null,
                aspect = store.aspectRatioOf(file, ReaderPage.DEFAULT_ASPECT),
            )
        }

        // 3) 新管线：先要原始字节，再在内存里解扰
        val raw = store.cachedRawFile(albumId, chapterId, imageFile)
            ?: withFetchSlot { store.fetchRawFile(albumId, chapterId, imageFile) }
        val bitmap = withDecodeSlot {
            store.displayBitmap(
                chapterId = chapterId,
                imageFile = imageFile,
                raw = raw,
                targetWidthPx = targetWidthPx(),
            )
        }
        val aspect = bitmapAspects.getOrPut(imageFile) {
            if (bitmap.width > 0) {
                bitmap.height.toFloat() / bitmap.width.toFloat()
            } else {
                ReaderPage.DEFAULT_ASPECT
            }
        }
        return LoadedPage(file = null, bitmap = bitmap, aspect = aspect)
    }

    /**
     * 占一个「同时下载图片数」名额执行 [block]（**会排队**）。
     *
     * 只有"用户正在看的那一页"用它——预取用 `tryAcquire`，绝不把名额排队排在
     * 用户当前的页前面。
     */
    private suspend fun <T> withFetchSlot(block: suspend () -> T): T {
        val slots = fetchSlots
        slots.acquire()
        try {
            return block()
        } finally {
            slots.release()
        }
    }

    /** 占一个解扰名额执行 [block]：位图内存才是瓶颈，所以这条线和下载名额分开限。 */
    private suspend fun <T> withDecodeSlot(block: suspend () -> T): T {
        decodeSlots.acquire()
        try {
            return block()
        } finally {
            decodeSlots.release()
        }
    }

    /**
     * 预取后面几页的**原始字节**（只有原图管线做）。
     *
     * 为什么要它：慢的从来不是传输（一页几十 KB），而是"滚到才下、下完才算"的串行。
     * 官方阅读器就是这么干的（提前 12 页 `new Image()` 灌进 HTTP 缓存），见文档 §8 与
     * `docs/PROGRESS.md`「阅读器取图管线」。
     *
     * 三个刻意的取舍：
     * - **只取字节、不解扰**：预取阶段解码长图会白占几十 MB 内存，等页面真的可见再解；
     * - **名额用 `tryAcquire`**：抢不到说明并发已满，绝不排队挡住用户正在看的那一页；
     * - **失败静默**：预取失败不打扰用户，滚到那一页时自然重试一次。
     */
    private fun prefetchAhead(from: Int) {
        if (cacheMode != ReaderCacheMode.RAW) return
        val current = _state.value
        val chapter = current.currentChapter ?: return
        val pages = current.pages
        for (offset in 1..PREFETCH_AHEAD) {
            val index = from + offset
            val page = pages.getOrNull(index) ?: break
            // 已经有字节 / 已在取 / 失败过的不再预取
            if (page.hasBytes || page.state == ReaderPage.PageState.Failed) continue
            if (fetching.containsKey(page.imageFile)) continue

            val slots = fetchSlots
            if (!slots.tryAcquire()) break
            val chapterId = chapter.chapterId
            fetching[page.imageFile] = viewModelScope.launch {
                try {
                    val raw = store.fetchRawFile(albumId, chapterId, page.imageFile)
                    // 顺手量出真实宽高比：这样用户滚到这一页时，占位框已经是正确高度，
                    // 图片出现时不会"跳一下"（这也是快翻时观感上的一部分闪烁）
                    val aspect = store.aspectRatioOf(raw, page.aspectRatio)
                    // 只写"字节已就绪"；界面收到后（`LaunchedEffect` 的 key 里有 rawFile）
                    // 会在这一页可见时解扰
                    if (_state.value.currentChapter?.chapterId == chapterId) {
                        updatePage(index) { existing ->
                            if (existing.hasBytes) {
                                existing
                            } else {
                                existing.copy(rawFile = raw, aspectRatio = aspect)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.tag("jm").d("预取失败（不影响阅读）：%s %s", page.imageFile, e.message)
                } finally {
                    slots.release()
                    fetching.remove(page.imageFile)
                }
            }
        }
    }

    /**
     * 重试失败页。
     *
     * 图床 / 网络出问题时**几乎不会只挂一张图**，一般是同时发出的那几张一起失败
     * （这也是用户看到的现象），所以点任意一页的「重试」就把**当前话所有失败的页**
     * 一起重取，不用一页页点（用户要求第 2 点）。
     *
     * 传进来的那一页即使不是失败态（本地文件被删了）也照样重取；
     * 已经在取（[ReaderPage.PageState.Loading]）的页不动它，免得把进行中的请求清回待取。
     */
    fun retryPage(pageIndex: Int) {
        val current = _state.value
        if (current.pages.getOrNull(pageIndex) == null) return

        // 目标 = 所有失败页 + 点的那一页（去重、保持页码顺序）
        val targets = current.pages.mapIndexedNotNull { index, page ->
            when {
                page.state == ReaderPage.PageState.Failed -> index
                index == pageIndex -> index
                else -> null
            }
        }

        targets.forEach { index ->
            // 逐页重读状态：`requestPage` 会把被点的页立刻标成 Loading
            val page = _state.value.pages.getOrNull(index) ?: return@forEach
            if (page.state == ReaderPage.PageState.Loading) return@forEach
            updatePage(index) {
                if (it.hasBytes) {
                    // 字节还在（成品或原图），只是没解扰 / 上一步失败：清掉显示态重来
                    it.copy(displayFile = null, bitmap = null, state = ReaderPage.PageState.Pending, error = null)
                } else {
                    it.copy(state = ReaderPage.PageState.Pending, error = null)
                }
            }
            requestPage(index)
        }
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
                    val finished = store.cachedFile(album, folder, page.imageFile)
                    when {
                        // 下载完成了：成品直接显示（比内存里的位图更"权威"，也是导出用的那份）
                        finished != null -> page.copy(
                            displayFile = finished,
                            bitmap = null,
                            aspectRatio = store.aspectRatioOf(finished, ReaderPage.DEFAULT_ASPECT),
                            state = ReaderPage.PageState.Ready,
                            error = null,
                        )
                        // 新管线：位图还在就继续用
                        page.bitmap != null -> page
                        else -> {
                            // 原图还在磁盘上（之前下过、位图被回收了）：回到待解扰，滚动到时重解
                            val raw = store.cachedRawFile(albumId, chapter.chapterId, page.imageFile)
                            if (raw != null) {
                                page.copy(displayFile = null, rawFile = raw, state = ReaderPage.PageState.Pending)
                            } else if (page.hasBytes) {
                                // 本地被删了且之前是就绪状态 -> 回到待取，滚动到时自动重新联网
                                page.copy(displayFile = null, bitmap = null, rawFile = null, state = ReaderPage.PageState.Pending)
                            } else {
                                page
                            }
                        }
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

    /**
     * 重新加载（空状态里的「重试」按钮）。
     *
     * 章节已知就重开当前话（「获取图片清单失败」的那次联网补齐会在这里重发）；
     * 连章节都还没拿到（本子数据缺失）才退回整段 [load]。
     */
    fun retryLoad() {
        loadJob?.cancel()
        val index = _state.value.chapterIndex
        if (index in _state.value.chapters.indices) {
            openChapter(index, restorePosition = false)
        } else {
            viewModelScope.launch { load() }
        }
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
        // 翻页顺手回收远处页的位图（新管线）：一张 800×1200 就是 3.7 MB，
        // 整话几十页全留着一定 OOM。只留当前页附近那几张，滚回来再解一次（本地文件，不联网）。
        //
        // **回收中心永远是 `currentPage`，不是"刚加载完的那一页"**：翻得快时先发出的页
        // 反而后到，用它当中心会把用户正看着的页的位图收掉——那就是"图片闪一下又回来"。
        scheduleBitmapTrim()
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

    // ------------------------------------------------------------------
    // 显示位图的内存窗口（只服务"原图缓存"管线）
    // ------------------------------------------------------------------

    /**
     * 位图回收的调度入口。分两级，**顺序不能反**：
     *
     * 1. [enforceBitmapBudget] **立刻**执行：这是防 OOM 的硬闸门，超预算就从最远处开始丢；
     * 2. [trimOutsideWindow] **等滚动停下来**再执行（去抖 [TRIM_SETTLE_MS]）。
     *
     * 为什么要去抖：翻得快时 `onPositionChanged` 一帧一个页码，如果每次都立刻按窗口回收，
     * 用户正在快速翻的那几页会被反复"丢掉 → 重新解 → 再丢掉"，屏幕上就是一闪一闪。
     * 停稳之后再收，既省内存又不闪；真到内存吃紧时第 1 步已经兜住了。
     */
    private fun scheduleBitmapTrim() {
        enforceBitmapBudget()
        trimJob?.cancel()
        trimJob = viewModelScope.launch {
            delay(TRIM_SETTLE_MS)
            trimOutsideWindow()
        }
    }

    /**
     * **硬预算**：位图总字节超过 [bitmapBudgetBytes] 时，从离当前页最远的开始丢（当前页永远留）。
     *
     * 只丢远处、不动窗口：这是"滚动中"唯一允许做的回收，因为它只会在真的快吃满内存时触发。
     */
    private fun enforceBitmapBudget() {
        val current = _state.value
        val center = current.currentPage
        val loaded = current.pages
            .mapIndexedNotNull { index, page -> page.bitmap?.let { index to page } }
        if (loaded.isEmpty()) return

        val budget = bitmapBudgetBytes()
        var total = loaded.sumOf { (_, page) -> page.bitmap!!.allocationByteCount.toLong() }
        if (total <= budget) return

        val updated = current.pages.toMutableList()
        var changed = false
        loaded.sortedByDescending { (index, _) -> abs(index - center) }.forEach { (index, page) ->
            if (total <= budget) return@forEach
            // 当前页**和它旁边那一页**永远留：一屏往往同时露出两页，
            // 预算再紧也不能把"正在屏幕上"的那一页抽掉（那就是可见的闪烁）
            if (abs(index - center) <= 1) return@forEach
            total -= page.bitmap!!.allocationByteCount.toLong()
            updated[index] = page.copy(bitmap = null, state = ReaderPage.PageState.Pending)
            changed = true
        }
        if (changed) _state.update { it.copy(pages = updated) }
    }

    /**
     * **窗口回收**：只保留当前页前后 [BITMAP_KEEP_RADIUS] 页的位图（滚动停稳后执行）。
     *
     * 为什么必须做：一张 681×946 的 ARGB_8888 就是 2.6 MB，800×12000 的长条是 38 MB。
     * 如果整话几十页的位图都挂在 `pages` 里，翻到十几页就会 OOM——
     * 而"位图"只是显示用的中间产物，**原图还在磁盘上**，滚回来重新解一次就行（不联网）。
     *
     * 中心一律取 `currentPage`（**不是"刚加载完的那一页"**）：翻得快时先发出的页反而后到，
     * 用它当中心会把用户正看着的页收掉，那就是用户报的"图片闪一下"。
     *
     * **不主动 `recycle()`**：Compose 可能还在引用上一帧的位图，回收会直接抛
     * "trying to use a recycled bitmap"；摘掉引用交给 GC 回收即可。
     */
    private fun trimOutsideWindow() {
        val current = _state.value
        val center = current.currentPage
        if (current.pages.none { it.bitmap != null }) return

        var changed = false
        val updated = current.pages.mapIndexed { index, page ->
            if (page.bitmap == null) return@mapIndexed page
            if (abs(index - center) <= BITMAP_KEEP_RADIUS) {
                page
            } else {
                changed = true
                page.copy(bitmap = null, state = ReaderPage.PageState.Pending)
            }
        }
        if (changed) _state.update { it.copy(pages = updated) }
    }

    /** 显示位图可用的内存预算：堆的 1/6，最多 96 MB，最少 24 MB。 */
    private fun bitmapBudgetBytes(): Long {
        val heap = Runtime.getRuntime().maxMemory()
        return (heap / BITMAP_BUDGET_DIVISOR).coerceIn(BITMAP_BUDGET_MIN_BYTES, BITMAP_BUDGET_MAX_BYTES)
    }

    /**
     * 解扰用的"显示宽度"（像素）。
     *
     * 读者是整屏铺满的，所以取系统屏幕宽度就够：`BitmapFactory.inSampleSize` 只用来判断
     * "要不要降到屏幕尺寸"（[com.haruhi.jmcomic.core.codec.ImageCodec.decodeSampleSize]）。
     * 拿不到时用 1080 兜底。
     */
    private fun targetWidthPx(): Int = runCatching {
        android.content.res.Resources.getSystem().displayMetrics.widthPixels
    }.getOrDefault(1080).coerceAtLeast(320)

    companion object {
        /** 位置落库的去抖时长。 */
        const val POSITION_DEBOUNCE_MS = 400L

        /**
         * 提前几页取**字节**（只有原图管线预取；对齐官方阅读器的预取思路）。
         *
         * 取 3：配合默认 4 个并发名额，可见页 + 3 页预取刚好铺满一轮；
         * 再多也只是把网络排满，用户滚不到那里就先占着名额。
         */
        const val PREFETCH_AHEAD = 3

        /** 同时解扰的页数上限（位图内存是这条路线的真正瓶颈，不是 CPU）。 */
        const val DECODE_CONCURRENCY = 2

        /** 当前页前后各留几页的位图（再远就回收，翻回来重新解）。 */
        const val BITMAP_KEEP_RADIUS = 3

        /**
         * 窗口回收的"滚动停稳"去抖时长。
         *
         * 快翻时一帧一个页码，立刻按窗口回收会让页反复"丢掉 → 重解"，屏幕上就是闪烁；
         * 停稳 400ms 再收，观感与内存都更稳（硬预算仍在每帧生效，见 [enforceBitmapBudget]）。
         */
        const val TRIM_SETTLE_MS = 400L

        /** 位图预算 = 堆的 1/6。 */
        const val BITMAP_BUDGET_DIVISOR = 6L

        const val BITMAP_BUDGET_MIN_BYTES = 24L * 1024 * 1024
        const val BITMAP_BUDGET_MAX_BYTES = 96L * 1024 * 1024
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
