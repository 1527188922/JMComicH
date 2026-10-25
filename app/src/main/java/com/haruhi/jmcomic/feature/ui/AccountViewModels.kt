package com.haruhi.jmcomic.feature.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.account.AccountState
import com.haruhi.jmcomic.core.account.MemberProfile
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.feature.api.UserEditDto
import com.haruhi.jmcomic.feature.data.AccountAlbum
import com.haruhi.jmcomic.feature.data.AccountCache
import com.haruhi.jmcomic.feature.data.AccountFolder
import com.haruhi.jmcomic.feature.data.AccountRepository
import com.haruhi.jmcomic.feature.data.AlbumsSnapshot
import com.haruhi.jmcomic.feature.data.CommentCache
import com.haruhi.jmcomic.feature.data.CommentRow
import com.haruhi.jmcomic.feature.data.FavoritesSnapshot
import com.haruhi.jmcomic.feature.data.Repository
import com.haruhi.jmcomic.feature.data.SIGN_UP_CODE_MAX
import com.haruhi.jmcomic.feature.data.SignUpForm
import com.haruhi.jmcomic.feature.data.SignUpValidation
import com.haruhi.jmcomic.feature.data.UserProfileForm
import com.haruhi.jmcomic.feature.data.limitCodePoints
import com.haruhi.jmcomic.feature.data.mergeComments
import com.haruhi.jmcomic.feature.data.toProfileForm
import com.haruhi.jmcomic.feature.data.validatePasswordChange
import com.haruhi.jmcomic.feature.data.validateSignUp
import com.haruhi.jmcomic.feature.data.validateSignUpCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

// ======================================================================
// 我的（原「更多」）
// ======================================================================

/**
 * 「我的」页状态。
 *
 * 账号部分**全部来自 [AccountRepository.state]**（唯一真相源）；
 * 「最近浏览」条数仍来自本机数据库（本机数据，与账号无关）。
 */
data class MyUiState(
    val account: AccountState = AccountState.Anonymous,
    val recentCount: Int = 0,
    /** 账号收藏夹个数（优先来自本地缓存，进页面后联网校正）。 */
    val folderCount: Int? = null,
    val loggingOut: Boolean = false,
    /** 下拉刷新收藏夹数量中（顶部下拉圈）。 */
    val refreshing: Boolean = false,
) {
    val profile: MemberProfile? get() = account.profileOrNull
    val signedIn: Boolean get() = account.isSignedIn

    /** 账户卡第一行下面的那行小字：`Lv.4 · 地上的月影`。 */
    val levelLine: String?
        get() = profile?.let { p ->
            buildString {
                append("Lv.").append(p.level)
                if (p.levelName.isNotBlank()) append(" · ").append(p.levelName)
            }
        }

    /** 会员行；**非会员返回 null**（用户要求：不是会员就不展示会员信息）。 */
    val memberLine: String?
        get() = profile?.takeIf { it.adFree }?.let { p ->
            val until = p.memberUntil
            if (until.isNullOrBlank()) "超级会员" else "超级会员 · 至 $until"
        }

    /** 「收藏」副标题：`178/600 · 2 个收藏夹`（上限拿不到时就只显示已收藏数）。 */
    val favoritesSummary: String?
        get() = profile?.let { p ->
            buildString {
                append(p.albumFavorites)
                if (p.albumFavoritesMax > 0) append('/').append(p.albumFavoritesMax)
                append(" 本")
                folderCount?.let { append(" · ").append(it).append(" 个收藏夹") }
            }
        }
}

