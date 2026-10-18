package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.AlbumDao
import com.haruhi.jmcomic.core.database.ChapterImageDao
import com.haruhi.jmcomic.core.database.FavoriteDao
import com.haruhi.jmcomic.core.database.RecentAlbumDao
import com.haruhi.jmcomic.core.database.SearchHistoryDao
import com.haruhi.jmcomic.core.database.PlaybackPositionDao
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteAlbumEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteEntity
import com.haruhi.jmcomic.core.database.entity.LibraryEntity
import com.haruhi.jmcomic.core.database.entity.PlaybackKind
import com.haruhi.jmcomic.core.database.entity.RecentAlbumEntity
import com.haruhi.jmcomic.core.database.entity.SearchHistoryEntity
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.protocol.SearchSort
import com.haruhi.jmcomic.core.storage.FileNaming
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.AlbumDto
import com.haruhi.jmcomic.feature.api.ApiClient
import com.haruhi.jmcomic.feature.api.ChapterDto
import com.haruhi.jmcomic.feature.api.ApiJson
import com.haruhi.jmcomic.feature.api.ForumItemDto
import com.haruhi.jmcomic.feature.api.SearchItemDto
import com.haruhi.jmcomic.feature.api.SeriesDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JM 仓库：在线数据 ↔ 本地库。
 *
 * 语义逐条对齐参考实现 `JmcomicSqliteServiceImpl`，这三条最容易写错，单独说明：
 *
 * 1. **入库是"全量覆盖但保留本地收藏"**。参考实现 `saveOrUpdateAlbum` 的做法是
 *    `deleteById` + `insert`，并在覆盖前把旧的 `collected` 带过来
 *    （它注释写明："collected 是本地收藏标记，JM 响应里没有这个字段…否则会丢掉收藏状态"）。
 *    本工程用 `insert(IGNORE)` / `update` 达到同样的效果，但**不删除行**——
 *    行里还有本地字段，删了再插会连带丢掉。
 *
 * 2. **章节列表的权威来源是 `album.series`**。参考实现 `listChapters` 先看
 *    `album.series` 是否为空：非空就用它；为空才回落到章节图片表。
 *    **不能只从已下载的图片反推章节**（计划 §D.1 的高优先级项），
 *    否则没下载过的本子在详情页会显示"没有章节"。
 *
 * 3. **章节图片入库是"整章替换"**。参考实现先按 `(albumId, chapterId)` 删除再插入，
 *    并把图片按 `sortImageFiles` 排序后写 `imageSort = i + 1`（从 1 开始，不是 0）。
 *
 * 另外：`chapterTitle` 取 `series.title`（参考实现 `toChapterImageEntities`），
 * 但 `Series.title` 在 `/album` 响应里通常为空、由 service 层补 `第{sort}话`。
 * 所以入库时也要做同样的补全，否则章节文件夹名会退化成 chapterId
 * （`getChapterFolderName` 的顺序是 title → name → id）。
 */
