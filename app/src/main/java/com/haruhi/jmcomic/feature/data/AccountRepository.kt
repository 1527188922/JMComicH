package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.account.AccountSession
import com.haruhi.jmcomic.core.account.AccountState
import com.haruhi.jmcomic.core.account.MemberProfile
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.feature.api.ApiClient
import com.haruhi.jmcomic.feature.api.EditResultDto
import com.haruhi.jmcomic.feature.api.FavoritePageDto
import com.haruhi.jmcomic.feature.api.LoginDto
import com.haruhi.jmcomic.feature.api.UserEditDto
import com.haruhi.jmcomic.feature.api.WebConfirmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
     * 两处用：
     * - [logoutInBackground]：在「个人资料」页点退出登录会立刻弹回上一页，
     *   页面级 `viewModelScope` 会跟着被取消，挂在它上面的 `/logout` 就打不完了；
     * - [login] 之后**后台补一次昵称**（登录响应里没有 `nickName`）。
     */
    private val scope: CoroutineScope,
    /** 账号数据的进程内缓存（进页面先渲染上次的快照，避免每次进来都闪一下）。 */
    private val cache: AccountCache,
    /**
     * 网页版重发验证信。
     *
     * App API 的 `POST /confirm{username,password}` 在服务端走错分支、永远返回
     * "无效的核查身份证"；网页版表单多一个 `submit_action=resend`，能正常重发。
     * 详见 [WebConfirmClient] 的注释。
     */
    private val webConfirm: WebConfirmClient,
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
        // 昵称（nickName）不在登录响应里，只能从资料接口补。这里**后台补一次**：
        // 补上之后「我的」摘要卡就能优先显示昵称（用户 2026-10-23 要求），
        // 失败不影响登录（只是继续显示登录名）。
        scope.launch {
            runCatching { userEdit(profile.uid) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.tag("jm").d(e, "登录后补取昵称失败（已忽略）")
                }
        }
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
    // 注册 / 邮箱验证
    // ------------------------------------------------------------------

    /**
     * 注册账号（`POST /register`）。
     *
     * 不需要登录态，所以**不走 [guarded]**；也不自动重试（注册不是幂等操作）。
     * 返回可展示的服务端文案（注册成功 / 失败原因）。
     *
     * 失败时抛 [NetworkError.Business]，`message` 是服务端 `errors` 拼起来的完整清单
     * （实测会一次返回"邮箱格式 + 用户名已存在 + 两次密码不一致"这样的多条）。
     */
    suspend fun register(
        username: String,
        password: String,
        passwordConfirm: String,
        email: String,
        gender: String,
    ): String {
        val dto: EditResultDto = api().register(
            username = username,
            password = password,
            passwordConfirm = passwordConfirm,
            email = email,
            gender = gender,
        )
        return dto.requireSuccess(
            fallback = "注册失败，请稍后重试",
            successFallback = "注册成功，请到邮箱查收验证码",
        )
    }

    /**
     * 提交邮箱验证码（`POST /confirm` 的 `username + numcode`）。
     *
     * 成功后账号才真正可用；界面随后会用注册时填的密码**自动登录**一次。
     */
    suspend fun confirmSignUp(username: String, numCode: String): String {
        val dto = api().confirmSignUp(username = username, numCode = numCode)
        return dto.requireSuccess(
            fallback = "验证失败，请稍后重试",
            successFallback = "邮箱验证成功",
        )
    }

    /**
     * 重新发送邮箱验证码。
     *
     * **走网页版 `/confirm`，不走 App API**：App 的 `POST /confirm{username,password}`
     * 少了网页版的 `submit_action=resend`，服务端会返回"无效的核查身份证"，
     * 导致未验证/验证码过期的账号永远收不到新邮件（2026-10-23 实测，官方 App 同问题）。
     * 网页版表单的字段与响应解析见 [WebConfirmClient]。
     */
    suspend fun resendSignUpCode(username: String, password: String): String =
        webConfirm.resend(username = username, password = password)

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    /** 收藏列表。`folderId` 为 null/空 = 全部收藏。 */
    suspend fun favorites(page: Int, folderId: String? = null, order: String = ORDER_BY_TIME): FavoritePage {
        val dto = guarded { api().favoritePage(page = page, folderId = folderId, order = order) }
        val pageData = dto.toPage()
        // `/favorite` 的 `folder_list` 每次都是**全量返回**（与 page / folder_id 无关，见
        // `docs/official-api-manga.md` §8.3）：任何一次收藏夹网络请求都顺手覆盖本地缓存，
        // 「我的」页的夹数量与详情页面板都能立刻用上最新名单（用户要求 2026-10-22）。
        if (dto.folderList != null) cache.putFolders(pageData.folders)
        return pageData
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
        // [favorites] 已负责把 `folder_list` 写回缓存
        return favorites(page = 1).folders
    }

    /**
     * 缓存里的收藏夹；没缓存 / 已作废时返回 null（调用方据此决定要不要转局部 loading）。
     *
     * @param allowStale true = 即使被标脏也返回旧名单。「我的」页先用它算夹数量铺上，
     *   网络回来再覆盖，避免副标题先少一截、1～2 秒后才补齐。
     */
    fun cachedFolders(allowStale: Boolean = false): List<AccountFolder>? =
        cache.folders?.takeIf { allowStale || !cache.foldersDirty }

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
        // `/favorite_folder` 不返回新 FID，只能重拉一次列表按名字找（夹名服务端唯一）。
        // 实测服务端偶尔"晚一拍"才把新夹放进 folder_list（多节点），所以重试几次再放弃。
        // 每轮 [favorites] 都会把最新 `folder_list` 覆盖进本地缓存，所以找到即缓存已更新。
        repeat(FOLDER_LOOKUP_ATTEMPTS) { attempt ->
            val page = favorites(page = 1)
            page.folders.firstOrNull { it.name == name }?.let { return it }
            if (attempt < FOLDER_LOOKUP_ATTEMPTS - 1) delay(FOLDER_LOOKUP_DELAY_MS)
        }
        // 服务端一直没把新夹放进列表：标脏，下次打开面板会重拉，而不是继续用缺一项的缓存
        cache.invalidateFolders()
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
        // 删除成功：本地名单直接去掉这一项并落盘（用户要求 2026-10-22，不再整份作废）
        cache.removeFolder(folderId)
    }

    // ------------------------------------------------------------------
    // 留言（我的留言 / 详情页发留言与回复）
    // ------------------------------------------------------------------

    /**
     * 「我的 → 留言记录」的一页留言（`GET /forum?uid={自己}&page=n`）。
     *
     * 走 [guarded]：未登录直接抛「请先登录」，401 统一翻译成「登录状态已失效」
     * （`docs/CONVENTIONS.md` §3 第 5 条：账号请求的前置检查只有这一处）。
     *
     * 每页 10 条、`page` 从 1 开始；`total` 上限 5000。每条都带 `AID` / `name`，
     * 所以列表能显示「来自 JMxxxx」并直接跳详情页。
     */
    suspend fun myComments(page: Int = 1): CommentPage {
        val uid = session.profile?.uid?.trim().orEmpty()
        if (uid.isEmpty()) throw NetworkError.Business("账号信息不完整，请重新登录")
        val resp = guarded { api().forumByUser(uid = uid, page = page) }
        val comments = resp.list.orEmpty()
            .map { it.toCommentRow() }
            .filter { it.cid.isNotEmpty() }
        val total = resp.total?.trim()?.toIntOrNull() ?: comments.size
        return CommentPage(page = page, total = total.coerceAtLeast(0), comments = comments)
    }

    /**
     * 发留言（[parentCid] 非空时是**回复**那条留言）。
     *
     * 服务端的成功/失败都在 `data.status` 里（HTTP 永远是 200），所以这里：
     * - `status == "ok"` → 返回服务端 `msg`（官方也是原样弹它，里面常带"获得 N 经验值"）；
     * - 否则抛 [NetworkError.Business]，`message` 是服务端 `msg` 原文
     *   （例如 `抱歉，暂时不可输入英文和数字，请确认后重新送出。`）。
     *
     * 例外的两种不发请求：正文为空（本地拦）、未登录（[guarded] 拦）。
     */
    suspend fun sendComment(
        aid: Long,
        content: String,
        parentCid: String? = null,
    ): CommentAction {
        val text = content.trim()
        commentDraftError(text)?.let { throw NetworkError.Business(it) }
        val dto = guarded { api().sendComment(aid = aid, content = text, parentCid = parentCid) }
        val message = commentMessage(dto.msg)
        if (!dto.isOk) throw NetworkError.Business(message ?: "留言发送失败，请稍后重试")
        return CommentAction(
            message = message ?: "留言已发布",
            cid = dto.cid?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
        )
    }

    /**
     * 删除自己发的一条留言（`POST /comment_delete{comment_id, aid}`）。
     *
     * 删别人的/已经被删的会回 `status=fail` + `msg`（实测 `無法刪除評論！ ？`），
     * 走 [NetworkError.Business] 交给界面弹出来——与发留言同一套判定。
     *
     * **不会**动详情页的评论内存缓存（`CommentCache`）：那是界面层的缓存，
     * 由调用方在成功后按本子号清掉（见 `AccountCommentsViewModel.delete`）。
     */
    suspend fun deleteComment(commentId: String, albumId: Long? = null): CommentAction {
        if (commentId.isBlank()) throw NetworkError.Business("找不到要删除的留言")
        val dto = guarded { api().deleteComment(commentId = commentId, aid = albumId) }
        val message = commentMessage(dto.msg)
        if (!dto.isOk) throw NetworkError.Business(message ?: "删除留言失败，请稍后重试")
        return CommentAction(message = message ?: "留言已删除", cid = dto.cid?.trim())
    }

    // ------------------------------------------------------------------
    // 个人资料
    // ------------------------------------------------------------------

    /**
     * 读个人资料（`GET /useredit/{uid}`）。
     *
     * 每次调用都**真的联网**（读页面能不能先用缓存是 UI 的事，不是这里的事）：
     * 资料编辑是"整条覆盖"，用一个过期快照去提交会把别处刚改的值冲掉。
     *
     * 顺手把结果记进缓存，并把昵称补进会话快照（「我的」页优先显示昵称）。
     */
    suspend fun userEdit(uid: String): UserEditDto {
        val dto = guarded { api().userEditGet(uid) }
        // 防御：资料接口正常返回的是整条资料（status="0"），万一回的是业务失败对象，
        // 不能把它当成"所有字段都是空"的资料塞进编辑表单。
        if (dto.status == "fail" || !dto.msg.isNullOrBlank()) {
            throw NetworkError.Business(cleanServerMessage(dto.msg) ?: "读取资料失败")
        }
        rememberProfileDetail(dto)
        return dto
    }

    /**
     * 保存个人资料（`POST /useredit/{uid}`，**整条覆盖**）。
     *
     * 调用方必须基于**刚刚 `GET` 到的表单**修改，再由 [UserProfileForm.toSubmitFields]
     * 把 24 个字段全量回传（少传一个就会被写成默认值）。
     *
     * 返回服务端回写的整条资料；同时更新资料缓存与会话里的昵称，
     * 这样返回资料页 / 「我的」页立刻就是新值，不用再联网。
     */
    suspend fun saveUserEdit(uid: String, form: UserProfileForm): UserEditDto {
        // 改密码的三条本地规则先拦一遍：只填一个 / 两次不一致，都不该发请求
        validatePasswordChange(form)?.let { throw NetworkError.Business(it) }
        val dto = guarded { api().userEditPost(uid, form.toSubmitFields()) }
        // 正常返回是"更新后的整条资料"（含 status="0"）；万一服务端回业务失败
        // （`{status:"fail", msg:"…"}`），DTO 里会带上 msg，不能当成保存成功。
        if (dto.status == "fail" || !dto.msg.isNullOrBlank()) {
            throw NetworkError.Business(cleanServerMessage(dto.msg) ?: "保存失败，请稍后重试")
        }
        rememberProfileDetail(dto)
        return dto
    }

    /**
     * 把一份资料结果收口：写缓存 + 把昵称补进会话快照。
     *
     * 昵称不在登录响应里，只能从资料接口拿到；所以任何一次成功的资料读取/保存
     * 都顺手更新快照，让「我的」摘要卡显示用户自己设的昵称（用户 2026-10-23 要求）。
     */
    suspend fun rememberProfileDetail(dto: UserEditDto) {
        // 请求在飞的时候用户可能已经退出登录：此时不能再把上一个账号的资料写回缓存
        if (!session.isSignedIn) return
        cache.putProfileDetail(dto)
        val nick = dto.nickName?.trim()?.takeIf { it.isNotEmpty() }
        val profile = session.profile ?: return
        if (profile.nickName == nick) return
        session.updateProfile(profile.copy(nickName = nick))
    }

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
@Serializable
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
    /**
     * 封面主 tag：接口的 `category.title`（用户 2026-10-22 明确只认这一个字段）。
     *
     * 取不到就是 null，界面不画 tag；**不要**用 `category_sub.title` 或别的字段来凑。
     */
    val categoryTag: String? = null,
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
        AccountAlbum(
            id = id,
            name = item.name,
            author = item.author,
            // 主 tag 只取 `category.title`；没有就是 null（不回落到子分类）
            categoryTag = item.category?.title?.trim()?.takeIf { it.isNotBlank() },
        )
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
