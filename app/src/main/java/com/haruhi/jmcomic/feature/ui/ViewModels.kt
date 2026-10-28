package com.haruhi.jmcomic.feature.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteEntity
import com.haruhi.jmcomic.core.database.entity.SearchHistoryEntity
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.core.protocol.ApiLanguage
import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.core.protocol.SearchSort
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.settings.SecretStore
import com.haruhi.jmcomic.core.settings.SettingKeys
import com.haruhi.jmcomic.feature.data.CategoryAlbumPage
import com.haruhi.jmcomic.feature.data.CategoryCache
import com.haruhi.jmcomic.feature.data.CategoryGroup
import com.haruhi.jmcomic.feature.data.CategoryListSnapshot
import com.haruhi.jmcomic.feature.data.CategorySelection
import com.haruhi.jmcomic.feature.data.CategorySub
import com.haruhi.jmcomic.feature.data.OnlineViewMode
import com.haruhi.jmcomic.feature.data.categorySelectorLabel
import com.haruhi.jmcomic.feature.data.fixCategorySelection
import com.haruhi.jmcomic.feature.data.mergeCategoryItems
import com.haruhi.jmcomic.feature.data.ExportedFormat
import com.haruhi.jmcomic.feature.data.FavoriteWriteResult
import com.haruhi.jmcomic.feature.data.AccountFolder
import com.haruhi.jmcomic.feature.data.AccountRepository
import com.haruhi.jmcomic.feature.data.AlbumCleaner
import com.haruhi.jmcomic.feature.data.CommentCache
import com.haruhi.jmcomic.feature.data.CommentReplyTarget
import com.haruhi.jmcomic.feature.data.CommentRow
import com.haruhi.jmcomic.feature.data.commentDraftError
import com.haruhi.jmcomic.feature.data.limitCommentDraft
import com.haruhi.jmcomic.feature.data.DownloadManager
import com.haruhi.jmcomic.feature.data.ExportFormat
import com.haruhi.jmcomic.feature.data.ExportManager
import com.haruhi.jmcomic.feature.data.mergeComments
import com.haruhi.jmcomic.feature.data.removeComment
import com.haruhi.jmcomic.feature.data.ReadingRepository
import com.haruhi.jmcomic.feature.data.Repository
import com.haruhi.jmcomic.feature.data.OnlineSearchItem
import com.haruhi.jmcomic.feature.data.OnlineSearchResult
import com.haruhi.jmcomic.feature.data.ReadingPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDate

// ======================================================================
// 书库
// ======================================================================

/** 书库的筛选条件。`null` 表示"全部"。 */
data class LibraryFilter(
    val keyword: String = "",
    val downloadedOnly: Boolean? = null,
    val collectedOnly: Boolean? = null,
    val favoriteId: Long? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
    /**
     * 只显示**已经导出过 ZIP / PDF** 的本子（用户第九轮要求第 1 点）。
     *
     * 两个条件互相独立：都打开就是"两种都导出过"。
     * 成品在系统下载目录里、库表里没有记录，所以这一条在拿到磁盘快照之后才生效
     * （见 [matchesExportFilter]）。
     */
    val hasZipOnly: Boolean = false,
    val hasPdfOnly: Boolean = false,
) {
    val isActive: Boolean
        get() = hasUserFilter || favoriteId != null

    /** 生效条件数量（**含**"在哪个收藏夹"这类页面上下文），界面上的"清除 N 项"用 [userActiveCount]。 */
    val activeCount: Int get() = userActiveCount + if (favoriteId != null) 1 else 0

    /**
     * **用户自己加的**条件数量（不含"当前在哪个收藏夹"）。
     *
     * 收藏夹内页的 `favoriteId` 是页面上下文，不是用户能清的筛选项：
     * 拿它算进"清除 N 项"会一直显示"清除 1 项"（用户反馈）。
     */
    val userActiveCount: Int
        get() = listOf(
            keyword.isNotBlank(),
            downloadedOnly != null,
            collectedOnly != null,
            tags.isNotEmpty(),
            author != null,
            hasZipOnly,
            hasPdfOnly,
        ).count { it }

    val hasUserFilter: Boolean get() = userActiveCount > 0
}

/**
 * "有 ZIP / 有 PDF"这一条筛选的判定（用户第九轮要求第 1 点）。
 *
 * 抽成纯函数是因为它同时用在书库与收藏夹内两处，而且**没查到快照时不能误杀**：
 * [formats] 为 null 表示"这本还没有查过导出情况"，此时一律放行，
 * 否则第一次进页面（快照还没回来）会显示成空列表。
 */
internal fun matchesExportFilter(
    formats: Set<ExportFormat>?,
    hasZipOnly: Boolean,
    hasPdfOnly: Boolean,
): Boolean {
    if (!hasZipOnly && !hasPdfOnly) return true
    if (formats == null) return true
    if (hasZipOnly && ExportFormat.ZIP !in formats) return false
    if (hasPdfOnly && ExportFormat.PDF !in formats) return false
    return true
}

/**
 * 拼「第一页（数据库实时）+ 后续页（快照）」。
 *
 * **必须按 id 去重**：第一页是活的 Flow，用户翻到第 3 页时如果有新本子入库，
 * 后面的条目会整体后移一位，第 2 页的第一条就会和第 1 页的最后一条重复；
 * 而列表用 `key = { it.album.id }` 渲染，重复 key 会直接抛
 * `IllegalArgumentException: Key was already used`（白屏崩溃）。
 * 顺序保持"第一页在前、后续页依次在后"，重复的以先出现的为准。
 */
internal fun mergeLibraryPages(
    firstPage: List<AlbumEntity>,
    extraPages: List<AlbumEntity>,
): List<AlbumEntity> {
    if (extraPages.isEmpty()) return firstPage
    val seen = HashSet<Long>(firstPage.size + extraPages.size)
    return (firstPage + extraPages).filter { seen.add(it.id) }
}

