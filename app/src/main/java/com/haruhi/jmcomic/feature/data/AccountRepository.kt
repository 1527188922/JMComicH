package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.account.AccountSession
import com.haruhi.jmcomic.core.account.AccountState
import com.haruhi.jmcomic.core.account.MemberProfile
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.feature.api.ApiClient
import com.haruhi.jmcomic.feature.api.FavoritePageDto
import com.haruhi.jmcomic.feature.api.LoginDto
import com.haruhi.jmcomic.feature.api.UserEditDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 账号（JM 会员）数据的唯一入口。
 *
 * 职责与边界（`docs/account-design.md` §12）：
 * - **登录 / 退出 / 401 翻译**都在这里，`ApiClient` 保持无状态；
 * - 收藏页要的数据都从这里拿，UI 不直接碰 `ApiClient`；
 * - 所有账号请求在未登录时直接抛"请先登录"，不会发出匿名请求（避免把 401 当网络错误）。
 *
 * 注意：这里**不做缓存**。账号数据是服务端真相，落库会出现"后台删了本地还在"的错觉；
 * 本地等价的离线数据在书库 / 本机收藏夹 / 本机最近浏览里。
 */
class AccountRepository(
    /** 每次取用都新建 `ApiClient`（它只持有几个读设置的闭包），与 `AppContainer.jmApi` 一致。 */
    private val api: () -> ApiClient,
    private val session: AccountSession,
    /**
     * 应用级作用域（`AppContainer.appScope`）。
     *
     * 只给 [logoutInBackground] 用：在「个人资料」页点退出登录会立刻弹回上一页，
     * 页面级 `viewModelScope` 会跟着被取消，挂在它上面的 `/logout` 就打不完了。
     */
    private val scope: CoroutineScope,
    /** 账号数据的进程内缓存（进页面先渲染上次的快照，避免每次进来都闪一下）。 */
    private val cache: AccountCache,
) {

    val state: StateFlow<AccountState> get() = session.state

    val profile: MemberProfile? get() = session.profile

    val isSignedIn: Boolean get() = session.isSignedIn

    // ------------------------------------------------------------------
    // 登录 / 退出
    // ------------------------------------------------------------------

    /**
     * 登录。失败时抛 [NetworkError]：
     * - 401 → [NetworkError.Business]，`message` 就是服务端 `errorMsg` 原文
     *   （实测：`无效的用户名和/或密码!` / `用户名和密码字段不能留空!`）；
     * - 网络问题 → `Timeout` / `Unreachable` 等，由 UI 换成"检查线路"文案。
     */
    suspend fun login(username: String, password: String): MemberProfile {
        val dto: LoginDto = api().login(username, password)
        val profile = dto.toProfile()
        val jwt = dto.jwtToken.orEmpty()
        val avs = dto.avs.orEmpty()
        // 换账号：上一份收藏/资料快照必须作废
        cache.clear()
        session.signIn(profile, jwt, avs)
        return profile
    }

    /** 退出登录：先尽力打 `/logout`，无论成败都清本地（服务器不可达也要能退）。 */
    suspend fun logout() {
        runCatching { api().logout() }
            .onFailure { Timber.tag("jm").d(it, "退出登录时 /logout 失败（已忽略）") }
        cache.clear()
        session.signOut()
    }

    /** 退出登录（应用级作用域）：页面被关掉也能走完。 */
    fun logoutInBackground() {
        scope.launch { logout() }
    }

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    /** 收藏列表。`folderId` 为 null/空 = 全部收藏。 */
    suspend fun favorites(page: Int, folderId: String? = null, order: String = ORDER_BY_TIME): FavoritePage {
        val dto = guarded { api().favoritePage(page = page, folderId = folderId, order = order) }
        return dto.toPage()
    }

    /**
     * 收藏夹列表（只有 FID / 名字）。
     *
     * 详情页「收藏」面板要用：**优先读缓存**（[AccountCache.folders]），没有才发请求并写回。
     * 缓存只在"新建 / 删除收藏夹"时作废（见 [createFolder] / [deleteFolder]），改名就地更新。
     */
    suspend fun folders(force: Boolean = false): List<AccountFolder> {
        if (!force) {
            cache.folders?.takeIf { !cache.foldersDirty }?.let { return it }
        }
        val list = favorites(page = 1).folders
        cache.putFolders(list)
        return list
    }

    /** 缓存里的收藏夹；没缓存 / 已作废时返回 null（调用方据此决定要不要转局部 loading）。 */
    fun cachedFolders(): List<AccountFolder>? = cache.folders?.takeIf { !cache.foldersDirty }

    /** 这一本是否在账号收藏里（本进程里核对过的结论）；没核对过返回 null。 */
    fun cachedFavoriteFlag(albumId: Long): Boolean? = cache.cachedFavoriteFlag(albumId)

    /** 记下得到的收藏状态（`/album` 的 `is_favorite` 或写操作的结果），供下次进详情页直接用。 */
    fun rememberFavoriteFlag(albumId: Long, inFavorites: Boolean) =
        cache.rememberFavoriteFlag(albumId, inFavorites)

    /**
     * 切换收藏状态。
     *
     * 返回 [FavoriteToggleResult]：`added = true` 表示这次是**加入**，false 表示**移除**。
     * 判定以服务端返回的 `type` 为准（`add`/`remove`），**不靠写完回读**
     * （实测偶发读到旧值，见 `docs/official-api-manga.md` §8.4 第 6 条）。
     */
    suspend fun toggleFavorite(aid: Long): FavoriteToggleResult {
        val dto = guarded { api().favoriteToggle(aid) }
        if (!dto.isOk) throw NetworkError.Business(dto.msg ?: "操作失败，请稍后重试")
        val added = when (dto.type) {
            "add" -> true
            "remove" -> false
            // 官方在个别场景会回 edit/move（不是"加入/移除"）；此时状态未知，
            // 让 UI 提示刷新，而不是把按钮画成错误的样子。
            else -> throw NetworkError.Business(dto.msg ?: "收藏状态异常，请刷新后重试")
        }
        if (added) {
            // 新加入的这本不知道会落在哪一页 / 哪个夹，只能让各夹内容整份重拉
            cache.invalidateFavorites()
            cache.invalidateAlbums()
        } else {
            // 取消收藏：把这本从内存快照里**就地删掉**（而不是整份作废）。
            // 这样返回收藏页 / 夹内页时不用下拉刷新就能看到它消失（用户要求 2026-10-22）。
            cache.removeAlbum(aid)
        }
        cache.rememberFavoriteFlag(aid, added)
        return FavoriteToggleResult(added = added, message = dto.msg)
    }

    /** 把漫画搬进某个收藏夹（`POST /favorite_folder{type=move}`）。 */
    suspend fun moveToFolder(aid: Long, folderId: String, folderName: String? = null) {
        val dto = guarded { api().favoriteFolder(type = "move", folderId = folderId, folderName = folderName, aid = aid) }
        if (!dto.isOk) throw NetworkError.Business(dto.msg ?: "移动失败，请稍后重试")
        cache.invalidateFavorites()
        cache.invalidateAlbums()
        cache.rememberFavoriteFlag(aid, true)
    }

    /** 新建收藏夹，返回新夹（从返回后的列表里按名字找）。 */
    suspend fun createFolder(name: String): AccountFolder {
        val dto = guarded { api().favoriteFolder(type = "add", folderName = name) }
        if (!dto.isOk) throw NetworkError.Business(dto.msg ?: "新建收藏夹失败")
        cache.invalidateFavorites()
        cache.invalidateAlbums()
        // 新建会让面板的收藏夹缓存过期（用户要求：新增/删除才清缓存）
        cache.invalidateFolders()
        // `/favorite_folder` 不返回新 FID，只能重拉一次列表按名字找（夹名服务端唯一）。
        // 实测服务端偶尔"晚一拍"才把新夹放进 folder_list（多节点），所以重试几次再放弃。
        repeat(FOLDER_LOOKUP_ATTEMPTS) { attempt ->
            val page = favorites(page = 1)
            page.folders.firstOrNull { it.name == name }?.let { return it }
            if (attempt < FOLDER_LOOKUP_ATTEMPTS - 1) delay(FOLDER_LOOKUP_DELAY_MS)
        }
        throw NetworkError.Business("收藏夹已创建，但列表里还没出现它，请下拉刷新")
    }

    suspend fun renameFolder(folderId: String, newName: String) {
        val dto = guarded { api().favoriteFolder(type = "edit", folderId = folderId, folderName = newName) }
        if (!dto.isOk) throw NetworkError.Business(dto.msg ?: "改名失败")
        cache.invalidateFavorites()
        cache.invalidateAlbums()
        // 改名只改已有条目，就地更新即可，不必让面板下次再拉一遍
        cache.renameFolder(folderId, newName)
    }

    /**
     * 删除收藏夹。
     *
     * 服务端**只允许删空夹**（非空会回"该收藏夹裡还有漫画，请先删除漫画"），
     * 所以调用方应在夹内有漫画时把按钮置灰（`docs/account-design.md` §4.3 L4）。
     */
    suspend fun deleteFolder(folderId: String) {
        val dto = guarded { api().favoriteFolder(type = "del", folderId = folderId) }
        if (!dto.isOk) throw NetworkError.Business(dto.msg ?: "删除收藏夹失败")
        cache.invalidateFavorites()
        cache.invalidateAlbums()
        cache.invalidateFolders()
    }

    // ------------------------------------------------------------------
    // 个人资料
    // ------------------------------------------------------------------

    suspend fun userEdit(uid: String): UserEditDto = guarded { api().userEditGet(uid) }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 先把"必须登录"挡掉，再把 401 翻译成可读文案。
     *
     * 401 同时把会话标成 [AccountState.Expired]：所有账号页会立刻显示"登录已失效"横幅，
     * 而不是每处各自弹一个错误。
     */
    private suspend fun <T> guarded(block: suspend () -> T): T {
        if (!session.isSignedIn) throw NetworkError.Business("请先登录")
        return try {
            block()
        } catch (e: NetworkError.HttpStatus) {
            if (e.code == 401) {
                session.markExpired()
                throw NetworkError.Business("登录状态已失效，请重新登录")
            }
            throw e
        }
    }

    companion object {
        /** `o=mr`：按收藏时间（接口默认，与不传一致）。 */
        const val ORDER_BY_TIME = "mr"

        /** `o=mp`：按更新时间。 */
        const val ORDER_BY_UPDATE = "mp"

        /** 新建夹后按名字回查的重试次数与间隔（服务端偶尔晚一拍）。 */
        private const val FOLDER_LOOKUP_ATTEMPTS = 3
        private const val FOLDER_LOOKUP_DELAY_MS = 600L
    }
}

