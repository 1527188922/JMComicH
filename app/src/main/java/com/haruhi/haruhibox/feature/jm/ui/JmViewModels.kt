package com.haruhi.haruhibox.feature.jm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.download.DownloadTaskRepository
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.core.network.NetworkError
import com.haruhi.haruhibox.core.network.toUserMessage
import com.haruhi.haruhibox.core.settings.SecretStore
import com.haruhi.haruhibox.core.settings.SettingKeys
import com.haruhi.haruhibox.feature.jm.data.ExportedFormat
import com.haruhi.haruhibox.feature.jm.data.FavoriteWriteResult
import com.haruhi.haruhibox.feature.jm.data.JmAlbumCleaner
import com.haruhi.haruhibox.feature.jm.data.JmDownloadManager
import com.haruhi.haruhibox.feature.jm.data.JmExportFormat
import com.haruhi.haruhibox.feature.jm.data.JmExportManager
import com.haruhi.haruhibox.feature.jm.data.JmReadingRepository
import com.haruhi.haruhibox.feature.jm.data.JmRepository
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchItem
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchResult
import com.haruhi.haruhibox.feature.jm.data.ReadingPosition
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
    formats: Set<JmExportFormat>?,
    hasZipOnly: Boolean,
    hasPdfOnly: Boolean,
): Boolean {
    if (!hasZipOnly && !hasPdfOnly) return true
    if (formats == null) return true
    if (hasZipOnly && JmExportFormat.ZIP !in formats) return false
    if (hasPdfOnly && JmExportFormat.PDF !in formats) return false
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
    firstPage: List<JmAlbumEntity>,
    extraPages: List<JmAlbumEntity>,
): List<JmAlbumEntity> {
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
    val favorites: List<JmFavoriteEntity> = emptyList(),
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
    val album: JmAlbumEntity,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val localSizeBytes: Long,
) {
    val displayName: String get() = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"
}