data class LibraryUiState(
    val albums: List<AlbumCard> = emptyList(),
    val total: Int = 0,
    val filter: LibraryFilter = LibraryFilter(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** 还有没有下一页（连续加载用）。 */
    val hasMore: Boolean = false,
    /** 已经翻过页并且翻到底了（界面上显示"没有更多了"）。 */
    val reachedEnd: Boolean = false,
    /** 已经加载了多少本（含筛选前的），"没有更多了"的显示条件要用。 */
    val loadedCount: Int = 0,
    /**
     * 「继续阅读」卡片（计划 §9 第 4 条：**有阅读记录时**显示，没有就不显示）。
     */
    val continueReading: ContinueReadingCard? = null,
    /** 批量"更改收藏夹"要用的收藏夹列表。 */
    val favorites: List<FavoriteEntity> = emptyList(),
    /** 批量操作的一次性提示。 */
    val message: String? = null,
)

/** 书库顶部"继续阅读"卡片的数据。 */
data class ContinueReadingCard(
    val albumId: Long,
    val albumTitle: String,
    val chapterTitle: String,
    /** 位置是否已经落到某一页（只有章节号时说明刚进过这一话）。 */
    val hasPage: Boolean,
) {
    val subtitle: String
        get() = if (hasPage) "继续阅读 · $chapterTitle" else "开始阅读 · $chapterTitle"
}

/** 书库卡片。`downloadedChapters`/`totalChapters` 用于"状态优先展示下载章节数"。 */
data class AlbumCard(
    val album: AlbumEntity,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val localSizeBytes: Long,
) {
    val displayName: String get() = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"
}

class LibraryViewModel(
    private val repo: Repository,
    private val reading: ReadingRepository,
    private val cleaner: AlbumCleaner,
    private val exports: ExportManager,
) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter())
    private val paging = MutableStateFlow(PagingState())
    private val extra = MutableStateFlow(ExtraState())

    /**
     * 分页状态。
     *
     * **第一页**来自数据库的 Flow（`observeLibrary(offset = 0)`），所以新增/删除本子会立刻反映；
     * 第二页起是**快照**（[extra]），追加式加载——再查一次数据库会把已经看过的条目再取一遍，
     * 而且深翻时容易和变化中的第一页错位，所以只在用户往下翻时追加，并按 id 去重。
     */
    private data class PagingState(
        val extra: List<AlbumEntity> = emptyList(),
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** 已经查到最后一页（再问一次数据库也不会多出条目）。 */
        val endReached: Boolean = false,
    )

    private data class ExtraState(
        val summaries: Map<Long, ChapterStat> = emptyMap(),
        val loadingStat: Boolean = false,
        val continueReading: ContinueReadingCard? = null,
        val favorites: List<FavoriteEntity> = emptyList(),
        /**
         * 本子 id → 已经导出过哪几种格式（用户第九轮要求第 1 点）。
         *
         * 成品在系统下载目录里，数据库里没有记录，所以这是一份**磁盘快照**，
         * 跟着 [refreshStats] 一起刷新（进页面、切筛选、从任务页回来都会重算）。
         * 还没查过的本子不在 map 里，此时筛选一律放行（见 [matchesExportFilter]）。
         */
        val exportFormats: Map<Long, Set<ExportFormat>> = emptyMap(),
        /** 批量操作的一次性提示。 */
        val message: String? = null,
    )

    private data class ChapterStat(val total: Int, val downloaded: Int, val size: Long)

    val uiState: StateFlow<LibraryUiState> = combine(
        filter.flatMapLatest { f ->
            repo.observeLibrary(
                keyword = f.keyword.takeIf { it.isNotBlank() },
                collected = f.collectedOnly,
                favoriteId = f.favoriteId,
                tags = f.tags,
                author = f.author,
                limit = PAGE_SIZE,
                offset = 0,
            )
        },
        filter.flatMapLatest { f ->
            repo.observeLibraryCount(
                keyword = f.keyword.takeIf { it.isNotBlank() },
                collected = f.collectedOnly,
                favoriteId = f.favoriteId,
                tags = f.tags,
                author = f.author,
            )
        },
        filter,
        paging,
        extra,
    ) { albums, total, f, p, e ->
        val loaded = mergeLibraryPages(albums, p.extra)
        val cards = loaded.map { album ->
            val stat = e.summaries[album.id]
            AlbumCard(
                album = album,
                totalChapters = stat?.total ?: 0,
                downloadedChapters = stat?.downloaded ?: 0,
                localSizeBytes = stat?.size ?: 0L,
            )
        }.let { list ->
            // "已下载 / 未下载"筛选要在拿到章节统计后再过滤
            when (f.downloadedOnly) {
                true -> list.filter { it.downloadedChapters > 0 }
                false -> list.filter { it.downloadedChapters == 0 }
                null -> list
            }
        }.let { list ->
            // "有 ZIP / 有 PDF"同样要等磁盘快照（用户第九轮要求第 1 点）
            list.filter { card ->
                matchesExportFilter(e.exportFormats[card.album.id], f.hasZipOnly, f.hasPdfOnly)
            }
        }
        LibraryUiState(
            albums = cards,
            total = total,
            filter = f,
            loading = e.loadingStat && e.summaries.isEmpty(),
            loadingMore = p.loadingMore,
            error = p.error,
            // 第一页满就说明"可能还有"；翻到底之后一律 false
            hasMore = !p.endReached && albums.size >= PAGE_SIZE,
            reachedEnd = p.endReached,
            loadedCount = loaded.size,
            continueReading = e.continueReading,
            favorites = e.favorites,
            message = e.message,
        )
    }.catch { e ->
        Timber.tag("jm").w(e, "书库查询失败")
        emit(LibraryUiState(error = e.toUserMessage(), loading = false))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    init {
        refreshStats()
        // 「继续阅读」卡片跟着**独立指针**走（不是逐本记录里"最新那条"）：
        // 读完一本回到书库，卡片立刻变成那一本；移出书库删逐本记录也不会让卡片乱跳。
        viewModelScope.launch {
            reading.observeContinueReading()
                .catch { e -> Timber.tag("jm").w(e, "观察继续阅读指针失败") }
                .collect { position ->
                    extra.update { it.copy(continueReading = resolveContinueReading(position)) }
                }
        }
        // 批量"更改收藏夹"要用收藏夹列表，跟着数据库走
        viewModelScope.launch {
            repo.observeFavorites()
                .catch { e -> Timber.tag("jm").w(e, "观察收藏夹失败") }
                .collect { favorites -> extra.update { it.copy(favorites = favorites) } }
        }
    }

    /**
     * 把「继续阅读」指针翻译成卡片内容。
     *
     * 正常情况下指针只指向**仍在书库**的本子（移出书库会连带清掉指针，用户方案 B）；
     * 这里仍保留一层兜底：万一指针指向的本子不在书库，就退回基础数据缓存取标题
     * （它永不删除）；两处都没有就干脆不显示卡片。
     */
    private suspend fun resolveContinueReading(position: ReadingPosition?): ContinueReadingCard? {
        val chapterId = position?.chapterId ?: return null
        val album = runCatching { repo.findAlbum(position.albumId) }.getOrNull()
            ?: runCatching { repo.localAlbumDetail(position.albumId)?.album }.getOrNull()
            ?: return null
        val chapterTitle = runCatching { reading.chapterFolderName(position.albumId, chapterId) }
            .getOrNull()
            ?: chapterId.toString()
        return ContinueReadingCard(
            albumId = position.albumId,
            albumTitle = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}",
            chapterTitle = chapterTitle,
            hasPage = !position.imageFile.isNullOrBlank(),
        )
    }

    fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) {
        filter.update(transform)
        // 换了筛选条件就是换了一份列表：之前翻过的页全部作废，从第一页重新来
        paging.update { PagingState() }
        // 筛选变化后章节统计要跟着重算（下载筛选用得到）
        refreshStats()
    }

    fun clearFilter() = updateFilter { LibraryFilter() }

    /**
     * 卡片右上角 ✕：清掉「继续阅读」指针，卡片随即消失。
     *
     * 只清这条独立指针，**不动**逐本阅读记录——之后读同一本仍能从原位置续上。
     */
    fun dismissContinueReading() {
        viewModelScope.launch {
            runCatching { reading.clearContinueReading() }
                .onFailure { Timber.tag("jm").w(it, "清除继续阅读指针失败") }
        }
    }

    /**
     * 往下翻一页（用户第十轮要求第 4 点）。
     *
     * 第一页之外的条目是**追加**进来的：数据库那边仍然只用第一页的 Flow 保证"新增/删除立刻可见"，
     * 深翻时再整段重查既浪费又容易与变化中的第一页错位。
     *
     * 两种触发方式：
     * - 列表滚到底（底部那颗「加载更多」/ 自动触发）；
     * - **筛选之后显示不足一屏**时自动连翻（见 `LibraryScreen` 的自动填充）——
     *   "有 ZIP"这类条件在数据库里查不了，只能在客户端过滤，不自动连翻的话
     *   第一页没有匹配项就会显示成空列表，看起来像"一本都没导出过"。
     */
    fun loadMore() {
        val current = paging.value
        if (current.loadingMore || current.endReached) return
        // **同步**置位"正在加载"：连点两下「加载更多」时，两次调用会先后进入这里，
        // 放到协程里再置位就晚了，同一页会被追加两遍。
        paging.update { it.copy(loadingMore = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val f = filter.value
                val offset = PAGE_SIZE + current.extra.size
                val next = repo.observeLibrary(
                    keyword = f.keyword.takeIf { it.isNotBlank() },
                    collected = f.collectedOnly,
                    favoriteId = f.favoriteId,
                    tags = f.tags,
                    author = f.author,
                    limit = PAGE_SIZE,
                    offset = offset,
                ).first()
                val stats = next.associate { album -> album.id to buildStat(album) }
                val formats = exports.presentFormats(next)
                extra.update {
                    it.copy(
                        summaries = it.summaries + stats,
                        exportFormats = it.exportFormats + formats,
                    )
                }
                paging.update {
                    it.copy(
                        extra = it.extra + next,
                        loadingMore = false,
                        // 取回来的不足一页 -> 后面没有了
                        endReached = next.size < PAGE_SIZE,
                    )
                }
            }.onFailure { e ->
                Timber.tag("jm").w(e, "加载下一页失败")
                paging.update { it.copy(loadingMore = false, error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 拉取**已经加载的全部**本子的章节数 / 已下载章节数 / 占用，顺带取一份导出成品快照。
     *
     * "有 ZIP / 有 PDF"筛选靠的就是这份快照，所以两者一起刷新——
     * 分开刷新会出现"筛选了但快照还是旧的"。
     */
    fun refreshStats() {
        viewModelScope.launch {
            extra.update { it.copy(loadingStat = true) }
            runCatching {
                val f = filter.value
                val first = repo.observeLibrary(
                    keyword = f.keyword.takeIf { it.isNotBlank() },
                    collected = f.collectedOnly,
                    favoriteId = f.favoriteId,
                    tags = f.tags,
                    author = f.author,
                    limit = PAGE_SIZE,
                    offset = 0,
                ).first()
                // 翻过页的话，把已加载的后续页一起重算（否则它们的状态会停在加载那一刻）
                val albums = mergeLibraryPages(first, paging.value.extra)
                val stats = albums.associate { album ->
                    album.id to buildStat(album)
                }
                val exportFormats = exports.presentFormats(albums)
                // 用 update 而不是整体替换：continueReading 由另一条流维护，不能被统计覆盖掉
                extra.update {
                    it.copy(summaries = stats, exportFormats = exportFormats, loadingStat = false)
                }
            }.onFailure { e ->
                Timber.tag("jm").w(e, "统计章节与占用失败")
                extra.update { it.copy(loadingStat = false) }
            }
        }
    }

    /**
     * 回到书库时刷新统计。
     *
     * 下载完成后章节数会变，"已下载 x/y 话"必须跟着变（否则用户会以为没下成功）。
     */
    fun onScreenResumed() = refreshStats()

    private suspend fun buildStat(album: AlbumEntity): ChapterStat = withContext(Dispatchers.IO) {
        val chapters = repo.chaptersOf(album)
        var downloaded = 0
        var totalImages = 0
        chapters.forEach { chapter ->
            val images = repo.imagesOf(album.id, chapter.chapterId)
            totalImages += images.size
            if (images.isNotEmpty()) {
                val folder = repo.chapterFolderName(chapter)
                val have = repo.downloadedImageCount(
                    album.albumFolderName,
                    folder,
                    images.map { it.imageFile },
                )
                if (have >= images.size) downloaded++
            }
        }
        // 没有任何章节记录时，用 album.images 的数量近似"总章节数"是不可靠的；
        // 这里只在有章节数据时给出统计（0 表示"未刷新过详情"）。
        ChapterStat(
            total = chapters.size,
            downloaded = downloaded,
            size = repo.localSize(album.albumFolderName),
        )
    }

    // ------------------------------------------------------------------
    // 多选批量操作（用户要求第 1、2 点）
    // ------------------------------------------------------------------

    /** 批量删除本地文件：图片 + 导出成品 + 导出半成品 + 任务行，**不动记录与封面**。 */
    fun deleteFiles(ids: List<Long>) {
        viewModelScope.launch {
            val albums = albumsByIds(ids)
            runCatching { cleaner.deleteFiles(albums) }
                .onSuccess { result ->
                    postMessage(
                        buildString {
                            append("已删除 ${albums.size} 本的本地文件")
                            if (result.removedExports > 0) append("（含 ${result.removedExports} 个导出文件）")
                        },
                    )
                }
                .onFailure { postMessage(it.toUserMessage()) }
            refreshStats()
        }
    }

    /** 批量从书库移除：记录 + 图片 + 成品 + 封面全清。 */
    fun removeFromLibrary(ids: List<Long>) {
        viewModelScope.launch {
            val albums = albumsByIds(ids)
            runCatching { cleaner.removeFromLibrary(albums) }
                .onSuccess { result ->
                    postMessage(
                        buildString {
                            append("已移除 ${albums.size} 本")
                            if (result.freedBytes > 0) append("，释放 ${formatBytes(result.freedBytes)}")
                        },
                    )
                }
                .onFailure { postMessage(it.toUserMessage()) }
            refreshStats()
        }
    }

    /** 批量"修改收藏夹"：勾选集合就是这些本子**最终所在的收藏夹**（用户要求第 3 点）。
     *
     * - 支持多选：勾了几个就同时属于这几个（"移动到多个收藏夹"）；
     * - 一个都没勾 = 移出所有收藏夹；
     * - 多出来的先移出、缺的再加入，语义是"修改"而不是"只增不减"。
     */
    fun modifyFavorites(ids: List<Long>, favoriteIds: Set<Long>) {
        viewModelScope.launch {
            runCatching {
                ids.forEach { id ->
                    val current = repo.favoriteIdsOf(id).toSet()
                    (current - favoriteIds).forEach { old -> repo.removeFromFavorite(id, old) }
                    (favoriteIds - current).forEach { target -> repo.addToFavorite(id, target) }
                    if (favoriteIds.isEmpty()) repo.uncollect(id)
                }
            }.onSuccess {
                val names = uiState.value.favorites.filter { it.id in favoriteIds }.map { it.name }
                postMessage(
                    when {
                        // 空集合就是"移出所有收藏夹"（用户要求第 3 点）
                        favoriteIds.isEmpty() -> "已把 ${ids.size} 本移出所有收藏夹"
                        // 刚新建的夹可能还没随 Flow 刷新出来，名字兜底成不带夹名
                        names.isEmpty() -> "已把 ${ids.size} 本移动到收藏夹"
                        else -> "已把 ${ids.size} 本移动到「${names.joinToString("、")}」"
                    },
                )
            }.onFailure { postMessage(it.toUserMessage()) }
            refreshStats()
        }
    }

    /**
     * 批量面板里"新建收藏夹"：成功返回新夹 id（面板自动勾上），失败提示原因并返回 null。
     *
     * 这里只负责建，真正移动仍然等用户点面板的「确认」——用户要求第 3 点。
     */
    suspend fun createFavoriteForSelection(name: String): Long? {
        val result = runCatching { repo.createFavorite(name) }
            .getOrDefault(FavoriteWriteResult.NameBlank)
        if (result != FavoriteWriteResult.Ok) {
            postMessage(
                when (result) {
                    FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了"
                    FavoriteWriteResult.NameBlank -> "请输入收藏夹名称"
                    FavoriteWriteResult.DefaultFolder -> "默认收藏夹已存在"
                    else -> "创建收藏夹失败"
                },
            )
            return null
        }
        return repo.listFavorites().firstOrNull { it.name == name.trim() }?.id
    }

    fun consumeMessage() = extra.update { it.copy(message = null) }

    private fun postMessage(text: String) = extra.update { it.copy(message = text) }

    private suspend fun albumsByIds(ids: List<Long>): List<AlbumEntity> =
        ids.mapNotNull { runCatching { repo.findAlbum(it) }.getOrNull() }

    fun setCollected(albumId: Long, collected: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (collected) repo.addToFavorite(albumId) else repo.uncollect(albumId)
            }.onFailure { Timber.tag("jm").w(it, "修改收藏状态失败") }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
    }
}

// ======================================================================
// 在线搜索（发现）
// ======================================================================

data class OnlineSearchUiState(
    val keyword: String = "",
    val sort: SearchSort = SearchSort.DEFAULT,
    val result: OnlineSearchResult? = null,
    val history: List<SearchHistoryEntity> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    /**
     * 正在入库、且**确实要联网拉详情**的本子号。
     *
     * 只有这一种情况才让卡片上的「加入书库」变成转圈 + 不可点：
     * 本地（书库 / 基础数据缓存）已有完整详情时，入库只是一次数据库写入，
     * 给它加 loading 只会"闪一下"，所以那条路径根本不置这个标记。
     */
    val addingIds: Set<Long> = emptySet(),
    /**
     * 正在"移出书库"的本子号（卡片上的「已入库」按钮转圈 + 不可点）。
     *
     * 与 [addingIds] 对称，但**含义不同**：入库只有走网络那一条才慢（本地命中一帧就完），
     * 而移出要删本地图片与导出成品，任何一本都可能花上一会儿，所以这里一律置标记。
     */
    val removingIds: Set<Long> = emptySet(),
    /**
     * 一次性提示，由根部弹 Snackbar。**成功与失败都走这里**。
     *
     * 失败不再挂列表上方的横幅：那条横幅的「重试」按钮绑的是 `search()`，
     * 也就是重发整个在线搜索，与用户刚点的「加入书库」不是同一件事；
     * 现在只提示一句失败原因，重试由用户自己决定（用户本轮要求）。
     */
    val message: String? = null,
    /** 历史未开启时提示"搜索历史已关闭"。 */
    val historyEnabled: Boolean = true,
    /**
     * **本次结果在 UI 上已展示的条数**。
     *
     * 用户反馈第 5 点：接口一页返回 80 条，一次性全部渲染会同时发起 80 个封面请求。
     * 所以首屏只渲染 [INITIAL_VISIBLE_COUNT] 条，滚动到底部时追加一屏；
     * 本页 80 条全部展示完之后，才显示"加载下一页"按钮。
     */
    val visibleCount: Int = INITIAL_VISIBLE_COUNT,
    /**
     * 搜索结果的展示方式：**默认卡片**（用户 2026-10-20 要求，与分类页同一套切换）。
     *
     * 只活在这一页的 ViewModel 里（不写存储）：同一次搜索里切到详情页再回来还是原来的模式；
     * 重新搜索会开一个新的结果页，又回到默认的卡片。
     */
    val viewMode: OnlineViewMode = OnlineViewMode.DEFAULT,
) {
    /** 本次结果里还剩多少条没展示。 */
    val remainingCount: Int
        get() = ((result?.items?.size ?: 0) - visibleCount).coerceAtLeast(0)

    /** 本页是否已经全部展示完（决定"加载下一页"按钮是否出现）。 */
    val allVisible: Boolean get() = remainingCount == 0

    /**
     * 滚动到某个位置时是否应该继续追加。
     *
     * 抽成纯函数是为了能单测——Compose 里的滚动触发在这台设备上没法自动验证，
     * 但"判定条件对不对"是可以测的（[shouldRevealMore] 的边界）。
     *
     * @param lastVisibleIndex `listState.layoutInfo` 里最后一个可见项的 index；
     *        列表还没有布局时传 -1
     */
    fun shouldRevealMore(lastVisibleIndex: Int): Boolean =
        remainingCount > 0 && lastVisibleIndex >= visibleItemsCount - REVEAL_AHEAD

    /** 当前批次的条数。 */
    val visibleItemsCount: Int get() = minOf(visibleCount, result?.items?.size ?: 0)

    companion object {
        /** 首屏渲染条数 / 每次追加条数。 */
        const val INITIAL_VISIBLE_COUNT = 10

        /** 距离底部还有几条时就开始追加，滚动更顺。 */
        const val REVEAL_AHEAD = 2
    }
}

/**
 * 搜索结果页的入参（结果页是独立页面，参数随路由带过来）。
 *
 * @param historyId > 0 表示从搜索历史点进来：**只回显快照，不发搜索请求**（用户要求）。
 */
data class OnlineSearchEntry(
    val keyword: String,
    val sort: SearchSort,
    val historyId: Long = 0L,
)

/**
 * 在线搜索。
 *
 * 一个实例只服务一种页面（用户要求把"搜索记录"和"搜索结果"拆成独立两页）：
 * - 发现页（`entry == null`）：输入框、排序、搜索历史，**本页不发搜索请求**；
 * - 结果页（`entry != null`）：拿路由参数进来，进页面就搜（或回显历史快照）。
 */
class OnlineSearchViewModel(
    private val repo: Repository,
    private val cleaner: AlbumCleaner,
    private val entry: OnlineSearchEntry? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnlineSearchUiState(
            keyword = entry?.keyword.orEmpty(),
            sort = entry?.sort ?: SearchSort.DEFAULT,
        ),
    )
    val state: StateFlow<OnlineSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        val page = entry
        when {
            // 发现页：只盯着搜索历史（结果页不展示历史，不需要订阅）
            page == null -> viewModelScope.launch {
                repo.observeSearchHistory().collect { history ->
                    _state.update { it.copy(history = history) }
                }
            }
            // 点历史记录进来：回显快照，不发搜索请求（用户要求）
            page.historyId > 0L -> loadHistorySnapshot(page.historyId)
            // 点「搜索」按钮 / 点作者进来：进页面就触发搜索（用户要求）
            else -> search()
        }
    }

    fun onKeywordChange(value: String) = _state.update { it.copy(keyword = value) }

    /**
     * 发现页换排序：只记住，等用户点「搜索」再一起带进结果页。
     *
     * 结果页换排序走 [changeSort]（要立刻重搜），两者语义不同。
     */
    fun onSortChange(sort: SearchSort) {
        _state.update { it.copy(sort = sort) }
    }

    /**
     * 从第一页开始搜索。
     *
     * 失败只弹一条提示（[OnlineSearchUiState.message]），不挂横幅：
     * 搜索这种事用户自己会再点一次「搜索」，不需要页面替他留一个重试按钮。
     */
    fun search() = runSearch(clearResult = true)

    /**
     * 下拉释放刷新：重新搜第一页。
     *
     * **已经在请求中时直接返回，不重复发请求**（用户要求）；屏幕上的下拉圈由
     * `searching` 驱动，请求结束自然会收回去。
     */
    fun refresh() {
        if (_state.value.searching || _state.value.loadingMore) return
        runSearch(clearResult = false)
    }

    /**
     * 结果页换排序：立刻按新排序重搜。
     *
     * 只改芯片不重搜的话，选中的芯片会和列表内容对不上。
     */
    fun changeSort(sort: SearchSort) {
        _state.update { it.copy(sort = sort) }
        runSearch(clearResult = false)
    }

    /**
     * @param clearResult 首次搜索清掉旧结果（页面显示居中的加载圈）；
     *        刷新/换排序保留旧结果（列表不闪，只有顶部下拉圈在转）。
     */
    private fun runSearch(clearResult: Boolean) {
        val keyword = _state.value.keyword.trim()
        if (keyword.isEmpty()) {
            _state.update { it.copy(message = "请输入搜索关键字") }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    result = if (clearResult) null else it.result,
                    message = null,
                    visibleCount = OnlineSearchUiState.INITIAL_VISIBLE_COUNT,
                )
            }
            // 纯数字关键词：同时查搜索与详情，把详情包装成第一条（用户要求第 3 点）
            runCatching { repo.searchOnlineByExactId(keyword, _state.value.sort, page = 1) }
                .onSuccess { result -> _state.update { it.copy(result = result, searching = false) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(searching = false, message = e.toUserMessage()) }
                }
        }
    }

    /**
     * 追加展示一屏（**不发网络请求**）。
     *
     * 与 [loadMore] 的区别：这个是"把本页已拿到的结果多渲染一些"，
     * 用户反馈第 5 点要求的"下滑继续追加显示"。
     */
    fun showMoreItems() {
        _state.update {
            if (it.remainingCount <= 0) {
                it
            } else {
                it.copy(visibleCount = it.visibleCount + OnlineSearchUiState.INITIAL_VISIBLE_COUNT)
            }
        }
    }

    /**
     * 切换"卡片 / 列表"（用户 2026-10-20 要求：搜索结果与分类页一样支持两种展示，默认卡片）。
     *
     * 纯界面状态：**不重新搜索、不改变已展示条数**，只是换个渲染方式。
     */
    fun toggleViewMode() {
        _state.update { it.copy(viewMode = it.viewMode.toggled()) }
    }

    /**
     * 加载下一页（**发网络请求**）。
     *
     * UI 上只在本页全部展示完之后才出现按钮（用户反馈第 5 点）。
     */
    fun loadMore() {
        val current = _state.value.result ?: return
        if (_state.value.loadingMore || current.page >= current.totalPages) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatching { repo.searchOnline(current.query, current.sort, page = current.page + 1) }
                .onSuccess { next ->
                    _state.update { s ->
                        val merged = s.result?.copy(
                            page = next.page,
                            items = s.result.items + next.items.filterNot { new ->
                                s.result.items.any { it.id == new.id }
                            },
                        ) ?: next
                        s.copy(result = merged, loadingMore = false)
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(loadingMore = false, message = e.toUserMessage()) }
                }
        }
    }

    /** 点历史记录：直接回显快照，不再请求 JM。 */
    private fun loadHistorySnapshot(historyId: Long) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    // 换了一批结果，展示条数从头开始
                    visibleCount = OnlineSearchUiState.INITIAL_VISIBLE_COUNT,
                )
            }
            val result = repo.searchHistorySnapshot(historyId)
            _state.update {
                it.copy(
                    searching = false,
                    result = result,
                    // 快照丢了同样只提示一句；`else it.message` 是不覆盖尚未弹出的那条提示
                    message = if (result == null) {
                        "这条搜索记录的结果已丢失，请重新搜索"
                    } else {
                        it.message
                    },
                )
            }
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch { runCatching { repo.deleteSearchHistory(id) } }
    }

    fun clearHistory() {
        viewModelScope.launch { runCatching { repo.clearSearchHistory() } }
    }

    /**
     * 加入书库（不下载）。已入库时是幂等操作，UI 只提示不报错。
     *
     * 走 `addToLibraryLocalFirst`：精确匹配那一条的详情**搜索时就已经拿到了**
     * （缓存在基础数据表里），这里不必再请求一次 `/album`——用户反馈的"点一下特别慢"
     * 就是那次多余的网络往返。只有本地完全没有数据时才回落到网络。
     *
     * **只有真的要联网时按钮才转圈**（用户本轮要求）：本地命中是一条数据库写入，
     * 一帧就结束，给它加 loading 只会闪一下。所以先用
     * [Repository.libraryAddNeedsNetwork] 问一句——它和 `addToLibraryLocalFirst`
     * 里"本地有没有"用的是同一个依据，两者不会打架。
     */
    fun addToLibrary(item: OnlineSearchItem) {
        // 转圈期间挡掉重复点按：每个点按都会新起一个协程，连点就是并发重复请求
        if (item.id in _state.value.addingIds) return
        viewModelScope.launch {
            val needsNetwork = runCatching { repo.libraryAddNeedsNetwork(item.id) }
                .getOrDefault(true)
            if (needsNetwork) {
                _state.update { it.copy(addingIds = it.addingIds + item.id) }
            }
            try {
                repo.addToLibraryLocalFirst(item.id)
                markExistsLocal(item.id, exists = true)
                _state.update { it.copy(message = "已加入书库") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 失败只提示一句，页面不留横幅（重试由用户再点一次按钮）
                _state.update { it.copy(message = e.toUserMessage()) }
            } finally {
                _state.update { it.copy(addingIds = it.addingIds - item.id) }
            }
        }
    }

    /**
     * 搜索结果里点「已入库」= **移出书库**（用户本轮要求）。
     *
     * 与详情页/书库页的「从书库移除」走同一条编排 [AlbumCleaner.removeFromLibrary]：
     * 记录 + 章节清单 + 收藏关联 + 阅读位置 + 本地图片 + 任务行 + 导出半成品全清，
     * **导出成品保留**（用户本轮拍板）；
     * **最近浏览记录与封面保留**（用户第八轮要求第 5 点），所以移出之后这张卡
     * 还能点进详情页看、也能再点一次「加入书库」回来。
     *
     * 破坏性操作由 UI 先弹一次确认框（`SearchResultsScreen` 的 `ConfirmDialog`），
     * 这里只管执行。执行期间置 [OnlineSearchUiState.removingIds]：按钮转圈并挡掉重复点按。
     *
     * 库里查不到这一本时**不当失败**：界面上的 `existsLocal` 可能已经过期
     * （例如刚从书库页把它移除了），此时只纠正卡片状态并提示一句。
     */
    fun removeFromLibrary(item: OnlineSearchItem) {
        if (item.id in _state.value.removingIds) return
        viewModelScope.launch {
            _state.update { it.copy(removingIds = it.removingIds + item.id) }
            try {
                val album = repo.findAlbum(item.id)
                if (album == null) {
                    markExistsLocal(item.id, exists = false)
                    _state.update { it.copy(message = "这本已经不在书库里了") }
                } else {
                    cleaner.removeFromLibrary(listOf(album))
                    markExistsLocal(item.id, exists = false)
                    _state.update {
                        it.copy(message = "已从书库移除，本地文件已删除（最近浏览记录保留）")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(message = e.toUserMessage()) }
            } finally {
                _state.update { it.copy(removingIds = it.removingIds - item.id) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * 把某条结果的"在不在书库"标记刷成 [exists]。
     *
     * 卡片上的按钮就是按这条标记切换的（false = 「加入书库」/ true = 「✓ 已入库」），
     * 所以入库成功后要刷成 true、移出成功后要刷回 false，不能只改数据库。
     */
    private fun markExistsLocal(albumId: Long, exists: Boolean) {
        _state.update { s ->
            val result = s.result ?: return@update s
            s.copy(
                result = result.copy(
                    items = result.items.map {
                        if (it.id == albumId) it.copy(existsLocal = exists) else it
                    },
                ),
            )
        }
    }
}

// ======================================================================
// 分类（在线分类浏览）
// ======================================================================

/**
 * 分类页的界面状态。
 *
 * 数据结构上分三块：
 * - **分类树**：[catalog] + 当前选中的 [mainSlug] / [subSlug]；
 * - **列表**：[albums] 与分页信息（[total] / [page] / [totalPages]）；
 * - **加载态**：[catalogLoading]（只发生在"本次进程第一次进分类页"）、
 *   [loading]（首屏 / 换分类）、[refreshing]（下拉刷新）、[loadingMore]（点加载更多）。
 */
data class CategoryUiState(
    /** 分类树；`null` = 本次进程还没拉到（界面据此显示加载/错误态）。 */
    val catalog: List<CategoryGroup>? = null,
    val mainSlug: String = CategorySelection.DEFAULT.mainSlug,
    val subSlug: String = CategorySelection.DEFAULT.subSlug,
    val sort: CategorySort = CategorySort.DEFAULT,
    val albums: List<OnlineSearchItem> = emptyList(),
    val total: Long = 0L,
    val page: Int = 0,
    val totalPages: Int = 1,
    val catalogLoading: Boolean = false,
    /** 分类树加载失败（整块错误态 + 重试：抽屉与选择器都靠它）。 */
    val catalogError: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** 列表首屏 / 换分类失败（列表为空时展示"重试"）。 */
    val error: String? = null,
    /** 一次性提示（下拉刷新失败、加载更多失败），由根部 Snackbar 弹。 */
    val message: String? = null,
    /** 展示方式：一行一本 / 三列卡片（用户要求，可切换；**默认卡片**）。 */
    val viewMode: OnlineViewMode = OnlineViewMode.DEFAULT,
) {
    val groups: List<CategoryGroup> get() = catalog.orEmpty()

    val main: CategoryGroup? get() = catalog?.firstOrNull { it.slug == mainSlug }

    val sub: CategorySub? get() = main?.subOf(subSlug)

    val selection: CategorySelection get() = CategorySelection(mainSlug, subSlug, sort)

    /** 当前主分类可用的排序项（官方：只有「最新A漫」给週排行）。 */
    val sorts: List<CategorySort> get() = CategorySort.optionsFor(mainSlug)

    /** 抽屉按钮文案：`同人 · 漢化`。 */
    val selectorLabel: String get() = categorySelectorLabel(main, sub)

    /** 还有没有下一页（底部「加载更多」按钮是否出现）。 */
    val hasMore: Boolean get() = page in 1 until totalPages
}

/**
 * 分类页。
 *
 * 用户要求（2026-10-19）：
 * 1. 分类树**只在打开 App 后第一次进分类页时请求一次**，之后走缓存，退出 App 清空；
 * 2. 漫画列表同样缓存，**切到别的页面再切回来还得是上次加载的那批**；
 * 3. 下拉刷新**只刷新当前分类的漫画**，不重新拉分类树；
 * 4. 滑到底部**不自动翻页**，由用户点「加载更多」手动翻（与搜索页的自动追加相反）；
 * 5. 切换分类 / 排序时，**缓存里有就直接用**（不再发请求），没有才拉第一页。
 *
 * 缓存挂在 `AppContainer.jmCategoryCache` 上（进程级），ViewModel 只是它的读写者。
 */
class CategoryViewModel(
    private val repo: Repository,
    private val cache: CategoryCache,
    /**
     * 当前接口语言（`AppContainer.apiLanguage` 的热流）。
     *
     * 分类树与分类列表里的中文（分类名、标签）都是服务端按请求时的 `lang` 渲染的：
     * 用户在设置页切了语言，**这一页还在导航栈里不一定会重建**，所以这里本来订阅它、
     * 语言一变就清缓存按新语言重拉。
     *
     * **目前这段行为被关掉了**（用户 2026-10-20 要求：切换语言不清理任何缓存），
     * 订阅那几行留在 [init] 里注释着；这个参数与 [reloadForLanguage] 一起留着，
     * 放开注释即可恢复，`@Suppress("unused")` 只是告诉编译器"它是有意留着的"。
     */
    @Suppress("unused")
    private val language: StateFlow<ApiLanguage> = MutableStateFlow(ApiLanguage.DEFAULT),
) : ViewModel() {

    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            val cached = cache.catalog
            if (cached != null) {
                // 本次进程已经拉过分类树（或上一次进分类页时拉过）：**不再请求**
                _state.update { it.copy(catalog = cached, catalogLoading = false) }
                ensureListLoaded()
            } else {
                loadCatalog()
            }
        }
        // 2026-10-20 用户要求：**切换接口语言不清理任何缓存**（分类树 / 分类列表 / 评论都保留），
        // 所以"语言一变就清缓存重拉整页"这段先关掉。以后需要时放开这四行即可
        // （[language] 与 [reloadForLanguage] 都还在，行尾的 @Suppress 可以一并删掉；
        //   同时要补回文件顶部被删掉的 `import kotlinx.coroutines.flow.drop`）。
        // viewModelScope.launch {
        //     // 第一项是"进这一页时的当前语言"，不算切换；之后每一项都是用户真的改了设置
        //     language.drop(1).collect { reloadForLanguage() }
        // }
    }

    /**
     * 接口语言变了：按新语言把这一页整体重来一遍。
     *
     * 只清分类这一块的缓存（分类树 + 所有已加载的列表）：评论缓存在设置页那边清
     * （评论不在这一页）。列表用"首屏加载"的形态重拉，失败会落到整块错误态，
     * 用户可以下拉重试。
     *
     * **目前没有调用点**：唯一入口是 [init] 里注释掉的那段订阅（见那里的说明）。
     */
    @Suppress("unused")
    private fun reloadForLanguage() {
        listJob?.cancel()
        cache.clearCatalogAndLists()
        val previous = _state.value
        _state.value = CategoryUiState(
            catalog = null,
            // slug 与语言无关，选中项照旧保留（loadCatalog 会再按新分类树修正一次）
            mainSlug = previous.mainSlug,
            subSlug = previous.subSlug,
            sort = previous.sort,
            catalogLoading = true,
            // 列表 / 卡片的选择也保留：切个语言不该把展示方式重置掉
            viewMode = previous.viewMode,
        )
        viewModelScope.launch { loadCatalog() }
    }

    /** 从缓存恢复"上次离开时"的样子（分类树 / 选中项 / 已加载的列表）。 */
    private fun restoredState(): CategoryUiState {
        val catalog = cache.catalog
        val selection = if (catalog != null) {
            fixCategorySelection(cache.selection, catalog)
        } else {
            cache.selection
        }
        val snapshot = cache.snapshot(selection)
        return CategoryUiState(
            catalog = catalog,
            mainSlug = selection.mainSlug,
            subSlug = selection.subSlug,
            sort = selection.sort,
            albums = snapshot?.items.orEmpty(),
            total = snapshot?.total ?: 0L,
            page = snapshot?.page ?: 0,
            totalPages = snapshot?.totalPages ?: 1,
            catalogLoading = catalog == null,
            viewMode = cache.viewMode,
        )
    }

    /** 切换"列表 / 卡片"（用户要求：切换按钮在数量页码那一行）。 */
    fun toggleViewMode() {
        val next = _state.value.viewMode.toggled()
        cache.saveViewMode(next)
        _state.update { it.copy(viewMode = next) }
    }

    /**
     * 选中一个主分类。
     *
     * 主分类一被选中就**默认选中它的「全部」二级分类**（用户要求），列表随之切过去；
     * 抽屉是否收起由界面决定（只有「全部」一项时界面会直接收起）。
     */
    fun selectMain(group: CategoryGroup) {
        val main = _state.value.catalog?.firstOrNull { it.slug == group.slug } ?: group
        applySelection(
            CategorySelection(
                mainSlug = main.slug,
                subSlug = main.subs.firstOrNull()?.slug.orEmpty(),
                sort = _state.value.sort,
            ),
        )
    }

    /** 选中一个二级分类。 */
    fun selectSub(sub: CategorySub) {
        applySelection(_state.value.selection.copy(subSlug = sub.slug))
    }

    /** 换排序；当前主分类不支持的排序项直接忽略（界面也不会摆出来）。 */
    fun selectSort(sort: CategorySort) {
        if (sort !in CategorySort.optionsFor(_state.value.mainSlug)) return
        applySelection(_state.value.selection.copy(sort = sort))
    }

    /**
     * 下拉刷新：**只重新拉当前分类的第一页**（用户要求），不动分类树。
     *
     * 已经在请求中时不重复发（与搜索页一致）；分类树还没拉到时退化成"重试分类树"。
     */
    fun refresh() {
        val current = _state.value
        if (current.loading || current.refreshing || current.loadingMore || current.catalogLoading) return
        if (current.catalog == null) {
            viewModelScope.launch { loadCatalog() }
            return
        }
        // 列表还是空的（首屏失败 / 一次都没成功过）：当成"重新加载第一页"，
        // 这样失败会落到整块错误态；不然界面会一边转下拉圈、一边写"这个分类下还没有漫画"。
        loadFirstPage(refresh = current.albums.isNotEmpty())
    }

    /**
     * 点「加载更多」：拉下一页并追加。
     *
     * **不监听滚动**（用户要求：滑到底部不自动加载）。失败只弹一句提示，
     * 已经加载的列表保持不动。
     */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.refreshing || current.loadingMore) return
        if (current.page <= 0 || current.page >= current.totalPages) return
        val selection = current.selection
        val nextPage = current.page + 1
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, message = null) }
            runCatching {
                repo.categoryAlbums(selection.mainSlug, selection.subSlug, selection.sort, nextPage)
            }.onSuccess { page ->
                _state.update { state ->
                    val items = mergeCategoryItems(state.albums, page.items)
                    cache.putSnapshot(
                        selection,
                        CategoryListSnapshot(items, page.total, page.page, page.totalPages),
                    )
                    state.copy(
                        albums = items,
                        total = page.total,
                        page = page.page,
                        totalPages = page.totalPages,
                        loadingMore = false,
                        error = null,
                    )
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loadingMore = false, message = e.toUserMessage()) }
            }
        }
    }

    /** 整块错误态里的「重试」：分类树没拉到就重拉分类树，否则重拉当前分类第一页。 */
    fun retry() {
        if (_state.value.catalog == null) {
            viewModelScope.launch { loadCatalog() }
        } else {
            loadFirstPage(refresh = false)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private suspend fun loadCatalog() {
        _state.update { it.copy(catalogLoading = true, catalogError = null) }
        runCatching { repo.categoryCatalog() }
            .onSuccess { groups ->
                cache.saveCatalog(groups)
                val fixed = fixCategorySelection(_state.value.selection, groups)
                cache.saveSelection(fixed)
                _state.update {
                    it.copy(
                        catalog = groups,
                        catalogLoading = false,
                        catalogError = null,
                        mainSlug = fixed.mainSlug,
                        subSlug = fixed.subSlug,
                        sort = fixed.sort,
                    )
                }
                ensureListLoaded()
            }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(catalogLoading = false, catalogError = e.toUserMessage()) }
            }
    }

    /** 缓存里已经有列表就什么都不做（用户要求：切回来还得是上次加载的漫画）。 */
    private fun ensureListLoaded() {
        if (_state.value.page > 0) return
        loadFirstPage(refresh = false)
    }

    /**
     * 拉第一页。
     *
     * @param refresh true = 下拉刷新：**保留旧列表**（列表不闪，只有顶部下拉圈在转），
     *        失败也不清空；false = 首屏 / 换分类：清空并显示居中加载圈。
     */
    private fun loadFirstPage(refresh: Boolean) {
        val selection = _state.value.selection
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.update { state ->
                if (refresh) {
                    state.copy(refreshing = true, error = null, message = null)
                } else {
                    state.copy(
                        loading = true,
                        error = null,
                        message = null,
                        albums = emptyList(),
                        total = 0L,
                        page = 0,
                        totalPages = 1,
                    )
                }
            }
            runCatching {
                repo.categoryAlbums(
                    mainSlug = selection.mainSlug,
                    subSlug = selection.subSlug,
                    sort = selection.sort,
                    page = 1,
                )
            }.onSuccess { page ->
                applyPage(selection, page)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // 刷新失败：旧列表留着，只弹一句；首屏失败：给整块错误态
                        error = if (refresh) it.error else e.toUserMessage(),
                        message = if (refresh) e.toUserMessage() else null,
                    )
                }
            }
        }
    }

    private fun applyPage(selection: CategorySelection, page: CategoryAlbumPage) {
        cache.putSnapshot(
            selection,
            CategoryListSnapshot(
                items = page.items,
                total = page.total,
                page = page.page,
                totalPages = page.totalPages,
            ),
        )
        _state.update {
            it.copy(
                albums = page.items,
                total = page.total,
                page = page.page,
                totalPages = page.totalPages,
                loading = false,
                refreshing = false,
                loadingMore = false,
                error = null,
            )
        }
    }

    /**
     * 应用一个新选择。
     *
     * **缓存优先**：这份"分类 + 排序"之前加载过就直接摆出来，一次请求都不发；
     * 没加载过才拉第一页。选中的组合没变而列表也还在时什么都不做
     * （抽屉里重复点同一项不该触发刷新——刷新是下拉手势的事）。
     */
    private fun applySelection(newSelection: CategorySelection) {
        val fixed = fixCategorySelection(newSelection, _state.value.groups)
        if (fixed == _state.value.selection && _state.value.page > 0) return

        cache.saveSelection(fixed)
        val snapshot = cache.snapshot(fixed)
        if (snapshot != null) {
            listJob?.cancel()
            _state.update {
                it.copy(
                    mainSlug = fixed.mainSlug,
                    subSlug = fixed.subSlug,
                    sort = fixed.sort,
                    albums = snapshot.items,
                    total = snapshot.total,
                    page = snapshot.page,
                    totalPages = snapshot.totalPages,
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    error = null,
                    message = null,
                )
            }
            return
        }

        _state.update {
            it.copy(
                mainSlug = fixed.mainSlug,
                subSlug = fixed.subSlug,
                sort = fixed.sort,
                message = null,
            )
        }
        loadFirstPage(refresh = false)
    }
}