class Repository(
    private val api: ApiClient,
    private val albumDao: AlbumDao,
    private val chapterDao: ChapterImageDao,
    private val favoriteDao: FavoriteDao,
    private val recentDao: RecentAlbumDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val dirs: LibraryDirs,
    /** 阅读位置表：从书库移除时要把这本的阅读记录一起删掉。 */
    private val positionDao: PlaybackPositionDao? = null,
    private val searchHistoryLimit: () -> Int = { Defaults.SEARCH_HISTORY_LIMIT },
    private val saveSearchHistory: () -> Boolean = { true },
    private val albumNameMaxBytes: () -> Int = { FileNaming.DEFAULT_ALBUM_NAME_MAX_BYTES },
) {

    // ------------------------------------------------------------------
    // 在线搜索
    // ------------------------------------------------------------------

    /**
     * 在线搜索。结果里每条都带 `existsLocal`（是否已入库），
     * 对应参考实现 `onlineSearch` 的"补上是否已入库标识"。
     *
     * 搜完会记一条搜索历史（可在设置里关闭）。
     */
    suspend fun searchOnline(
        query: String,
        sort: SearchSort = SearchSort.DEFAULT,
        page: Int = 1,
    ): OnlineSearchResult = searchOnlineInternal(query, sort, page, recordHistory = true)

    private suspend fun searchOnlineInternal(
        query: String,
        sort: SearchSort,
        page: Int,
        recordHistory: Boolean,
    ): OnlineSearchResult {
        val resp = api.search(query, sort, page)
        val items = resp.content.orEmpty()
        val ids = items.mapNotNull { it.id?.toLongOrNull() }
        val localIds = if (ids.isEmpty()) emptySet() else albumDao.existingIds(ids).toSet()

        val total = resp.total?.toLongOrNull() ?: items.size.toLong()
        val pageSize = Defaults.SEARCH_PAGE_SIZE
        val result = OnlineSearchResult(
            query = query.trim(),
            sort = sort,
            page = page,
            total = total,
            pageSize = pageSize,
            totalPages = ((total + pageSize - 1) / pageSize).toInt().coerceAtLeast(1),
            items = items.mapNotNull { it.toItem(localIds) },
        )
        if (recordHistory) recordSearchHistory(result)
        return result
    }

    /**
     * 用户要求第 3 点：关键词是**纯数字**时，同时查 `/search` 与 `/album`。
     *
     * 纯数字多数是直接贴 JM 号，但搜索接口不一定能命中（标题里不含这个数字），
     * 而 `/album` 一定能查到。所以两边都发一次：
     * - `/album` 成功 → 把详情**包装成一条搜索结果**放在最前面（标题后面标"精确匹配"）；
     * - 搜索没结果但详情有 → 最终就只有这一条；
     * - 两边都失败 → 按搜索的失败原因报错（详情失败不报错，它本来就是"试试看"）。
     *
     * 详情数据只写进**基础数据表**（永久缓存），点进详情页时不用再联网；
     * 但**不写最近预览**——请求接口不等于加入最近预览（用户要求，两者解耦）。
     *
     * 搜索历史记的是**合并之后**的结果（用户要求第 6 点①）：
     * 记合并前的搜索结果的话，点历史记录回显时就看不到置顶的"精确匹配"那一条。
     */
    suspend fun searchOnlineByExactId(
        query: String,
        sort: SearchSort = SearchSort.DEFAULT,
        page: Int = 1,
    ): OnlineSearchResult {
        val exactId = query.trim().toLongOrNull()?.takeIf { it > 0 }
        // 纯数字要等"精确匹配"合并完再记历史，所以这里先不记
        val base = searchOnlineInternal(query, sort, page, recordHistory = exactId == null)
        if (exactId == null) return base

        val dto = runCatching { api.album(exactId) }
            .onSuccess { cacheAlbum(it) }
            .getOrNull()
            // 详情查不到就按普通搜索结果收尾（历史仍然要记一条，和以前一样）
            ?: return base.also { recordSearchHistory(it) }

        // 作者在 `/album` 里是字符串数组，直接编码会显示成 ["作者1","作者2"]（用户要求第 6 点②）。
        // 搜索结果的 author 是普通字符串，这里按详情页的风格拼成一行。
        val author = dto.author
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")

        val pinned = OnlineSearchItem(
            id = exactId,
            name = dto.name,
            author = author,
            description = dto.description,
            coverUrl = Defaults.buildCoverUrl(exactId),
            tags = dto.tags.orEmpty(),
            updateAt = null,
            // "已入库"看的是书库成员关系，不是基础数据缓存
            existsLocal = albumDao.existingIds(listOf(exactId)).isNotEmpty(),
            isExactIdMatch = true,
            raw = SearchItemDto(
                id = exactId.toString(),
                name = dto.name,
                author = author,
                description = dto.description,
            ),
        )

        val merged = mergeExactIdMatch(base, pinned)
        recordSearchHistory(merged)
        return merged
    }

    private suspend fun recordSearchHistory(result: OnlineSearchResult) {
        if (!saveSearchHistory()) return
        val limit = searchHistoryLimit()
        if (limit <= 0) return
        runCatching {
            val keyword = result.query
            val sortCode = result.sort.code
            val existing = searchHistoryDao.findSameCondition(keyword, sortCode, result.page)
            val itemsJson = ApiJson.encodeToString(
                ListSerializer(SearchItemDto.serializer()),
                result.items.map { it.raw },
            )
            val now = System.currentTimeMillis()
            if (existing != null) {
                // 相同条件只刷新时间，避免同一条件反复搜索刷屏（参考实现语义）
                searchHistoryDao.refresh(existing.id, now, result.total, result.items.size, itemsJson)
            } else {
                searchHistoryDao.insert(
                    SearchHistoryEntity(
                        keyword = keyword,
                        sort = sortCode,
                        page = result.page,
                        total = result.total,
                        resultCount = result.items.size,
                        items = itemsJson,
                    ),
                )
            }
            searchHistoryDao.trim(limit)
        }.onFailure { Timber.tag("jm").w(it, "保存搜索历史失败（不影响搜索）") }
    }

    fun observeSearchHistory(limit: Int = searchHistoryLimit()): Flow<List<SearchHistoryEntity>> =
        searchHistoryDao.observeRecent(limit)

    suspend fun deleteSearchHistory(id: Long) = searchHistoryDao.deleteById(id)

    suspend fun clearSearchHistory(): Int = searchHistoryDao.clear()

    suspend fun searchHistoryCount(): Int = searchHistoryDao.count()

    /**
     * 点历史记录时直接回显快照，不再请求 JM（参考实现就是这么做的），
     * 但"是否已入库"按**当前**库状态重新计算，避免显示过期状态。
     *
     * 纯数字搜索的那条"精确匹配"也要跟着回显（用户要求第 6 点①）：
     * 它就在快照里，这里按"条目 id = 数字关键字"的规则把标记还原出来，
     * 回显时同样带上"精确匹配"标签。
     */
    suspend fun searchHistorySnapshot(id: Long): OnlineSearchResult? {
        val row = searchHistoryDao.findById(id) ?: return null
        val raw = row.items?.takeIf { it.isNotBlank() }?.let { json ->
            runCatching {
                ApiJson.decodeFromString(ListSerializer(SearchItemDto.serializer()), json)
            }.getOrNull()
        }.orEmpty()
        val ids = raw.mapNotNull { it.id?.toLongOrNull() }
        val localIds = if (ids.isEmpty()) emptySet() else albumDao.existingIds(ids).toSet()
        val exactId = row.keyword.trim().toLongOrNull()
        return OnlineSearchResult(
            query = row.keyword,
            sort = SearchSort.fromCode(row.sort),
            page = row.page,
            total = row.total,
            pageSize = Defaults.SEARCH_PAGE_SIZE,
            totalPages = row.page,
            items = raw.mapNotNull { it.toItem(localIds) }.map { item ->
                if (exactId != null && item.id == exactId) item.copy(isExactIdMatch = true) else item
            },
            fromHistory = true,
        )
    }

    // ------------------------------------------------------------------
    // 本子入库、最近预览与详情
    // ------------------------------------------------------------------

    /**
     * 点进详情页（或阅读器）时调用：**这是最近预览的唯一写入入口**。
     *
     * 用户明确要求「请求漫画详情接口 ≠ 加入最近预览」：
     * 拉取 `/album` 只写基础数据表（[cacheAlbum]），不碰这张表；
     * 只有真正进入详情页/阅读页（搜索、书库、收藏夹三处入口 + 阅读器），
     * 才来这里记一笔浏览时间。
     *
     * 表里只有 `id` 与 `viewed_time`，一条 REPLACE 天然原子，
     * 不再需要旧版本那种"读旧时间再保留"的串行化处理。
     */
    suspend fun touchRecent(aid: Long) {
        recentDao.upsert(RecentAlbumEntity(id = aid, viewedTime = System.currentTimeMillis()))
    }

    /**
     * 把在线详情写进**基础数据表**（永久缓存）。
     *
     * 这是所有 `/album` 拉取路径（详情页加载/刷新、精确匹配搜索、加入书库）的公共落点：
     * **每次请求成功都会整行刷新基础表**（事务内先删后插，见 `AlbumDao.replaceAlbum`），
     * `create_time` 保留首次缓存时间、`modify_time` 更新为当前；
     * 同时不写最近预览、不写书库成员——那两件事分别由 [touchRecent] 与 [addToLibrary] 负责。
     * 本地收藏标记 `collected` 在成员表里，刷新基础数据不会碰到它。
     */
    private suspend fun cacheAlbum(dto: AlbumDto) {
        val now = nowText()
        albumDao.replaceAlbum(toAlbumEntity(dto).copy(createTime = now, modifyTime = now))
    }

    /** 最近预览列表：按浏览时间倒序，内容与基础数据表拼接（见 DAO 里的 LEFT JOIN）。 */
    fun observeRecentAlbums(limit: Int = 60, offset: Int = 0): Flow<List<AlbumEntity>> =
        recentDao.observeRecent(limit, offset)

    fun observeRecentCount(): Flow<Int> = recentDao.observeCount()

    /** 最近预览记录本身（只有 id 与浏览时间）。 */
    suspend fun findRecent(aid: Long): RecentAlbumEntity? = recentDao.findById(aid)

    /**
     * 最近预览里的全部 id。
     *
     * 清空之前先取一份：清完就查不到"刚才有哪几本"了，
     * 而这几本的封面要不要一起清，正是靠这份名单判断的（见 `AlbumCleaner`）。
     */
    suspend fun recentAlbumIds(): List<Long> = recentDao.allIds()

    suspend fun removeRecent(aid: Long) = recentDao.delete(aid)

    suspend fun clearRecent(): Int = recentDao.clear()

    /**
     * 详情页数据来源：**先本地（书库 → 基础数据缓存），都没有才请求网络**。
     *
     * `null` 表示基础数据表里还没有这本的在线数据，调用方需要走一次网络。
     * 基础数据只在拿到真实 `/album` 结果时写入，所以不存在旧版本"骨架行冒充数据"的问题；
     * 而它永不删除，于是"看过一次 → 离线仍可进详情/阅读器"的能力得以保留。
     */
    suspend fun localAlbumDetail(aid: Long): LocalAlbumDetail? {
        albumDao.findLibraryAlbum(aid)?.let { return LocalAlbumDetail(it, fromLibrary = true) }
        albumDao.findById(aid)?.let { return LocalAlbumDetail(it.toReadModel(), fromLibrary = false) }
        return null
    }

    /**
     * 拉取本子详情：**只缓存基础数据**，不改书库成员、不写最近预览。
     *
     * 详情页首次加载与「刷新」都走这里；刷新更新的是基础数据，
     * 书库列表与详情页因为都拼这张表，看到的也是新内容，浏览时间不受影响。
     */
    suspend fun fetchAlbumDetail(aid: Long): AlbumDto {
        val dto = api.album(aid)
        cacheAlbum(dto)
        return dto
    }

    /** 加入书库成员；已存在时保留原入库时间（IGNORE）。 */
    private suspend fun addLibraryRow(id: Long) {
        albumDao.insertLibraryRow(LibraryEntity(albumId = id, createTime = nowText()))
    }

    /** 把本子加入书库（先拉一次 `/album`，用于只有 aid 的场景，例如搜索结果）。 */
    suspend fun addToLibrary(aid: Long): AlbumEntity = saveAlbum(api.album(aid))

    /**
     * 用**已有的**详情数据入库，不再请求网络。
     *
     * 详情页手里已经有完整的本子数据（基础数据缓存或刚拉到的），
     * 收藏时没必要再拉一次 `/album`（之前"从未收藏过的漫画点收藏"会明显卡一下，
     * 用户反馈的现象正是这个原因）。
     */
    suspend fun addToLibrary(dto: AlbumDto): AlbumEntity = saveAlbum(dto)

    /**
     * 把内存里的实体入库（不请求网络）：写基础数据 + 加书库成员。
     *
     * 详情页在"未入库"状态下手里的数据就来自基础数据表，直接落库即可；
     * 收藏标记由调用方随后设置。
     */
    suspend fun addToLibrary(album: AlbumEntity): AlbumEntity {
        val now = nowText()
        albumDao.replaceAlbum(album.toBaseEntity().copy(createTime = now, modifyTime = now))
        addLibraryRow(album.id)
        return albumDao.findLibraryAlbum(album.id) ?: album
    }

    /**
     * 入库时**是否需要联网**：本地已有完整详情就不需要。
     *
     * 判定依据与 [addToLibraryLocalFirst] 完全一致（同一个 [localAlbumDetail]：
     * 先看书库、再看基础数据缓存），供 UI 决定卡片上的「加入书库」要不要转圈。
     * 只有回落到 `/album` 的这一条路径会让用户真的等一次网络往返；
     * 本地命中是一帧就结束的数据库写入，给它加 loading 只会变成"闪一下"。
     */
    suspend fun libraryAddNeedsNetwork(aid: Long): Boolean = localAlbumDetail(aid) == null

    /**
     * 搜索结果点「加入书库」：**本地已经有完整详情就不联网**。
     *
     * 精确匹配那一条本来就是 `/album` 的结果（`searchOnlineByExactId` 已经把它
     * 写进基础数据表），再走一遍 [addToLibrary] 会重新请求一次详情接口
     * ——用户看到的就是"点一下卡住"。用户之前看过/缓存过的本子也一样，基础数据表里就有完整数据。
     *
     * 只有"本地真的没有"（普通搜索结果、且从没点进去过）才回落到网络。
     */
    suspend fun addToLibraryLocalFirst(aid: Long): AlbumEntity {
        // localAlbumDetail 会先看书库、再看基础数据缓存；两处都没有才返回 null
        localAlbumDetail(aid)?.let { local ->
            return addToLibrary(local.album)
        }
        return addToLibrary(aid)
    }

    /**
     * 入库（不请求网络）：写基础数据 + 加书库成员；返回书库视角的实体。
     *
     * 成员行的 `create_time` 是首次入库时间——**已存在时不动**，
     * 所以重复入库/刷新详情不会改变它在书库里的排序位置。
     */
    suspend fun saveAlbum(dto: AlbumDto): AlbumEntity {
        cacheAlbum(dto)
        addLibraryRow(dto.id)
        return albumDao.findLibraryAlbum(dto.id) ?: error("本子入库失败：${dto.id}")
    }

    /** 接口 DTO → 基础数据实体（字段与原书库表一一对应，`collected` 除外）。 */
    private fun toAlbumEntity(dto: AlbumDto) = AlbumBaseEntity(
        id = dto.id,
        name = dto.name,
        albumFolderName = FileNaming.albumFolderName(dto.name, dto.id.toString(), albumNameMaxBytes()),
        images = encodeStringList(dto.images),
        addTime = dto.addTime,
        description = dto.description,
        totalViews = dto.totalViews,
        likes = dto.likes,
        // series 原样存 JSON 字符串：详情页的章节列表以它为准（见类注释第 2 条）
        series = dto.series?.let { ApiJson.encodeToString(ListSerializer(SeriesDto.serializer()), it) },
        seriesId = dto.seriesId,
        commentTotal = dto.commentTotal,
        author = encodeStringList(dto.author),
        tags = encodeStringList(dto.tags),
        works = encodeStringList(dto.works),
        actors = encodeStringList(dto.actors),
        relatedList = dto.relatedList?.let {
            ApiJson.encodeToString(
                ListSerializer(com.haruhi.jmcomic.feature.api.RelatedDto.serializer()),
                it,
            )
        },
        liked = dto.liked,
        isFavorite = dto.isFavorite,
        isAids = dto.isAids,
        price = dto.price,
        purchased = dto.purchased,
    )

    /** 读取模型 → 基础数据实体（写入时丢掉书库语义的 `collected`）。 */
    private fun AlbumEntity.toBaseEntity() = AlbumBaseEntity(
        id = id,
        name = name,
        albumFolderName = albumFolderName,
        images = images,
        addTime = addTime,
        description = description,
        totalViews = totalViews,
        likes = likes,
        series = series,
        seriesId = seriesId,
        commentTotal = commentTotal,
        author = author,
        tags = tags,
        works = works,
        actors = actors,
        relatedList = relatedList,
        liked = liked,
        isFavorite = isFavorite,
        isAids = isAids,
        price = price,
        purchased = purchased,
        raw = raw,
        createTime = createTime,
        modifyTime = modifyTime,
    )

    /** 基础数据实体 → 读取模型（未入库时 `collected` 为空）。 */
    private fun AlbumBaseEntity.toReadModel(collected: Boolean? = null) = AlbumEntity(
        id = id,
        name = name,
        albumFolderName = albumFolderName,
        images = images,
        addTime = addTime,
        description = description,
        totalViews = totalViews,
        likes = likes,
        series = series,
        seriesId = seriesId,
        commentTotal = commentTotal,
        author = author,
        tags = tags,
        works = works,
        actors = actors,
        relatedList = relatedList,
        liked = liked,
        isFavorite = isFavorite,
        collected = collected,
        isAids = isAids,
        price = price,
        purchased = purchased,
        raw = raw,
        createTime = createTime,
        modifyTime = modifyTime,
    )

    /** 拉取章节详情（图片清单）并入库。返回入库后的图片数。 */
    suspend fun fetchAndSaveChapter(albumId: Long, chapterId: Long): Int {
        val album = albumDao.findById(albumId)?.toReadModel()
        val chapter = api.chapter(chapterId)
        saveChapterImages(albumId, chapter, album?.let { seriesOf(it, chapterId) })
        return chapterDao.imageCount(albumId, chapterId)
    }

    /** 只拉章节详情，不入库（调用方决定何时写库）。 */
    suspend fun fetchChapter(chapterId: Long): ChapterDto = api.chapter(chapterId)

    // ------------------------------------------------------------------
    // 评论
    // ------------------------------------------------------------------

    /**
     * 拉一页漫画评论（详情页「评论」tab）。
     *
     * `page` **从 1 开始**、每页固定 10 条；`total` 被服务端截断到 5000。
     * 评论**不入库**：按用户要求只存在内存里（`CommentCache`），退出 App 即清空。
     *
     * 返回的每一条都做过三件事：正文 HTML 清洗、时间文案归一、点赞数归一
     * （见 `Comments.kt`）。`cid` 为空的条目直接丢掉——它是列表的 key，不能空。
     */
    suspend fun comments(albumId: Long, page: Int = 1): CommentPage {
        val resp = api.forum(aid = albumId, page = page)
        val comments = resp.list.orEmpty()
            .map { it.toCommentRow() }
            .filter { it.cid.isNotEmpty() }
        val total = resp.total?.trim()?.toIntOrNull() ?: comments.size
        return CommentPage(page = page, total = total.coerceAtLeast(0), comments = comments)
    }

    /** 接口 DTO → 可直接渲染的评论（回复列表同样递归归一化）。 */
    private fun ForumItemDto.toCommentRow(): CommentRow = CommentRow(
        cid = cid?.trim().orEmpty(),
        author = listOf(nickname, username)
            .firstOrNull { !it.isNullOrBlank() && !it.equals("null", ignoreCase = true) }
            ?.trim()
            .orEmpty()
            .ifEmpty { ANONYMOUS_COMMENT_AUTHOR },
        avatar = photo?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
        text = cleanCommentHtml(content),
        likes = commentLikeCount(likes),
        timeLabel = commentTimeLabel(addTime),
        levelName = expInfo?.levelName?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
        replies = replies.orEmpty()
            .map { it.toCommentRow() }
            .filter { it.cid.isNotEmpty() },
    )

    /** 从本子已存的 `series` JSON 里找出某一章对应的条目。 */
    fun seriesOf(album: AlbumEntity, chapterId: Long): SeriesDto? =
        album.series
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?.let { json ->
                runCatching {
                    ApiJson.decodeFromString(ListSerializer(SeriesDto.serializer()), json)
                }.getOrNull()
            }
            ?.firstOrNull { it.id == chapterId.toString() }

    /** 某章的 `scramble_id`（解扰块数的输入），异常时返回兜底值。 */
    suspend fun scrambleIdOf(chapterId: Long): Long = api.scrambleId(chapterId)

    /** 书库视角读一本（基础数据 + 成员关系）。未入库返回 null。 */
    suspend fun findAlbum(aid: Long): AlbumEntity? = albumDao.findLibraryAlbum(aid)

    /** 书库视角观察一本（详情页"是否已入库"与阅读器收藏状态用）。 */
    fun observeAlbum(aid: Long): Flow<AlbumEntity?> = albumDao.observeLibraryAlbum(aid)

    /**
     * 章节图片清单入库（整章替换）。
     *
     * 排序与 `imageSort` 起始值照抄参考实现：先 `sortImageFiles` 排序，
     * 再写 `imageSort = index + 1`。
     */
    suspend fun saveChapterImages(albumId: Long, chapter: ChapterDto, series: SeriesDto? = null) {
        val rawImages = chapter.images.orEmpty()
        if (rawImages.isEmpty()) {
            Timber.tag("jm").d("章节无图片，跳过入库 chapterId=%d", chapter.id)
            return
        }
        // 单话本子没有 series，但**标题必须与磁盘目录名一致**，否则会出现
        // "图片下好了却一直显示未下载"（详见 singleChapterSeries 的注释）
        val effectiveSeries = series ?: singleChapterSeries(albumId, chapter.id)
        val sorted = FileNaming.sortImageFiles(rawImages)
        val title = chapterTitleOf(effectiveSeries, chapter)
        val entities = sorted.mapIndexed { index, image ->
            ChapterImageEntity(
                albumId = albumId,
                chapterId = chapter.id,
                chapterSort = effectiveSeries?.sort,
                chapterTitle = title,
                // 参考实现：有 series 时用 series.name，否则用 chapter.name
                chapterName = if (effectiveSeries != null) effectiveSeries.name else chapter.name,
                chapterAddTime = chapter.addTime,
                seriesId = chapter.seriesId,
                liked = chapter.liked,
                isFavorite = chapter.isFavorite,
                imageFile = image,
                imageSort = index + 1,
            )
        }
        chapterDao.replaceChapter(albumId, chapter.id, entities)
    }

    /**
     * 单话本子合成的 series 条目；不是单话本子时返回 null。
     *
     * 为什么必须有它：章节目录名由 `chapterTitle` 决定（`chapterFolderName` 的规则）。
     * 单话本子没有 series，`chapterTitle` 会退化成接口给的 `chapter.name`
     * ——那往往就是**本子标题**，而下载时用的目录名是合成的「第1话」。
     * 两边对不上，已经下好的图片会被 `downloadedImageCount` 判成"未下载"，
     * 界面上永远显示 0/1 已下载（模拟器实测踩到）。
     * 参考实现 `downloadAlbumWithoutLock` 第 866～872 行也是先合成 `Series` 再入库。
     */
    private suspend fun singleChapterSeries(albumId: Long, chapterId: Long): SeriesDto? {
        val album = albumDao.findById(albumId) ?: return null
        val hasSeries = !album.series.isNullOrBlank() && album.series != "[]"
        if (hasSeries || chapterId != album.id) return null
        return SeriesDto(id = chapterId.toString(), sort = "1", title = "第1话", name = album.name)
    }

    /** 章节展示名：`series.title` → `第{sort}话` → `chapter.name` → chapterId。 */
    private fun chapterTitleOf(series: SeriesDto?, chapter: ChapterDto): String {
        val fromSeries = series?.title?.takeIf { it.isNotBlank() }
            ?: series?.sort?.takeIf { it.isNotBlank() }?.let { "第${it}话" }
        if (fromSeries != null) return fromSeries
        return chapter.name?.takeIf { it.isNotBlank() } ?: chapter.id.toString()
    }

    /**
     * 详情页的章节列表。
     *
     * 优先用 `album.series`（在线数据），为空时回落到章节图片表（本地已下载的章节），
     * 与参考实现 `listChapters` 的分支一致。
     */
    suspend fun chaptersOf(album: AlbumEntity): List<ChapterSummary> {
        val series = album.series
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?.let { json ->
                runCatching {
                    ApiJson.decodeFromString(ListSerializer(SeriesDto.serializer()), json)
                }.getOrNull()
            }
        if (!series.isNullOrEmpty()) {
            return series
                .filter { !it.id.isNullOrBlank() }
                .map { dto ->
                    val chapterId = dto.id!!.toLongOrNull() ?: return@map null
                    ChapterSummary(
                        chapterId = chapterId,
                        sort = dto.sort,
                        name = dto.name,
                        title = dto.title?.takeIf { it.isNotBlank() }
                            ?: dto.sort?.takeIf { it.isNotBlank() }?.let { "第${it}话" }
                            ?: dto.name
                            ?: chapterId.toString(),
                    )
                }
                .filterNotNull()
        }

        val local = chapterDao.chapterList(album.id).map { row ->
            ChapterSummary(
                chapterId = row.chapterId,
                sort = row.chapterSort,
                name = row.chapterName,
                title = chapterTitleOfRow(row.chapterId, row.chapterTitle, row.chapterName, album.id),
            )
        }
        if (local.isNotEmpty()) return local

        // 单话本子：`/album` 不返回 series，本地也还没下过任何一话。
        // JM 的约定是"**章节 id 就是本子 id**"，`/chapter?id=<aid>` 能取到这一话的图片清单
        // （参考实现 `downloadAlbumWithoutLock` 第 866～872 行就是这么做兜底的）。
        // 不补这一条的话，单话本子在 App 里会显示"共 0 话"，永远下不动。
        return listOf(SINGLE_CHAPTER)
            .map { it.copy(chapterId = album.id, name = album.name) }
    }

    /**
     * 章节标题的统一规则：**单话本子（章节 id = 本子 id）固定叫「第1话」**，
     * 其余用 `title → name → id`。
     *
     * 为什么单话本子不看存下来的 `chapterTitle`：接口给的 `chapter.name` 往往就是
     * **本子标题**，而下载用的目录名是"第1话"。历史数据里存了前者的话，
     * 已经下好的图片会因为目录名对不上而被判成"未下载"（模拟器实测踩到）。
     * 把它固定下来，目录名 / 任务里的章节名 / 界面上的章节名三者才永远一致。
     */
    private fun chapterTitleOfRow(
        chapterId: Long,
        chapterTitle: String?,
        chapterName: String?,
        albumId: Long,
    ): String {
        if (chapterId == albumId) return SINGLE_CHAPTER.title
        return chapterTitle?.takeIf { it.isNotBlank() }
            ?: chapterName?.takeIf { it.isNotBlank() }
            ?: chapterId.toString()
    }

    /** 某章的图片清单（本地已入库的）。 */
    suspend fun imagesOf(albumId: Long, chapterId: Long): List<ChapterImageEntity> =
        chapterDao.images(albumId, chapterId)

    fun observeImages(albumId: Long, chapterId: Long): Flow<List<ChapterImageEntity>> =
        chapterDao.observeImages(albumId, chapterId)

    /**
     * 统计某章在磁盘上已有的图片数（"已下载 / 部分下载 / 未下载"的判定依据）。
     *
     * 判定规则与下载层一致：**文件存在且非空**才算数
     * （0 字节残留文件必须重新下载，见 `DownloadTaskRepositoryTest` 的用例）。
     */
    fun downloadedImageCount(albumFolderName: String?, chapterFolder: String, imageFiles: List<String>): Int {
        if (albumFolderName.isNullOrBlank()) return 0
        val dir = dirs.chapterDir(albumFolderName, chapterFolder)
        if (!dir.isDirectory) return 0
        return imageFiles.count { name -> File(dir, name).let { it.isFile && it.length() > 0 } }
    }

    /** 章节目录名：`title` → `name` → `chapterId`（对齐参考实现 `getChapterFolderName`）。 */
    fun chapterFolderName(summary: ChapterSummary): String =
        summary.title.takeIf { it.isNotBlank() }
            ?: summary.name?.takeIf { it.isNotBlank() }
            ?: summary.chapterId.toString()

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    fun observeFavorites(): Flow<List<FavoriteEntity>> = favoriteDao.observeAll()

    suspend fun listFavorites(): List<FavoriteEntity> = favoriteDao.all()

    /**
     * 收藏夹列表 + 本子数量 + 封面来源。**返回 Flow**：关联表任何变动都会重新发射，
     * 收藏之后收藏页的数量与封面会立刻更新。
     */
    fun observeFavoritesWithCount(): Flow<List<FavoriteSummary>> =
        favoriteDao.observeFavoritesWithCount().map { rows ->
            rows.map { row ->
                FavoriteSummary(
                    id = row.id,
                    name = row.name,
                    sortOrder = row.sortOrder,
                    albumCount = row.albumCount,
                    isDefault = row.name == DEFAULT_FAVORITE_NAME,
                    createTime = row.createTime,
                    lastAddedTime = row.lastAddedTime,
                    latestAlbumId = row.latestAlbumId,
                )
            }
        }

    /** 确保默认收藏夹存在（首次使用时创建，不允许删除/重命名）。 */
    suspend fun ensureDefaultFavorite(): FavoriteEntity {
        favoriteDao.findByName(DEFAULT_FAVORITE_NAME)?.let { return it }
        val id = favoriteDao.insert(
            FavoriteEntity(name = DEFAULT_FAVORITE_NAME, sortOrder = 0, createTime = nowText()),
        )
        return favoriteDao.findByName(DEFAULT_FAVORITE_NAME)
            ?: error("创建默认收藏夹失败(id=$id)")
    }

    /**
     * 新建收藏夹。
     *
     * 重名与空名都要**分别**告诉用户，不能都回一个 null
     * （用户反馈第 7 点：改名撞名时提示成了"默认收藏夹不支持重命名"）。
     */
    suspend fun createFavorite(name: String): FavoriteWriteResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return FavoriteWriteResult.NameBlank
        if (favoriteDao.findByName(trimmed) != null) return FavoriteWriteResult.NameTaken
        val id = favoriteDao.insert(
            FavoriteEntity(name = trimmed, sortOrder = nextSortOrder(), createTime = nowText()),
        )
        return if (id > 0) FavoriteWriteResult.Ok else FavoriteWriteResult.NameTaken
    }

    private suspend fun nextSortOrder(): Int =
        (favoriteDao.all().maxOfOrNull { it.sortOrder } ?: 0) + 1

    /**
     * 重命名收藏夹。
     *
     * 三种失败原因必须分开：默认夹不允许改名、新名字已被占用、夹不存在。
     * 之前统一返回 false，界面只能猜一个文案，于是"撞名"被说成了"默认收藏夹不支持重命名"。
     */
    suspend fun renameFavorite(id: Long, newName: String): FavoriteWriteResult {
        val favorite = favoriteDao.all().firstOrNull { it.id == id } ?: return FavoriteWriteResult.NotFound
        if (favorite.name == DEFAULT_FAVORITE_NAME) return FavoriteWriteResult.DefaultFolder
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return FavoriteWriteResult.NameBlank
        if (trimmed == favorite.name) return FavoriteWriteResult.Ok
        // 唯一索引也会拦，但提前查一次才能给出准确原因
        if (favoriteDao.findByName(trimmed) != null) return FavoriteWriteResult.NameTaken
        favoriteDao.rename(id, trimmed, nowText())
        return FavoriteWriteResult.Ok
    }

    /**
     * 删除收藏夹：只删夹与关联，**不动书库记录和本地文件**（计划 §9 第 6 条）。
     * 默认收藏夹不允许删除。
     */
    suspend fun deleteFavorite(id: Long): FavoriteWriteResult {
        val favorite = favoriteDao.all().firstOrNull { it.id == id } ?: return FavoriteWriteResult.NotFound
        if (favorite.name == DEFAULT_FAVORITE_NAME) return FavoriteWriteResult.DefaultFolder
        favoriteDao.deleteWithLinks(id)
        return FavoriteWriteResult.Ok
    }

    /**
     * 收藏：把本子加入收藏夹并置 `collected = true`。
     *
     * 未指定收藏夹时回落到默认收藏夹（参考实现 `addAlbumsToFavorite`）。
     * 幂等：重复收藏同一夹不会产生重复关联（唯一索引 + IGNORE）。
     */
    suspend fun addToFavorite(albumId: Long, favoriteId: Long? = null): Long {
        val target = favoriteId ?: ensureDefaultFavorite().id
        favoriteDao.link(
            FavoriteAlbumEntity(favoriteId = target, albumId = albumId, createTime = nowText()),
        )
        albumDao.setCollected(albumId, true)
        return target
    }

    /** 移出收藏夹：只删关联；其它收藏夹里还有的话 `collected` 保持 true。 */
    suspend fun removeFromFavorite(albumId: Long, favoriteId: Long) {
        favoriteDao.unlink(favoriteId, albumId)
        val remaining = favoriteDao.favoriteIdsOf(albumId)
        albumDao.setCollected(albumId, remaining.isNotEmpty())
    }

    /** 取消收藏：移出所有收藏夹。 */
    suspend fun uncollect(albumId: Long) {
        favoriteDao.deleteLinksOfAlbum(albumId)
        albumDao.setCollected(albumId, false)
    }

    suspend fun favoriteIdsOf(albumId: Long): List<Long> = favoriteDao.favoriteIdsOf(albumId)

    /** 观察本子所属的收藏夹（详情页与阅读器共用，跨页面同步）。 */
    fun observeFavoriteIdsOf(albumId: Long): Flow<List<Long>> = favoriteDao.observeFavoriteIdsOf(albumId)

    suspend fun setCollected(albumId: Long, collected: Boolean) =
        albumDao.setCollected(albumId, collected)

    // ------------------------------------------------------------------
    // 书库
    // ------------------------------------------------------------------

    /**
     * 书库列表（含筛选与排序）。
     *
     * 语义对齐参考实现 `searchAlbums`：
     * - `tags` 的多选是 **OR**（命中所选标签之一即可），用 `INSTR(tags, "\"tag\"") > 0`
     *   做子串匹配——因为 `tags` 列存的是 JSON 数组字符串；
     * - **指定收藏夹时不再叠加 `collected` 条件**（收藏夹本身已隐含"已收藏"）；
     * - 默认按**首次入库时间**（`t_jm_library.create_time`）倒序。
     */
    fun observeLibrary(
        keyword: String? = null,
        collected: Boolean? = null,
        author: String? = null,
        tags: List<String> = emptyList(),
        favoriteId: Long? = null,
        limit: Int = 60,
        offset: Int = 0,
    ): Flow<List<AlbumEntity>> = albumDao.observeLibrary(
        keyword = keyword?.takeIf { it.isNotBlank() },
        // 指定收藏夹时不再叠加 collected（参考实现语义）
        onlyCollected = if (favoriteId != null) null else collected,
        author = author?.takeIf { it.isNotBlank() },
        tagsCsv = tagsCsv(tags),
        favoriteId = favoriteId,
        limit = limit,
        offset = offset,
    )

    fun observeLibraryCount(
        keyword: String? = null,
        collected: Boolean? = null,
        author: String? = null,
        tags: List<String> = emptyList(),
        favoriteId: Long? = null,
    ): Flow<Int> = albumDao.observeLibraryCount(
        keyword = keyword?.takeIf { it.isNotBlank() },
        onlyCollected = if (favoriteId != null) null else collected,
        author = author?.takeIf { it.isNotBlank() },
        tagsCsv = tagsCsv(tags),
        favoriteId = favoriteId,
    )

    /** 标签列表 → DAO 需要的 CSV。空串表示"不筛选"。 */
    private fun tagsCsv(tags: List<String>): String =
        tags.map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('"') && !it.contains(',') }
            .distinct()
            .joinToString(",")

    /**
     * 从书库移除：**删掉这本漫画在本地的数据库记录**（用户要求第 7 点）。
     *
     * 覆盖的库表：
     * - `t_jm_library`（书库**成员关系**本身）
     * - `t_jm_chapter_image`（章节图片清单）
     * - `t_jm_favorite_album`（收藏夹关联）
     * - `t_playback_position`（阅读位置）
     *
     * **`t_jm_recent_album`（最近预览）刻意不动**（用户要求第 5 点）：
     * "移出书库"影响的是书库与本地文件，"刚看过什么"是另一件事——
     * 保留后最近列表里还能看到这本，点进去也能重新加入书库。
     *
     * **`t_jm_album`（基础数据缓存）也刻意不动**（用户要求：缓存表不因任何操作删除）：
     * 移出书库后再进详情页/阅读器仍然有完整数据可看；
     * `collected` 在成员行里，随 [deleteLibraryRows] 一起消失（与旧实现"整行删掉"的效果一致）。
     *
     * **本地文件不在这里删**：图片在本子目录、成品在系统下载目录、封面在 Coil 缓存，
     * 三处都要清，由调用方（详情页 / 书库多选）统一编排。
     *
     * **「继续阅读」指针**（kind = `JM_CONTINUE`，独立一行）只在它正指向被移出的本子时
     * 一并清掉（用户方案 B）：卡片宁可消失，也不回退到一本更早读过的旧书。
     */
    suspend fun deleteAlbums(ids: List<Long>) {
        if (ids.isEmpty()) return
        val keys = ids.map { it.toString() }
        ids.forEach { id ->
            favoriteDao.deleteLinksOfAlbum(id)
            chapterDao.deleteByAlbum(id)
            positionDao?.delete(PlaybackKind.JM, id.toString())
        }
        // 指针若指向被移出的本子就清掉整条指针（单条 SQL 原子）；指向别本则不受影响。
        positionDao?.deleteForKeys(ReadingRepository.CONTINUE_KIND, keys)
        albumDao.deleteLibraryRows(ids)
    }

    /**
     * 删除本子目录（图片）。**保留库记录**，也**不动封面缓存**。
     *
     * 注意：导出成品（zip/pdf）落在系统下载目录，不在这里——调用方要另外删，
     * 见 `ExportManager.deleteOutputs`。
     */
    suspend fun deleteAlbumFiles(albumFolderName: String?): Long {
        if (albumFolderName.isNullOrBlank()) return 0L
        val dir = dirs.albumDir(albumFolderName)
        val freed = dirs.sizeOf(dir)
        dir.deleteRecursively()
        return freed
    }

    /**
     * 删除某一话的本地文件，**保留章节清单与任务记录**（计划 §9 第 7 条：
     * 章节行尾菜单可"重下、删除章节文件"）。
     *
     * 只删文件不删清单：清单是"这一话应该有哪些图"的权威来源，
     * 删掉它就没法判断缺图，也没法重新下载。
     */
    suspend fun deleteChapterFiles(albumFolderName: String, chapterFolder: String): Long {
        if (albumFolderName.isBlank() || chapterFolder.isBlank()) return 0L
        val dir = dirs.chapterDir(albumFolderName, chapterFolder)
        val freed = dirs.sizeOf(dir)
        dir.deleteRecursively()
        return freed
    }

    /** 本子本地占用（字节）。 */
    fun localSize(albumFolderName: String?): Long =
        albumFolderName?.takeIf { it.isNotBlank() }?.let { dirs.sizeOf(dirs.albumDir(it)) } ?: 0L

    private fun nowText(): String = TEXT_FORMAT.format(LocalDateTime.now())

    private fun encodeStringList(list: List<String>?): String? =
        list?.let { ApiJson.encodeToString(ListSerializer(String.serializer()), it) }

    companion object {
        const val DEFAULT_FAVORITE_NAME = "默认收藏夹"

        /** 接口没给昵称/用户名时的展示名（不要显示成一串数字 id）。 */
        const val ANONYMOUS_COMMENT_AUTHOR = "匿名用户"

        /**
         * 单话本子的章节标题（同时也是章节目录名）。
         *
         * 与参考实现 `downloadAlbumWithoutLock` 的兜底完全一致：
         * `sort = "1"`、`title = "第1话"`、`id = aid`。
         */
        const val SINGLE_CHAPTER_TITLE = "第1话"

        /**
         * 单话本子的兜底章节（章节 id 随后被替换成本子 id）。
         */
        private val SINGLE_CHAPTER = ChapterSummary(
            chapterId = 0L,
            sort = "1",
            name = null,
            title = SINGLE_CHAPTER_TITLE,
        )

        private val TEXT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/**
 * 收藏夹写操作的结果。
 *
 * 每种失败都有独立取值，界面才能给出**准确**的提示——
 * 之前用 Boolean/null 表达，导致"改名撞车"被说成"默认收藏夹不支持重命名"。
 */
enum class FavoriteWriteResult {
    Ok,

    /** 默认收藏夹不可改名/删除。 */
    DefaultFolder,

    /** 名称已被占用（`name` 上有唯一索引）。 */
    NameTaken,

    /** 名称为空。 */
    NameBlank,

    /** 收藏夹不存在。 */
    NotFound,
}

/** 在线搜索的一条结果。 */
data class OnlineSearchItem(
    val id: Long,
    val name: String?,
    val author: String?,
    val description: String?,
    val coverUrl: String,
    val tags: List<String>,
    val updateAt: Long?,
    val existsLocal: Boolean,
    /**
     * 是不是"用户输入纯数字、由 `/album` 详情包装出来"的那一条。
     *
     * 界面据此标一个"精确匹配"，与普通搜索结果区分开（用户要求第 3 点）。
     */
    val isExactIdMatch: Boolean = false,
    val raw: SearchItemDto,
)

/** 在线搜索的一页结果。 */
data class OnlineSearchResult(
    val query: String,
    val sort: SearchSort,
    val page: Int,
    val total: Long,
    val pageSize: Int,
    val totalPages: Int,
    val items: List<OnlineSearchItem>,
    /** 是否来自历史快照（来自历史时不再请求 JM）。 */
    val fromHistory: Boolean = false,
)

/**
 * 把"精确匹配"的那一条并进搜索结果（用户要求第 3 点）。
 *
 * 三条规则：
 * 1. 精确匹配**置顶**——用户输入号码时想看的就是它；
 * 2. 搜索里已经有同一 aid 的，摘掉后面那条，避免同一本出现两次；
 * 3. 只在搜索结果里**没有**它时才把总数 +1，否则"共 N 条"会比卡片多一条。
 *
 * 另有一条"总数"的兜底（用户要求第 6 点③）：**搜索一条都没返回时，
 * 接口给的 total 不可信**——纯数字查询实测会返回 `total = 1` 但 `content` 为空，
 * 直接 +1 就显示成"共 2 条"。这时以"实际有的条目"为准。
 */
internal fun mergeExactIdMatch(
    base: OnlineSearchResult,
    pinned: OnlineSearchItem,
): OnlineSearchResult {
    val alreadyThere = base.items.any { it.id == pinned.id }
    val baseTotal = if (base.items.isEmpty()) 0L else base.total
    return base.copy(
        items = listOf(pinned) + base.items.filterNot { it.id == pinned.id },
        total = baseTotal + if (alreadyThere) 0 else 1,
    )
}

/** 详情页的章节条目。 */
data class ChapterSummary(
    val chapterId: Long,
    val sort: String?,
    val name: String?,
    val title: String,
)

/**
 * 详情页的本地数据。
 *
 * [fromLibrary] 区分数据来源：
 * - true：已在书库（基础数据 + 成员关系），详情页显示"已在书库"；
 * - false：只在基础数据缓存里（没入库），详情页显示「加入书库」按钮。
 */
data class LocalAlbumDetail(
    val album: AlbumEntity,
    val fromLibrary: Boolean,
)

/** 收藏夹 + 本子数量 + 封面来源（收藏页列表用）。 */
data class FavoriteSummary(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val albumCount: Int,
    val isDefault: Boolean,
    /** 创建时间；收藏页的"创建时间"排序用它。 */
    val createTime: String? = null,
    /** 夹内最近一次加入漫画的时间；空夹为 null。 */
    val lastAddedTime: String? = null,
    /** 夹内最近加入的本子 id，列表拿它当封面；空夹为 null。 */
    val latestAlbumId: Long? = null,
)

private fun SearchItemDto.toItem(localIds: Set<Long>): OnlineSearchItem? {
    val jmId = id?.toLongOrNull() ?: return null
    return OnlineSearchItem(
        id = jmId,
        name = name,
        author = normalizeAuthors(author),
        description = description,
        // 封面走图片线路：`https://{cdn}/media/albums/{aid}.jpg`
        coverUrl = Defaults.buildCoverUrl(jmId),
        tags = listOfNotNull(category?.title, categorySub?.title).filter { it.isNotBlank() },
        updateAt = updateAt,
        existsLocal = jmId in localIds,
        raw = this,
    )
}

internal fun normalizeAuthors(raw: String?): String? =
    raw?.split(" ")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(" / ")