/** 一次收藏切换的结果。 */
data class FavoriteToggleResult(val added: Boolean, val message: String?)

/** 收藏夹（账号）。[count]/[coverAlbumId] 是**补拉**出来的，拉不到就是 null（界面显示"…"）。 */
data class AccountFolder(
    val fid: String,
    val name: String,
    val count: Int? = null,
    val coverAlbumId: Long? = null,
)

/** 收藏夹里的一本漫画（账号）。 */
data class AccountAlbum(
    val id: Long,
    val name: String?,
    val author: String?,
)

/** 一页账号收藏。 */
data class FavoritePage(
    val albums: List<AccountAlbum>,
    val folders: List<AccountFolder>,
    val total: Int,
    val pageSize: Int,
)

private fun LoginDto.toProfile(): MemberProfile = MemberProfile(
    uid = uid.orEmpty(),
    username = username.orEmpty(),
    email = email,
    photo = photo,
    level = level ?: 0,
    levelName = levelName.orEmpty(),
    exp = exp?.trim()?.toLongOrNull() ?: 0L,
    nextLevelExp = nextLevelExp ?: 0L,
    coin = coin?.trim().orEmpty().ifBlank { "0" },
    albumFavorites = albumFavorites ?: 0,
    albumFavoritesMax = albumFavoritesMax ?: 0,
    adFree = adFree ?: false,
    adFreeBefore = adFreeBefore,
)

private fun FavoritePageDto.toPage(): FavoritePage = FavoritePage(
    albums = list.orEmpty().mapNotNull { item ->
        val id = item.id?.trim()?.toLongOrNull() ?: return@mapNotNull null
        AccountAlbum(id = id, name = item.name, author = item.author)
    },
    folders = folderList.orEmpty().mapNotNull { folder ->
        val fid = folder.fid?.trim().orEmpty()
        if (fid.isBlank()) return@mapNotNull null
        AccountFolder(fid = fid, name = folder.name.orEmpty().ifBlank { "未命名" })
    },
    total = total?.trim()?.toIntOrNull() ?: list?.size ?: 0,
    pageSize = count?.takeIf { it > 0 } ?: DEFAULT_FAVORITE_PAGE_SIZE,
)

/** 收藏列表每页 20 条（实测 `count:20`）。 */
const val DEFAULT_FAVORITE_PAGE_SIZE = 20