// ======================================================================
// 收藏夹
// ======================================================================

/**
 * 收藏夹列表的排序方式（用户要求：三种，全部降序，默认"创建时间"）。
 *
 * 「默认收藏夹始终置顶」不在这里表达，而是由 [sortFavorites] 统一保证——
 * 排序方式换来换去，默认夹的位置都不动。
 */
enum class FavoriteSort(val label: String) {
    CREATED_DESC("创建时间"),
    ALBUM_COUNT_DESC("漫画数量"),
    LAST_ADDED_DESC("最近加入"),
    ;

    companion object {
        val DEFAULT = CREATED_DESC
    }
}

data class FavoritesUiState(
    val favorites: List<FavoriteRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val sort: FavoriteSort = FavoriteSort.DEFAULT,
) {
    data class FavoriteRow(
        val id: Long,
        val name: String,
        val albumCount: Int,
        val isDefault: Boolean,
        /** 创建时间（"创建时间"排序用）。 */
        val createTime: String? = null,
        /** 夹内最近一次加入漫画的时间；空夹为 null。 */
        val lastAddedTime: String? = null,
        /** 夹内最近加入的本子 id，列表拿它当封面；空夹为 null。 */
        val latestAlbumId: Long? = null,
    ) {
        val isEmpty: Boolean get() = albumCount == 0
    }
}

