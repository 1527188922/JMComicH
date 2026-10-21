package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.feature.api.UserEditDto

/** 账号收藏页要渲染的最小快照（夹列表 + 总数 + 「全部收藏」封面）。 */
data class FavoritesSnapshot(
    val folders: List<AccountFolder>,
    val total: Int,
    val allCoverAlbumId: Long?,
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

    /** 登录成功 / 退出登录：整份缓存作废（避免把上一个账号的数据留在内存里）。 */
    fun clear() {
        favorites = null
        profileDetail = null
        favoritesDirty = true
        profileDirty = true
    }
}