class MyViewModel(
    private val account: AccountRepository,
    repo: Repository,
) : ViewModel() {

    private val recentCount: StateFlow<Int> = repo.observeRecentAlbums()
        .map { it.size }
        .catch { e ->
            Timber.tag("jm").w(e, "最近浏览条数查询失败")
            emit(0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 收藏夹个数：初始值直接取本地缓存（落盘的夹名单），首帧就能显示，
     * 之后每次进页面联网校正（用户要求 2026-10-22）。
     */
    private val folderCount = MutableStateFlow(account.cachedFolders(allowStale = true)?.size)
    private val loggingOut = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)

    /** 防止 onResume 与下拉刷新同时打两个 `/favorite`。 */
    private var folderRequestInFlight = false

    val state: StateFlow<MyUiState> = combine(
        account.state,
        recentCount,
        folderCount,
        loggingOut,
        refreshing,
    ) { accountState, recent, folders, out, refreshingNow ->
        MyUiState(
            account = accountState,
            recentCount = recent,
            folderCount = folders,
            loggingOut = out,
            refreshing = refreshingNow,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MyUiState(
            account = account.state.value,
            folderCount = account.cachedFolders(allowStale = true)?.size,
        ),
    )

    /**
     * 进页面 / 回到页面：**只读本地缓存，不联网**（用户要求 2026-10-22：页面上不能有信息跳动）。
     *
     * 只有本地完全没有缓存（刚登录、首次安装）时才请求一次把数量补上。
     */
    fun restoreCachedFolderCount() {
        if (!account.isSignedIn) {
            folderCount.value = null
            return
        }
        val cached = account.cachedFolders(allowStale = true)
        if (cached != null) {
            folderCount.value = cached.size
            return
        }
        requestFolderCount(showRefreshing = false)
    }

    /** 下拉刷新：强制联网重拉，成功后覆盖本地缓存（用户主动触发，允许数量变化）。 */
    fun refreshFolderCount() = requestFolderCount(showRefreshing = true)

    private fun requestFolderCount(showRefreshing: Boolean) {
        if (folderRequestInFlight) return
        folderRequestInFlight = true
        if (showRefreshing) refreshing.value = true
        viewModelScope.launch {
            try {
                if (!account.isSignedIn) {
                    folderCount.value = null
                    return@launch
                }
                // 网络回来前先保持缓存里的数量，避免刷新过程中副标题闪动
                account.cachedFolders(allowStale = true)?.size?.let { folderCount.value = it }
                runCatching { account.favorites(page = 1).folders.size }
                    .onSuccess { folderCount.value = it }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Timber.tag("jm").d(e, "拉账号收藏夹个数失败")
                    }
            } finally {
                refreshing.value = false
                folderRequestInFlight = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            loggingOut.value = true
            runCatching { account.logout() }
                .onFailure { if (it is CancellationException) throw it else Timber.tag("jm").w(it, "退出登录失败") }
            folderCount.value = null
            loggingOut.value = false
        }
    }
}

// ======================================================================
// 登录
// ======================================================================

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val usernameError: String? = null,
    val passwordError: String? = null,
    /** 接口级 / 本地校验错误（弹窗正文）。 */
    val error: String? = null,
    /** 错误弹窗标题（"登录失败" / "请检查填写内容"）。 */
    val errorTitle: String = "登录失败",
    /** 登录成功（页面据此关闭）。 */
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = !submitting
}

/**
 * 登录页 ViewModel。
 *
 * 只做登录（注册 / 验证码 / 找回密码本版未接入，页面上有一行说明）。
 * 请求纪律：**空字段不发请求**、提交中重复点击忽略、服务端错误原样展示。
 */
class AccountAuthViewModel(private val account: AccountRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun setUsername(value: String) = _state.update {
        it.copy(username = value, usernameError = null, error = null)
    }

    fun setPassword(value: String) = _state.update {
        it.copy(password = value, passwordError = null, error = null)
    }

    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun submit() {
        val current = _state.value
        if (current.submitting) return
        val username = current.username.trim()
        val password = current.password
        var hasError = false
        var next = current.copy(error = null)
        if (username.isEmpty()) {
            next = next.copy(usernameError = "请输入用户名")
            hasError = true
        }
        if (password.isEmpty()) {
            next = next.copy(passwordError = "请输入密码")
            hasError = true
        }
        if (hasError) {
            // 空字段校验保持页内 `supportingText`（用户明确要求：这类不要弹框）
            _state.value = next
            return
        }
        _state.update { it.copy(submitting = true, errorTitle = "登录失败") }
        viewModelScope.launch {
            runCatching { account.login(username, password) }
                .onSuccess {
                    _state.update { s -> s.copy(submitting = false, done = true, error = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        s -> s.copy(submitting = false, errorTitle = "登录失败", error = e.toUserMessage())
                    }
                }
        }
    }
}

// ======================================================================
// 账号收藏（收藏夹列表）
// ======================================================================

data class AccountFavoritesUiState(
    val signedIn: Boolean = true,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** 不含「全部收藏」（那是虚拟卡）。 */
    val folders: List<AccountFolder> = emptyList(),
    /** 全部收藏的总数（虚拟卡上的数字）。 */
    val total: Int = 0,
    val allCoverAlbumId: Long? = null,
    val error: String? = null,
    val message: String? = null,
    /** 正在执行增删改的夹（按钮转圈用）。 */
    val busyFolderId: String? = null,
    /**
     * 增删改完成标记：每次操作结束（成功 / 失败 / 校验提前返回）都自增一次。
     * 页面据此在**接口响应之后**再收起弹窗（弹窗里确认按钮转圈）。
     */
    val actionFinished: Long = 0,
)

/**
 * 账号收藏夹列表（`docs/account-design.md` §5）。
 *
 * 首屏只发一个请求（全部收藏第一页），拿到 `folder_list`；
 * 各夹的数量与封面再**并发补拉**（每夹一次 `?folder_id=X&page=1`，上限 3 并发），
 * 拉不到就显示「…」——不退化成整页报错。
 */
class AccountFavoritesViewModel(
    private val account: AccountRepository,
    /** 进程内快照：进页面先铺上次的内容，避免每次进来都重新联网而闪一下。 */
    private val cache: AccountCache,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountFavoritesUiState())
    val state: StateFlow<AccountFavoritesUiState> = _state.asStateFlow()

    /** 正在拉数据（防重复：连续下拉 / 下拉时又点了重试，都只发一次）。 */
    private var inFlight = false

    init {
        restoreOrLoad()
    }

    /**
     * 进页面：**有快照就直接铺**，不转圈、不联网（用户反馈：每次进来都重新加载会闪一下）。
     *
     * 只有快照被"动过"（[AccountCache.favoritesDirty]，例如在详情页加了收藏）才静默补一次，
     * 补的时候内容还在，不闪。
     */
    private fun restoreOrLoad() {
        if (!account.isSignedIn) {
            _state.update { it.copy(signedIn = false, loading = false) }
            return
        }
        val cached = cache.favorites
        if (cached != null) {
            _state.value = AccountFavoritesUiState(
                signedIn = true,
                loading = false,
                refreshing = false,
                folders = cached.folders,
                total = cached.total,
                allCoverAlbumId = cached.allCoverAlbumId,
            )
            if (cache.favoritesDirty) fetch(silent = true)
            return
        }
        fetch(silent = false)
    }

    /**
     * 从夹内页 / 详情页回到本页：先按内存快照同步一次。
     *
     * 取消收藏已由 [AccountCache.removeAlbum] 就地减掉（总数 / 受影响夹的数量与封面），
     * 所以这里不用联网、也不用用户下拉；只有快照被标脏（那本在没缓存到的夹里）时才静默补一次。
     */
    fun onScreenResumed() {
        if (!account.isSignedIn) return
        cache.favorites?.let { snapshot ->
            _state.update { current ->
                if (current.loading || current.refreshing) {
                    current
                } else {
                    current.copy(
                        folders = snapshot.folders,
                        total = snapshot.total,
                        allCoverAlbumId = snapshot.allCoverAlbumId,
                    )
                }
            }
        }
        if (cache.favoritesDirty) fetch(silent = true)
    }

    /** 用户下拉刷新 / 报错后的「重试」：走顶部下拉圈。 */
    fun refresh() = fetch(silent = false)

    private fun fetch(silent: Boolean) {
        if (inFlight) return
        if (!account.isSignedIn) {
            _state.update { it.copy(signedIn = false, loading = false) }
            return
        }
        inFlight = true
        val hasContent = _state.value.folders.isNotEmpty() || _state.value.total > 0
        _state.update {
            it.copy(
                signedIn = true,
                // 首屏（没有内容）才转整页 LoadingBox；有内容时只在静默刷新，不闪烁
                loading = !hasContent && !silent,
                refreshing = hasContent && !silent,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val page = runCatching { account.favorites(page = 1) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            // 已有内容时不清空列表，只弹一句提示；首屏失败才显示整页错误
                            error = if (hasContent) null else e.toUserMessage(),
                            message = if (hasContent) e.toUserMessage() else it.message,
                        )
                    }
                    return@launch
                }
                // 收藏夹名单已由 `AccountRepository.favorites()` 统一写回本地缓存
                // （`folder_list` 每次全量返回，任何联网请求都会覆盖它）
                // 这一页（`folder_id` 空 = 全部收藏，`o=mr`）顺手缓存成「全部收藏」的第一页：
                // 点进「全部收藏」时就不用再发一次请求（用户要求 2026-10-21）
                cache.putAlbums(
                    folderId = null,
                    order = AccountRepository.ORDER_BY_TIME,
                    snapshot = AlbumsSnapshot(
                        albums = page.albums,
                        total = page.total,
                        nextPage = 2,
                    ),
                )
                // 列表先换名字，但**沿用旧的「N 本 / 封面」**——否则会先闪成「…」再填上
                val previous = _state.value.folders.associateBy { it.fid }
                _state.update {
                    it.copy(
                        folders = page.folders.map { folder ->
                            previous[folder.fid]?.let { old ->
                                folder.copy(count = old.count, coverAlbumId = old.coverAlbumId)
                            } ?: folder
                        },
                        total = page.total,
                        allCoverAlbumId = page.albums.firstOrNull()?.id,
                        error = null,
                    )
                }
                // 关键：**等所有并发的补拉都结束**才收掉 loading / 下拉圈（用户要求），
                // 不然 LoadingBox 先消失、卡片先显示「…」，再一个个变成「N 本 + 封面」。
                backfillFolders(page.folders)
                _state.update { it.copy(loading = false, refreshing = false, error = null) }
                publishCache()
                // 有夹没补拉成功（数量未知）→ 标脏：下次进来静默重试一次，不闪
                if (_state.value.folders.any { it.count == null }) cache.invalidateFavorites()
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * 给每个夹补「N 本 + 封面」：一次 `page=1` 就够（`total` 与第一条都在里面）。
     *
     * 是 **suspend** 的：调用方要等它（以及它内部所有并发请求）全部结束才收 loading。
     */
    private suspend fun backfillFolders(folders: List<AccountFolder>) {
        if (folders.isEmpty()) return
        val semaphore = Semaphore(MAX_BACKFILL_CONCURRENCY)
        coroutineScope {
            folders.map { folder ->
                async {
                    semaphore.withPermit {
                        runCatching { account.favorites(page = 1, folderId = folder.fid) }
                            .getOrNull()
                            ?.let { page ->
                                // 这次补拉拿到的就是该夹的第一页（20 条）——顺手缓存下来，
                                // 之后点进这个夹（`o=mr`）就不用再请求一次了（用户要求 2026-10-21）
                                cache.putAlbums(
                                    folderId = folder.fid,
                                    order = AccountRepository.ORDER_BY_TIME,
                                    snapshot = AlbumsSnapshot(
                                        albums = page.albums,
                                        total = page.total,
                                        nextPage = 2,
                                    ),
                                )
                                _state.update { current ->
                                    current.copy(
                                        folders = current.folders.map { f ->
                                            if (f.fid == folder.fid) {
                                                f.copy(
                                                    count = page.total,
                                                    coverAlbumId = page.albums.firstOrNull()?.id,
                                                )
                                            } else {
                                                f
                                            }
                                        },
                                    )
                                }
                            }
                    }
                }
            }.awaitAll()
        }
    }

    private fun publishCache() {
        val current = _state.value
        if (!current.signedIn) return
        cache.putFavorites(
            FavoritesSnapshot(
                folders = current.folders,
                total = current.total,
                allCoverAlbumId = current.allCoverAlbumId,
            ),
        )
    }

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update {
                it.copy(message = "请输入收藏夹名称", actionFinished = it.actionFinished + 1)
            }
            return
        }
        _state.update { it.copy(busyFolderId = BUSY_CREATE) }
        viewModelScope.launch {
            val result = runCatching { account.createFolder(trimmed) }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                _state.update {
                    it.copy(
                        message = failure.toUserMessage(),
                        busyFolderId = null,
                        actionFinished = it.actionFinished + 1,
                    )
                }
                return@launch
            }
            val created = result.getOrNull()
            _state.update {
                it.copy(
                    // 消息里不带夹名：文字隐私下夹名只能露首字符，这里直接不显示，避免绕过遮蔽
                    message = "已创建收藏夹",
                    busyFolderId = null,
                    // 先就地补进列表：弹窗关闭与提示出现的同一帧就能看到新夹，不用等静默刷新
                    folders = created?.let { folder ->
                        (it.folders + folder).distinctBy { f -> f.fid }
                    } ?: it.folders,
                    actionFinished = it.actionFinished + 1,
                )
            }
            fetch(silent = true)
        }
    }

    fun rename(folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _state.update {
                it.copy(message = "名称不能为空", actionFinished = it.actionFinished + 1)
            }
            return
        }
        _state.update { it.copy(busyFolderId = folderId) }
        viewModelScope.launch {
            val result = runCatching { account.renameFolder(folderId, trimmed) }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                _state.update {
                    it.copy(
                        message = failure.toUserMessage(),
                        busyFolderId = null,
                        actionFinished = it.actionFinished + 1,
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    // 提示里不带夹名：与新建一致，隐私模式下不通过 snackbar 露出全名
                    message = "已重命名收藏夹",
                    busyFolderId = null,
                    folders = it.folders.map { folder ->
                        if (folder.fid == folderId) folder.copy(name = trimmed) else folder
                    },
                    actionFinished = it.actionFinished + 1,
                )
            }
            fetch(silent = true)
        }
    }

    fun delete(folderId: String) {
        _state.update { it.copy(busyFolderId = folderId) }
        viewModelScope.launch {
            val result = runCatching { account.deleteFolder(folderId) }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                _state.update {
                    it.copy(
                        message = failure.toUserMessage(),
                        busyFolderId = null,
                        actionFinished = it.actionFinished + 1,
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    message = "收藏夹已删除",
                    busyFolderId = null,
                    folders = it.folders.filterNot { folder -> folder.fid == folderId },
                    actionFinished = it.actionFinished + 1,
                )
            }
            fetch(silent = true)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val MAX_BACKFILL_CONCURRENCY = 3
        const val BUSY_CREATE = "__create__"
    }
}