/**
 * 收藏夹列表的排序。
 *
 * 规则（用户要求）：
 * 1. **默认收藏夹永远置顶**，与排序方式无关；
 * 2. 其余按所选方式**降序**：创建时间 / 漫画数量 / 最近加入漫画的时间；
 * 3. 同值时按 id 兜底，保证顺序稳定（不会因为重组而跳动）。
 *
 * 时间字段是 `yyyy-MM-dd HH:mm:ss` 文本，字典序即时间序；`null` 当空串处理，
 * 降序时自然排到最后（例如还没放过东西的夹子）。
 */
fun sortFavorites(
    rows: List<FavoritesUiState.FavoriteRow>,
    sort: FavoriteSort,
): List<FavoritesUiState.FavoriteRow> {
    val comparator = when (sort) {
        FavoriteSort.CREATED_DESC -> compareByDescending<FavoritesUiState.FavoriteRow> { it.createTime.orEmpty() }
        FavoriteSort.ALBUM_COUNT_DESC -> compareByDescending<FavoritesUiState.FavoriteRow> { it.albumCount }
        FavoriteSort.LAST_ADDED_DESC -> compareByDescending<FavoritesUiState.FavoriteRow> { it.lastAddedTime.orEmpty() }
    }
    return rows.sortedWith(
        compareByDescending<FavoritesUiState.FavoriteRow> { it.isDefault }
            .then(comparator)
            .thenBy { it.id },
    )
}

/**
 * 收藏夹列表。
 *
 * 数量与封面来自 [Repository.observeFavoritesWithCount] 的 **Flow**：
 * 之前用一次性查询，收藏之后回到收藏页数量不更新（用户反馈第 1 点）。
 * 改成 Flow 后，关联表任何变动（包括封面对应的"最近加入的那本"）都会自动重发。
 */
class FavoritesViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 默认收藏夹必须存在（新建/删除/改名都依赖它做兜底）
            runCatching { repo.ensureDefaultFavorite() }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }

            repo.observeFavoritesWithCount()
                .catch { e -> Timber.tag("jm").w(e, "观察收藏夹失败") }
                .collect { list ->
                    _state.update { current ->
                        current.copy(
                            favorites = list.map {
                                FavoritesUiState.FavoriteRow(
                                    id = it.id,
                                    name = it.name,
                                    albumCount = it.albumCount,
                                    isDefault = it.isDefault,
                                    createTime = it.createTime,
                                    lastAddedTime = it.lastAddedTime,
                                    latestAlbumId = it.latestAlbumId,
                                )
                            },
                            loading = false,
                        )
                    }
                }
        }
    }

    /** 切换排序方式（只影响展示顺序，不写库）。 */
    fun setSort(sort: FavoriteSort) = _state.update { it.copy(sort = sort) }

    fun create(name: String) {
        viewModelScope.launch {
            val result = runCatching { repo.createFavorite(name) }
                .getOrDefault(FavoriteWriteResult.NameBlank)
            _state.update {
                it.copy(
                    message = when (result) {
                        FavoriteWriteResult.Ok -> "已创建「${name.trim()}」"
                        FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了"
                        FavoriteWriteResult.NameBlank -> "请输入收藏夹名称"
                        FavoriteWriteResult.DefaultFolder -> "默认收藏夹已存在"
                        FavoriteWriteResult.NotFound -> "收藏夹不存在"
                    },
                )
            }
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch {
            val result = runCatching { repo.renameFavorite(id, newName) }
                .getOrDefault(FavoriteWriteResult.NotFound)
            // 每种失败原因分开提示（用户反馈第 7 点：撞名被说成了"默认收藏夹不支持重命名"）
            _state.update {
                it.copy(
                    message = when (result) {
                        FavoriteWriteResult.Ok -> "已重命名为「${newName.trim()}」"
                        FavoriteWriteResult.DefaultFolder -> "默认收藏夹不支持重命名"
                        FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了，换个名字"
                        FavoriteWriteResult.NameBlank -> "名称不能为空"
                        FavoriteWriteResult.NotFound -> "收藏夹不存在"
                    },
                )
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val result = runCatching { repo.deleteFavorite(id) }
                .getOrDefault(FavoriteWriteResult.NotFound)
            _state.update {
                it.copy(
                    message = when (result) {
                        FavoriteWriteResult.Ok -> "已删除收藏夹（书库记录与本地文件都保留）"
                        FavoriteWriteResult.DefaultFolder -> "默认收藏夹不支持删除"
                        FavoriteWriteResult.NotFound -> "收藏夹不存在"
                        else -> "删除失败"
                    },
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

// ======================================================================
// 最近浏览
// ======================================================================

data class RecentUiState(
    val albums: List<AlbumCard> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !loading && albums.isEmpty()
}

/**
 * 最近浏览：按浏览时间倒序（用户要求）。
 *
 * 只展示、不统计章节：这张表的用途是"快速回到刚看过的本子"，
 * 每次进来都为 60 条记录查一次章节会明显拖慢页面。
 *
 * 删除走 [AlbumCleaner]：删记录的同时要判断"这本还在不在书库里"，
 * 不在的话它的封面缓存也就没用了（用户第九轮要求第 3 点）。
 */
class RecentViewModel(
    private val repo: Repository,
    private val cleaner: AlbumCleaner,
) : ViewModel() {

    val state: StateFlow<RecentUiState> = repo.observeRecentAlbums()
        .map { rows ->
            RecentUiState(
                // 列表条目本身就是"基础数据 + 浏览时间"拼出来的，直接渲染
                albums = rows.map { album ->
                    AlbumCard(
                        album = album,
                        totalChapters = 0,
                        downloadedChapters = 0,
                        localSizeBytes = 0L,
                    )
                },
                loading = false,
            )
        }
        .catch { e ->
            Timber.tag("jm").w(e, "最近浏览查询失败")
            emit(RecentUiState(loading = false, error = e.toUserMessage()))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecentUiState(),
        )

    fun remove(albumId: Long) {
        viewModelScope.launch { runCatching { cleaner.removeRecent(albumId) } }
    }

    fun clearAll() {
        viewModelScope.launch { runCatching { cleaner.clearRecent() } }
    }
}

// ======================================================================
// 漫画详情
// ======================================================================

data class ChapterRow(
    val chapterId: Long,
    val title: String,
    val sortIndex: Int,
    val totalImages: Int,
    val downloadedImages: Int,
) {
    val isDownloaded: Boolean get() = totalImages > 0 && downloadedImages >= totalImages
    val isPartial: Boolean get() = downloadedImages in 1 until totalImages
    val isMissing: Boolean get() = downloadedImages == 0

    /** "已下载 / 部分下载 12/45 / 未下载"（计划 §9 第 7 条）。 */
    val statusText: String
        get() = when {
            isDownloaded -> "已缓存"
            isPartial -> "部分缓存 $downloadedImages/$totalImages"
            else -> "未下载"
        }
}

/**
 * 简介下方那两个 tab（用户要求）。
 *
 * 章节相关的所有内容（导出文件、章节进度、分组列表）都在 [CHAPTERS] 里，
 * 评论单独一页；**默认停在章节**：进详情页的第一诉求还是"从这一话开始读"。
 */
enum class AlbumDetailTab(val code: String, val label: String) {
    CHAPTERS("chapters", "章节"),
    COMMENTS("comments", "评论"),
    ;

    companion object {
        val DEFAULT = CHAPTERS

        /**
         * 路由参数 → tab（`Routes.detail(albumId, tab="comments")`）。
         *
         * 认不出的一律回 [DEFAULT]：路由是内部拼的，出现陌生值时"停在章节"比报错友好。
         */
        fun fromCode(code: String?): AlbumDetailTab =
            entries.firstOrNull { it.code == code?.trim() } ?: DEFAULT
    }
}

data class AlbumDetailUiState(
    val album: AlbumEntity? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val localSizeBytes: Long = 0,
    val favoriteIds: List<Long> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /**
     * 数据来源：true = 已在书库；false = 只在基础数据缓存里（刚拉到的在线数据也走这条）。
     * 决定详情页显示「加入书库」还是「已在书库」。
     */
    val inLibrary: Boolean = false,
    val message: String? = null,
    /** 章节倒序（正序/倒序切换）。 */
    val reversed: Boolean = false,
    val descriptionExpanded: Boolean = false,
    val allFavorites: List<FavoriteEntity> = emptyList(),
    /**
     * **已经在下载队列里**的章节 id（用户要求第 3 点）。
     *
     * 取这个本子当前活跃任务的章节明细：排队、下载中、暂停、等待网络、失败的都算
     * ——"已经在队列里"本身就是不让重复提交的理由，与它跑到哪一步无关。
     */
    val queuedChapterIds: Set<Long> = emptySet(),

    /** 已经在**官方 ZIP** 队列里的章节 id（与普通图片下载队列分开）。 */
    val officialZipQueuedChapterIds: Set<Long> = emptySet(),

    // ---------------- 阅读进度（用户第十一轮要求第 1 点） ----------------

    /**
     * 上次读到哪儿（还没读过就是 null）。
     *
     * 有它的时候主按钮是「继续阅读」（按这个位置续读），
     * 没有的时候是「阅读」（从第一话第一张开始）。
     */
    val progress: ReadingPosition? = null,
    /** 「继续阅读」按钮下面那行小字（"上次读到 第2话 · 第15页"）。 */
    val progressText: String? = null,

    // ---------------- 导出（P4） ----------------

    /** 用户点了哪个导出（决定密码框的标题与默认密码）。 */
    val exportFormat: ExportFormat? = null,
    /** 该格式的默认密码（来自设置/凭据存储），密码框预填它；**空串 = 设置里留空，框里就是空的**。 */
    val exportDefaultPassword: String = "",
    /**
     * 目标文件（系统下载目录里的同名 zip/pdf）**已经存在**。
     *
     * 再次生成会直接覆盖，所以在密码框里用醒目颜色提醒一句（用户要求第 5 点）。
     */
    val exportTargetExists: Boolean = false,
    /** 缺图预检结果；非 null 时展示"缺图"对话框而不是密码框。 */
    val exportPrecheck: ExportManager.Precheck? = null,
    /**
     * 这本漫画**已经导出到系统下载目录**的成品，带大小与生成时间
     * （用户第九轮要求第 2 点）。空列表 = 还没导出过，此时详情页不显示这个区块。
     */
    val exports: List<ExportedFormat> = emptyList(),

    /**
     * **正在跑**的导出任务（用户第十一轮要求第 3 点）。
     *
     * 点完「导出 ZIP/PDF」立刻就能在折叠栏里看到"排队中 / 导出中 45%"，
     * 不用去任务页确认；跑完之后这一行消失、成品行出现（见 `AlbumDetailViewModel`）。
     */
    val activeExports: List<ActiveExport> = emptyList(),

    /**
     * 系统能不能打开成品所在的文件夹（用户反馈第 2 点）。
     *
     * 公共下载目录（API 29+）可以，老系统上成品在应用私有目录里、别人的文件管理器看不到，
     * 这时为 false，界面不显示「打开文件夹」。
     */
    val canOpenFolder: Boolean = false,

    // ---------------- 简介下方的两个 tab（用户要求） ----------------

    /** 当前停在哪一个 tab；默认章节。 */
    val tab: AlbumDetailTab = AlbumDetailTab.DEFAULT,

    /**
     * 已经加载出来的评论（**跨页累积**，下滑追加）。
     *
     * 只在内存里：`CommentCache` 按 jm 号存一份快照，退出 App 清空。
     */
    val comments: List<CommentRow> = emptyList(),
    /** 评论总条数（`/forum` 的 `total`，上限 5000）。 */
    val commentTotal: Int = 0,
    /** 第一次拉第一页（评论列表还空着）：整块显示加载圈。 */
    val commentsLoading: Boolean = false,
    /** 正在追加下一页（列表已有内容，底部显示一行"加载中"）。 */
    val commentsLoadingMore: Boolean = false,
    /** 评论加载失败的原因（第一次失败显示空态+重试，追加失败显示底部一行）。 */
    val commentsError: String? = null,
    /** 已经加载过评论（有缓存或至少请求过一次）；用来决定切到评论 tab 时要不要发请求。 */
    val commentsLoaded: Boolean = false,
    /** 已经到底了（服务端没有更多，或条数已经够 total）。 */
    val commentsReachedEnd: Boolean = false,

    // ---------------- 发留言 / 回复（用户要求，2026-10-22） ----------------

    /**
     * 底部输入条里的正文。
     *
     * 放在 VM 里而不是 `remember`：转屏、或者未登录点「去登录」去登录页再回来，
     * 已经敲了一半的内容不能丢。长度上限在 [setCommentInput] 里按码位截断。
     */
    val commentInput: String = "",
    /** 正在发留言（发送按钮转圈，防重复提交）。 */
    val commentSending: Boolean = false,
    /** 正在回复哪一条（null = 发新留言）。 */
    val commentReplyTo: CommentReplyTarget? = null,
    /**
     * 每次发留言/回复成功后 +1。
     *
     * 界面拿它当"把列表滚回评论 tab 开头"的信号：新留言在列表最上面，用户刚发完就该看见它，
     * 而不是还停在刚才翻到的位置（官方 App 发完也是 `scrollToTop`）。
     */
    val commentsRefreshToken: Int = 0,
    /**
     * 发留言/回复的结果提示（成功与失败都放这里）。
     *
     * **故意不走根部的 Snackbar**：那条提示贴在窗口底部，正好盖住评论输入条
     * （用户 2026-10-22 反馈）。这条文案显示在输入条**内部**、输入框上方一行，
     * 既不挡输入框，也不会因为页面滚动而跑掉。
     */
    val commentNotice: String? = null,
    /** [commentNotice] 是不是失败（决定用错误色画）。 */
    val commentNoticeError: Boolean = false,
    /** 正在删除的留言 `cid`（那一行的删除按钮转圈 + 防重复点击）。 */
    val commentDeletingCid: String? = null,

    // ---------------- 账号收藏（官方在线收藏，见 docs/account-design.md §4.4） ----------------

    /** 当前是否已登录（未登录时收藏按钮点了要先去登录）。 */
    val accountSignedIn: Boolean = false,
    /**
     * 当前登录账号的 uid。
     *
     * 「这条留言是不是我发的」只能靠它比 `ForumItemDto.UID`（官方 `ForumList` 同样比 `UID`）；
     * 未登录 / 资料缺失时为 null，界面据此不显示「删除」。
     */
    val accountUid: String? = null,
    /** 这本是否已在账号收藏里（**以服务端为准**；本机 `collected` 字段不再用作这个含义）。 */
    val accountInFavorites: Boolean = false,
    /** 收藏夹列表（打开面板时拉一次）。 */
    val accountFolders: List<AccountFolder> = emptyList(),
    /**
     * 正在读收藏夹列表（面板里显示**局部** loading：只有收藏夹那一段转圈，
     * 说明行 / 「新建」都不受影响）。
     */
    val accountFoldersLoading: Boolean = false,
    /** 正在写收藏（按钮/行转圈，防重复提交）。 */
    val accountFavoriteBusy: Boolean = false,
    /** 收藏面板是否展开（放 VM 里，重组/转屏后不会自己关掉）。 */
    val accountSheetOpen: Boolean = false,
) {
    /**
     * 「评论」tab 上那个数字：优先用 `/forum` 给的 `total`，
     * 还没加载过评论时退回 `/album` 的 `comment_total`（两者实测一致）。
     * 都没有就是 0，界面据此不显示数字。
     */
    val commentTabCount: Int
        get() = if (commentsLoaded || commentTotal > 0) {
            commentTotal
        } else {
            album?.commentTotal?.trim()?.toIntOrNull() ?: 0
        }

    /**
     * 下载面板的默认勾选：**没下完、也不在队列里**的章节。
     *
     * 已下载的与已在队列里的都不默认勾选，避免用户点一下就把重复的活又排一遍。
     */
    val defaultDownloadSelection: Set<Long>
        get() = chapters
            .filter { !it.isDownloaded && it.chapterId !in queuedChapterIds }
            .map { it.chapterId }
            .toSet()

    /**
     * 官方 ZIP 面板的默认勾选：**空**。
     *
     * 官方 ZIP 是整话打包、单体体积大，默认全选容易误触下载整本；让用户自己勾。
     * 已经在官方 ZIP 队列里的章节在面板里仍然不可选。
     */
    val defaultOfficialZipSelection: Set<Long>
        get() = emptySet()
}

/**
 * 一次正在跑的导出（详情页折叠栏里的"导出中"那一行）。
 *
 * [progress] 是 0..1；导出任务的总量是"图片张数"，未开始时为 0（显示成不确定进度）。
 */
data class ActiveExport(
    val format: ExportFormat,
    val status: DownloadStatus,
    val progress: Float,
) {
    /** "排队中" / "导出中 45%" / "等待网络" …。 */
    val statusText: String
        get() = when (status) {
            DownloadStatus.QUEUED -> "排队中"
            DownloadStatus.RUNNING -> if (progress > 0f) {
                "导出中 ${(progress * 100).toInt()}%"
            } else {
                "导出中"
            }

            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.WAITING_NETWORK -> "等待网络"
            else -> "处理中"
        }
}

/**
 * 「继续阅读」下面那行小字。
 *
 * 位置只记到章节（还没翻页就退出）时不写页码，免得显示成"第 0 页"。
 * 章节标题拿不到就退化成"上次读到的地方"，总比不显示强。
 */
internal fun readingProgressLabel(chapterTitle: String?, hasPage: Boolean, pageNumber: Int): String {
    val where = chapterTitle?.takeIf { it.isNotBlank() } ?: "上次读到的地方"
    return if (hasPage && pageNumber > 0) "上次读到 $where · 第 $pageNumber 页" else "上次读到 $where"
}

/**
 * 等本子数据就位的上限（毫秒）。
 *
 * 首屏是"本地读库 → 没有就联网拉"，正常几百毫秒内就有结果；给 5 秒是为了容忍
 * 慢网络下第一次拉取，同时保证"本地没有、联网也失败"时协程会被放开并放弃这次重算。
 */
private const val ALBUM_WAIT_TIMEOUT_MS = 5_000L

/**
 * 漫画详情。
 *
 * ## 数据读取顺序（用户要求第 4 点）
 *
 * 1. **先读本地**：书库 → 基础数据缓存；任一命中就直接渲染，不发网络请求；
 * 2. 都没有才请求 `/album`，成功后**只更新基础数据缓存**（[Repository.fetchAlbumDetail]）；
 * 3. 「刷新」按钮：同样只更新基础数据缓存——它不改浏览时间，
 *    书库列表与详情页因为都拼这张表，看到的也是新内容。
 *
 * ## 浏览时间（用户要求第 3、4 点）
 *
 * 进入详情页时调 [Repository.touchRecent]，**无论从搜索、书库还是收藏夹进来**都走这里，
 * 所以三种入口的浏览时间都会更新；刷新不会再动它。
 *
 * ## 点详情 ≠ 加入书库（用户要求第 2 点）
 *
 * 构造时只 [Repository.touchRecent]（最近预览），不写书库成员表。
 * 入书库只发生在用户点「加入书库」时。
 */
class AlbumDetailViewModel(
    private val repo: Repository,
    private val downloads: DownloadManager,
    private val exports: ExportManager,
    private val tasks: DownloadTaskRepository,
    private val secrets: SecretStore,
    private val cleaner: AlbumCleaner,
    private val reading: ReadingRepository,
    /**
     * 评论的进程内缓存（按 jm 号）。挂在 `AppContainer` 上，所以退出页面再进来、
     * 甚至换一个 ViewModel 实例，评论都还在；退出 App 即清空。
     */
    private val commentCache: CommentCache,
    /**
     * 应用级协程作用域（`AppContainer.appScope`）。
     *
     * 只给「移出书库」这种**必须活过本页**的删除用：移出书库不再自动返回上一页，
     * 用户完全可能删到一半就自己按返回，挂在 `viewModelScope` 上会随导航 entry 一起取消。
     */
    private val appScope: CoroutineScope,
    /** 账号（官方在线收藏）：详情页的收藏按钮/面板都走它。 */
    private val account: AccountRepository,
    private val albumId: Long,
    /**
     * 进来时停在哪个 tab。
     *
     * 「留言记录」点一行进来时是 [AlbumDetailTab.COMMENTS]（`Routes.detail(…, tab="comments")`），
     * 其余入口（书库、搜索、收藏、任务页）一律默认章节——用户点一本本子的第一诉求是读它。
     */
    private val initialTab: AlbumDetailTab = AlbumDetailTab.CHAPTERS,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    /**
     * 下一次要请求的评论页码（**从 1 开始**）。
     *
     * 单独放在字段里而不是从 `comments.size` 反推：接口每页 10 条，但服务端有可能
     * 少给（到底了）或多给，用条数除页大小算出页码迟早会错位。
     */
    private var commentNextPage = 1

    /**
     * 上一次评论请求失败时，那次请求是不是"替换整个列表"（第一页 / 下拉刷新）。
     *
     * 「重试」按它决定重走哪条路，见 [retryComments]。
     */
    private var commentRetryReset = false

    /**
     * 刚发出去、但服务端列表里还没出现的那条留言 / 回复（**本地乐观插入**）。
     *
     * 为什么要它：2026-10-22 实测"发留言成功 → 立刻拉列表"时，新留言不一定马上出现在
     * `page=1` 里（服务端列表有延迟）。如果只靠重拉，用户会看到自己刚发的留言 disappear，
     * 以为发失败了、再发一次。
     *
     * 一次重拉后就把它们清掉：服务端一旦给了真数据，以真数据为准（正文可能被服务端改写，
     * 例如实测会被统一成繁体），本地那条只负责"先让用户看到"。
     */
    private var pendingNewComment: CommentRow? = null
    private var pendingNewReply: CommentRow? = null
    private var pendingNewReplyParent: String? = null

    init {
        // 1) 记一次浏览（三种入口共用这一条路径）
        viewModelScope.launch {
            runCatching { repo.touchRecent(albumId) }
                .onFailure { Timber.tag("jm").w(it, "记录最近浏览失败") }
        }

        // 2) 本地优先：有本地数据就先渲染
        viewModelScope.launch {
            val local = runCatching { repo.localAlbumDetail(albumId) }.getOrNull()
            if (local == null) {
                // 本地没有 -> 必须联网
                loadFromNetwork(initial = true)
            } else {
                _state.update {
                    it.copy(
                        album = local.album,
                        inLibrary = local.fromLibrary,
                        loading = false,
                        // 本机快照说"已收藏"就先画成已收藏，等 /album 回来再校正（避免先显示「收藏」）
                        accountInFavorites = it.accountInFavorites || local.album.isFavorite == true,
                    )
                }
                refreshLocalStats()
            }
        }

        viewModelScope.launch {
            repo.observeFavorites().collect { favorites ->
                _state.update { it.copy(allFavorites = favorites) }
            }
        }

        // 3) 盯着这个本子的活跃任务：面板里要把"已在队列"的章节标出来且不可选，
        //    下载完成后也要立刻把状态清掉（用户要求第 3 点）
        viewModelScope.launch {
            tasks.observeQueuedChapters(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察下载队列失败") }
                .collect { queued ->
                    _state.update { it.copy(queuedChapterIds = queued) }
                }
        }

        viewModelScope.launch {
            tasks.observeOfficialZipQueuedChapters(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察官方 ZIP 队列失败") }
                .collect { queued ->
                    _state.update { it.copy(officialZipQueuedChapterIds = queued) }
                }
        }

        // 3.5) **下载进度实时化**（用户本轮要求第 2、3 点）：
        //    下载期间每隔一小段时间重算一次本地状态，于是章节行下面的"部分下载 1/100"
        //    和章节区标题里的占用体积会自己往上走，不用退出页面再进来
        //    （原来只在进页面 / 从别处返回时才重算一次）。
        //
        //    节流放在仓库层（`sample`），这里不再自己 delay：详情页每次重算都要逐话
        //    核对磁盘文件、还要遍历整个本子目录算体积，而下载期间"进度变了"的信号是
        //    连续的 —— 在这里去抖会把窗口一直往后推，结果整个下载过程一次都不刷新。
        viewModelScope.launch {
            tasks.observeChapterProgressEvents(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察下载进度失败") }
                .collect { loadLocalStats() }
        }

        // 4) 收藏状态跟着数据库走（用户反馈第 1、3 点）：
        //    "在收藏夹面板里全部移出"或"在阅读器里点了收藏"之后回到详情页，
        //    必须立刻看到正确的按钮状态。之前只在页面加载时读一次，所以看不到变化。
        viewModelScope.launch {
            repo.observeAlbum(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察本子失败") }
                .collect { album ->
                    if (album != null) {
                        _state.update {
                            it.copy(
                                album = album,
                                inLibrary = true,
                                accountInFavorites = it.accountInFavorites || album.isFavorite == true,
                            )
                        }
                    }
                }
        }
        viewModelScope.launch {
            repo.observeFavoriteIdsOf(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察收藏夹关联失败") }
                .collect { ids ->
                    _state.update { it.copy(favoriteIds = ids) }
                }
        }

        // 4.5) 账号（官方在线收藏）：登录态 + `is_favorite` 只信服务端
        //      （本机库里的 collected 是"本机收藏夹"的旧语义，与账号收藏无关）
        viewModelScope.launch {
            account.state.collect { accountState ->
                _state.update {
                    it.copy(
                        accountSignedIn = accountState.isSignedIn,
                        // 只在登录有效时给 uid：失效后"删除"按钮不该还在
                        accountUid = accountState.profileOrNull?.uid?.takeIf {
                            accountState.isSignedIn && it.isNotBlank()
                        },
                    )
                }
                if (accountState.isSignedIn) syncAccountFavoriteOnce()
            }
        }

        // 5) 阅读进度（用户第十一轮要求第 1 点）：有进度主按钮就是「继续阅读」。
        //    用 Flow 而不是读一次：在阅读器里翻了几页再返回详情页，按钮下那行小字要跟着变。
        viewModelScope.launch {
            reading.observePosition(albumId)
                .catch { e -> Timber.tag("jm").w(e, "观察阅读进度失败") }
                .collect { position ->
                    val chapterTitle = position?.chapterId?.let { id ->
                        runCatching { reading.chapterFolderName(albumId, id) }.getOrNull()
                    }
                    _state.update {
                        it.copy(
                            progress = position,
                            progressText = position?.takeIf { p -> p.isUsable }?.let { p ->
                                readingProgressLabel(
                                    chapterTitle = chapterTitle,
                                    hasPage = p.imageFile != null,
                                    pageNumber = p.imageIndex + 1,
                                )
                            },
                        )
                    }
                }
        }

        // 6) 导出任务的实时状态（用户第十一轮要求第 3 点）：
        //    - 任务出现 -> 折叠栏里立刻多一行"排队中 / 导出中 45%"；
        //    - 任务消失 -> 这一轮跑完了（成功/失败/取消），立刻重算成品列表，
        //      于是成功的那些马上变成可打开/发送/删除的成品行，不用退出页面再进来。
        ExportFormat.entries.forEach { format ->
            viewModelScope.launch {
                var hadTask = false
                tasks.observeActiveTask(format.taskType, albumId.toString())
                    .catch { e -> Timber.tag("jm").w(e, "观察导出任务失败") }
                    .collect { task ->
                        if (task == null && hadTask) loadLocalStats()
                        hadTask = task != null
                        val active = task?.let {
                            ActiveExport(
                                format = format,
                                status = it.status,
                                progress = if (it.totalImages > 0) {
                                    (it.doneImages.toFloat() / it.totalImages).coerceIn(0f, 1f)
                                } else {
                                    0f
                                },
                            )
                        }
                        _state.update { state ->
                            state.copy(
                                activeExports = state.activeExports
                                    .filterNot { row -> row.format == format }
                                    .let { rows -> if (active != null) rows + active else rows }
                                    .sortedBy { row -> row.format.ordinal },
                            )
                        }
                    }
            }
        }

        // 7) 评论：进来先把**内存里那一份**接上（用户要求：按 jm 号缓存、退出 App 才清）。
        //    命中缓存就不再发请求——用户"看几条 → 返回 → 再进来"时应当立刻看到原来的列表；
        //    想拿最新的就下拉刷新（`refreshComments`）。
        commentCache.get(albumId)?.let { snapshot ->
            commentNextPage = snapshot.nextPage
            _state.update {
                it.copy(
                    comments = snapshot.comments,
                    commentTotal = snapshot.total,
                    commentsLoaded = true,
                    commentsReachedEnd = snapshot.reachedEnd,
                )
            }
        }

        // 8) 从「留言记录」点进来时直接停在评论 tab（`Routes.detail(albumId, tab="comments")`）。
        //    走 selectTab 而不是自己改 state：它顺带负责"第一次切到评论才去拉第一页"，
        //    于是这一条路径和用户自己点 tab 的行为完全一致。
        if (initialTab == AlbumDetailTab.COMMENTS) selectTab(AlbumDetailTab.COMMENTS)
    }

    /**
     * 点某一话进阅读器之前，**先把这本的阅读进度改写成这一话**（用户第十一轮要求第 1 点）。
     *
     * 不这么做的话：上次读到第 3 话第 20 页，用户点第 1 话从头看，
     * 中途退出时"继续阅读"仍然指向第 3 话——用户以为自己已经换了进度。
     * 位置只记到章节（[imageFile] 为 null），阅读器会从这一话的第一张开始读。
     */
    fun onChapterOpened(chapterId: Long) {
        viewModelScope.launch {
            runCatching {
                reading.savePosition(
                    albumId = albumId,
                    chapterId = chapterId,
                    imageFile = null,
                    imageIndex = 0,
                    offsetRatio = 0f,
                )
            }.onFailure { Timber.tag("jm").w(it, "覆盖阅读进度失败") }
        }
    }

    /**
     * 刷新详情。
     *
     * 与"进入页面"的区别：这里**不改浏览时间**——`/album` 只更新基础数据缓存，
     * 最近预览的浏览时间只由进入页面时写（[Repository.touchRecent]）。
     */
    fun refresh() {
        viewModelScope.launch { loadFromNetwork(initial = false) }
    }

    private suspend fun loadFromNetwork(initial: Boolean) {
        val inLibrary = _state.value.inLibrary || runCatching { repo.findAlbum(albumId) }.getOrNull() != null
        _state.update {
            it.copy(refreshing = true, loading = initial && it.album == null)
        }
        runCatching {
            // 只拉取并缓存基础数据：不改书库成员、不写最近预览
            repo.fetchAlbumDetail(albumId)
        }
            .onSuccess {
                val album = if (inLibrary) {
                    repo.findAlbum(albumId)
                } else {
                    // 未入库：用基础数据缓存里的副本渲染
                    repo.localAlbumDetail(albumId)?.album
                }
                _state.update {
                    val next = album ?: it.album
                    it.copy(
                        refreshing = false,
                        loading = false,
                        album = next,
                        inLibrary = inLibrary,
                        // 这次 /album 已经拿到服务端的 is_favorite：顺手写回快照与缓存，
                        // 并把"已核对过"置位，避免再为同一本多发一次 /album
                        accountInFavorites = if (it.accountSignedIn && next != null) {
                            next.isFavorite == true
                        } else {
                            it.accountInFavorites
                        },
                    )
                }
                if (_state.value.accountSignedIn) {
                    album?.let { fresh ->
                        val flag = fresh.isFavorite == true
                        account.rememberFavoriteFlag(albumId, flag)
                        repo.setAlbumFavoriteFlag(albumId, flag)
                        favoriteFlagSynced = true
                    }
                }
                refreshLocalStats()
            }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(
                        refreshing = false,
                        loading = false,
                        // 离线时不阻断本地阅读，**只弹一句提示**（计划 §9 第 7 条）。
                        // 页面上不再挂横幅 / 重试按钮：用户想再试就自己下拉释放一次
                        // （用户本轮要求第 1 点）。这里走 message 这条既有的一次性提示通道，
                        // 与"已加入书库"等提示同一条路——先消费再弹，切页不会重放。
                        message = "在线刷新失败：${e.toUserMessage()}",
                    )
                }
            }
    }

    /** 加入书库（用户要求第 2 点：只有点这里才真正入书库）。 */
    fun addToLibrary() {
        viewModelScope.launch {
            runCatching { ensureInLibrary() }
                .onSuccess { album ->
                    _state.update { it.copy(album = album, inLibrary = true, message = "已加入书库") }
                    refreshLocalStats()
                }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    /**
     * 确保本子已在书库，返回库里的实体。
     *
     * **不请求网络**：详情页手里已经有完整数据（本地副本或刚拉到的在线数据），
     * 直接落库即可。之前这里调 `repo.addToLibrary(albumId)` 会再拉一次 `/album`，
     * 于是"从未收藏过的漫画点收藏"要等一次网络往返，看起来卡一下；
     * 取消再收藏时本地已有记录就直接落库，所以很快（用户反馈的正是这个现象）。
     */
    private suspend fun ensureInLibrary(): AlbumEntity {
        val current = _state.value.album
        val library = if (current != null) {
            repo.addToLibrary(current)
        } else {
            repo.addToLibrary(albumId)
        }
        _state.update { it.copy(album = library, inLibrary = true) }
        return library
    }

    /** 回到这个页面时重算本地章节状态（下载完成后数字必须跟着变）。 */
    fun refreshLocalStats() {
        viewModelScope.launch { loadLocalStats() }
    }

    /**
     * 等本子数据就位后再取值（[loadLocalStats] 与下载进度通知都会用到）。
     *
     * 直接读 `_state.value.album` 会漏掉"通知比首次加载先到"的那一次（见 [loadLocalStats] 的注释），
     * 所以这里挂起等一会儿；本子确实拉不到（本地没有、联网也失败）时由
     * [ALBUM_WAIT_TIMEOUT_MS] 兜底返回 null，不会把协程永远挂住。
     */
    private suspend fun awaitAlbum(): AlbumEntity? {
        _state.value.album?.let { return it }
        return withTimeoutOrNull(ALBUM_WAIT_TIMEOUT_MS) {
            _state.map { it.album }.filterNotNull().first()
        }
    }

    /**
     * 重算章节状态与本地占用。
     *
     * **必须切到 IO**：`downloadedImageCount` 要逐张看文件在不在，`localSize` 要递归遍历
     * 整个本子目录。放在主线程就是"进详情页卡一下"（用户反馈第 9 点）。
     */
    private suspend fun loadLocalStats() {
        // **等本子就位，而不是"还没有就放弃"**：下载进度的每一次通知都会走到这里，
        // 而本子行本身要等"本地读库 / 联网详情"那一路跑完才填进 state。
        // 首屏（基础表里没有这本、正在联网拉）时两者会赛跑，一旦这里直接 return，
        // 那次进度更新就被吞掉，界面只能等下一次通知才动 —— 表现为"进度偶尔卡住不动"。
        val album = awaitAlbum() ?: return
        val (rows, localSize, favoriteIds) = withContext(Dispatchers.IO) {
            val chapters = repo.chaptersOf(album)
            val rows = chapters.sortedBy { it.sort?.toIntOrNull() ?: Int.MAX_VALUE }.map { chapter ->
                val images = repo.imagesOf(album.id, chapter.chapterId)
                val folder = repo.chapterFolderName(chapter)
                val have = repo.downloadedImageCount(
                    album.albumFolderName,
                    folder,
                    images.map { it.imageFile },
                )
                ChapterRow(
                    chapterId = chapter.chapterId,
                    title = chapter.title,
                    sortIndex = chapter.sort?.toIntOrNull() ?: 0,
                    totalImages = images.size,
                    downloadedImages = have,
                )
            }
            Triple(rows, repo.localSize(album.albumFolderName), repo.favoriteIdsOf(albumId))
        }
        // 导出成品也要跟着重算：刚导出完回到这个页面，区块里必须马上多一行；
        // 在任务页删掉文件再回来，那一行也要消失（用户第九轮要求第 2 点）
        val exportedFiles = runCatching { exports.exportsOf(album) }.getOrDefault(emptyList())
        val folderUri = runCatching { exports.outputFolderUri() }.getOrNull()
        _state.update {
            it.copy(
                chapters = if (it.reversed) rows.reversed() else rows,
                totalChapters = rows.size,
                downloadedChapters = rows.count { row -> row.isDownloaded },
                localSizeBytes = localSize,
                favoriteIds = favoriteIds,
                exports = exportedFiles,
                canOpenFolder = folderUri != null,
            )
        }
    }

    /** 「打开文件夹」用的目录 URI；系统不支持时返回 null（界面据此提示）。 */
    fun outputFolderUri(): android.net.Uri? = runCatching { exports.outputFolderUri() }.getOrNull()

    /**
     * 成品的「打开至 / 发送至」要拿内容 Uri。
     *
     * 与任务页同一个入口：Uri 按文件名去系统下载目录里查，所以界面不用缓存它。
     */
    suspend fun outputUri(displayName: String): android.net.Uri? = exports.outputUri(displayName)

    /** 详情页的「删除」：删掉下载目录里的成品文件，漫画本身与图片都不动。 */
    fun deleteExport(displayName: String) {
        viewModelScope.launch {
            val ok = runCatching { exports.deleteOutput(displayName) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) "已删除导出文件" else "文件已经不在了")
            }
            loadLocalStats()
        }
    }

    fun toggleReversed() {
        _state.update { it.copy(reversed = !it.reversed, chapters = it.chapters.reversed()) }
    }

    fun toggleDescription() = _state.update { it.copy(descriptionExpanded = !it.descriptionExpanded) }

    // ------------------------------------------------------------------
    // 评论（简介下方的第二个 tab）
    // ------------------------------------------------------------------

    /**
     * 切 tab。**第一次切到「评论」时才去请求第一页**——大多数人进详情页是来读的，
     * 不该为一次"可能会看"的评论多打一次网络请求。
     */
    fun selectTab(tab: AlbumDetailTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        if (tab == AlbumDetailTab.COMMENTS) ensureCommentsLoaded()
    }

    /** 评论列表滑到底：追加下一页（已在拉 / 已到底 / 还没加载过时什么都不做）。 */
    fun loadMoreComments() {
        val current = _state.value
        if (!current.commentsLoaded || current.commentsReachedEnd) return
        if (current.commentsLoading || current.commentsLoadingMore) return
        fetchComments(page = commentNextPage, reset = false)
    }

    /**
     * 评论 tab 下拉刷新：从第一页重取。
     *
     * 成功后就以这一份为**新的缓存基准**（`fetchComments` 会覆盖缓存），
     * 所以这里不必先清缓存——刷新失败时反而要保住旧的，别把用户已经看到的评论弄丢。
     */
    fun refreshComments() {
        val current = _state.value
        if (current.commentsLoading || current.commentsLoadingMore) return
        fetchComments(page = 1, reset = true)
    }

    /**
     * 评论加载失败后的「重试」。
     *
     * 重试**走原来那条路**：上一次失败的是"刷新第一页"就还刷第一页，
     * 是"追加下一页"就还追加那一页。否则"下拉刷新失败 → 点重试"会变成追加第 N 页，
     * 用户看到的是列表末尾多了一段，而不是刷新。
     */
    fun retryComments() {
        val current = _state.value
        if (current.commentsLoading || current.commentsLoadingMore) return
        val reset = commentRetryReset || current.comments.isEmpty()
        fetchComments(page = if (reset) 1 else commentNextPage, reset = reset)
    }

    /** 还没加载过才发请求（缓存命中或已经拉过就直接用现有的）。 */
    private fun ensureCommentsLoaded() {
        val current = _state.value
        if (current.commentsLoaded || current.commentsLoading) return
        fetchComments(page = 1, reset = true)
    }

    /**
     * 拉一页评论。
     *
     * @param reset true = 用这一页**替换**整个列表（首次加载 / 下拉刷新）；
     *        false = 按 `cid` 去重后追加在后面（下滑追加）。
     *
     * 成功时立刻把结果写回 [CommentCache]（这样退出页面再进来不用重新拉）；
     * 失败时只记 `commentsError`，**不丢掉已经拿到的那些**——用户滑了很久的列表
     * 不该因为下一页超时被清空。
     */
    private fun fetchComments(page: Int, reset: Boolean) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    commentsLoading = reset,
                    commentsLoadingMore = !reset,
                    commentsError = null,
                )
            }
            runCatching { repo.comments(albumId, page) }
                .onSuccess { result ->
                    commentRetryReset = false
                    // 走 mergeComments 而不是直接用 result.comments：它顺手做了**按 cid 去重**，
                    // 万一服务端同一页里给了重复的评论，也不会让列表出现重复 key（会白屏崩）
                    var merged = if (reset) {
                        mergeComments(emptyList(), result.comments)
                    } else {
                        mergeComments(_state.value.comments, result.comments)
                    }
                    // 本地乐观插入（见 pendingNewComment 的注释）：只在"重拉第一页"时补一次。
                    // 服务端已经给了（cid 命中）就用服务端的，别多画一条。
                    var extra = 0
                    if (reset) {
                        pendingNewComment?.let { local ->
                            pendingNewComment = null
                            if (merged.none { it.cid == local.cid }) {
                                merged = listOf(local) + merged
                                extra = 1
                            }
                        }
                        pendingNewReply?.let { local ->
                            val parent = pendingNewReplyParent
                            pendingNewReply = null
                            pendingNewReplyParent = null
                            val index = merged.indexOfFirst { it.cid == parent }
                            if (index >= 0 && merged[index].replies.none { it.cid == local.cid }) {
                                merged = merged.toMutableList().also { list ->
                                    list[index] = list[index].copy(
                                        replies = list[index].replies + local,
                                    )
                                }
                            }
                        }
                    }
                    val total = result.total + extra
                    commentNextPage = result.page + 1
                    // 三种"到底"的判定：空页 / 这一页没给满 / 总条数已经够了。
                    // 空页那条不能省：`total` 有被截断（5000）的历史，出现空页就是真的没了。
                    val reachedEnd = result.comments.isEmpty() ||
                        result.comments.size < COMMENT_PAGE_SIZE ||
                        merged.size >= total
                    _state.update {
                        it.copy(
                            comments = merged,
                            commentTotal = total,
                            commentsLoaded = true,
                            commentsLoading = false,
                            commentsLoadingMore = false,
                            commentsError = null,
                            commentsReachedEnd = reachedEnd,
                        )
                    }
                    commentCache.put(
                        albumId,
                        CommentCache.Snapshot(
                            comments = merged,
                            total = total,
                            nextPage = commentNextPage,
                            reachedEnd = reachedEnd,
                        ),
                    )
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.tag("jm").w(e, "加载评论失败：albumId=%d page=%d", albumId, page)
                    commentRetryReset = reset
                    _state.update {
                        it.copy(
                            commentsLoading = false,
                            commentsLoadingMore = false,
                            commentsError = e.toUserMessage(),
                            // 失败也算"加载过"：否则切回章节再切回来会立刻再打一次同样的请求
                            commentsLoaded = true,
                        )
                    }
                }
        }
    }

    // ------------------------------------------------------------------
    // 发留言 / 回复（用户要求，2026-10-22）
    // ------------------------------------------------------------------

    /**
     * 输入框内容变化：**就地按码位截到 200 字**（官方输入框写死 `maxLength={200}`）。
     *
     * 截断放在这里而不是只在界面上：粘贴一长段时界面不会"先显示超长再被服务端拒"，
     * 用户看到的始终是最终会发出去的那段文字。
     */
    fun setCommentInput(value: String) = _state.update {
        it.copy(commentInput = limitCommentDraft(value))
    }

    /** 点某条留言的「回复」：输入条变成「回复 @昵称」，发送时带上它的 `cid`。 */
    fun replyToComment(comment: CommentRow) {
        if (comment.cid.isBlank()) return
        _state.update {
            it.copy(commentReplyTo = CommentReplyTarget(cid = comment.cid, author = comment.author))
        }
    }

    /** 取消回复（输入条上的 ✕），回到"发新留言"。 */
    fun cancelCommentReply() = _state.update { it.copy(commentReplyTo = null) }

    /** 关掉输入条里的那条发送提示（✕ 或自动消失时调用）。 */
    fun consumeCommentNotice() = _state.update { it.copy(commentNotice = null) }

    /**
     * 发送输入条里的内容（有回复目标就是回复）。
     *
     * 三种"不发请求"的情况都在本地拦掉：正在发、正文为空、未登录。
     * 成功/失败都走服务端的 `msg`（失败由 `AccountRepository` 抛成 `NetworkError.Business`，
     * 这里统一 `toUserMessage()`）。
     *
     * 成功之后**重拉第一页**（官方也是 `loadList(false, true)`），同时把刚发的这条记成
     * "待补的本地留言"（见 [pendingNewComment]）：服务端列表有延迟时，用户也能立刻看到
     * 自己刚发的那条，而不是以为发失败又发一遍。
     */
    fun sendComment() {
        val current = _state.value
        if (current.commentSending) return
        val error = commentDraftError(current.commentInput)
        if (error != null) {
            _state.update { it.copy(commentNotice = error, commentNoticeError = true) }
            return
        }
        if (!account.isSignedIn) {
            _state.update { it.copy(commentNotice = "请先登录后再留言", commentNoticeError = true) }
            return
        }
        val text = current.commentInput.trim()
        val parentCid = current.commentReplyTo?.cid
        _state.update { it.copy(commentSending = true, commentNotice = null) }
        viewModelScope.launch {
            runCatching { account.sendComment(aid = albumId, content = text, parentCid = parentCid) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            commentSending = false,
                            commentInput = "",
                            commentReplyTo = null,
                            // 服务端的成功文案比"已发布"信息量大（常带"获得 N 经验值"），
                            // 拿不到时才用兜底文案。放 commentNotice 而不是 message：
                            // 后者走底部 Snackbar，会盖住输入条。
                            commentNotice = result.message,
                            commentNoticeError = false,
                            // 让界面把列表滚回评论 tab 开头：新留言就在最上面
                            commentsRefreshToken = it.commentsRefreshToken + 1,
                        )
                    }
                    // 记住这条"还没出现在列表里"的新留言 / 回复，重拉回来后补进列表
                    result.cid?.let { cid -> rememberPendingComment(cid, text, parentCid) }
                    // 重新拉第一页：缓存里的那份已经过期（少一条新留言 / 回复），先清掉再拉
                    commentCache.clear(albumId)
                    fetchComments(page = 1, reset = true)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.tag("jm").w(e, "发留言失败：albumId=%d", albumId)
                    _state.update {
                        it.copy(
                            commentSending = false,
                            commentNotice = e.toUserMessage(),
                            commentNoticeError = true,
                        )
                    }
                }
        }
    }

    /**
     * 把刚发成功的留言/回复记成"待补的本地条目"（[pendingNewComment] / [pendingNewReply]）。
     *
     * 作者信息用**自己的**账号资料（接口不会立刻返回这条，拿不到就回落成"我"），
     * 时间用今天——与 [commentTimeLabel] 归一化出来的格式一致，不会有第二种样子。
     */
    private fun rememberPendingComment(cid: String, text: String, parentCid: String?) {
        val profile = account.profile
        val row = CommentRow(
            cid = cid,
            author = profile?.username?.takeIf { it.isNotBlank() } ?: "我",
            avatar = profile?.photo,
            text = text,
            timeLabel = LocalDate.now().toString(),
            uid = profile?.uid,
            albumId = albumId,
            albumName = "JM$albumId",
            parentCid = parentCid,
        )
        if (parentCid == null) {
            pendingNewComment = row
        } else {
            pendingNewReply = row
            pendingNewReplyParent = parentCid
        }
    }

    /**
     * 删除自己发的一条留言 / 回复（详情页评论 tab 里的「删除」）。
     *
     * 只有 `UID` 与当前账号一致时界面才会给这个入口（官方 `ForumList` 的判定相同），
     * 这里再校验一次——删除是不可撤销的，不指望界面那一层兜住。
     *
     * 成功后**就地**从列表里摘掉（主楼直接删、回复从父楼的 `replys` 里删），
     * 不清空整个列表：用户可能已经翻了好几页。同时把 `CommentCache` 清掉，
     * 下次进这一本时重新拉，避免缓存里还留着已删的那条。
     */
    fun deleteComment(comment: CommentRow) {
        val cid = comment.cid.trim()
        if (cid.isEmpty()) return
        val current = _state.value
        if (current.commentDeletingCid != null) return
        if (!account.isSignedIn) {
            _state.update {
                it.copy(commentNotice = "请先登录后再删除留言", commentNoticeError = true)
            }
            return
        }
        if (!comment.isAuthoredBy(current.accountUid)) {
            _state.update { it.copy(commentNotice = "只能删除自己发的留言", commentNoticeError = true) }
            return
        }
        _state.update { it.copy(commentDeletingCid = cid, commentNotice = null) }
        viewModelScope.launch {
            runCatching {
                account.deleteComment(commentId = cid, albumId = comment.albumId ?: albumId)
            }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            commentDeletingCid = null,
                            comments = removeComment(it.comments, cid),
                            commentTotal = (it.commentTotal - 1).coerceAtLeast(0),
                            commentNotice = result.message,
                            commentNoticeError = false,
                        )
                    }
                    commentCache.clear(albumId)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.tag("jm").w(e, "删除留言失败：cid=%s", cid)
                    _state.update {
                        it.copy(
                            commentDeletingCid = null,
                            commentNotice = e.toUserMessage(),
                            commentNoticeError = true,
                        )
                    }
                }
        }
    }

    /** 收藏/取消收藏。未入库时会先入库（收藏隐含入库，参考实现的 `collectAlbums` 语义）。 */
    fun toggleCollected() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val nowCollected = album.collected == true
            runCatching {
                if (nowCollected) {
                    repo.uncollect(albumId)
                } else {
                    if (!_state.value.inLibrary) ensureInLibrary()
                    repo.addToFavorite(albumId)
                }
            }.onSuccess {
                val refreshed = repo.findAlbum(albumId)
                _state.update {
                    it.copy(
                        album = refreshed ?: it.album,
                        inLibrary = refreshed != null,
                        message = if (nowCollected) "已取消收藏" else "已加入收藏",
                    )
                }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    // ---------------- 账号收藏（官方在线收藏） ----------------

    /** 本次详情页是否已经和服务端核对过收藏状态（只核对一次，避免重复请求）。 */
    private var favoriteFlagSynced = false

    /** 进页面时同步一次收藏状态：本进程核对过就直接用，没核对过才请求一次 `/album`。 */
    private fun syncAccountFavoriteOnce() {
        if (favoriteFlagSynced) return
        val cached = account.cachedFavoriteFlag(albumId)
        if (cached != null) {
            favoriteFlagSynced = true
            _state.update { it.copy(accountInFavorites = cached) }
            return
        }
        // 首屏正在联网拉详情：那次响应里就有 `is_favorite`（loadFromNetwork 会写回并置位），
        // 不要为同一本再发一次 `/album`。
        if (_state.value.album == null && _state.value.loading) return
        viewModelScope.launch {
            favoriteFlagSynced = true
            refreshAccountFavoriteFlag()
        }
    }

    /**
     * 向服务端核对一次 `is_favorite`（本机快照可能过期）。
     *
     * 结论会写回两处：`AccountCache`（本进程后续进页面直接用）与 `t_jm_album.is_favorite`
     * （重启后第一帧也是对的）。
     */
    private suspend fun refreshAccountFavoriteFlag() {
        val flag = repo.fetchAlbumFavoriteFlag(albumId) ?: return
        account.rememberFavoriteFlag(albumId, flag)
        repo.setAlbumFavoriteFlag(albumId, flag)
        _state.update { it.copy(accountInFavorites = flag) }
    }

    /**
     * 写操作成功后把"已收藏 / 已移出"落到两处快照：
     * `AccountCache`（本进程内后续进页面直接用）与 `t_jm_album.is_favorite`（重启后也对）。
     * 这样就不会再出现"先显示收藏、再翻成已收藏"。
     */
    private suspend fun persistFavoriteFlag(value: Boolean) {
        account.rememberFavoriteFlag(albumId, value)
        repo.setAlbumFavoriteFlag(albumId, value)
    }

    /**
     * 打开账号收藏面板。
     *
     * 收藏夹**优先用缓存**（`AccountCache.folders`，只有新建/删除夹才作废）；
     * 没有缓存时才拉一次，并在面板里显示**局部 loading**。
     */
    fun openAccountFavoriteSheet() {
        _state.update { it.copy(accountSheetOpen = true) }
        if (!account.isSignedIn) return

        val cachedFolders = account.cachedFolders()
        if (cachedFolders != null) {
            _state.update { it.copy(accountFolders = cachedFolders, accountFoldersLoading = false) }
        } else {
            _state.update { it.copy(accountFoldersLoading = true) }
            viewModelScope.launch {
                runCatching { account.folders(force = true) }
                    .onSuccess { list ->
                        _state.update { it.copy(accountFolders = list, accountFoldersLoading = false) }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        _state.update {
                            it.copy(accountFoldersLoading = false, message = e.toUserMessage())
                        }
                    }
            }
        }

        // 收藏状态：本进程核对过就直接用，不再请求
        val known = account.cachedFavoriteFlag(albumId)
        if (known != null) {
            _state.update { it.copy(accountInFavorites = known) }
        } else {
            viewModelScope.launch { refreshAccountFavoriteFlag() }
        }
    }

    fun dismissAccountFavoriteSheet() = _state.update { it.copy(accountSheetOpen = false) }

    /**
     * 面板里点了一行（`docs/account-design.md` §4.4 的状态机）。
     *
     * - 未收藏 · 点某个夹：`add` + `move`（两步；move 失败时如实提示"只加了收藏"）；
     * - 未收藏 · 点「全部收藏」：只 `add`；
     * - 已收藏 · 点某个夹：只 `move`；
     * - 已收藏 · 点「全部收藏」：`toggle` 一次 = 取消收藏。
     */
    fun chooseAccountFavorite(folderId: String?, folderName: String?) {
        if (_state.value.accountFavoriteBusy) return
        _state.update { it.copy(accountFavoriteBusy = true) }
        viewModelScope.launch {
            try {
                val wasIn = _state.value.accountInFavorites
                if (wasIn && folderId == null) {
                    account.toggleFavorite(albumId)
                    persistFavoriteFlag(false)
                    _state.update {
                        it.copy(
                            accountFavoriteBusy = false,
                            accountInFavorites = false,
                            accountSheetOpen = false,
                            message = "已从收藏移出",
                        )
                    }
                    return@launch
                }
                if (!wasIn) {
                    account.toggleFavorite(albumId)
                }
                if (folderId != null) {
                    try {
                        account.moveToFolder(albumId, folderId, folderName)
                        persistFavoriteFlag(true)
                        _state.update {
                            it.copy(
                                accountFavoriteBusy = false,
                                accountInFavorites = true,
                                accountSheetOpen = false,
                                message = if (wasIn) {
                                    "已移动到「${folderName ?: "收藏夹"}」"
                                } else {
                                    "已加入「${folderName ?: "收藏夹"}」"
                                },
                            )
                        }
                        return@launch
                    } catch (moveError: Throwable) {
                        if (moveError is CancellationException) throw moveError
                        persistFavoriteFlag(true)
                        _state.update {
                            it.copy(
                                accountFavoriteBusy = false,
                                accountInFavorites = true,
                                message = "已加入收藏，但归入收藏夹失败：${moveError.toUserMessage()}",
                            )
                        }
                        return@launch
                    }
                }
                persistFavoriteFlag(true)
                _state.update {
                    it.copy(
                        accountFavoriteBusy = false,
                        accountInFavorites = true,
                        accountSheetOpen = false,
                        message = "已加入收藏（未归类）",
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(accountFavoriteBusy = false, message = e.toUserMessage())
                }
            }
        }
    }

    /** 面板里「新建收藏夹并加入」：先建夹，再（必要时）加收藏，最后移入。 */
    fun createAccountFolderAndAdd(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = "请输入收藏夹名称") }
            return
        }
        if (_state.value.accountFavoriteBusy) return
        _state.update { it.copy(accountFavoriteBusy = true) }
        viewModelScope.launch {
            try {
                val folder = account.createFolder(trimmed)
                if (!_state.value.accountInFavorites) account.toggleFavorite(albumId)
                account.moveToFolder(albumId, folder.fid, folder.name)
                persistFavoriteFlag(true)
                _state.update {
                    it.copy(
                        accountFavoriteBusy = false,
                        accountInFavorites = true,
                        accountSheetOpen = false,
                        accountFolders = (it.accountFolders + folder).distinctBy { f -> f.fid },
                        // 消息里不带夹名：文字隐私下夹名只能露首字符，这里直接不显示，避免绕过遮蔽
                        message = "已加入收藏夹",
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(accountFavoriteBusy = false, message = e.toUserMessage())
                }
            }
        }
    }

    /** 移动到指定收藏夹（用户要求第 6 点：支持修改收藏夹）。 */
    fun moveToFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching {
                if (!_state.value.inLibrary) ensureInLibrary()
                // 先移出其它夹再入目标夹，语义是"移动到"而不是"同时属于多个"
                repo.favoriteIdsOf(albumId).forEach { old ->
                    if (old != favoriteId) repo.removeFromFavorite(albumId, old)
                }
                repo.addToFavorite(albumId, favoriteId)
            }.onSuccess {
                val name = _state.value.allFavorites.firstOrNull { it.id == favoriteId }?.name
                _state.update {
                    it.copy(
                        album = repo.findAlbum(albumId) ?: it.album,
                        inLibrary = true,
                        message = if (name != null) "已移动到「$name」" else "已移动收藏夹",
                    )
                }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun addToFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching {
                if (!_state.value.inLibrary) ensureInLibrary()
                repo.addToFavorite(albumId, favoriteId)
            }.onSuccess {
                val name = _state.value.allFavorites.firstOrNull { it.id == favoriteId }?.name
                _state.update {
                    it.copy(
                        album = repo.findAlbum(albumId) ?: it.album,
                        inLibrary = true,
                        message = if (name != null) "已加入「$name」" else "已加入收藏夹",
                    )
                }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun removeFromFavorite(favoriteId: Long) {
        viewModelScope.launch {
            runCatching { repo.removeFromFavorite(albumId, favoriteId) }
                .onSuccess {
                    val name = _state.value.allFavorites.firstOrNull { it.id == favoriteId }?.name
                    _state.update {
                        it.copy(message = if (name != null) "已移出「$name」" else "已移出收藏夹")
                    }
                    refreshLocalStats()
                }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun createFavoriteAndAdd(name: String) {
        viewModelScope.launch {
            val result = runCatching { repo.createFavorite(name) }
                .getOrDefault(FavoriteWriteResult.NameBlank)
            if (result != FavoriteWriteResult.Ok) {
                _state.update {
                    it.copy(
                        message = when (result) {
                            FavoriteWriteResult.NameTaken -> "已经有同名的收藏夹了"
                            FavoriteWriteResult.NameBlank -> "请输入收藏夹名称"
                            else -> "创建收藏夹失败"
                        },
                    )
                }
                return@launch
            }
            val created = repo.listFavorites().firstOrNull { it.name == name.trim() } ?: return@launch
            if (!_state.value.inLibrary) runCatching { ensureInLibrary() }
            repo.addToFavorite(albumId, created.id)
            _state.update {
                it.copy(album = repo.findAlbum(albumId) ?: it.album, inLibrary = true)
            }
            refreshLocalStats()
        }
    }

    /**
     * 下载选中的章节（计划 §9 第 7 条）。
     *
     * 下载的前置是"本子有书库记录"——目录名由书库记录决定，所以这里先确保入库，
     * 用户不必先点一次「加入书库」再回来下载。
     *
     * 已在队列里的章节会被过滤掉：面板里已经不让选，这里再兜一次，
     * 避免别处（例如阅读器的"补下缺图"）误传进来。
     */
    fun downloadChapters(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) {
            _state.update { it.copy(message = "请先选择要下载的章节") }
            return
        }
        viewModelScope.launch {
            val current = _state.value
            val usable = chapterIds.filterNot { it in current.queuedChapterIds }
            val selected = current.chapters.filter { it.chapterId in usable }
            if (selected.isEmpty()) {
                _state.update { it.copy(message = "选中的章节都已经在下载队列里了") }
                return@launch
            }
            runCatching {
                val album = ensureInLibrary()
                val summaries = repo.chaptersOf(album).filter { it.chapterId in usable }
                downloads.submit(album, summaries)
            }.onSuccess { result ->
                val text = when (result) {
                    is com.haruhi.jmcomic.core.download.DownloadTaskRepository.SubmitResult.Created ->
                        "已加入下载队列（${selected.size} 话），可在「任务」里查看进度"

                    is com.haruhi.jmcomic.core.download.DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已有下载任务，已把选中的 ${selected.size} 话加进去"
                }
                _state.update { it.copy(message = text) }
                refreshLocalStats()
            }.onFailure { e ->
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    /**
     * 下载官方 ZIP（官方 App 的整话/整部打包）。
     *
     * 与普通图片下载完全分开：一个本子一个 `JM_OFFICIAL_ZIP` 任务，落到
     * `Download/JMComicH/官方zip/<本子>/第N话.zip`；多本并发由设置里的
     * 「同时下载官方 ZIP 数量」控制。必须先登录，匿名拿不到下载链接。
     */
    fun downloadOfficialZip(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) {
            _state.update { it.copy(message = "请先选择要下载官方 ZIP 的章节") }
            return
        }
        if (!account.isSignedIn) {
            _state.update { it.copy(message = "官方 ZIP 需要先登录账号") }
            return
        }
        viewModelScope.launch {
            val current = _state.value
            val usable = chapterIds.filterNot { it in current.officialZipQueuedChapterIds }
            val selected = current.chapters.filter { it.chapterId in usable }
            if (selected.isEmpty()) {
                _state.update { it.copy(message = "选中的章节都已经在官方 ZIP 队列里了") }
                return@launch
            }
            runCatching {
                val album = ensureInLibrary()
                val summaries = repo.chaptersOf(album).filter { it.chapterId in usable }
                downloads.submitOfficialZip(album, summaries)
            }.onSuccess { result ->
                val text = when (result) {
                    is com.haruhi.jmcomic.core.download.DownloadTaskRepository.SubmitResult.Created ->
                        "已加入官方 ZIP 队列（${selected.size} 话），可在「任务」里查看进度"

                    is com.haruhi.jmcomic.core.download.DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已有官方 ZIP 任务，已把选中的 ${selected.size} 话加进去"
                }
                _state.update { it.copy(message = text) }
            }.onFailure { e ->
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    /** 删除某一话的本地文件（保留章节清单与任务记录）。 */
    fun deleteChapterFiles(chapterId: Long) {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val folder = album.albumFolderName
            if (folder.isNullOrBlank()) return@launch
            val chapter = _state.value.chapters.firstOrNull { it.chapterId == chapterId } ?: return@launch
            runCatching { repo.deleteChapterFiles(album.id, chapterId, folder, chapter.title) }
                .onSuccess { freed ->
                    _state.update { it.copy(message = "已删除这一话的本地文件（${formatBytes(freed)}）") }
                    refreshLocalStats()
                }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------
    // 导出（P4）
    // ------------------------------------------------------------------

    /**
     * 用户点了「导出 ZIP / PDF」。
     *
     * 先做**缺图预检**：缺图时不静默导出残缺内容（计划 §9 第 7 条），
     * 而是列出缺哪些话、缺多少张，让用户先去补下载。
     * **完全没下载过的话也算缺**（连图片清单都没有，导出包里会是空的），
     * 见 `ExportManager.precheck` 的注释。
     */
    fun startExport(format: ExportFormat) {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val precheck = runCatching { exports.precheck(album) }
                .getOrElse { e ->
                    _state.update { it.copy(message = e.toUserMessage()) }
                    return@launch
                }
            if (!precheck.isComplete) {
                _state.update { it.copy(exportPrecheck = precheck) }
                return@launch
            }
            // 同名成品已存在时提醒"会覆盖"（用户要求第 5 点）
            val exists = runCatching { exports.outputExists(album, format) }.getOrDefault(false)
            _state.update {
                it.copy(
                    exportFormat = format,
                    exportDefaultPassword = defaultPasswordFor(format),
                    exportTargetExists = exists,
                )
            }
        }
    }

    /** 缺图对话框里点「先补下载」：把缺图的那些话排进下载队列。 */
    fun downloadMissingChapters() {
        val album = _state.value.album ?: return
        val missing = _state.value.exportPrecheck?.missingChapters?.map { it.title }.orEmpty()
        _state.update { it.copy(exportPrecheck = null) }
        viewModelScope.launch {
            val chapters = runCatching { repo.chaptersOf(album) }
                .getOrDefault(emptyList())
                .filter { it.title in missing }
            if (chapters.isEmpty()) return@launch
            runCatching {
                val library = ensureInLibrary()
                downloads.submit(library, chapters)
            }.onSuccess {
                _state.update { it.copy(message = "已把缺图的 ${chapters.size} 话加入下载队列") }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    fun dismissExportPrecheck() = _state.update { it.copy(exportPrecheck = null) }

    /** 密码框里点「确认」（密码留空等同「不设密码」）。 */
    fun confirmExport(password: String) {
        val format = _state.value.exportFormat ?: return
        _state.update { it.copy(exportFormat = null, exportTargetExists = false) }
        viewModelScope.launch {
            runCatching {
                val library = ensureInLibrary()
                exports.submit(library, format, password.trim().ifEmpty { null })
            }.onSuccess { result ->
                val text = when (result) {
                    is DownloadTaskRepository.SubmitResult.Created ->
                        "已开始导出 ${format.label}，可在「任务」里看进度"

                    is DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已经有一个在跑的 ${format.label} 导出"
                }
                _state.update { it.copy(message = text) }
                refreshLocalStats()
            }.onFailure { e -> _state.update { it.copy(message = e.toUserMessage()) } }
        }
    }

    /** 密码框里点「不设密码」。 */
    fun exportWithoutPassword() = confirmExport("")

    fun cancelExport() = _state.update { it.copy(exportFormat = null, exportTargetExists = false) }

    /**
     * 导出密码框的**预填值** = 设置页那个「默认密码」的原文（凭据存储里读出来的）。
     *
     * **设置里没设过或者被清空 = 预填空串**（用户 2026-10-15 反馈的 bug：原来这里会兜底成
     * 参考实现的 `Defaults.DEFAULT_EXPORT_PASSWORD`，于是"设置里明明留空、点导出却又冒出个 1234"）。
     *
     * 不能兜底还有一个结构上的原因：`SecretStore.put(key, "")` 是**删掉这一项**
     * （见该类的注释），所以"用户清空过"和"从来没设过"在存储里是同一个状态，分不出来。
     *
     * 设置页显示的也是同一个值（`SettingsViewModel.reload` 里同样只 `orEmpty()` 不兜底），
     * 两边必须一致，否则又会变成"设置里看到的和导出时预填的不是一个值"。
     */
    private fun defaultPasswordFor(format: ExportFormat): String {
        val key = when (format) {
            ExportFormat.ZIP -> SettingKeys.SECRET_JM_ZIP_PASSWORD
            ExportFormat.PDF -> SettingKeys.SECRET_JM_PDF_PASSWORD
        }
        return secrets.get(key).orEmpty()
    }

    /**
     * 「删除本地文件」：图片 + 导出成品 + 导出半成品 + 任务行，**保留书库记录与封面**。
     *
     * 在跑的任务会先被停掉（下载落 `PAUSED`、导出停 Job）再删任务行；
     * 导出成品放在系统下载目录，容易漏删，所以统一交给 [AlbumCleaner] 编排。
     */
    fun deleteAlbumFiles() {
        viewModelScope.launch {
            val album = _state.value.album ?: return@launch
            val result = runCatching { cleaner.deleteFiles(listOf(album)) }.getOrNull()
            refreshLocalStats()
            val text = if (result != null && result.removedExports > 0) {
                "已删除本地文件（含 ${result.removedExports} 个导出成品）"
            } else {
                "已删除本地文件"
            }
            _state.update { it.copy(message = text) }
        }
    }

    /**
     * 「从书库移除」：记录 + 本地图片 + 任务行 + 半成品全清，**导出成品保留**（用户本轮拍板）。
     *
     * 与「删除本地文件」的差别只在于**要不要连记录一起删**。
     * **最近浏览记录保留**（用户要求第 5 点：移出书库不影响"刚看过什么"），
     * 所以移除后"最近"页仍然能看到这本、点进去还能再次加入书库。
     *
     * 移完之后**页面留在原地**（用户要求：退不退由用户自己按返回键决定）：
     * 这里只把 `inLibrary` 翻成 false、报一句提示，不通知上层做任何跳转——
     * 界面随即变成"未入库 + 已下载 0 话"，用户可以再点「未入库」加回来。
     *
     * ## 为什么删除跑在 [appScope] 而不是 `viewModelScope`（用户要求）
     *
     * 一本本子要删**六处**痕迹：图片目录、导出半成品、任务行、封面缓存、五张表……
     * 上千张图删起来不是一瞬间的事。以前"删完自动返回上一页"顺带掩盖了取消问题；
     * 现在返回由用户自己按，他完全可能删到一半就退出去 —— 那时导航 entry 被销毁、
     * `viewModelScope` 连同还在跑的删除一起取消，结果是**删了一半**：
     * 目录空了但书库记录还在（或者反过来），下次进来看到一本"下载过但一话都没有"的僵尸本。
     *
     * `appScope` 是应用级作用域（下载、导出也挂在它上面，见 `AppContainer.appScope`），
     * 进程活着就一定把删除跑完，与页面在不在无关。协程里照旧写 `_state`：
     * 页面还在就顺手把提示和本地统计刷新掉；页面已经退出的话没人观察 `_state`，写进去也无害。
     */
    fun removeFromLibrary() {
        val album = _state.value.album ?: return
        // 不在书库里就不再走一遍（按钮与菜单项也会跟着 inLibrary 一起失效，
        // 这里只是兜住"确认框还开着时状态被别处改掉"这种情况）
        if (!_state.value.inLibrary) return
        // 先让按钮翻成「未入库」：删除要跑一会儿，别让用户对着「已入库」干等。
        // 提示语等真删完再报，免得"已删除"说出口时其实还没删完。
        _state.update { it.copy(inLibrary = false) }
        appScope.launch {
            runCatching { cleaner.removeFromLibrary(listOf(album)) }
                .onFailure { Timber.tag("jm").w(it, "移出书库失败：%s", album.id) }
            _state.update {
                it.copy(message = "已从书库移除，本地文件已删除（最近浏览记录保留）")
            }
            // 文件都删了，本页的"已下载 x/y 话"和占用体积必须跟着归零。
            // loadLocalStats 自己会切 IO、只读写 _state，不碰 viewModelScope，所以在这里调用是安全的。
            loadLocalStats()
        }
    }

    companion object {
        /**
         * `/forum` 每页固定 10 条（实测）。
         *
         * 拿它判断"到底了"：某一页**没给满** 10 条就说明后面没有了，
         * 不必等下一页的空响应再收尾（省一次请求、也让最后一屏不闪一下"加载中"）。
         */
        const val COMMENT_PAGE_SIZE = 10
    }
}

/** 把异常翻译成可理解文案：实现已移到 `core/network`（下载执行器也要用），这里只做转发注释。 */
// 详见 core/network/UserMessage.kt

