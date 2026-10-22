package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.feature.api.UserEditDto

/** 账号收藏页要渲染的最小快照（夹列表 + 总数 + 「全部收藏」封面）。 */
data class FavoritesSnapshot(
    val folders: List<AccountFolder>,
    val total: Int,
    val allCoverAlbumId: Long?,
)

/**
 * 某个收藏夹里已加载的漫画（[nextPage] = 下次加载更多要请求的页码）。
 *
 * 收藏夹内容基本不变，所以整份缓存起来直接复用；用户下拉或数据被动过才重拉。
 */
data class AlbumsSnapshot(
    val albums: List<AccountAlbum>,
    val total: Int,
    val nextPage: Int,
)

/**
 * 账号数据的**进程内缓存**（挂在 `AppContainer` 上，跟着进程走）。
 *
 * 为什么需要它（用户反馈 2026-10-21）：收藏页 / 个人资料页每次进入都会重建 ViewModel，
 * 重建就要重新联网 —— 列表先清空、再重新铺；夹的「N 本 / 封面」也要重新补拉，
 * 于是每次进来都**闪一下**。现在改成：进页面先用上次的快照渲染（不转圈、不闪），
 * 只有"数据被动过"（[invalidateFavorites]）或用户**下拉**时才打网络。
 *
 * 只在内存里：
 * - 退出登录 / 换账号时 [clear]（不能把上一个账号的收藏夹留在屏幕上）；
 * - 进程结束自然消失，没有"后台删了本地还在"的问题。
 */
class AccountCache {

    @Volatile
    var favorites: FavoritesSnapshot? = null
        private set

    /** 收藏数据被动过（本机加/移/改夹）。没有快照时它恒为 true。 */
    @Volatile
    var favoritesDirty: Boolean = true
        private set

    @Volatile
    var profileDetail: UserEditDto? = null
        private set

    @Volatile
    var profileDirty: Boolean = true
        private set

    fun putFavorites(snapshot: FavoritesSnapshot) {
        favorites = snapshot
        favoritesDirty = false
    }

    /** 收藏有写入（加/移/移夹/夹增删改）→ 下次进收藏页时静默重拉一次。 */
    fun invalidateFavorites() {
        favoritesDirty = true
    }

    fun putProfileDetail(detail: UserEditDto) {
        profileDetail = detail
        profileDirty = false
    }

    fun invalidateProfile() {
        profileDirty = true
    }

    // ------------------------------------------------------------------
    // 收藏夹列表（详情页「收藏」面板用）
    // ------------------------------------------------------------------

    /**
     * 收藏夹列表（只有 `{FID, name}`，没有数量/封面）。
     *
     * 详情页面板每次打开都重新拉一遍太浪费（用户反馈 2026-10-21）：
     * **优先用这份缓存**，只有"新建 / 删除收藏夹"才作废（[invalidateFolders]），
     * 改名直接就地改（[renameFolder]），退出 App 时随进程消失。
     */
    @Volatile
    var folders: List<AccountFolder>? = null
        private set

    @Volatile
    var foldersDirty: Boolean = true
        private set

    fun putFolders(list: List<AccountFolder>) {
        folders = list
        foldersDirty = false
    }

    /** 新建 / 删除收藏夹后调用：下次打开面板时重新拉一次。 */
    fun invalidateFolders() {
        foldersDirty = true
    }

    /** 改名是改了**已有条目**，不用整份作废（否则下次打开面板又要多一次请求）。 */
    fun renameFolder(folderId: String, newName: String) {
        folders = folders?.map { if (it.fid == folderId) it.copy(name = newName) else it }
    }

    // ------------------------------------------------------------------
    // 收藏夹里的漫画列表（夹内页用）
    // ------------------------------------------------------------------

    /**
     * 每个收藏夹已加载的漫画，key = `folderId#order`（`folderId` 空串表示「全部收藏」）。
     *
     * 用户反馈 2026-10-21：夹内页每次进入都重新请求太浪费——**收藏夹内容基本不变**，
     * 所以进页面先用这份缓存，只有下面这些情况才整份作废：
     * - 收藏（加入）（[AccountRepository.toggleFavorite]）
     * - 移到收藏夹（[AccountRepository.moveToFolder]）
     * - 新建 / 删除收藏夹（夹内的东西跟着变了）
     * - 登录 / 退出（[clear]）
     * - 用户下拉刷新（只覆盖自己那一个 key）
     *
     * **取消收藏不走整份作废**：用 [removeAlbum] 把这本就地删掉（见那里的注释）。
     */
    private val albumPages = java.util.concurrent.ConcurrentHashMap<String, AlbumsSnapshot>()