// ======================================================================
// 账号收藏（夹内漫画网格）
// ======================================================================

data class AccountAlbumsUiState(
    val title: String = "",
    /** null = 全部收藏。 */
    val folderId: String? = null,
    val albums: List<AccountAlbum> = emptyList(),
    val total: Int = 0,
    val order: String = AccountRepository.ORDER_BY_TIME,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** 用户下拉刷新中（顶部下拉圈）。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val removingId: Long? = null,
    /** 移出操作的完成标记，语义同 [AccountFavoritesUiState.actionFinished]。 */
    val actionFinished: Long = 0,
) {
    val isEmpty: Boolean get() = !loading && error == null && albums.isEmpty()
    val hasMore: Boolean get() = albums.size < total
}

/**
 * 收藏夹内页。
 *
 * 缓存策略（用户反馈 2026-10-21）：**收藏夹内容基本不变**，所以进页面先用
 * [AccountCache] 里的快照铺内容（不转圈、不联网）；只有"收藏关系变了"
 * （本机加/移收藏、移夹、新建/删除夹）才作废（见 `AccountCache.invalidateAlbums`），
 * 或者用户**下拉释放**时强制重拉。换排序会按 `folderId#order` 用另一份缓存。
 */
class AccountAlbumsViewModel(
    private val account: AccountRepository,
    private val cache: AccountCache,
    private val folderId: String?,
    title: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AccountAlbumsUiState(
            title = title.ifBlank { if (folderId.isNullOrBlank()) "全部收藏" else "收藏夹" },
            folderId = folderId,
        ),
    )
    val state: StateFlow<AccountAlbumsUiState> = _state.asStateFlow()

    /** 下次「加载更多」要请求的页码。 */
    private var nextPage = 1

    private var inFlight = false

    init {
        restoreOrLoad()
    }

    private fun restoreOrLoad() {
        if (!account.isSignedIn) {
            _state.update { it.copy(loading = false, error = "登录状态已失效，请重新登录") }
            return
        }
        val cached = cache.cachedAlbums(folderId, _state.value.order)
        if (cached != null) {
            nextPage = cached.nextPage
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    albums = cached.albums,
                    total = cached.total,
                    error = null,
                )
            }
            return
        }
        refresh()
    }

    /**
     * 从详情页回到本页：按内存快照同步一次（不联网）。
     *
     * 在详情页取消收藏时 [AccountCache.removeAlbum] 已把这本从各夹快照里删掉，
     * 所以回来就能直接少这一本，不用下拉刷新（用户要求 2026-10-22）。
     * 正在加载 / 下拉 / 翻页时不覆盖，避免和手上的请求打架。
     */
    fun onScreenResumed() {
        if (inFlight) return
        val current = _state.value
        if (current.loading || current.refreshing || current.loadingMore) return
        val cached = cache.cachedAlbums(folderId, current.order) ?: return
        nextPage = cached.nextPage
        _state.update { it.copy(albums = cached.albums, total = cached.total) }
    }

    /** 用户下拉刷新 / 报错后的「重试」：强制联网，成功后覆盖缓存。 */
    fun refresh() {
        if (inFlight) return
        if (!account.isSignedIn) {
            _state.update { it.copy(loading = false, error = "登录状态已失效，请重新登录") }
            return
        }
        inFlight = true
        val current = _state.value
        val hasContent = current.albums.isNotEmpty()
        _state.update {
            it.copy(
                // 有内容时只转下拉圈；首屏才转整页 loading
                loading = !hasContent,
                refreshing = hasContent,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { account.favorites(page = 1, folderId = current.folderId, order = current.order) }
                .onSuccess { page ->
                    nextPage = 2
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            albums = page.albums,
                            total = page.total,
                            error = null,
                        )
                    }
                    publishCache()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = if (hasContent) null else e.toUserMessage(),
                            message = if (hasContent) e.toUserMessage() else it.message,
                        )
                    }
                }
            inFlight = false
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.refreshing || !current.hasMore) return
        _state.update { it.copy(loadingMore = true) }
        val page = nextPage
        viewModelScope.launch {
            runCatching { account.favorites(page = page, folderId = current.folderId, order = current.order) }
                .onSuccess { result ->
                    nextPage = page + 1
                    _state.update { s ->
                        val merged = (s.albums + result.albums).distinctBy { it.id }
                        s.copy(loadingMore = false, albums = merged, total = result.total)
                    }
                    publishCache()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(loadingMore = false, message = e.toUserMessage()) }
                }
        }
    }

    fun setOrder(order: String) {
        if (_state.value.order == order) return
        // 换排序 = 换一份缓存 key；清掉当前内容，交给 restoreOrLoad 决定用缓存还是重拉
        _state.update { it.copy(order = order, albums = emptyList(), total = 0, loading = true) }
        nextPage = 1
        restoreOrLoad()
    }

    /** 从收藏里移出（= 取消收藏；官方不支持"只移出夹"，见 design §4.3 L2）。 */
    fun remove(albumId: Long) {
        if (_state.value.removingId != null) {
            // 已有移出在飞：立即报完成，避免页面的弹窗一直等不到响应结束的信号
            _state.update { it.copy(actionFinished = it.actionFinished + 1) }
            return
        }
        _state.update { it.copy(removingId = albumId) }
        viewModelScope.launch {
            runCatching { account.toggleFavorite(albumId) }
                .onSuccess { result ->
                    _state.update { s ->
                        s.copy(
                            removingId = null,
                            albums = if (result.added) s.albums else s.albums.filterNot { it.id == albumId },
                            total = if (result.added) s.total else (s.total - 1).coerceAtLeast(0),
                            message = if (result.added) "已重新加入收藏" else "已从收藏移出",
                            actionFinished = s.actionFinished + 1,
                        )
                    }
                    // 收藏关系变了，全局夹缓存已被作废；把当前这份（已减掉那本）重新写回去，
                    // 这样返回列表再进来时不用白等一次网络
                    publishCache()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            removingId = null,
                            message = e.toUserMessage(),
                            actionFinished = it.actionFinished + 1,
                        )
                    }
                }
        }
    }

    private fun publishCache() {
        val current = _state.value
        cache.putAlbums(
            folderId = current.folderId,
            order = current.order,
            snapshot = AlbumsSnapshot(
                albums = current.albums,
                total = current.total,
                nextPage = nextPage,
            ),
        )
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

// ======================================================================
// 留言记录（我的留言）
// ======================================================================

data class AccountCommentsUiState(
    val signedIn: Boolean = true,
    /** 首屏（还没有内容）正在拉第一页。 */
    val loading: Boolean = true,
    /** 用户下拉刷新中（顶部下拉圈）。 */
    val refreshing: Boolean = false,
    /** 正在追加下一页（列表底部一行）。 */
    val loadingMore: Boolean = false,
    val comments: List<CommentRow> = emptyList(),
    /** `/forum` 的 `total`（上限 5000）。 */
    val total: Int = 0,
    /** 已经到底（服务端没有更多）。 */
    val reachedEnd: Boolean = false,
    /** 首屏失败的原因（有内容时失败只弹提示，不清列表）。 */
    val error: String? = null,
    val message: String? = null,
    /** 正在删的那条 `cid`（删除按钮转圈 + 防重复点击）。 */
    val deletingCid: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && comments.isEmpty()

    /** 还能不能翻下一页（`total` 被截断到 5000，所以同时看"给没给满一页"）。 */
    val hasMore: Boolean get() = !reachedEnd && comments.size < total
}

/**
 * 「我的 → 留言记录」：我发过的留言（`GET /forum?uid={自己}`，10/页）。
 *
 * 与详情页评论的关系：
 * - **读**走 [AccountRepository.myComments]（要登录；官方 `Member/CommentList.tsx` 也是登录后才拉）；
 * - **删**走官方的 `POST /comment_delete`，成功后**就地**从列表里去掉那一行（不整页重拉，
 *   用户的滚动位置与已翻的页都不会丢）；
 * - 详情页的评论内存缓存（[CommentCache]）在被删留言所属的本子上**清掉**，
 *   否则从留言记录点进那本漫画还能看到已经删掉的留言。
 *
 * 不做跨页面缓存：这是"我发过什么"的列表，服务端真相，删一条必须立刻消失；
 * 进页面拉一次、用户下拉再拉一次就够了（10 条/页，一次请求很轻）。
 */
class AccountCommentsViewModel(
    private val account: AccountRepository,
    private val commentCache: CommentCache,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountCommentsUiState())
    val state: StateFlow<AccountCommentsUiState> = _state.asStateFlow()

    /** 下一次要请求的页码（从 1 开始）。 */
    private var nextPage = 1

    /** 一次只飞一个请求：连续下拉 / 下拉时又点重试都只发一次。 */
    private var inFlight = false

    init {
        load(reset = true)
    }

    /** 用户下拉刷新 / 报错后的「重试」。 */
    fun refresh() = load(reset = true)

    /** 重试：列表空着就重拉第一页，否则接着拉没拿到的下一页。 */
    fun retry() = load(reset = _state.value.comments.isEmpty())

    /** 列表滑到底：追加下一页。 */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.refreshing || !current.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        if (inFlight) return
        if (!account.isSignedIn) {
            _state.update { it.copy(signedIn = false, loading = false) }
            return
        }
        inFlight = true
        val page = if (reset) 1 else nextPage
        val hasContent = _state.value.comments.isNotEmpty()
        _state.update {
            it.copy(
                signedIn = true,
                // 首屏才转整页 loading；有内容时只转下拉圈 / 底部一行，不闪
                loading = reset && !hasContent,
                refreshing = reset && hasContent,
                loadingMore = !reset,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { account.myComments(page = page) }
                .onSuccess { result ->
                    // 翻页期间有人发了新留言，后面的条目会整体后移 → 按 cid 去重（与详情页同一套）
                    val merged = if (reset) {
                        mergeComments(emptyList(), result.comments)
                    } else {
                        mergeComments(_state.value.comments, result.comments)
                    }
                    nextPage = result.page + 1
                    val reachedEnd = result.comments.isEmpty() ||
                        result.comments.size < AlbumDetailViewModel.COMMENT_PAGE_SIZE ||
                        merged.size >= result.total
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            loadingMore = false,
                            comments = merged,
                            total = result.total,
                            reachedEnd = reachedEnd,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Timber.tag("jm").w(e, "拉我的留言失败：page=%d", page)
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            loadingMore = false,
                            // 已有内容时不清空列表，只弹一句；首屏失败才显示整页错误
                            error = if (hasContent) null else e.toUserMessage(),
                            message = if (hasContent) e.toUserMessage() else it.message,
                        )
                    }
                }
            inFlight = false
        }
    }

    /**
     * 删除一条留言。
     *
     * 成功后**就地**去掉这一行并把 `total` 减一（不重拉：重拉会把用户翻到的页与滚动位置打回原处）。
     * 失败（别人的 / 已经被删 / 网络）原样弹服务端文案，列表不动。
     */
    fun delete(comment: CommentRow) {
        val cid = comment.cid.trim()
        if (cid.isEmpty() || _state.value.deletingCid != null) return
        _state.update { it.copy(deletingCid = cid) }
        viewModelScope.launch {
            runCatching { account.deleteComment(commentId = cid, albumId = comment.albumId) }
                .onSuccess { result ->
                    _state.update { current ->
                        current.copy(
                            deletingCid = null,
                            comments = current.comments.filterNot { it.cid == cid },
                            total = (current.total - 1).coerceAtLeast(0),
                            message = result.message,
                        )
                    }
                    // 详情页的评论缓存里还留着这条：不清的话点进那本还能看到它
                    comment.albumId?.let { commentCache.clear(it) }
                    // 极端情况：删掉的是这一页最后一条、而后面还有留言 —— 列表会空成
                    // "还没有留言记录"的假象，所以补一次第一页（`total` 已经减过，用 >0 判定）
                    val now = _state.value
                    if (now.comments.isEmpty() && now.total > 0 && !now.reachedEnd) refresh()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Timber.tag("jm").w(e, "删除留言失败：cid=%s", cid)
                    _state.update { it.copy(deletingCid = null, message = e.toUserMessage()) }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

// ======================================================================
// 个人资料（只读）
// ======================================================================

data class AccountProfileUiState(
    val signedIn: Boolean = true,
    val profile: MemberProfile? = null,
    val detail: UserEditDto? = null,
    val loading: Boolean = true,
    /** 用户下拉刷新中（顶部下拉圈）。 */
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val memberLine: String?
        get() = profile?.takeIf { it.adFree }?.let { p ->
            val until = p.memberUntil
            if (until.isNullOrBlank()) "超级会员" else "超级会员 · 至 $until"
        }
}

/**
 * 个人资料（只读）。
 *
 * 与收藏页同一套缓存策略：进页面**先铺上次的资料**（[AccountCache.profileDetail]），
 * 不转圈、不联网；只有被动过或用户下拉时才重新拉
 * （用户反馈 2026-10-21：每次进来都刷新会闪一下）。
 */
class AccountProfileViewModel(
    private val account: AccountRepository,
    private val cache: AccountCache,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AccountProfileUiState(profile = account.profile, signedIn = account.isSignedIn),
    )
    val state: StateFlow<AccountProfileUiState> = _state.asStateFlow()

    private var inFlight = false

    init {
        restoreOrLoad()
    }

    private fun restoreOrLoad() {
        val profile = account.profile
        if (!account.isSignedIn || profile == null) {
            _state.update { it.copy(signedIn = false, loading = false) }
            return
        }
        val cached = cache.profileDetail
        if (cached != null) {
            _state.update {
                it.copy(signedIn = true, profile = profile, detail = cached, loading = false, refreshing = false)
            }
            if (cache.profileDirty) fetch(silent = true)
            return
        }
        fetch(silent = false)
    }

    /** 用户下拉刷新 / 报错后的「重试」。 */
    fun refresh() = fetch(silent = false)

    /**
     * 从「编辑资料」返回时同步一次。
     *
     * 保存成功后 `AccountRepository.saveUserEdit` 已经把新资料写进 [AccountCache]，
     * 这里只负责把它搬到界面状态上——否则返回资料页看到的还是编辑前的内容，
     * 用户会以为没保存成功。
     */
    fun onScreenResumed() {
        if (inFlight || _state.value.loading || _state.value.refreshing) return
        val detail = cache.profileDetail ?: return
        _state.update { current ->
            current.copy(
                profile = account.profile,
                detail = detail,
                error = null,
            )
        }
    }

    private fun fetch(silent: Boolean) {
        if (inFlight) return
        val profile = account.profile
        if (!account.isSignedIn || profile == null) {
            _state.update { it.copy(signedIn = false, loading = false, refreshing = false) }
            return
        }
        inFlight = true
        val hasContent = _state.value.detail != null
        _state.update {
            it.copy(
                profile = profile,
                loading = !hasContent && !silent,
                refreshing = hasContent && !silent,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { account.userEdit(profile.uid) }
                .onSuccess { detail ->
                    // 缓存与昵称快照由 `AccountRepository.userEdit` 统一写入；
                    // 这里把会话里的最新快照同步过来，头像首字才能立刻用上昵称。
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            profile = account.profile ?: it.profile,
                            detail = detail,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // 资料拉不到不算致命：快照还能显示昵称/等级/收藏数
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = if (hasContent) null else e.toUserMessage(),
                        )
                    }
                }
            inFlight = false
        }
    }
}

// ======================================================================
// 注册（表单 → 邮箱验证码 → 自动登录）
// ======================================================================

/** 注册页当前停在哪一步。 */
enum class SignUpStep {
    /** 填注册信息。 */
    FORM,

    /** 已提交注册，等邮箱验证码。 */
    VERIFY,
}

/**
 * 注册页状态。
 *
 * 表单字段的本地校验结果单独放 [validation]，服务端/网络错误放 [error]，
 * 这样"某个输入框该红"与"服务器说邮箱已存在"是两条通道，不会互相覆盖。
 */
data class SignUpUiState(
    val step: SignUpStep = SignUpStep.FORM,
    val form: SignUpForm = SignUpForm(),
    val validation: SignUpValidation = SignUpValidation(),
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    /** 服务端 / 网络错误（弹窗正文）。 */
    val error: String? = null,
    /** 错误对话框的标题（"注册失败" / "验证失败" / "发送失败"）。 */
    val errorTitle: String = "提示",
    /** 验证码字段的**本地**校验错误（空验证码），显示在验证码输入框下方、不弹窗。 */
    val codeError: String? = null,
    /** 「已有验证码」入口的用户名 / 密码**本地**校验错误，显示在对应输入框下方（与登录页一致）。 */
    val verifyUsernameError: String? = null,
    val verifyPasswordError: String? = null,
    /** 一次性提示（注册成功、验证码已重发等），页内展示、不弹窗。 */
    val notice: String? = null,
    /** 验证码重发冷却剩余秒数（官方 60 秒）。 */
    val resendCooldown: Int = 0,
    /** 正在重发验证信（走网页版接口，可能比普通接口慢，按钮要给出反馈）。 */
    val resending: Boolean = false,
    /** 验证码输入。 */
    val code: String = "",
    /** 注册请求已经成功（账号已创建，只差邮箱验证）；回表单后给一个「继续验证」出口。 */
    val registered: Boolean = false,
    /** 从登录页「已有验证码」进来：没有注册表单，直接填用户名 / 密码 / 验证码。 */
    val verifyOnly: Boolean = false,
    /** 验证码已通过，但自动登录失败：界面改为「去登录」。 */
    val verifiedNeedLogin: Boolean = false,
    /** 注册 + 验证 + 登录全部完成（页面据此关闭并跳到 next）。 */
    val done: Boolean = false,
) {
    val canResend: Boolean get() = resendCooldown <= 0 && !submitting && !resending
    val canSubmit: Boolean get() = !submitting

    /** 验证码步骤要显示的邮箱（注册时填的那个）。 */
    val email: String get() = form.email.trim()
    val username: String get() = form.username.trim()
}

/**
 * 注册页 ViewModel（`docs/account-design.md` §8）。
 *
 * 流程：填表 → `POST /register` → 验证码步骤 → `POST /confirm` → 用刚才的密码自动登录。
 * 验证码重发走同一个 `/confirm`（换成 `username + password`），60 秒冷却。
 *
 * 请求纪律与登录页一致：**本地校验不过不发请求**、提交中重复点击忽略、
 * 服务端文案原样展示（`docs/CONVENTIONS.md` §3 第 5 条：错误翻译只在账号层）。
 */
class AccountSignUpViewModel(
    private val account: AccountRepository,
    /** 从登录页「已有验证码」进来：直接停在验证码步骤（不需要再注册一次）。 */
    private val verifyOnly: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SignUpUiState(
            step = if (verifyOnly) SignUpStep.VERIFY else SignUpStep.FORM,
            verifyOnly = verifyOnly,
            registered = verifyOnly,
        ),
    )
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()

    /** 冷却倒计时协程；重发/重新进入验证码步骤时先取消旧的，避免两个计时器打架。 */
    private var cooldownJob: Job? = null

    fun setUsername(value: String) = updateForm { it.copy(username = value) }

    fun setPassword(value: String) = updateForm { it.copy(password = value) }

    fun setPasswordConfirm(value: String) = updateForm { it.copy(passwordConfirm = value) }

    fun setEmail(value: String) = updateForm { it.copy(email = value) }

    fun setGender(value: String) = updateForm { it.copy(gender = value) }

    fun setAdultConfirmed(value: Boolean) = updateForm { it.copy(adultConfirmed = value) }

    fun setTermsAccepted(value: Boolean) = updateForm { it.copy(termsAccepted = value) }

    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun setCode(value: String) = _state.update {
        it.copy(code = limitCodePoints(value, SIGN_UP_CODE_MAX), codeError = null)
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    /** 验证码步骤 → 返回注册表单（保留已填内容；返回键也走这里）。 */
    fun backToForm() {
        if (_state.value.submitting) return
        // 从登录页「已有验证码」进来时没有表单可回，调用方不该走这里
        if (_state.value.verifyOnly) return
        _state.update {
            it.copy(
                step = SignUpStep.FORM,
                notice = null,
                error = null,
                codeError = null,
                verifyUsernameError = null,
                verifyPasswordError = null,
            )
        }
    }

    /**
     * 回注册表单后重新进验证码步骤。
     *
     * 账号**已经创建成功**时，再提交一次注册只会收到"用户名已经存在"，
     * 所以表单上给的是「继续验证」而不是「注册」——不然用户点返回键就卡死了。
     */
    fun resumeVerification() {
        val current = _state.value
        if (current.submitting) return
        if (!current.registered && !current.verifyOnly) return
        _state.update {
            it.copy(
                step = SignUpStep.VERIFY,
                notice = null,
                error = null,
                codeError = null,
                verifyUsernameError = null,
                verifyPasswordError = null,
            )
        }
    }

    private fun updateForm(transform: (SignUpForm) -> SignUpForm) = _state.update { current ->
        // 输入即清掉对应字段的旧错误：用户已经在改了，红框 / 页内提示都该收起来
        current.copy(
            form = transform(current.form).limited(),
            validation = SignUpValidation(),
            error = null,
            codeError = null,
            verifyUsernameError = null,
            verifyPasswordError = null,
        )
    }

    /** 提交注册表单。 */
    fun submit() {
        val current = _state.value
        if (current.submitting) return
        val validation = validateSignUp(current.form)
        if (validation.hasError) {
            // 表单校验**保持页内提示**（用户 2026-10-23 明确要求）：
            // 输入框下方 / 勾选行下方各自显示，不弹对话框。
            _state.update {
                it.copy(validation = validation, error = null, codeError = null)
            }
            return
        }
        val form = current.form
        _state.update {
            it.copy(
                submitting = true,
                validation = SignUpValidation(),
                error = null,
                codeError = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                account.register(
                    username = form.username.trim(),
                    password = form.password,
                    passwordConfirm = form.passwordConfirm,
                    email = form.email.trim(),
                    gender = form.gender,
                )
            }
                .onSuccess { message ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            step = SignUpStep.VERIFY,
                            registered = true,
                            notice = message,
                            error = null,
                            codeError = null,
                            code = "",
                            verifiedNeedLogin = false,
                        )
                    }
                    startCooldown()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            submitting = false,
                            errorTitle = "注册失败",
                            error = e.toUserMessage(),
                            codeError = null,
                        )
                    }
                }
        }
    }

    /** 提交邮箱验证码；成功后自动用注册时的密码登录。 */
    fun submitCode() {
        val current = _state.value
        if (current.submitting || current.resending || current.step != SignUpStep.VERIFY) return
        // 本地校验：错误挂到**对应输入框下方**（与登录 / 注册表单一致），全部一起判、不发请求
        val codeError = validateSignUpCode(current.code)
        // 「已有验证码」入口没有注册表单，用户名 / 密码要现填（验证后的自动登录也要用密码）
        val usernameError = if (current.verifyOnly && current.username.isBlank()) "请输入用户名" else null
        val passwordError = if (current.verifyOnly && current.form.password.isEmpty()) "请输入密码" else null
        if (codeError != null || usernameError != null || passwordError != null) {
            _state.update {
                it.copy(
                    codeError = codeError,
                    verifyUsernameError = usernameError,
                    verifyPasswordError = passwordError,
                    error = null,
                )
            }
            return
        }
        val username = current.username
        val password = current.form.password
        _state.update { it.copy(submitting = true, error = null, codeError = null) }
        viewModelScope.launch {
            val confirm = runCatching { account.confirmSignUp(username, current.code.trim()) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            submitting = false,
                            errorTitle = "验证失败",
                            error = e.toUserMessage(),
                            codeError = null,
                        )
                    }
                }
                .getOrNull() ?: return@launch
            runCatching { account.login(username, password) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            submitting = false,
                            notice = confirm,
                            done = true,
                            error = null,
                            codeError = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // 账号已经验证成功，只是自动登录失败：不要卡在验证码页，
                    // 改成"去登录"，把用户名预填进登录页（用户自己输密码）。
                    _state.update {
                        it.copy(
                            submitting = false,
                            verifiedNeedLogin = true,
                            errorTitle = "自动登录失败",
                            error = "邮箱验证成功，但自动登录失败：${e.toUserMessage()}",
                            codeError = null,
                        )
                    }
                }
        }
    }

    /** 重新发送验证码（`username + password`）；60 秒冷却。 */
    fun resendCode() {
        val current = _state.value
        if (!current.canResend || current.step != SignUpStep.VERIFY) return
        // 重发只需要用户名 / 密码；本地校验同样挂到对应输入框下方
        if (current.verifyOnly) {
            val usernameError = if (current.username.isBlank()) "请输入用户名" else null
            val passwordError = if (current.form.password.isEmpty()) "请输入密码" else null
            if (usernameError != null || passwordError != null) {
                _state.update {
                    it.copy(
                        verifyUsernameError = usernameError,
                        verifyPasswordError = passwordError,
                        error = null,
                    )
                }
                return
            }
        }
        val username = current.username
        val password = current.form.password
        // 重发走网页版接口，可能比普通接口慢：用独立的 resending 状态，
        // 不要复用 submitting，否则主按钮会误显示"正在验证…"。
        _state.update { it.copy(resending = true, error = null, codeError = null) }
        viewModelScope.launch {
            runCatching { account.resendSignUpCode(username, password) }
                .onSuccess { message ->
                    _state.update {
                        it.copy(
                            resending = false,
                            notice = message,
                            error = null,
                            codeError = null,
                        )
                    }
                    startCooldown()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            resending = false,
                            errorTitle = "发送失败",
                            error = e.toUserMessage(),
                            codeError = null,
                        )
                    }
                }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            _state.update { it.copy(resendCooldown = RESEND_COOLDOWN_SECONDS) }
            var left = RESEND_COOLDOWN_SECONDS
            while (left > 0) {
                delay(1_000)
                left -= 1
                _state.update { it.copy(resendCooldown = left) }
            }
        }
    }

    private companion object {
        /** 官方 UI 的验证码重发冷却。 */
        const val RESEND_COOLDOWN_SECONDS = 60
    }
}

