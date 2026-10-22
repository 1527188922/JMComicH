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
import com.haruhi.jmcomic.feature.data.FavoritesSnapshot
import com.haruhi.jmcomic.feature.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** 接口级错误（服务端 `errorMsg` 原文或网络错误文案）。 */
    val error: String? = null,
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
            _state.value = next
            return
        }
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            runCatching { account.login(username, password) }
                .onSuccess {
                    _state.update { s -> s.copy(submitting = false, done = true, error = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { s -> s.copy(submitting = false, error = e.toUserMessage()) }
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
            _state.update { it.copy(message = "请输入收藏夹名称") }
            return
        }
        _state.update { it.copy(busyFolderId = BUSY_CREATE) }
        viewModelScope.launch {
            runCatching { account.createFolder(trimmed) }
                .onSuccess { folder ->
                    _state.update { it.copy(message = "已创建「${folder.name}」") }
                    fetch(silent = true)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(message = e.toUserMessage()) }
                }
            _state.update { it.copy(busyFolderId = null) }
        }
    }

    fun rename(folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = "名称不能为空") }
            return
        }
        _state.update { it.copy(busyFolderId = folderId) }
        viewModelScope.launch {
            runCatching { account.renameFolder(folderId, trimmed) }
                .onSuccess {
                    _state.update { it.copy(message = "已重命名为「$trimmed」") }
                    fetch(silent = true)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(message = e.toUserMessage()) }
                }
            _state.update { it.copy(busyFolderId = null) }
        }
    }

    fun delete(folderId: String) {
        _state.update { it.copy(busyFolderId = folderId) }
        viewModelScope.launch {
            runCatching { account.deleteFolder(folderId) }
                .onSuccess {
                    _state.update { it.copy(message = "收藏夹已删除") }
                    fetch(silent = true)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(message = e.toUserMessage()) }
                }
            _state.update { it.copy(busyFolderId = null) }
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
        if (_state.value.removingId != null) return
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
                        )
                    }
                    // 收藏关系变了，全局夹缓存已被作废；把当前这份（已减掉那本）重新写回去，
                    // 这样返回列表再进来时不用白等一次网络
                    publishCache()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(removingId = null, message = e.toUserMessage()) }
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
                    cache.putProfileDetail(detail)
                    _state.update {
                        it.copy(loading = false, refreshing = false, detail = detail, error = null)
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