    fun cachedAlbums(folderId: String?, order: String): AlbumsSnapshot? =
        albumPages[albumsKey(folderId, order)]

    fun putAlbums(folderId: String?, order: String, snapshot: AlbumsSnapshot) {
        albumPages[albumsKey(folderId, order)] = snapshot
    }

    /** 收藏关系变了（加入 / 移夹 / 夹增删）：所有夹的内容都可能过期（一本从 A 夹搬到 B 夹会同时影响两个夹）。 */
    fun invalidateAlbums() {
        albumPages.clear()
    }

    /**
     * 取消收藏成功：把这本从**每一份已加载的夹快照**里就地删掉（用户要求 2026-10-22）。
     *
     * 以前是整份 [invalidateAlbums] 清掉，但页面回来时 ViewModel 还活着、屏幕上的列表还是旧的，
     * 用户得再下拉刷新一次才能看到它消失。现在条目和计数一起改掉，配合页面 onResume 读缓存，
     * 返回时直接就是对的。
     *
     * 只处理"已经加载过的页"：
     * - 条目在快照里 → 删掉、总数减一；
     * - 「全部收藏」的快照即使没加载到这本，总数也一定减一（它必然属于全部收藏）；
     * - 具体夹的「N 本 / 封面」只在能确定它属于该夹（该夹缓存里确实有这本）时才改，
     *   其余（这本在后面几页、不知道归哪个夹）交给 [favoritesDirty] 让收藏页回来时静默校正。
     */
    fun removeAlbum(albumId: Long) {
        // 1) 各夹已加载的列表：删条目 + 总数减一；记下删完后的第一本（当封面用）
        val firstAlbumByKey = mutableMapOf<String, Long?>()
        for ((key, snapshot) in albumPages) {
            val remaining = snapshot.albums.filterNot { it.id == albumId }
            val removed = remaining.size != snapshot.albums.size
            // 没加载到这本时，只有「全部收藏」（folderId 空串）的总数能确定要减
            if (!removed && key.substringBefore('#').isNotEmpty()) continue
            albumPages[key] = snapshot.copy(
                albums = remaining,
                total = (snapshot.total - 1).coerceAtLeast(0),
            )
            if (removed) firstAlbumByKey[key] = remaining.firstOrNull()?.id
        }

        // 2) 收藏页快照：总数、受影响夹的「N 本 / 封面」一起改
        favorites = favorites?.let { snapshot ->
            snapshot.copy(
                folders = snapshot.folders.map { folder ->
                    val coverKey = albumsKey(folder.fid, AccountRepository.ORDER_BY_TIME)
                    if (!firstAlbumByKey.containsKey(coverKey)) return@map folder
                    folder.copy(
                        count = folder.count?.let { (it - 1).coerceAtLeast(0) },
                        coverAlbumId = folder.coverAlbumId
                            ?.takeUnless { it == albumId }
                            ?: firstAlbumByKey[coverKey],
                    )
                },
                total = (snapshot.total - 1).coerceAtLeast(0),
                allCoverAlbumId = snapshot.allCoverAlbumId
                    ?.takeUnless { it == albumId }
                    ?: firstAlbumByKey[albumsKey(null, AccountRepository.ORDER_BY_TIME)],
            )
        }

        // 有夹没缓存到这一本（在后面的页里）→ 数量可能还差一，收藏页回来时静默校正一次
        favoritesDirty = true
    }

    private fun albumsKey(folderId: String?, order: String): String = "${folderId.orEmpty()}#$order"

    // ------------------------------------------------------------------
    // 每本是否已在账号收藏里
    // ------------------------------------------------------------------

    /**
     * `aid -> is_favorite` 的进程内备忘。
     *
     * 解决"进入已收藏的漫画详情页，按钮先显示「收藏」再变成「已收藏」"：
     * 本机数据库里的 `is_favorite` 是上一次 `/album` 的快照（我们点了收藏并不会改它），
     * 所以进页面时先按本机值画（可能是过期的 false），等 `/album` 回来再翻成 true。
     * 现在把每次拿到的结论记在这里，同一个进程里再进这本就直接是正确的状态。
     */
    private val favoriteFlags = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    fun cachedFavoriteFlag(albumId: Long): Boolean? = favoriteFlags[albumId]

    fun rememberFavoriteFlag(albumId: Long, value: Boolean) {
        favoriteFlags[albumId] = value
    }

    /** 登录成功 / 退出登录：整份缓存作废（避免把上一个账号的数据留在内存里）。 */
    fun clear() {
        favorites = null
        profileDetail = null
        folders = null
        favoriteFlags.clear()
        albumPages.clear()
        favoritesDirty = true
        profileDirty = true
        foldersDirty = true
    }
}