// ======================================================================
// 个人资料编辑
// ======================================================================

/**
 * 资料编辑页状态。
 *
 * [base] 是**本次从服务端拉到的原值**，只用于判断"有没有改过"；
 * [form] 是用户正在编辑的表单。两者相同 = 没有未保存修改。
 */
data class AccountProfileEditUiState(
    val signedIn: Boolean = true,
    /** 正在 `GET /useredit`（加载完成前**不渲染表单**，见 `AccountProfileEditViewModel` 的注释）。 */
    val loading: Boolean = true,
    /** 只读展示：当前登录名。 */
    val username: String = "",
    /** 只读展示：当前邮箱。 */
    val email: String = "",
    val form: UserProfileForm? = null,
    val base: UserProfileForm? = null,
    val updating: Boolean = false,
    /** 整页加载失败（没有可编辑的内容）。 */
    val error: String? = null,
    /**
     * **本地**校验错误（改密码只填一个 / 两次不一致 / 账号信息不完整）。
     *
     * 与 [saveError] 分开：本地错误仍然用页内横幅（用户要求"只有服务端返回错误才弹框"），
     * 服务端 / 网络错误才走 [saveError] 的弹窗。
     */
    val localError: String? = null,
    /** 保存时服务端 / 网络返回的错误（弹窗）。 */
    val saveError: String? = null,
    /** 一次性成功提示（由根部 Snackbar 展示）。 */
    val message: String? = null,
    /** 保存成功（页面据此返回资料页）。 */
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = form != null && form != base
    val canSave: Boolean get() = form != null && dirty && !loading && !updating
}