class JmLibraryViewModel(
    private val repo: JmRepository,
    private val reading: JmReadingRepository,
    private val cleaner: JmAlbumCleaner,
    private val exports: JmExportManager,
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
        val extra: List<JmAlbumEntity> = emptyList(),
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** 已经查到最后一页（再问一次数据库也不会多出条目）。 */
        val endReached: Boolean = false,
    )

    private data class ExtraState(
        val summaries: Map<Long, ChapterStat> = emptyMap(),
        val loadingStat: Boolean = false,
        val continueReading: ContinueReadingCard? = null,
        val favorites: List<JmFavoriteEntity> = emptyList(),
        /**
         * 本子 id → 已经导出过哪几种格式（用户第九轮要求第 1 点）。
         *
         * 成品在系统下载目录里，数据库里没有记录，所以这是一份**磁盘快照**，
         * 跟着 [refreshStats] 一起刷新（进页面、切筛选、从任务页回来都会重算）。
         * 还没查过的本子不在 map 里，此时筛选一律放行（见 [matchesExportFilter]）。
         */
        val exportFormats: Map<Long, Set<JmExportFormat>> = emptyMap(),
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
     * - **筛选之后显示不足一屏**时自动连翻（见 `JmLibraryScreen` 的自动填充）——
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

    private suspend fun buildStat(album: JmAlbumEntity): ChapterStat = withContext(Dispatchers.IO) {
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

    private suspend fun albumsByIds(ids: List<Long>): List<JmAlbumEntity> =
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
    val sort: JmSearchSort = JmSearchSort.DEFAULT,
    val result: OnlineSearchResult? = null,
    val history: List<JmSearchHistoryEntity> = emptyList(),
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
    val sort: JmSearchSort,
    val historyId: Long = 0L,
)

/**
 * 在线搜索。
 *
 * 一个实例只服务一种页面（用户要求把"搜索记录"和"搜索结果"拆成独立两页）：
 * - 发现页（`entry == null`）：输入框、排序、搜索历史，**本页不发搜索请求**；
 * - 结果页（`entry != null`）：拿路由参数进来，进页面就搜（或回显历史快照）。
 */
class JmOnlineSearchViewModel(
    private val repo: JmRepository,
    private val cleaner: JmAlbumCleaner,
    private val entry: OnlineSearchEntry? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnlineSearchUiState(
            keyword = entry?.keyword.orEmpty(),
            sort = entry?.sort ?: JmSearchSort.DEFAULT,
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
    fun onSortChange(sort: JmSearchSort) {
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
    fun changeSort(sort: JmSearchSort) {
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
     * [JmRepository.libraryAddNeedsNetwork] 问一句——它和 `addToLibraryLocalFirst`
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
     * 与详情页/书库页的「从书库移除」走同一条编排 [JmAlbumCleaner.removeFromLibrary]：
     * 记录 + 章节清单 + 收藏关联 + 阅读位置 + 本地图片 + 任务行 + 导出半成品全清，
     * **导出成品保留**（用户本轮拍板）；
     * **最近浏览记录与封面保留**（用户第八轮要求第 5 点），所以移出之后这张卡
     * 还能点进详情页看、也能再点一次「加入书库」回来。
     *
     * 破坏性操作由 UI 先弹一次确认框（`JmSearchResultsScreen` 的 `ConfirmDialog`），
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
 * 数量与封面来自 [JmRepository.observeFavoritesWithCount] 的 **Flow**：
 * 之前用一次性查询，收藏之后回到收藏页数量不更新（用户反馈第 1 点）。
 * 改成 Flow 后，关联表任何变动（包括封面对应的"最近加入的那本"）都会自动重发。
 */
class JmFavoritesViewModel(private val repo: JmRepository) : ViewModel() {

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
 * 删除走 [JmAlbumCleaner]：删记录的同时要判断"这本还在不在书库里"，
 * 不在的话它的封面缓存也就没用了（用户第九轮要求第 3 点）。
 */
class JmRecentViewModel(
    private val repo: JmRepository,
    private val cleaner: JmAlbumCleaner,
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
            isDownloaded -> "已下载"
            isPartial -> "部分下载 $downloadedImages/$totalImages"
            else -> "未下载"
        }
}

data class AlbumDetailUiState(
    val album: JmAlbumEntity? = null,
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
    val allFavorites: List<JmFavoriteEntity> = emptyList(),
    /**
     * **已经在下载队列里**的章节 id（用户要求第 3 点）。
     *
     * 取这个本子当前活跃任务的章节明细：排队、下载中、暂停、等待网络、失败的都算
     * ——"已经在队列里"本身就是不让重复提交的理由，与它跑到哪一步无关。
     */
    val queuedChapterIds: Set<Long> = emptySet(),

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
    val exportFormat: JmExportFormat? = null,
    /** 该格式的默认密码（来自设置/凭据存储），密码框预填它。 */
    val exportDefaultPassword: String = "",
    /**
     * 目标文件（系统下载目录里的同名 zip/pdf）**已经存在**。
     *
     * 再次生成会直接覆盖，所以在密码框里用醒目颜色提醒一句（用户要求第 5 点）。
     */
    val exportTargetExists: Boolean = false,
    /** 缺图预检结果；非 null 时展示"缺图"对话框而不是密码框。 */
    val exportPrecheck: JmExportManager.Precheck? = null,
    /**
     * 这本漫画**已经导出到系统下载目录**的成品，带大小与生成时间
     * （用户第九轮要求第 2 点）。空列表 = 还没导出过，此时详情页不显示这个区块。
     */
    val exports: List<ExportedFormat> = emptyList(),

    /**
     * **正在跑**的导出任务（用户第十一轮要求第 3 点）。
     *
     * 点完「导出 ZIP/PDF」立刻就能在折叠栏里看到"排队中 / 导出中 45%"，
     * 不用去任务页确认；跑完之后这一行消失、成品行出现（见 `JmAlbumDetailViewModel`）。
     */
    val activeExports: List<ActiveExport> = emptyList(),

    /**
     * 系统能不能打开成品所在的文件夹（用户反馈第 2 点）。
     *
     * 公共下载目录（API 29+）可以，老系统上成品在应用私有目录里、别人的文件管理器看不到，
     * 这时为 false，界面不显示「打开文件夹」。
     */
    val canOpenFolder: Boolean = false,
) {
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
}

/**
 * 一次正在跑的导出（详情页折叠栏里的"导出中"那一行）。
 *
 * [progress] 是 0..1；导出任务的总量是"图片张数"，未开始时为 0（显示成不确定进度）。
 */
data class ActiveExport(
    val format: JmExportFormat,
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
 * 2. 都没有才请求 `/album`，成功后**只更新基础数据缓存**（[JmRepository.fetchAlbumDetail]）；
 * 3. 「刷新」按钮：同样只更新基础数据缓存——它不改浏览时间，
 *    书库列表与详情页因为都拼这张表，看到的也是新内容。
 *
 * ## 浏览时间（用户要求第 3、4 点）
 *
 * 进入详情页时调 [JmRepository.touchRecent]，**无论从搜索、书库还是收藏夹进来**都走这里，
 * 所以三种入口的浏览时间都会更新；刷新不会再动它。
 *
 * ## 点详情 ≠ 加入书库（用户要求第 2 点）
 *
 * 构造时只 [JmRepository.touchRecent]（最近预览），不写书库成员表。
 * 入书库只发生在用户点「加入书库」时。
 */
class JmAlbumDetailViewModel(
    private val repo: JmRepository,
    private val downloads: JmDownloadManager,
    private val exports: JmExportManager,
    private val tasks: DownloadTaskRepository,
    private val secrets: SecretStore,
    private val cleaner: JmAlbumCleaner,
    private val reading: JmReadingRepository,
    /**
     * 应用级协程作用域（`AppContainer.appScope`）。
     *
     * 只给「移出书库」这种**必须活过本页**的删除用：移出书库不再自动返回上一页，
     * 用户完全可能删到一半就自己按返回，挂在 `viewModelScope` 上会随导航 entry 一起取消。
     */
    private val appScope: CoroutineScope,
    private val albumId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

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
                    it.copy(album = local.album, inLibrary = local.fromLibrary, loading = false)
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
                        _state.update { it.copy(album = album, inLibrary = true) }
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
        JmExportFormat.entries.forEach { format ->
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
     * 最近预览的浏览时间只由进入页面时写（[JmRepository.touchRecent]）。
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
                    it.copy(refreshing = false, loading = false, album = album ?: it.album, inLibrary = inLibrary)
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
    private suspend fun ensureInLibrary(): JmAlbumEntity {
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
    private suspend fun awaitAlbum(): JmAlbumEntity? {
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
                    is com.haruhi.haruhibox.core.download.DownloadTaskRepository.SubmitResult.Created ->
                        "已加入下载队列（${selected.size} 话），可在「任务」里查看进度"

                    is com.haruhi.haruhibox.core.download.DownloadTaskRepository.SubmitResult.AlreadyRunning ->
                        "这本漫画已有下载任务，已把选中的 ${selected.size} 话加进去"
                }
                _state.update { it.copy(message = text) }
                refreshLocalStats()
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
            runCatching { repo.deleteChapterFiles(folder, chapter.title) }
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
     * 见 `JmExportManager.precheck` 的注释。
     */
    fun startExport(format: JmExportFormat) {
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

    /** 默认密码来自凭据存储；没设过就用参考实现的默认值。 */
    private fun defaultPasswordFor(format: JmExportFormat): String {
        val key = when (format) {
            JmExportFormat.ZIP -> SettingKeys.SECRET_JM_ZIP_PASSWORD
            JmExportFormat.PDF -> SettingKeys.SECRET_JM_PDF_PASSWORD
        }
        return secrets.get(key)?.takeIf { it.isNotEmpty() } ?: JmDefaults.DEFAULT_EXPORT_PASSWORD
    }

    /**
     * 「删除本地文件」：图片 + 导出成品 + 导出半成品 + 任务行，**保留书库记录与封面**。
     *
     * 在跑的任务会先被停掉（下载落 `PAUSED`、导出停 Job）再删任务行；
     * 导出成品放在系统下载目录，容易漏删，所以统一交给 [JmAlbumCleaner] 编排。
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
}

/** 把异常翻译成可理解文案：实现已移到 `core/network`（下载执行器也要用），这里只做转发注释。 */
// 详见 core/network/UserMessage.kt