/**
 * 个人资料编辑页 ViewModel。
 *
 * ## 为什么"加载完才显示表单"（这里踩的是官方 App 的坑）
 *
 * 官方 `InfoModal.tsx` 的输入框全是 `defaultValue={formData.xxx}`，而 `formData` 是
 * `GET /useredit` 回来后才填的：首帧渲染时还是空对象，**之后 `defaultValue` 不会再更新**，
 * 于是"已有资料回显不出来"。本工程的做法是：
 * 1. 进页面先 `GET`，加载中只显示 LoadingBox，**根本不渲染输入框**；
 * 2. 拿到 DTO 后一次性 [UserEditDto.toProfileForm] 生成受控状态，输入框永远是"状态驱动"；
 * 3. 用户每次进编辑页都会重新拉一次，避免用上一次的快照做整条覆盖。
 */
class AccountProfileEditViewModel(
    private val account: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AccountProfileEditUiState(
            signedIn = account.isSignedIn,
            username = account.profile?.username.orEmpty(),
            email = account.profile?.email.orEmpty(),
        ),
    )
    val state: StateFlow<AccountProfileEditUiState> = _state.asStateFlow()

    private var inFlight = false

    init {
        load()
    }

    /** 重试整页加载。 */
    fun retry() = load()

    private fun load() {
        val profile = account.profile
        if (!account.isSignedIn || profile == null) {
            _state.update { it.copy(signedIn = false, loading = false) }
            return
        }
        if (inFlight) return
        inFlight = true
        _state.update {
            it.copy(
                signedIn = true,
                loading = true,
                error = null,
                localError = null,
                saveError = null,
                username = profile.username,
                email = profile.email.orEmpty(),
            )
        }
        viewModelScope.launch {
            runCatching { account.userEdit(profile.uid) }
                .onSuccess { detail ->
                    // 服务端理论上也按 50/200 截断，这里再套一次上限，
                    // 保证"表单值"和"提交值"与输入框的限制是同一套。
                    val form = detail.toProfileForm().limited()
                    _state.update {
                        it.copy(
                            loading = false,
                            form = form,
                            base = form,
                            error = null,
                            localError = null,
                            saveError = null,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            loading = false,
                            form = null,
                            base = null,
                            error = e.toUserMessage(),
                            localError = null,
                            saveError = null,
                        )
                    }
                }
            inFlight = false
        }
    }

    /** 表单变化（截断上限在 [UserProfileForm.limited] 里统一做）。 */
    fun updateForm(form: UserProfileForm) = _state.update {
        it.copy(form = form.limited(), localError = null, saveError = null)
    }

    /** 保存：全量提交 24 个字段。 */
    fun save() {
        val current = _state.value
        val form = current.form ?: return
        if (current.updating || current.loading) return
        val passwordError = validatePasswordChange(form)
        if (passwordError != null) {
            // 本地校验 → 页内横幅（不弹框）
            _state.update { it.copy(localError = passwordError, saveError = null) }
            return
        }
        val uid = account.profile?.uid.orEmpty()
        if (uid.isEmpty()) {
            _state.update {
                it.copy(localError = "账号信息不完整，请重新登录", saveError = null)
            }
            return
        }
        _state.update { it.copy(updating = true, localError = null, saveError = null) }
        viewModelScope.launch {
            runCatching { account.saveUserEdit(uid, form) }
                .onSuccess { updated ->
                    val fresh = updated.toProfileForm().limited()
                    _state.update {
                        it.copy(
                            updating = false,
                            form = fresh,
                            base = fresh,
                            // 密码字段不留在界面上：保存成功后它已经没有意义
                            localError = null,
                            saveError = null,
                            saved = true,
                            message = if (form.password.isNotEmpty()) "资料已保存，密码已更新" else "资料已保存",
                            username = account.profile?.username.orEmpty(),
                            email = account.profile?.email.orEmpty(),
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // 只有服务端 / 网络错误才走弹窗
                    _state.update { it.copy(updating = false, saveError = e.toUserMessage()) }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** 关掉保存失败的对话框（错误正文在弹窗里，不再用占位的横幅把表单顶下去）。 */
    fun consumeSaveError() = _state.update { it.copy(saveError = null) }
}
