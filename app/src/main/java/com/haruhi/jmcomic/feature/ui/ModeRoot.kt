package com.haruhi.jmcomic.feature.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
// 别名：直接 import `List` 会把 kotlin.collections.List 这个名字在文件里遮住，
// 以后在这里写 `List<…>` 会莫名其妙解析到图标属性上。
import androidx.compose.material.icons.automirrored.filled.List as CategoryListIcon
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.haruhi.jmcomic.core.di.AppContainer
import com.haruhi.jmcomic.core.download.AppDownloadGateway
import com.haruhi.jmcomic.core.protocol.SearchSort
import com.haruhi.jmcomic.feature.api.ReleaseChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 带任务角标的底栏图标。
 *
 * 任务从底栏搬进「更多」之后，**"有东西在下"这件事全靠这个角标兜住**：
 * 所以角标挂在「更多」而不是「任务」上（「任务」已经不在底栏了），
 * 保证在任何一个 tab 上都看得见。
 *
 * 计划 §9 第 9 条：**角标只统计排队/执行任务**，失败另用提示点。
 * 所以"有失败但没有排队任务"时画一个空角标当提示点，不把失败数加进数字里。
 */
@Composable
private fun TaskBadgeIcon(badgeCount: Int, hasFailure: Boolean, icon: ImageVector) {
    val content: @Composable () -> Unit = { Icon(icon, contentDescription = "更多") }
    when {
        badgeCount > 0 -> BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { content() }
        hasFailure -> BadgedBox(badge = { Badge {} }) { content() }
        else -> content()
    }
}

/** 底栏与侧栏共用的 tab 图标（「我的」带任务角标）。 */
@Composable
private fun TabIcon(tab: Tab, badgeCount: Int, hasFailure: Boolean) {
    if (tab == Tab.MINE) {
        TaskBadgeIcon(badgeCount = badgeCount, hasFailure = hasFailure, icon = tab.icon)
    } else {
        Icon(tab.icon, contentDescription = tab.label)
    }
}

object Routes {
    const val LIBRARY = "jm/library"
    const val RECENT = "jm/recent"
    const val DISCOVER = "jm/discover"
    const val FAVORITES = "jm/favorites"
    const val TASKS = "jm/tasks"

    /**
     * 「分类」tab（用户 2026-10-19 要求）。
     *
     * 位置固定在**书库与发现之间**（用户口述：「分类页放到书库的右侧、发现页的左侧」）。
     * 页面是纯在线分类浏览：分类树 + 双层抽屉 + 排序 + 下拉刷新 + 手动翻页。
     */
    const val CATEGORY = "jm/category"

    /**
     * 「我的」tab（原「更多」，2026-10 改名）。
     *
     * 底栏固定为 **书库 / 分类 / 发现 / 我的**；「收藏」（账号在线收藏）、「最近浏览」
     * 「下载任务」「应用设置」都收在这一页里，所以底栏不因为它们变长。
     */
    const val MINE = "jm/mine"

    // ---------------- 账号（JM 会员，见 docs/account-design.md） ----------------

    /**
     * 登录页。
     *
     * `next` 是登录成功后要落到的页面（空 = 回「我的」）。
     * `username` 用于"注册验证成功但自动登录失败"时预填用户名（用户要求：回登录页预填）。
     */
    const val LOGIN = "jm/account/login?next={next}&username={username}"

    /**
     * 注册页（注册 → 邮箱验证码 → 自动登录）。
     *
     * 与登录页共用同一个 `next`：从「收藏」引导进来的用户注册完直接落在收藏页，
     * 不用再回「我的」重新点一次。
     *
     * `verify=true` 是「已有验证码」入口：不显示注册表单，直接填用户名 / 密码 / 验证码
     * （账号已经创建、只是没验证完的情况，见 [signup]）。
     */
    const val SIGNUP = "jm/account/signup?next={next}&verify={verify}"

    /**
     * 忘记密码页（`POST /forgot`，`docs/official-api-manga.md` §7.5）。
     *
     * 只要一个**注册邮箱**：服务端把「用户名 + 新密码」发到该邮箱，并**当场作废旧密码**
     * （2026-10-25 实测）。所以没有参数、没有验证码，也没有"设置新密码"这一步。
     * 入口在登录页底部的「找回密码」。
     */
    const val FORGOT = "jm/account/forgot"

    /** 账号收藏夹列表。 */
    const val ACCOUNT_FAVORITES = "jm/account/favorites"

    /** 某个收藏夹内的漫画网格；`folderId <= 0` 表示「全部收藏」。 */
    const val ACCOUNT_FAVORITE_ALBUMS = "jm/account/favorites/{folderId}?name={name}"

    /** 个人资料（只读，右上角「编辑」进编辑页）。 */
    const val ACCOUNT_PROFILE = "jm/account/profile"

    /** 个人资料编辑（`GET` 最新资料 → 全量 `POST /useredit`）。 */
    const val ACCOUNT_PROFILE_EDIT = "jm/account/profile/edit"

    /**
     * 「我的 → 留言记录」：我发过的留言（`GET /forum?uid={自己}`）。
     *
     * 与收藏一样只在登录后可用：未登录时入口会先跳登录页（`next` 带回本页）。
     */
    const val ACCOUNT_COMMENTS = "jm/account/comments"

    /**
     * 应用设置页（「更多」→ 应用设置）。
     *
     * **设置入口只有这一处**：模式首页不再单独挂顶栏齿轮（底栏已经能到设置，
     * 再挂一个齿轮是重复入口）。阅读器控制栏里的那个小齿轮**保留**——
     * 它和这个页面读写的是同一份 `Jm.READING_*` 偏好，两处入口、一份存储。
     */
    const val SETTINGS = "jm/settings"

    /**
     * 关于页（「我的 → 关于」）：版本号、检查更新（Gitee 最新发行版 → 应用内下载
     * → 唤起安装）、五类本地存储的分段统计。
     */
    const val ABOUT = "jm/about"
    const val FAVORITE_ALBUMS = "jm/favorites/{favoriteId}?name={name}"
    /**
     * 详情页。
     *
     * `tab` 是可选参数（默认空串 = 章节）：从「留言记录」点一行进来时传 `comments`，
     * 直接停在评论 tab 并开始拉评论（见 `Routes.detail(albumId, tab)`）。
     * 可选查询参数带默认值，所以旧的 `jm/album/{id}` 地址照样匹配。
     */
    const val DETAIL = "jm/album/{albumId}?tab={tab}"
    const val READER = "jm/read/{albumId}?chapterId={chapterId}"

    /**
     * 搜索结果页（独立页面，用户要求）。
     *
     * **不是底栏 tab**，所以进它时底栏自动隐藏（见 [ModeRoot] 的 showBottomBar 判定）；
     * 返回键按普通入栈回上一层（发现页 / 详情页），不需要页面内接管。
     */
    const val SEARCH_RESULTS = "jm/discover/results?keyword={keyword}&sort={sort}&historyId={historyId}"

    fun favoriteAlbums(id: Long, name: String) =
        "jm/favorites/$id?name=${encodeRouteArg(name)}"

    /**
     * 登录页地址。
     *
     * @param next 登录成功后要落到的页面路由；空串 = 回「我的」。
     *        必须走 [encodeRouteArg]（它本身是带 `?` 与中文的任意路由串）。
     * @param username 预填的用户名（注册验证成功但自动登录失败时带过来），空串 = 不预填。
     */
    fun login(next: String = "", username: String = "") =
        "jm/account/login?next=${encodeRouteArg(next)}&username=${encodeRouteArg(username)}"

    /**
     * 注册页地址。
     *
     * @param next 注册 + 验证 + 自动登录成功后要落到的页面；空串 = 回「我的」。
     * @param verifyOnly true = 「已有验证码」入口（账号已创建，只补验证码）。
     */
    fun signup(next: String = "", verifyOnly: Boolean = false) =
        "jm/account/signup?next=${encodeRouteArg(next)}&verify=$verifyOnly"

    /**
     * 账号收藏夹内页。
     *
     * @param folderId null = 「全部收藏」（路由里用 `0` 表示，Long 参数类型天然支持）。
     */
    fun accountFavoriteAlbums(folderId: String?, name: String) =
        "jm/account/favorites/${folderId?.takeIf { it.isNotBlank() } ?: "0"}?name=${encodeRouteArg(name)}"

    /**
     * 结果页的地址。
     *
     * @param keyword 搜索关键字。**必须**走 [encodeRouteArg]：关键字是用户输入的任意文本，
     *        直接拼进路由会被 URI 那一层改坏（空格、`%`、`+`……，见 [encodeRouteArg] 的注释）。
     * @param sort 排序（点作者进来用默认排序）
     * @param historyId > 0 表示从搜索历史点进来：结果页**只回显快照，不发搜索请求**（用户要求）
     */
    fun searchResults(
        keyword: String,
        sort: SearchSort = SearchSort.DEFAULT,
        historyId: Long = 0L,
    ) = "jm/discover/results?keyword=${encodeRouteArg(keyword)}" +
        "&sort=${sort.code}&historyId=$historyId"

    fun detail(albumId: Long) = "jm/album/$albumId"

    /**
     * 详情页地址（带要停靠的 tab）。
     *
     * 目前只有「留言记录」用它：点一行留言 → 打开那本漫画并**直接落在评论 tab**，
     * 不然用户还得自己再点一次「评论」才能确认自己刚才发的那条还在。
     *
     * 章节入口不传（路由里就是 `jm/album/{id}`），行为与以前完全一致。
     */
    fun detail(albumId: Long, tab: AlbumDetailTab) =
        "jm/album/$albumId?tab=${tab.code}"

    /**
     * 阅读器。
     *
     * `chapterId <= 0` 表示"接着上次读的位置"；给了具体章节就**从那一话的第一页开始**。
     */
    fun reader(albumId: Long, chapterId: Long = 0L) = "jm/read/$albumId?chapterId=$chapterId"
}

/**
 * 底部导航的五个 tab（用户 2026-10-19 定的顺序：书库 / 分类 / 发现 / 收藏 / 更多）。
 *
 * **声明顺序就是底栏顺序**，也是页面切换动画方向的依据（见 [Tab.indexOfRoute]）。
 * 「分类」夹在书库与发现之间：左边是"我有的"，中间是"按分类找"，右边是"按关键字搜"。
 *
 * 「最近浏览」与「下载任务」**不在这里**：它们收进了「更多」页当二级页，
 * 底栏因此没有继续变长。代价是任务不再常驻底栏，靠 [TaskBadgeIcon] 的角标补可见性。
 *
 * 图标只能用 `material-icons-core` 里有的：本工程**不引入 `material-icons-extended`**
 * （体积代价，见 `libs.versions.toml`）。"更多"用竖排三点（`MoreVert`），
 * 表示"还有别的东西收在这里面"；"分类"用列表图标（`AutoMirrored.Filled.List`，
 * RTL 下会自动镜像），和「书库」的房子、「发现」的放大镜都区分得开。
 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY(Routes.LIBRARY, "书库", Icons.Default.Home),
    CATEGORY(Routes.CATEGORY, "分类", Icons.AutoMirrored.Filled.CategoryListIcon),
    DISCOVER(Routes.DISCOVER, "发现", Icons.Default.Search),
    // 【本地UI隐藏】v3 起收藏走账号在线收藏（入口在「我的」里），底栏不再有收藏 tab。
    // 放开下面这一行即可恢复 5 项底栏（本机收藏页的 composable 也要一起放开）。
    // FAVORITES(Routes.FAVORITES, "收藏", Icons.Default.Favorite),
    MINE(Routes.MINE, "我的", Icons.Default.Person),
    ;

    companion object {
        /** 底栏里的位置；不是 tab（详情页/阅读器）时返回 -1。 */
        fun indexOfRoute(route: String?): Int = entries.indexOfFirst { it.route == route }
    }
}

/** 页面切换动画时长（毫秒）。 */
private const val PAGE_TRANSITION_MS = 260

/**
 * 漫画模式的导航壳。
 *
 * 底部导航：**书库 / 分类 / 发现 / 收藏 / 更多**（分类是 2026-10-19 新增，夹在书库与发现之间）。
 * 「最近浏览」「下载任务」「应用设置」都收在「更多」页里当二级页。
 *
 * 详情页、阅读器以及「更多」下面的二级页都隐藏底栏
 * （§9 第 2 条：阅读器/全屏播放器隐藏主导航；二级页自带返回按钮，靠系统返回或按钮回上一级）。
 */
@Composable
fun ModeRoot(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val factory = remember(container) { ViewModelFactory(container) }
    // 主栈：四个 tab、登录/注册，以及换到右栏之前的所有页面都在这里。
    val primaryEntry by navController.currentBackStackEntryAsState()
    val currentRoute = primaryEntry?.destination?.route
    // 平板（窗口短边 >= 600dp）用侧栏替代底栏；手机横屏的短边仍只有 300~400dp，
    // 因此不会被误判成平板，底栏保持原样。
    val wideShell = isWideWindow()
    // 双栏：左侧 + 右侧。除了平板判定，还要求整窗宽度够放下
    // "420dp 左栏 + 右栏"，所以再卡一道 840dp；手机永远不满足，UI 保持原样。
    val splitCapable = wideShell && windowWidthDp() >= SPLIT_MIN_WIDTH
    // 阅读器的左右分栏只在**横屏**启用：pad 竖起来时（用户要求）阅读页占满屏幕。
    val landscape = windowWidthDp() > windowHeightDp()
    // 辅栈：第二个独立的 NavHost。两个栈轮流出任"左栏（上一页）" / "右栏（当前页）"。
    // 这样从右栏再点进下一页时，当前页只是**换到左边**，不会被销毁重建——
    // 详情 / 搜索结果 / 收藏夹 / 阅读位置都能原样保留（用户 2026-10-27 要求）。
    val paneNavController = rememberNavController()
    val paneEntry by paneNavController.currentBackStackEntryAsState()
    // 两个栈的左右站位；存进 rememberSaveable，配置变更后仍能还原
    val primaryOnLeftState = rememberSaveable { mutableStateOf(true) }
    val activeTabState = rememberSaveable { mutableStateOf(Routes.LIBRARY) }
    // `splitCapable` 进 remember 的 key：转屏 / 分屏拖动会改变它，
    // 导航动作要跟着换"走双栏还是走单栈"，所以 navigator 必须重建一次。
    val navigator = remember(navController, paneNavController, splitCapable) {
        CarouselNavigator(
            primaryNav = navController,
            secondaryNav = paneNavController,
            primaryOnLeftState = primaryOnLeftState,
            activeTabState = activeTabState,
            splitPanes = splitCapable,
        )
    }
    // 主栈的动作：tab / 登录页 / 换到右栏后的页面都用它
    val primaryActions = remember(navigator) {
        PaneNavActions(
            openChild = { route -> navigator.openChild(CarouselHost.Primary, route) },
            // 页面左上角的返回按钮与系统返回键走**同一条**路（[CarouselNavigator.backAction]）：
            // 手机上二级页压在主栈上，只退"右栏"的 [CarouselNavigator.back] 会什么都不做。
            back = { navigator.backAction() },
            openLogin = { next -> navigator.openLogin(next) },
            openReadingFromTab = { albumId, chapterId ->
                navigator.openReadingFromTab(albumId, chapterId)
            },
        )
    }
    // 辅栈的动作：详情 / 结果 / 二级页 / 阅读器
    val secondaryActions = remember(navigator) {
        PaneNavActions(
            openChild = { route -> navigator.openChild(CarouselHost.Secondary, route) },
            back = { navigator.backAction() },
            openLogin = { next -> navigator.openLogin(next) },
            openReadingFromTab = { albumId, chapterId ->
                navigator.openChild(CarouselHost.Secondary, Routes.reader(albumId, chapterId))
            },
        )
    }
    // 左右两栏当前各显示什么（主栈/辅栈按站位换边）
    val primaryOnLeft = primaryOnLeftState.value
    val leftRoute = if (primaryOnLeft) currentRoute else paneEntry?.destination?.route
    val rightRoute = if (primaryOnLeft) paneEntry?.destination?.route else currentRoute
    val rightHasPage = rightRoute != null && rightRoute != SPLIT_PANE_EMPTY
    val leftIsTab = leftRoute != null && Tab.entries.any { it.route == leftRoute }
    // 登录/注册/找回密码永远整页：压在主栈上，布局临时只显示主栈
    val authOpen = isAuthRoute(currentRoute)
    // 阅读器挂在右侧（当前页）时：竖屏 / 窄窗整屏，横屏平板保留"左详情 + 右阅读器"
    val readerFullscreen = rightRoute == Routes.READER && !(splitCapable && landscape)
    val readerOnScreen = !authOpen &&
        (leftRoute == Routes.READER || rightRoute == Routes.READER)
    val mode = when {
        authOpen -> CarouselMode.OnlyPrimary
        readerFullscreen -> CarouselMode.OnlyRight
        splitCapable -> CarouselMode.Split
        rightHasPage -> CarouselMode.OnlyRight
        else -> CarouselMode.OnlyLeft
    }
    // 侧栏：分栏时一直显示（阅读中也保留，方便切 tab）；左栏是 tab 的单栏布局也显示
    val showRail = wideShell &&
        (mode == CarouselMode.Split || (mode == CarouselMode.OnlyLeft && leftIsTab))
    // 手机底栏：只有"当前页就是 tab 本身"时才显示
    val showBottomBar = !wideShell && mode == CarouselMode.OnlyLeft && leftIsTab
    // 底栏/侧栏共用同一套切换逻辑：各 tab 保留独立滚动位置与筛选（§9 第 2 条）
    val switchTab: (Tab) -> Unit = { tab -> navigator.switchTab(tab) }
    // 页面内部的返回拦截表（多选退出、编辑页未保存、注册验证码步骤……）。
    // 真正的系统返回键处理挂在两个 NavHost **之后**（见 CarouselPanes 下方）：
    // NavHost 内部也会注册返回回调，只有比它更晚注册才不会被它抢走——抢走的结果
    // 是退掉"背后那一栏"的页面。页面自己的拦截则通过这张表优先处理。
    val backInterceptors = remember { BackInterceptors() }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 一次性提示统一走根部：**先消费、再这里弹**（见 MessageEffect 的注释）。
    // 挂在根部的 scope 上，切 tab 时提示不会被正在销毁的页面协程一起取消。
    //
    // 新提示到来时**先取消上一条**：`showSnackbar` 是挂起函数、内部按 Mutex 串行排队，
    // 默认每条要播 ~4 秒，提示一多就会"排队逐个播完"，用户要等很久（用户反馈）。
    // 取消正在展示的那条协程后，Material3 会在 finally 里清空 currentSnackbarData，
    // 当前提示立即消失，新提示马上顶上。
    val messageJob = remember { mutableStateOf<Job?>(null) }
    val showMessage: (String) -> Unit = { message ->
        messageJob.value?.cancel()
        messageJob.value = scope.launch { snackbar.showSnackbar(message) }
    }
    // 导出成品的「打开至 / 发送至」要发系统 Intent（计划 §8 第 10 条）
    val context = LocalContext.current

    // 任务角标要在任意 tab 都显示，所以这个 ViewModel 挂在 Activity 上（不在 NavHost 内部），
    // 任务页复用同一个实例，两处看到的状态一定一致。
    val taskViewModel: TaskViewModel = viewModel(factory = factory)
    val taskState by taskViewModel.state.collectAsStateWithLifecycle()

    // 封面统一走应用级 ImageLoader：正确的 UA/代理 + files/covers 磁盘缓存
    // （用户要求第 8 点：加载过的封面优先读本地）
    // 图片线路同时下发：封面 URL 跟着设置页选中的那条走，切线路后已显示的封面立即换源。
    val imageDomain by container.imageDomain.collectAsStateWithLifecycle()
    // 隐私模式：设置页 Switch 与全局双指手势共用同一份存储，封面只订阅这一个值。
    val privacyMode by container.privacyMode.collectAsStateWithLifecycle()
    // 文字隐私：独立保存的开关，只有隐私模式开启时才生效。
    val textPrivacy by container.textPrivacy.collectAsStateWithLifecycle()
    // 稳定回调：pointerInput 的 lambda 不会因为页面重组被换成新实例。
    val setPrivacyMode: (Boolean) -> Unit = remember(container) {
        { enabled -> container.setPrivacyMode(enabled) }
    }
    val setTextPrivacy: (Boolean) -> Unit = remember(container) {
        { enabled -> container.setTextPrivacy(enabled) }
    }
    CompositionLocalProvider(
        LocalImageLoader provides container.imageLoader,
        LocalImageDomain provides imageDomain,
        LocalPrivacyMode provides privacyMode,
        LocalTextPrivacy provides (privacyMode && textPrivacy),
        LocalBackInterceptors provides backInterceptors,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                // 「点输入框以外的地方就失焦、收起软键盘」挂在**根节点**上，一处覆盖所有页面
                // （实现与两条踩坑约束见 `FocusOnTap.kt`）。挂根节点而不是喂给每个输入框，
                // 是为了让以后新增的页面/输入框自动具备这个行为，不用每处再挂一次。
                modifier = modifier
                    .fillMaxSize()
                    .clearFocusOnTap()
                    // 双指聚拢开启、张开关闭隐私模式。
                    // 阅读器内禁用：它的双指缩小/还原会跨过同样的阈值，造成误触发。
                    // 阅读器也可能挂在右栏（阅读时左详情 + 右阅读器），所以两个路由都要判。
                    .privacyPinchGesture(
                        enabled = !readerOnScreen,
                        onPinchIn = { setPrivacyMode(true) },
                        onPinchOut = { setPrivacyMode(false) },
                    ),
                // Scaffold 默认容器色是 `colorScheme.background`（本主题下是 #F5F5F5 灰）。
                // 但状态栏那条（topBar 槽为空、由 contentWindowInsets 让出来）画的正是这个颜色，
                // 必须是纯白才能和底栏 NavigationBar（surfaceContainer = #FFFFFF）一致。
                // 各页真正的**滚动内容区**由各 Screen 自己铺 `background` 灰底。
                containerColor = MaterialTheme.colorScheme.surface,
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = activeTabState.value == tab.route,
                                    onClick = { switchTab(tab) },
                                    icon = {
                                        TabIcon(
                                            tab = tab,
                                            badgeCount = taskState.badgeCount,
                                            hasFailure = taskState.failedCount > 0,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // 平板上底栏换成左侧导航栏
                    if (showRail) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            // 系统栏内边距已经由外层 Scaffold 的 padding 让出，这里不再叠加
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            Tab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = activeTabState.value == tab.route,
                                    onClick = { switchTab(tab) },
                                    icon = {
                                        TabIcon(
                                            tab = tab,
                                            badgeCount = taskState.badgeCount,
                                            hasFailure = taskState.failedCount > 0,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                    // 两个 NavHost 始终留在组合里，只在"左栏 / 右栏 / 整屏"之间换宽度和位置。
                    // 这样换栏只是布局变化，页面实例（ViewModel、滚动位置、阅读进度）全都保留。
                    CarouselPanes(
                        mode = mode,
                        primaryOnLeft = primaryOnLeft,
                        leftWidth = SPLIT_LIST_WIDTH,
                        dividerColor = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        primary = {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LIBRARY,
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    // 页面切换动画（与 [pageEnter] 等四个函数共用一套）：
                    // 1. **底栏 tab 之间**：按目标 tab 在底栏里的位置决定方向——
                    //    往右边的 tab 切就从右侧滑入、往左边切就从左侧滑入，
                    //    与"底栏本身是一条从左到右的轴"一致，手指和视线都不用重新找方向；
                    // 2. **进入详情页/阅读器**（push）：新页面从右侧滑入，返回时反向；
                    // 3. 其余情况（例如从详情页返回底栏）走同一套反向动画。
                    enterTransition = {
                        pageEnter(tabShift(initialState.destination.route, targetState.destination.route))
                    },
                    exitTransition = {
                        pageExit(tabShift(initialState.destination.route, targetState.destination.route))
                    },
                    popEnterTransition = { pagePopEnter() },
                    popExitTransition = { pagePopExit() },
                ) {
                    composable(Routes.LIBRARY) { entry ->
                        val vm: LibraryViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        // 从详情页/阅读器回来时重算"已下载 x/y 话"（下载完成后数字必须跟着变）
                        OnResumeEffect { vm.onScreenResumed() }
                        // 提示语先消费再弹；切 tab 也不会重放（用户反馈的 bug，见 MessageEffect）
                        MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                        LibraryScreen(
                            state = state,
                            columns = 3,
                            onFilterChange = vm::updateFilter,
                            onClearFilter = vm::clearFilter,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                            onToggleCollected = vm::setCollected,
                            // 「继续阅读」卡片（§9 第 4 条）：直接回到保存的位置。
                            // 先开这一本的详情、再开阅读器，于是左栏是详情、右栏是阅读器。
                            onContinueReading = { albumId ->
                                primaryActions.openReadingFromTab(albumId, 0L)
                            },
                            // 卡片右上角 ✕：清掉「继续阅读」指针，卡片消失
                            onDismissContinueReading = vm::dismissContinueReading,
                            // 长按多选（用户要求第 1 点）
                            onDeleteFiles = vm::deleteFiles,
                            onRemoveFromLibrary = vm::removeFromLibrary,
                            // 【本地UI隐藏】批量「修改收藏夹」：本机收藏夹的 UI 已下线（收藏改走账号
                            // 在线收藏，入口在「我的」）。ViewModel 的方法与面板实现都保留，
                            // 放开这四行即可恢复书库多选里的「修改收藏夹」。
                            // onModifyFavorites = vm::modifyFavorites,
                            // onCreateFavorite = vm::createFavoriteForSelection,
                            // 往下翻页（用户第十轮要求第 4 点：筛选也要能翻到后面的页）
                            onLoadMore = vm::loadMore,
                        )
                    }

                    centeredComposable(Routes.MINE) { entry ->
                        val vm: MyViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.state.collectAsStateWithLifecycle()
                        // 进页面/从别处回来**不联网**：只读本地缓存的夹数量，避免副标题跳动；
                        // 只有本地完全没有缓存时才请求一次（用户要求 2026-10-22）
                        OnResumeEffect { vm.restoreCachedFolderCount() }
                        val accountState = state.account
                        MyScreen(
                            state = state,
                            taskState = taskState,
                            // 下拉刷新：联网重拉收藏夹名单，成功后覆盖本地缓存
                            onRefresh = { vm.refreshFolderCount() },
                            // 未登录点账号入口 = 直接去登录页，登录成功后回到「收藏」
                            // 平板分栏时二级页一律进右栏，左栏仍是「我的」（用户 2026-10-27 要求）
                            onOpenFavorites = {
                                if (accountState.isSignedIn) {
                                    primaryActions.openChild(Routes.ACCOUNT_FAVORITES)
                                } else {
                                    primaryActions.openLogin(Routes.ACCOUNT_FAVORITES)
                                }
                            },
                            onOpenRecent = { primaryActions.openChild(Routes.RECENT) },
                            onOpenComments = {
                                if (accountState.isSignedIn) {
                                    primaryActions.openChild(Routes.ACCOUNT_COMMENTS)
                                } else {
                                    primaryActions.openLogin(Routes.ACCOUNT_COMMENTS)
                                }
                            },
                            onOpenTasks = { primaryActions.openChild(Routes.TASKS) },
                            onOpenSettings = { primaryActions.openChild(Routes.SETTINGS) },
                            onOpenAbout = { primaryActions.openChild(Routes.ABOUT) },
                            onOpenProfile = {
                                if (accountState.isSignedIn) {
                                    primaryActions.openChild(Routes.ACCOUNT_PROFILE)
                                } else {
                                    primaryActions.openLogin(Routes.ACCOUNT_PROFILE)
                                }
                            },
                            onLogin = { primaryActions.openLogin(Routes.ACCOUNT_FAVORITES) },
                            onLogout = vm::logout,
                        )
                    }

                    // 登录页：成功后关掉自己，有 next 就去 next（否则留在「我的」）
                    centeredComposable(
                        route = Routes.LOGIN,
                        arguments = listOf(
                            navArgument("next") { type = NavType.StringType; defaultValue = "" },
                            // 注册验证成功但自动登录失败时，带用户名回登录页预填
                            navArgument("username") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        val next = remember(entry.arguments?.getString("next")) {
                            decodeRouteArg(entry.arguments?.getString("next").orEmpty())
                        }
                        val prefilledUsername = remember(entry.arguments?.getString("username")) {
                            decodeRouteArg(entry.arguments?.getString("username").orEmpty())
                        }
                        val vm: AccountAuthViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.state.collectAsStateWithLifecycle()
                        // 预填只做一次（转屏/重组会重放 LaunchedEffect，但 setUsername 是幂等的）
                        LaunchedEffect(prefilledUsername) {
                            if (prefilledUsername.isNotBlank()) vm.setUsername(prefilledUsername)
                        }
                        AccountLoginScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onUsernameChange = vm::setUsername,
                            onPasswordChange = vm::setPassword,
                            onTogglePasswordVisible = vm::togglePasswordVisible,
                            onSubmit = vm::submit,
                            onConsumeError = vm::consumeError,
                            onOpenSignUp = { navController.navigate(Routes.signup(next)) },
                            onOpenVerify = { navController.navigate(Routes.signup(next, verifyOnly = true)) },
                            onOpenForgot = {
                                navController.navigate(Routes.FORGOT) { launchSingleTop = true }
                            },
                            onLoggedIn = {
                                // 登录成功：摘掉登录页，按落点回到左栏或右栏（手机行为不变）
                                navigator.finishAuthTo(next)
                            },
                        )
                    }

                    // 注册页：注册 → 邮箱验证码 → 自动登录。返回键三级语义（验证码 → 表单 → 上一页）
                    // 由 `AccountSignUpScreen` 自己接管；这里只负责成功后的落点。
                    centeredComposable(
                        route = Routes.SIGNUP,
                        arguments = listOf(
                            navArgument("next") { type = NavType.StringType; defaultValue = "" },
                            // 「已有验证码」入口：跳过注册表单，直接填用户名 / 密码 / 验证码
                            navArgument("verify") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        val next = remember(entry.arguments?.getString("next")) {
                            decodeRouteArg(entry.arguments?.getString("next").orEmpty())
                        }
                        val verifyOnly = entry.arguments?.getBoolean("verify") ?: false
                        val vm: AccountSignUpViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = ViewModelFactory(container, signUpVerifyOnly = verifyOnly),
                        )
                        val state by vm.state.collectAsStateWithLifecycle()
                        AccountSignUpScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onBackToForm = vm::backToForm,
                            onUsernameChange = vm::setUsername,
                            onPasswordChange = vm::setPassword,
                            onPasswordConfirmChange = vm::setPasswordConfirm,
                            onEmailChange = vm::setEmail,
                            onGenderChange = vm::setGender,
                            onAdultConfirmedChange = vm::setAdultConfirmed,
                            onTermsAcceptedChange = vm::setTermsAccepted,
                            onTogglePasswordVisible = vm::togglePasswordVisible,
                            onSubmit = vm::submit,
                            onCodeChange = vm::setCode,
                            onSubmitCode = vm::submitCode,
                            onResendCode = vm::resendCode,
                            onConsumeError = vm::consumeError,
                            onResumeVerification = vm::resumeVerification,
                            onSignedUp = {
                                // 注册成功即已登录：登录/注册页一起摘掉，再按落点回到左栏或右栏
                                navigator.finishAuthTo(next)
                            },
                            onGoLogin = { username ->
                                // 账号已创建但自动登录失败：去登录页，预填用户名（密码让用户自己输）。
                                // `popUpTo(LOGIN)` 把"旧的登录页 + 注册页"一起摘掉，
                                // 否则栈里会叠出两个登录页、返回键会回到没预填的那个。
                                navController.navigate(Routes.login(next, username)) {
                                    popUpTo(Routes.LOGIN) { inclusive = true }
                                }
                            },
                        )
                    }

                    // 忘记密码：填注册邮箱 → `POST /forgot` → 「新密码已发送」面板。
                    // 服务端把用户名 + 新密码发到邮箱并作废旧密码；「去登录」回到栈里的登录页。
                    centeredComposable(Routes.FORGOT) { entry ->
                        val vm: AccountForgotViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.state.collectAsStateWithLifecycle()
                        AccountForgotScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onEmailChange = vm::setEmail,
                            onSubmit = vm::submit,
                            onConsumeError = vm::consumeError,
                            onEditAgain = vm::editAgain,
                            onGoLogin = { navController.popBackStack() },
                        )
                    }

                    centeredComposable(Routes.ACCOUNT_FAVORITES) { entry ->
                        AccountFavoritesDestination(
                            entry = entry,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onOpenFolder = { folderId, name ->
                                primaryActions.openChild(
                                    Routes.accountFavoriteAlbums(folderId, name),
                                )
                            },
                            onLogin = { primaryActions.openLogin(Routes.ACCOUNT_FAVORITES) },
                        )
                    }

                    composable(
                        route = Routes.ACCOUNT_FAVORITE_ALBUMS,
                        arguments = accountAlbumsArguments(),
                    ) { entry ->
                        AccountAlbumsDestination(
                            entry = entry,
                            container = container,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                        )
                    }

                    centeredComposable(Routes.ACCOUNT_PROFILE) { entry ->
                        AccountProfileDestination(
                            entry = entry,
                            factory = factory,
                            onBack = primaryActions.back,
                            onEdit = {
                                primaryActions.openChild(Routes.ACCOUNT_PROFILE_EDIT)
                            },
                        )
                    }

                    // 个人资料编辑：进页面先 GET 最新资料，加载完再渲染表单（避免官方 App 的回显 bug）
                    centeredComposable(Routes.ACCOUNT_PROFILE_EDIT) { entry ->
                        AccountProfileEditDestination(
                            entry = entry,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onSaved = primaryActions.back,
                        )
                    }

                    // 留言记录：我发过的留言（10/页）。删一条就地消失，点一行跳那本的评论 tab。
                    centeredComposable(Routes.ACCOUNT_COMMENTS) { entry ->
                        AccountCommentsDestination(
                            entry = entry,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(
                                    Routes.detail(albumId, AlbumDetailTab.COMMENTS),
                                )
                            },
                            onLogin = { primaryActions.openLogin(Routes.ACCOUNT_COMMENTS) },
                        )
                    }

                    centeredComposable(Routes.RECENT) { entry ->
                        RecentDestination(
                            entry = entry,
                            factory = factory,
                            onBack = primaryActions.back,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                        )
                    }

                    composable(Routes.CATEGORY) { entry ->
                        val vm: CategoryViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.state.collectAsStateWithLifecycle()
                        // 提示由分类页自己的 MessageEffect 消费后交给根部弹（与发现页同一套），
                        // 这里**不再叠一层** MessageEffect，否则同一条提示会弹两次。
                        CategoryScreen(
                            state = state,
                            onSelectMain = vm::selectMain,
                            onSelectSub = vm::selectSub,
                            onSortChange = vm::selectSort,
                            onToggleViewMode = vm::toggleViewMode,
                            onRefresh = vm::refresh,
                            onLoadMore = vm::loadMore,
                            onRetry = vm::retry,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                            onMessage = showMessage,
                            onConsumeMessage = vm::consumeMessage,
                        )
                    }

                    centeredComposable(Routes.DISCOVER) { entry ->
                        val vm: OnlineSearchViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.state.collectAsStateWithLifecycle()
                        OnlineSearchScreen(
                            state = state,
                            onKeywordChange = vm::onKeywordChange,
                            onSortChange = vm::onSortChange,
                            // 点「搜索」：**先跳结果页，由结果页自己发请求**（用户要求）。
                            // 关键字为空就不跳、原地提示——结果页没有输入框，空关键字跳过去没法改。
                            // 结果页进右栏，发现页（含搜索历史）留在左栏。
                            onSearch = {
                                val keyword = state.keyword.trim()
                                if (keyword.isEmpty()) {
                                    showMessage("请输入搜索关键字")
                                } else {
                                    primaryActions.openChild(
                                        Routes.searchResults(keyword, state.sort),
                                    )
                                }
                            },
                            // 点历史记录：带 historyId 进结果页，那边**只回显快照、不重搜**（用户要求）
                            onOpenHistory = { history ->
                                primaryActions.openChild(
                                    Routes.searchResults(
                                        keyword = history.keyword,
                                        sort = SearchSort.fromCode(history.sort),
                                        historyId = history.id,
                                    ),
                                )
                            },
                            onDeleteHistory = vm::deleteHistory,
                            onClearHistory = vm::clearHistory,
                            // 本页自己没有 Snackbar 宿主，提示统一交给根部弹
                            onMessage = showMessage,
                            onConsumeMessage = vm::consumeMessage,
                        )
                    }

                    composable(
                        route = Routes.SEARCH_RESULTS,
                        arguments = searchResultsArguments(),
                    ) { entry ->
                        SearchResultsDestination(
                            entry = entry,
                            container = container,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                        )
                    }

                    // 【本地UI隐藏】本机收藏夹页（v3 起收藏走账号在线收藏，入口在「我的」）。
                    // 放开这段注释 + `Tab` 里那一行 + `Routes.FAVORITE_ALBUMS` 那段，即可完整恢复。
                    /*
                    composable(Routes.FAVORITES) { entry ->
                        val vm: FavoritesViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        val state by vm.state.collectAsStateWithLifecycle()
                        MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                        FavoritesScreen(
                            state = state,
                            onOpenFavorite = { id, name ->
                                navController.navigate(Routes.favoriteAlbums(id, name))
                            },
                            onCreate = vm::create,
                            onRename = vm::rename,
                            onDelete = vm::delete,
                            onSortChange = vm::setSort,
                        )
                    }
                    */

                    centeredComposable(Routes.TASKS) {
                        TasksDestination(
                            taskViewModel = taskViewModel,
                            taskState = taskState,
                            context = context,
                            scope = scope,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                        )
                    }

                    centeredComposable(Routes.SETTINGS) { entry ->
                        SettingsDestination(
                            entry = entry,
                            factory = factory,
                            showMessage = showMessage,
                            privacyMode = privacyMode,
                            textPrivacy = textPrivacy,
                            onSetPrivacyMode = setPrivacyMode,
                            onSetTextPrivacy = setTextPrivacy,
                            onBack = primaryActions.back,
                        )
                    }

                    // 关于页：版本号 / 检查更新（应用内下载 APK 并唤起安装）/ 存储统计。
                    // 更新下载由系统 DownloadManager 承担（进程被杀也继续），
                    // 这里只负责状态接线与"发安装 Intent / 开设置页"这类 Context 动作
                    //（与导出成品的 launchOutputIntent 同一模式）。
                    centeredComposable(Routes.ABOUT) { entry ->
                        AboutDestination(
                            entry = entry,
                            container = container,
                            factory = factory,
                            context = context,
                            scope = scope,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            onOpenAlbum = { albumId ->
                                primaryActions.openChild(Routes.detail(albumId))
                            },
                        )
                    }

                    // 【本地UI隐藏】本机收藏夹内页（原「夹内复用书库布局」）。
                    // 与上面的 `Routes.FAVORITES` 一起放开即可恢复。
                    /*
                    composable(
                        route = Routes.FAVORITE_ALBUMS,
                        arguments = listOf(
                            navArgument("favoriteId") { type = NavType.LongType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        val favoriteId = entry.arguments?.getLong("favoriteId") ?: return@composable
                        // 与搜索关键字同一条路：收藏夹名也是用户输入的任意文本（可能带空格 / `%`），
                        // 所以同样只解一次 [decodeRouteArg]，不再叠 `URLDecoder.decode`
                        val favoriteName = remember(entry.arguments?.getString("name")) {
                            decodeRouteArg(entry.arguments?.getString("name").orEmpty())
                        }
                        // 夹内复用书库布局（计划 §9 第 6 条）
                        val vm: LibraryViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        LaunchedEffect(favoriteId) {
                            vm.updateFilter { it.copy(favoriteId = favoriteId) }
                        }
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                        LibraryScreen(
                            state = state,
                            columns = 3,
                            // 用户要求第 7 点：显示当前收藏夹名称
                            title = favoriteName.ifBlank { "收藏夹" },
                            // 用户要求第 7 点：夹内只保留「已下载 / 未下载」
                            showCollectedChip = false,
                            onFilterChange = vm::updateFilter,
                            onClearFilter = { vm.updateFilter { it.copy(favoriteId = favoriteId) } },
                            onOpenAlbum = { navController.navigate(Routes.detail(it)) },
                            onToggleCollected = vm::setCollected,
                            // 夹内批量模式（用户要求第 3 点）：
                            // - 不提供「删除文件」「移出书库」；
                            // - 按钮文案改成「修改收藏夹」；
                            // - 打开面板时把当前夹预先勾上（选中的本子本来都在这夹里）。
                            moveFavoriteLabel = "修改收藏夹",
                            preselectedFavoriteIds = setOf(favoriteId),
                            onModifyFavorites = vm::modifyFavorites,
                            onCreateFavorite = vm::createFavoriteForSelection,
                            onLoadMore = vm::loadMore,
                        )
                    }
                    */

                    // 详情页：主栈与辅栈共用同一份实现；点阅读 / 点章节时由外层
                    // 把详情换到左栏、阅读器放到右栏（用户 2026-10-27 要求）。
                    centeredComposable(
                        route = Routes.DETAIL,
                        arguments = detailArguments(),
                    ) { entry ->
                        AlbumDetailDestination(
                            entry = entry,
                            container = container,
                            actions = primaryActions,
                            context = context,
                            showMessage = showMessage,
                            scope = scope,
                        )
                    }

                    composable(
                        route = Routes.READER,
                        arguments = readerArguments(),
                    ) { entry ->
                        ReaderDestination(
                            entry = entry,
                            container = container,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = primaryActions.back,
                            windowEffectsEnabled = !authOpen,
                        )
                    }
                    }
                        },
                        secondary = {
                            SecondaryPaneHost(
                                paneNavController = paneNavController,
                                container = container,
                                factory = factory,
                                taskViewModel = taskViewModel,
                                taskState = taskState,
                                privacyMode = privacyMode,
                                textPrivacy = textPrivacy,
                                setPrivacyMode = setPrivacyMode,
                                setTextPrivacy = setTextPrivacy,
                                context = context,
                                showMessage = showMessage,
                                scope = scope,
                                actions = secondaryActions,
                                windowEffectsEnabled = !authOpen,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                    // 系统返回键：**放在两个 NavHost 之后**组合，注册顺序最靠后，
                    // 才能赢过 NavHost 内部注册的返回回调（否则它会把背后那一栏的
                    // 页面退掉，屏幕上留着当前页）。顺序是：
                    // 页面自己的拦截（[PageBackHandler]，见 backInterceptors）→ 轮播逻辑。
                    // 右栏没内容、左栏是 tab 时不禁用，交给系统退出 App（与原来一致）。
                    BackHandler(
                        enabled = backInterceptors.hasEnabled || navigator.canBack(),
                    ) {
                        if (!backInterceptors.consume()) navigator.backAction()
                    }
                }
            }

            GlobalSnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }
    }
}

/**
 * 全局消息提示的宿主。
 *
 * 挂在 ModeRoot 的 Box 覆盖层里、用 `Alignment.Center` 居中，不再交给 Scaffold 的
 * `snackbarHost` 槽——那个槽固定贴着底栏上方，提示总在屏幕最底部（用户反馈看着累）。
 */
@Composable
private fun GlobalSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        // 宿主的淡入淡出层按"最宽的那条"定宽，这里铺满可用宽度再居中，
        // 新旧提示交替时才不会把窄的那条顶到左边。
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppSnackbar(message = data.visuals.message)
        }
    }
}

/**
 * 提示框本体：宽度随内容自适应（最短 140dp），到两侧留白处自动换行；文字居中；
 * 底色是比 `inverseSurface` 略浅的深灰再叠 90% 不透明度（不是白底）。
 */
@Composable
private fun AppSnackbar(message: String) {
    Surface(
        modifier = Modifier.widthIn(min = 140.dp),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.90f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 两个底栏 tab 之间的"移动方向"：正数表示往右切（目标在右边），负数表示往左切。
 *
 * 有一端不是底栏 tab（进详情页/阅读器、从它们返回）时返回正数，
 * 于是走的还是原来那套"新页面从右侧滑入"。
 */
internal fun tabShift(fromRoute: String?, toRoute: String?): Int {
    val from = Tab.indexOfRoute(fromRoute)
    val to = Tab.indexOfRoute(toRoute)
    if (from < 0 || to < 0) return 1
    return to - from
}

/**
 * 入栈时新页面的入场动画：从侧边整页滑入（[shift] > 0 从右、< 0 从左）。
 *
 * **两个导航宿主必须用同一套**（见 [CarouselPanes] 的两个 `NavHost`）：
 * `NavHost` 不传这几个参数时走的是 framework 默认的 `fadeIn/fadeOut`，
 * 于是同一份代码在"左栏 / 右栏"里切换就变成了渐入渐出（用户反馈的 bug）。
 */
private fun pageEnter(shift: Int): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> if (shift < 0) -fullWidth else fullWidth },
        animationSpec = tween(PAGE_TRANSITION_MS),
    ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))

/** 入栈时旧页面的退场：往反方向退 1/4 宽，两页像一条传送带。 */
private fun pageExit(shift: Int): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> if (shift < 0) fullWidth / 4 else -fullWidth / 4 },
        animationSpec = tween(PAGE_TRANSITION_MS),
    ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_MS))

/** 返回时上一页的入场：从左侧 1/4 处滑回原位（[pageEnter] 的反向）。 */
private fun pagePopEnter(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(PAGE_TRANSITION_MS),
    ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))

/** 返回时当前页的退场：整页往右滑走（[pageExit] 的反向）。 */
private fun pagePopExit(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(PAGE_TRANSITION_MS),
    ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_MS))

/**
 * 与 [composable] 相同，只是把页面内容套进 [CenteredContent]。
 *
 * 平板横屏时阅读型页面（设置、账号、我的、详情等）保持 840dp 的内容列；
 * 手机窗口比 840dp 窄，套与不套的布局完全一致。
 */
private fun NavGraphBuilder.centeredComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        CenteredContent { content(entry) }
    }
}

/**
 * 登录 / 注册 / 找回密码这三个表单页的路由。
 *
 * 它们**永远整页显示**（不放进右栏）：登录成功后的落点由 `finishAuthTo` 统一处理，
 * 这样手机上的行为一点不变，平板上也能把页面放回正确的栏。
 */
private val AUTH_ROUTES = setOf(Routes.LOGIN, Routes.SIGNUP, Routes.FORGOT)

/** [route] 是不是登录 / 注册 / 找回密码这类整页表单。 */
private fun isAuthRoute(route: String?): Boolean = route != null && route in AUTH_ROUTES

/** 右栏的起点：还没有选任何漫画时的空态。 */
private const val SPLIT_PANE_EMPTY = "jm/split/empty"

/** 详情页的路由参数（主栈与右栏共用，避免两处各写一份）。 */
private fun detailArguments(): List<NamedNavArgument> = listOf(
    navArgument("albumId") { type = NavType.LongType },
    // 可选：空串 = 章节（默认），`comments` = 进来就停在评论 tab。
    // 带默认值的查询参数不写也能匹配 `jm/album/{id}`，旧入口不用改。
    navArgument("tab") { type = NavType.StringType; defaultValue = "" },
)

/**
 * 第二个导航宿主（辅栈）。
 *
 * 两个宿主轮流出任"左栏（上一页）/ 右栏（当前页）"，所以这里不再固定叫"右栏"。
 * - 左栏列表点一本 → 详情进这里（显示在右）；
 * - 从右栏的页面再点进下一页时，当前页换到左边，新页进另一个宿主——页面实例保留。
 * - 这里始终从 [SPLIT_PANE_EMPTY] 起步，空态就是平板右栏的"在左侧选择一本漫画"提示。
 *
 * **手机上这个宿主不装页面**（窗口分不了栏时二级页一律压在主栈上，见
 * [CarouselNavigator.openChild]）：它只在平板分栏时出任右栏。
 */
@Composable
private fun SecondaryPaneHost(
    paneNavController: NavHostController,
    container: AppContainer,
    factory: ViewModelFactory,
    taskViewModel: TaskViewModel,
    taskState: TaskUiState,
    privacyMode: Boolean,
    textPrivacy: Boolean,
    setPrivacyMode: (Boolean) -> Unit,
    setTextPrivacy: (Boolean) -> Unit,
    context: Context,
    showMessage: (String) -> Unit,
    scope: CoroutineScope,
    actions: PaneNavActions,
    /** 被登录页等整页盖住时传 false：阅读器仍在组合里，但不接管窗口亮度。 */
    windowEffectsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = paneNavController,
        startDestination = SPLIT_PANE_EMPTY,
        modifier = modifier,
        // 与主栈**同一套**切换动画（[pageEnter] 等）。这里不传参的话就是 framework
        // 默认的淡入淡出：手机上看不出问题，但平板分栏时右栏换页会变成渐入渐出。
        // 右栏里没有底栏那条"从左到右的轴"，方向恒为"新页从右边进来"（shift = 1）。
        enterTransition = { pageEnter(shift = 1) },
        exitTransition = { pageExit(shift = 1) },
        popEnterTransition = { pagePopEnter() },
        popExitTransition = { pagePopExit() },
    ) {
        composable(SPLIT_PANE_EMPTY) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "在左侧选择一本漫画",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        centeredComposable(
            route = Routes.DETAIL,
            arguments = detailArguments(),
        ) { entry ->
            AlbumDetailDestination(
                entry = entry,
                container = container,
                actions = actions,
                context = context,
                showMessage = showMessage,
                scope = scope,
            )
        }
        composable(
            route = Routes.READER,
            arguments = readerArguments(),
        ) { entry ->
            ReaderDestination(
                entry = entry,
                container = container,
                factory = factory,
                showMessage = showMessage,
                onBack = actions.back,
                windowEffectsEnabled = windowEffectsEnabled,
            )
        }
        // ---- 发现页 → 搜索结果 ----
        composable(
            route = Routes.SEARCH_RESULTS,
            arguments = searchResultsArguments(),
        ) { entry ->
            SearchResultsDestination(
                entry = entry,
                container = container,
                factory = factory,
                showMessage = showMessage,
                onBack = actions.back,
                onOpenAlbum = { albumId -> actions.openChild(Routes.detail(albumId)) },
            )
        }
        // ---- 「我的」→ 各二级页 ----
        centeredComposable(Routes.ACCOUNT_FAVORITES) { entry ->
            AccountFavoritesDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                onBack = actions.back,
                onOpenFolder = { folderId, name ->
                    actions.openChild(Routes.accountFavoriteAlbums(folderId, name))
                },
                onLogin = { actions.openLogin(Routes.ACCOUNT_FAVORITES) },
            )
        }
        composable(
            route = Routes.ACCOUNT_FAVORITE_ALBUMS,
            arguments = accountAlbumsArguments(),
        ) { entry ->
            AccountAlbumsDestination(
                entry = entry,
                container = container,
                showMessage = showMessage,
                onBack = actions.back,
                onOpenAlbum = { albumId -> actions.openChild(Routes.detail(albumId)) },
            )
        }
        centeredComposable(Routes.ACCOUNT_PROFILE) { entry ->
            AccountProfileDestination(
                entry = entry,
                factory = factory,
                onBack = actions.back,
                onEdit = { actions.openChild(Routes.ACCOUNT_PROFILE_EDIT) },
            )
        }
        centeredComposable(Routes.ACCOUNT_PROFILE_EDIT) { entry ->
            AccountProfileEditDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                onBack = actions.back,
                onSaved = actions.back,
            )
        }
        centeredComposable(Routes.ACCOUNT_COMMENTS) { entry ->
            AccountCommentsDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                onBack = actions.back,
                onOpenAlbum = { albumId ->
                    actions.openChild(Routes.detail(albumId, AlbumDetailTab.COMMENTS))
                },
                onLogin = { actions.openLogin(Routes.ACCOUNT_COMMENTS) },
            )
        }
        centeredComposable(Routes.RECENT) { entry ->
            RecentDestination(
                entry = entry,
                factory = factory,
                onBack = actions.back,
                onOpenAlbum = { albumId -> actions.openChild(Routes.detail(albumId)) },
            )
        }
        centeredComposable(Routes.TASKS) {
            TasksDestination(
                taskViewModel = taskViewModel,
                taskState = taskState,
                context = context,
                scope = scope,
                showMessage = showMessage,
                onBack = actions.back,
                onOpenAlbum = { albumId -> actions.openChild(Routes.detail(albumId)) },
            )
        }
        centeredComposable(Routes.SETTINGS) { entry ->
            SettingsDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                privacyMode = privacyMode,
                textPrivacy = textPrivacy,
                onSetPrivacyMode = setPrivacyMode,
                onSetTextPrivacy = setTextPrivacy,
                onBack = actions.back,
            )
        }
        centeredComposable(Routes.ABOUT) { entry ->
            AboutDestination(
                entry = entry,
                container = container,
                factory = factory,
                context = context,
                scope = scope,
                showMessage = showMessage,
                onBack = actions.back,
                onOpenAlbum = { albumId -> actions.openChild(Routes.detail(albumId)) },
            )
        }
    }
}

/**
 * 详情页的唯一实现：两个导航宿主共用。
 *
 * 导航分工（全部走 [PaneNavActions]，由外层按"详情当前在左栏还是右栏"决定落点）：
 * - 返回 → [PaneNavActions.back]：退掉当前页并把上一页换到右栏；
 * - 点推荐漫画 → 当前详情换到左栏、新详情放右栏（返回能回到上一本）；
 * - 阅读器 / 作者搜索 → 同样作为"当前页的子页"打开；
 * - 登录 → 整页，登录完回落到本页。
 */
@Composable
private fun AlbumDetailDestination(
    entry: NavBackStackEntry,
    container: AppContainer,
    actions: PaneNavActions,
    context: Context,
    showMessage: (String) -> Unit,
    scope: CoroutineScope,
) {
    val albumId = entry.arguments?.getLong("albumId") ?: return
    val initialTab = AlbumDetailTab.fromCode(entry.arguments?.getString("tab"))
    val vm: AlbumDetailViewModel = viewModel(
        viewModelStoreOwner = entry,
        factory = ViewModelFactory(container, albumId, initialTab = initialTab),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // 从任务页/阅读器回来时重算章节状态（刚下完的章节要立刻显示"已下载"）
    OnResumeEffect { vm.refreshLocalStats() }
    // 「移出书库」**不再自动退回上一页**（用户要求：退不退由用户自己按返回键决定）。
    //
    // 移除成功后停留在这页是有意义的：本子本身还在（最近浏览记录保留），
    // 页面就地变成"未入库 + 已下载 0 话"的样子，用户可以再点「未入库」加回来，
    // 也可以自己返回。删除本身仍跑在这个导航 entry 的 viewModelScope 里，
    // 所以移除途中用户自己退出的话，那次删除会和 ViewModel 一起被取消
    // ——这一点由用户掌握，不再由界面替他决定跳转。
    AlbumDetailScreen(
        state = state,
        onBack = actions.back,
        onRefresh = { vm.refresh() },
        onToggleReversed = vm::toggleReversed,
        onToggleDescription = vm::toggleDescription,
        onAddToLibrary = vm::addToLibrary,
        onMoveToFavorite = vm::moveToFavorite,
        onAddToFavorite = vm::addToFavorite,
        onRemoveFromFavorite = vm::removeFromFavorite,
        onCreateFavoriteAndAdd = vm::createFavoriteAndAdd,
        // ---- 账号收藏（官方在线收藏）----
        // 未登录点「收藏」= 去登录，登录成功回到本详情页（不自动收藏，用户再点一次）
        onOpenAccountFavoriteSheet = {
            if (state.accountSignedIn) {
                vm.openAccountFavoriteSheet()
            } else {
                actions.openLogin("")
            }
        },
        onDismissAccountFavoriteSheet = vm::dismissAccountFavoriteSheet,
        onChooseAccountFavorite = vm::chooseAccountFavorite,
        onCreateAccountFolderAndAdd = vm::createAccountFolderAndAdd,
        onRequireLogin = {
            actions.openLogin("")
        },
        // 点章节进阅读器：**先把这个本子的进度改写成这一话**，
        // 阅读器再按"从这一话第一张开始"打开（用户第十一轮要求第 1 点）。
        // 外层会把当前详情换到左栏、阅读器放到右栏；手机竖屏则整页进入阅读器。
        onReadChapter = { chapterId ->
            vm.onChapterOpened(chapterId)
            actions.openChild(Routes.reader(albumId, chapterId))
        },
        // 主按钮：有进度就按存下来的位置续读（不带章节号 = 续读），
        // 没进度才从第一话第一张开始（用户第十一轮要求第 1 点）
        onReadFromStart = {
            val position = state.progress
            if (position?.isUsable == true) {
                actions.openChild(Routes.reader(albumId, 0L))
            } else {
                val first = state.chapters.firstOrNull()
                if (first != null) {
                    vm.onChapterOpened(first.chapterId)
                    actions.openChild(Routes.reader(albumId, first.chapterId))
                }
            }
        },
        onDownloadChapters = vm::downloadChapters,
        onDownloadOfficialZip = vm::downloadOfficialZip,
        onDeleteChapterFiles = vm::deleteChapterFiles,
        onDeleteAlbumFiles = vm::deleteAlbumFiles,
        // 简介下方两个 tab（章节 / 评论）：切到评论时才去拉第一页，
        // 加载过的评论挂在内存缓存上（退出 App 清空）
        onSelectTab = vm::selectTab,
        onRefreshComments = vm::refreshComments,
        onLoadMoreComments = vm::loadMoreComments,
        onRetryComments = vm::retryComments,
        // 评论 tab 底部输入条：发留言 / 回复（未登录时那条「去登录」）
        onCommentInputChange = vm::setCommentInput,
        onSendComment = vm::sendComment,
        onReplyComment = vm::replyToComment,
        onCancelCommentReply = vm::cancelCommentReply,
        onConsumeCommentNotice = vm::consumeCommentNotice,
        onDeleteComment = vm::deleteComment,
        onExport = vm::startExport,
        onDownloadMissing = vm::downloadMissingChapters,
        onDismissExportPrecheck = vm::dismissExportPrecheck,
        onConfirmExport = vm::confirmExport,
        onExportWithoutPassword = vm::exportWithoutPassword,
        onCancelExport = vm::cancelExport,
        // 详情页「导出文件」区块的三个操作（用户第九轮要求第 2 点）
        onOpenOutput = { name ->
            launchOutputIntent(context, name, share = false, showMessage, scope) {
                vm.outputUri(name)
            }
        },
        onSendOutput = { name ->
            launchOutputIntent(context, name, share = true, showMessage, scope) {
                vm.outputUri(name)
            }
        },
        onDeleteExport = vm::deleteExport,
        // 「打开文件夹」：把系统文件管理器拉到成品目录（用户反馈第 2 点）
        onOpenFolder = {
            openExportFolder(context, vm.outputFolderUri(), showMessage)
        },
        // 点作者：**直接开结果页按作者名搜**（用户要求），不再绕去发现页填搜索框。
        // 作为当前详情页的子页打开（详情换到左栏、结果页在右栏）。
        onSearchAuthor = { author ->
            actions.openChild(Routes.searchResults(author))
        },
        // 点评论里的「我推荐这本书 <jmid>」：打开那本漫画的详情页。
        // 当前这本保持无反应；其它本子作为子页打开（详情换到左栏、新详情在右栏）。
        onOpenAlbum = { targetId ->
            if (targetId != albumId) {
                actions.openChild(Routes.detail(targetId))
            }
        },
        // 提示交给根 Scaffold 弹，否则会被收藏夹底部面板挡住（用户反馈）；
        // 页面内部的 MessageEffect 会先把消息消费掉，所以切页不会重放
        onMessage = showMessage,
        // 移除成功后**不自动返回**（用户要求）：页面就地变成"未入库"，
        // 什么时候离开由用户自己按返回键决定
        onDeleteAlbumRecord = vm::removeFromLibrary,
        onConsumeMessage = vm::consumeMessage,
    )
}

/**
 * 搜索结果页的唯一实现。
 *
 * 主栈（手机 / 非分栏）与平板右栏共用（用户 2026-10-27 要求：发现页搜索 / 点历史时
 * 左栏仍是发现页、右栏才是结果页）。`onBack` 由宿主决定退哪一栏。
 */
@Composable
private fun SearchResultsDestination(
    entry: NavBackStackEntry,
    container: AppContainer,
    factory: ViewModelFactory,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    // 路由参数只在这里解一次（[encodeRouteArg] 是自描述的：不带前缀就是明文）。
    // **不要再叠一层 `URLDecoder.decode`**：Navigation 已经做过一次百分号解码，
    // 关键字里的 `%` 会让第二次解码抛异常并退回未解码的串（用户报的 `+` bug）。
    val keyword = remember(entry.arguments?.getString("keyword")) {
        decodeRouteArg(entry.arguments?.getString("keyword").orEmpty())
    }
    val sort = SearchSort.fromCode(entry.arguments?.getString("sort"))
    val historyId = entry.arguments?.getLong("historyId") ?: 0L
    // 搜索请求由结果页自己的 ViewModel 发起（进页面就搜）：
    // 挂在这个 entry 上保证转屏/重组不会重复发，路由参数只作为入参传一次。
    val vm: OnlineSearchViewModel = viewModel(
        viewModelStoreOwner = entry,
        factory = ViewModelFactory(
            container,
            searchEntry = OnlineSearchEntry(keyword, sort, historyId),
        ),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    SearchResultsScreen(
        state = state,
        // 结果页自带输入框（用户要求）：改完关键字点右侧按钮/回车是**原地重搜**，
        // 不再往导航栈上再压一层结果页（`SearchResultsScreen` 内部也不再跳转）。
        onKeywordChange = vm::onKeywordChange,
        onSearch = vm::search,
        onBack = onBack,
        onRefresh = vm::refresh,
        // 换排序立刻重搜：只改芯片不重搜的话，选中的芯片会和列表内容对不上
        onSortChange = vm::changeSort,
        onLoadMore = vm::loadMore,
        onRevealMore = vm::showMoreItems,
        // 卡片 / 列表切换（默认卡片，与分类页同一套；纯界面状态，不重搜）
        onToggleViewMode = vm::toggleViewMode,
        onOpenAlbum = onOpenAlbum,
        onAddToLibrary = vm::addToLibrary,
        // 点卡片上的「已入库」= 移出书库（页面内已二次确认）
        onRemoveFromLibrary = vm::removeFromLibrary,
        // 本页自己没有 Snackbar 宿主，提示统一交给根部弹
        onMessage = showMessage,
        onConsumeMessage = vm::consumeMessage,
    )
}

/** 「最近浏览」页（主栈与右栏共用）。 */
@Composable
private fun RecentDestination(
    entry: NavBackStackEntry,
    factory: ViewModelFactory,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val vm: RecentViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    RecentScreen(
        state = state,
        // 二级页自己带返回按钮（底栏在这一层是隐藏的）
        onBack = onBack,
        onOpenAlbum = onOpenAlbum,
        onClearAll = vm::clearAll,
        onRemoveAlbum = vm::remove,
    )
}

/** 账号收藏夹列表页（主栈与右栏共用）。 */
@Composable
private fun AccountFavoritesDestination(
    entry: NavBackStackEntry,
    factory: ViewModelFactory,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenFolder: (String?, String) -> Unit,
    onLogin: () -> Unit,
) {
    val vm: AccountFavoritesViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    // 从夹内页 / 详情页回来时先按内存快照同步：取消收藏已就地减掉总数，
    // 不用用户再下拉刷新一次（用户要求 2026-10-22）。
    OnResumeEffect { vm.onScreenResumed() }
    // 进页面**不再自动刷新**：ViewModel 先用进程内快照铺内容（见 AccountCache），
    // 只有数据被动过才静默补一次；用户想刷新就下拉。
    AccountFavoritesScreen(
        state = state,
        onBack = onBack,
        onOpenFolder = onOpenFolder,
        onCreate = vm::create,
        onRename = vm::rename,
        onDelete = vm::delete,
        onRefresh = vm::refresh,
        onLogin = onLogin,
        onMessage = showMessage,
        onConsumeMessage = vm::consumeMessage,
    )
}

/** 账号收藏夹内页（漫画网格；主栈与右栏共用）。 */
@Composable
private fun AccountAlbumsDestination(
    entry: NavBackStackEntry,
    container: AppContainer,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val rawFolderId = entry.arguments?.getLong("folderId") ?: 0L
    val name = remember(entry.arguments?.getString("name")) {
        decodeRouteArg(entry.arguments?.getString("name").orEmpty())
    }
    val folderId = rawFolderId.takeIf { it > 0L }?.toString()
    val vm: AccountAlbumsViewModel = viewModel(
        viewModelStoreOwner = entry,
        factory = ViewModelFactory(container, accountFolderId = folderId, accountFolderName = name),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // 从详情页回来时按内存快照同步：在详情页取消收藏后，
    // 这本已从缓存里删掉，返回即可见、不用下拉刷新（用户要求 2026-10-22）。
    OnResumeEffect { vm.onScreenResumed() }
    AccountAlbumsScreen(
        state = state,
        onBack = onBack,
        onOpenAlbum = onOpenAlbum,
        onLoadMore = vm::loadMore,
        onOrderChange = vm::setOrder,
        onRemove = vm::remove,
        onRefresh = vm::refresh,
        onMessage = showMessage,
        onConsumeMessage = vm::consumeMessage,
    )
}

/** 个人资料页（主栈与右栏共用）。 */
@Composable
private fun AccountProfileDestination(
    entry: NavBackStackEntry,
    factory: ViewModelFactory,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val vm: AccountProfileViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    // 从编辑页返回：保存成功后新资料已写进账号缓存，这里把它同步到界面上
    // （否则返回资料页看到的还是编辑前的内容，用户会以为没保存）。
    OnResumeEffect { vm.onScreenResumed() }
    AccountProfileScreen(
        state = state,
        onBack = onBack,
        onRefresh = vm::refresh,
        onEdit = onEdit,
    )
}

/** 个人资料编辑页（主栈与右栏共用）。 */
@Composable
private fun AccountProfileEditDestination(
    entry: NavBackStackEntry,
    factory: ViewModelFactory,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val vm: AccountProfileEditViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    AccountProfileEditScreen(
        state = state,
        onBack = onBack,
        onFormChange = vm::updateForm,
        onSave = vm::save,
        onRetry = vm::retry,
        onMessage = showMessage,
        onConsumeMessage = vm::consumeMessage,
        onConsumeSaveError = vm::consumeSaveError,
        onSaved = onSaved,
    )
}

/** 「留言记录」页（主栈与右栏共用）。 */
@Composable
private fun AccountCommentsDestination(
    entry: NavBackStackEntry,
    factory: ViewModelFactory,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onLogin: () -> Unit,
) {
    val vm: AccountCommentsViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    AccountCommentsScreen(
        state = state,
        onBack = onBack,
        onOpenAlbum = onOpenAlbum,
        onRefresh = vm::refresh,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onDelete = vm::delete,
        onLogin = onLogin,
        // 本页自己没有 Snackbar 宿主，提示统一交给根部弹
        onMessage = showMessage,
        onConsumeMessage = vm::consumeMessage,
    )
}

/** 「下载任务」页（主栈与右栏共用）。任务 ViewModel 挂在 Activity 上，两处一致。 */
@Composable
private fun TasksDestination(
    taskViewModel: TaskViewModel,
    taskState: TaskUiState,
    context: Context,
    scope: CoroutineScope,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    MessageEffect(
        taskState.message,
        onConsume = taskViewModel::consumeMessage,
        onShow = showMessage,
    )
    TaskScreen(
        state = taskState,
        // 二级页自己带返回按钮（底栏在这一层是隐藏的）
        onBack = onBack,
        onToggleExpanded = taskViewModel::toggleExpanded,
        onPause = taskViewModel::pause,
        onResume = taskViewModel::resume,
        onCancel = taskViewModel::cancel,
        onRetry = taskViewModel::retry,
        onRemove = taskViewModel::remove,
        onClearFinished = taskViewModel::clearFinished,
        // 点封面进详情页（用户要求第 1 点）
        onOpenAlbum = onOpenAlbum,
        // 导出成品的「打开至 / 发送至」（用户要求第 4 点）
        onOpenOutput = { name ->
            launchOutputIntent(context, name, share = false, showMessage, scope) {
                taskViewModel.outputUri(name)
            }
        },
        onSendOutput = { name ->
            launchOutputIntent(context, name, share = true, showMessage, scope) {
                taskViewModel.outputUri(name)
            }
        },
        onDeleteOutput = taskViewModel::deleteOutput,
    )
}

/** 「应用设置」页（主栈与右栏共用）。 */
@Composable
private fun SettingsDestination(
    entry: NavBackStackEntry,
    factory: ViewModelFactory,
    showMessage: (String) -> Unit,
    privacyMode: Boolean,
    textPrivacy: Boolean,
    onSetPrivacyMode: (Boolean) -> Unit,
    onSetTextPrivacy: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
    SettingsScreen(
        state = state,
        onBack = onBack,
        // 阅读与显示：阅读背景与亮度放在阅读器的设置面板里（跟着环境变，进阅读器再改）
        onSetReadingMode = vm::setReadingMode,
        onSetReadingDirection = vm::setReadingDirection,
        onSetKeepScreenOn = vm::setKeepScreenOn,
        // 隐私模式：与全局双指手势共用同一个存储值
        privacyMode = privacyMode,
        onSetPrivacyMode = onSetPrivacyMode,
        // 文字隐私：只在隐私模式开启时显示，单独保存
        textPrivacy = textPrivacy,
        onSetTextPrivacy = onSetTextPrivacy,
        // 下载
        onSetDownloadThreads = vm::setDownloadThreads,
        onSetOfficialZipConcurrency = vm::setOfficialZipConcurrency,
        onSetDownloadNetwork = vm::setDownloadNetwork,
        // 语言：接口返回的中文用简中还是繁中（默认简中）
        onSetApiLanguage = vm::setApiLanguage,
        // 导出默认密码（原文写进加密凭据存储，页面上默认用圆点遮住）
        onSaveZipPassword = vm::saveZipPassword,
        onSavePdfPassword = vm::savePdfPassword,
        // 导出图片编码：有损（默认，接近官方包）/ 无损（像素完全不变）
        onSetImageEncoding = vm::setImageEncoding,
        // 网络与线路
        onSetTimeoutSeconds = vm::setTimeoutSeconds,
        onSetImageTimeoutSeconds = vm::setImageTimeoutSeconds,
        onSaveProxy = vm::saveProxy,
        // 线路：清单里选中即生效（没有「保存」按钮）；
        // 两个面板里都能「全部测速」（接口线路打 /setting，图片线路取探针图）
        onSelectApiLine = vm::selectApiLine,
        onSelectImageLine = vm::selectImageLine,
        onResetApiLine = vm::resetApiLine,
        onResetImageLine = vm::resetImageLine,
        onTestApiLines = vm::testAllApiLines,
        onTestImageLines = vm::testAllImageLines,
        // 高级：接口请求重试（所有接口共用）与图片加载/下载重试（仅超时）
        onSetRequestRetry = vm::setRequestRetry,
        onSetImageRetry = vm::setImageRetry,
        // 账号只读接口是否复用服务端缓存（关掉后请求带 t，强制回源）
        onSetAccountApiCache = vm::setAccountApiCache,
        // 恢复默认：只重置偏好，不动书库与文件、不动凭据
        onResetSettings = vm::resetSettings,
    )
}

/** 「关于」页（主栈与右栏共用）。 */
@Composable
private fun AboutDestination(
    entry: NavBackStackEntry,
    container: AppContainer,
    factory: ViewModelFactory,
    context: Context,
    scope: CoroutineScope,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val vm: AboutViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    // 从详情页（删文件/下载）或别的页面回来时重扫存储，数字跟着变
    OnResumeEffect { vm.rescan() }
    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
    AboutScreen(
        state = state,
        onBack = onBack,
        onSelectSegment = vm::select,
        onOpenAlbum = onOpenAlbum,
        onCheckUpdate = vm::checkUpdate,
        onRetryScan = vm::rescan,
        onDownloadUpdate = vm::downloadUpdate,
        onInstallUpdate = { downloadId ->
            launchApkInstall(context, container.appDownloadGateway, downloadId, showMessage)
        },
        onCancelUpdateDownload = vm::cancelUpdateDownload,
        onUpdateToBackground = vm::updateToBackground,
        onDismissUpdateDialog = vm::dismissUpdateDialog,
        onOpenReleasesPage = {
            openUrl(context, ReleaseChecker.RELEASES_PAGE_URL, showMessage)
        },
        // 明细行操作：导出成品的「打开至 / 发送至」（与任务页/详情页同一套 Intent）
        onOpenOutput = { name ->
            launchOutputIntent(context, name, share = false, showMessage, scope) {
                vm.outputUri(name)
            }
        },
        onSendOutput = { name ->
            launchOutputIntent(context, name, share = true, showMessage, scope) {
                vm.outputUri(name)
            }
        },
        onDeleteUsage = vm::deleteUsage,
    )
}

/**
 * 阅读器页（主栈与右栏共用）。
 *
 * 横屏双栏时它挂在右栏（左栏是这一本的详情）；手机 / 竖屏时挂在主栈、占满屏幕。
 * `onBack` 由宿主决定退右栏还是整页，退出前都把去抖窗口里的位置立刻落库。
 */
@Composable
private fun ReaderDestination(
    entry: NavBackStackEntry,
    container: AppContainer,
    factory: ViewModelFactory,
    showMessage: (String) -> Unit,
    onBack: () -> Unit,
    /** 右栏被整页盖住（登录等）时为 false：阅读器仍在组合里保温，但不接管窗口亮度。 */
    windowEffectsEnabled: Boolean = true,
) {
    val albumId = entry.arguments?.getLong("albumId") ?: return
    val chapterId = entry.arguments?.getLong("chapterId") ?: 0L
    val vm: ReaderViewModel = viewModel(
        viewModelStoreOwner = entry,
        factory = ViewModelFactory(container, albumId, chapterId.takeIf { it > 0 }),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
    // 从任务页/详情页回到阅读器时重新扫一遍本地缓存：
    // 刚下好的页要立刻显示出来（用户要求第 2 点）
    OnResumeEffect { vm.refreshFromDisk() }
    ReaderScreen(
        state = state,
        onBack = {
            vm.flushPosition()
            onBack()
        },
        onPositionChanged = vm::onPositionChanged,
        onToggleControls = vm::toggleControls,
        onPreviousChapter = vm::previousChapter,
        onNextChapter = vm::nextChapter,
        onOpenChapter = vm::openChapterAt,
        onShowChapterPicker = vm::showChapterPicker,
        onShowSettings = vm::showSettings,
        onDismissSheets = vm::dismissSheets,
        onRetryPage = vm::retryPage,
        onRequestPage = vm::requestPage,
        onDownloadChapter = vm::downloadCurrentChapter,
        onRetryLoad = vm::retryLoad,
        onToggleCollected = vm::toggleCollected,
        onSetReadingMode = vm::setReadingMode,
        onSetDirection = vm::setReadingDirection,
        onSetBackground = vm::setBackground,
        onSetBrightnessFollowSystem = vm::setBrightnessFollowSystem,
        onSetBrightness = vm::setBrightness,
        onSetKeepScreenOn = vm::setKeepScreenOn,
        windowEffectsEnabled = windowEffectsEnabled,
    )
}

/** 搜索结果页的路由参数（主栈与右栏共用）。 */
private fun searchResultsArguments(): List<NamedNavArgument> = listOf(
    navArgument("keyword") { type = NavType.StringType; defaultValue = "" },
    navArgument("sort") { type = NavType.StringType; defaultValue = SearchSort.DEFAULT.code },
    navArgument("historyId") { type = NavType.LongType; defaultValue = 0L },
)

/** 阅读器的路由参数（主栈与右栏共用）。 */
private fun readerArguments(): List<NamedNavArgument> = listOf(
    navArgument("albumId") { type = NavType.LongType },
    navArgument("chapterId") { type = NavType.LongType; defaultValue = 0L },
)

/** 账号收藏夹内页的路由参数（主栈与右栏共用）。 */
private fun accountAlbumsArguments(): List<NamedNavArgument> = listOf(
    navArgument("folderId") { type = NavType.LongType },
    navArgument("name") { type = NavType.StringType; defaultValue = "" },
)

/**
 * 导出成品的「打开至 / 发送至」。
 *
 * 不假设用户装了哪个阅读器：
 * - 打开至 → `ACTION_VIEW`：系统自己弹"用什么打开"（有默认应用就直接开）；
 * - 发送至 → `ACTION_SEND` + 选择器：分享给微信/邮箱等任意 App。
 *
 * Uri 要用 MediaStore 查（成品在系统下载目录，不是应用的私有文件），所以是挂起操作；
 * 任务页与详情页都要用，所以 Uri 由调用方给（[resolveUri]），这里只负责发 Intent。
 */
private fun launchOutputIntent(
    context: Context,
    displayName: String,
    share: Boolean,
    showMessage: (String) -> Unit,
    scope: CoroutineScope,
    resolveUri: suspend () -> android.net.Uri?,
) {
    scope.launch {
        val uri = resolveUri()
        if (uri == null) {
            showMessage("找不到这个文件，可能已经被删除")
            return@launch
        }
        val mime = if (displayName.endsWith(".pdf", ignoreCase = true)) {
            "application/pdf"
        } else {
            "application/zip"
        }
        runCatching {
            if (share) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "发送至"))
            } else {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(view)
            }
        }.onFailure {
            showMessage("没有可以打开这个文件的应用")
        }
    }
}

/**
 * 「打开文件夹」：让系统文件管理器直接定位到成品目录（用户反馈第 2 点）。
 *
 * 用的是文档提供者的目录 URI（`com.android.externalstorage.documents` 的
 * `primary:Download/JMComicH`）+ `ACTION_VIEW`：**不需要任何存储权限**——
 * 读目录的是系统文件管理器自己，我们只是把位置告诉它。
 *
 * 系统里没有能处理目录 URI 的文件管理器（部分定制 ROM）时会抛
 * `ActivityNotFoundException`，那时退回一句"文件在哪"的提示，至少让用户找得到。
 */
private fun openExportFolder(
    context: Context,
    folderUri: android.net.Uri?,
    showMessage: (String) -> Unit,
) {
    val message = "没有可以打开文件夹的应用，成品在 下载/JMComicH"
    if (folderUri == null) {
        showMessage(message)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(folderUri, "vnd.android.document/directory")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { showMessage(message) }
}

/**
 * 安装下载好的新版本 APK（「关于」页的检查更新）。
 *
 * 两道前置检查：
 * 1. 成品 Uri 还在不在——用户可能在文件管理器里把 APK 删了（记录一般会一起消失，
 *    兜底提示重新下载）；
 * 2. **「安装未知应用」授权**（API 26+ 的必备步骤，manifest 已声明
 *    `REQUEST_INSTALL_PACKAGES`）：没有授权时直接发安装 Intent 会被系统静默拒绝，
 *    所以先跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 让用户在系统设置里给本应用开开关，
 *    开完回来再点一次「安装」。
 */
private fun launchApkInstall(
    context: Context,
    downloads: AppDownloadGateway,
    downloadId: Long,
    showMessage: (String) -> Unit,
) {
    val uri = downloads.downloadedFileUri(downloadId)
    if (uri == null) {
        showMessage("安装包不在了，请重新下载")
        return
    }
    if (!context.packageManager.canRequestPackageInstalls()) {
        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(settings) }
            .onFailure { showMessage("请先在系统设置中允许本应用安装未知应用") }
        return
    }
    val install = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, AppDownloadGateway.APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(install) }
        .onFailure {
            Timber.tag("update").w(it, "启动安装器失败")
            showMessage("无法启动安装")
        }
}

/** 浏览器打开一个链接（发行版没带 APK 资产时的兜底入口）。 */
private fun openUrl(context: Context, url: String, showMessage: (String) -> Unit) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { showMessage("没有可以打开链接的应用") }
}

/** 两个导航宿主：主栈（tab / 登录）与辅栈（右栏的页面）。 */
private enum class CarouselHost { Primary, Secondary }

/** 双栏布局的四种形态。 */
private enum class CarouselMode {
    /** 左栏 + 右栏（平板分栏）。 */
    Split,

    /** 只有左栏（手机 / 窄窗，当前页是 tab 本身）。 */
    OnlyLeft,

    /** 只有右栏（手机 / 窄窗 / 竖屏阅读器，当前页是一个子页）。 */
    OnlyRight,

    /** 只有主栈（登录 / 注册等整页表单，盖住整个轮播）。 */
    OnlyPrimary,
}

/**
 * 页面级导航动作。
 *
 * 两个 NavHost 各持有一份：`openChild` 会按**当前哪一栏是当前页**决定是
 * "替换右栏"还是"把当前页换到左边、新页放右边"，页面本身不用关心。
 */
private class PaneNavActions(
    val openChild: (String) -> Unit,
    val back: () -> Unit,
    val openLogin: (String) -> Unit,
    /** 从 tab（书库「继续阅读」）进阅读：先开详情、再开阅读器。 */
    val openReadingFromTab: (Long, Long) -> Unit,
)

/**
 * 双栏轮播的核心状态：两个 NavHost 的返回栈 + 谁在左边。
 *
 * 规则（用户 2026-10-27 要求）：
 * - 左栏点进下一页：右栏已有内容就替换，没有就推进去；
 * - **右栏（当前页）**点进下一页：先把当前页换到左边，新页放右边；
 * - 返回：退掉右栏当前页；右栏还剩内容就左右换位，否则保持（露出左栏）。
 *
 * 两个 NavHost 始终留在组合里，换栏只是布局换宽度/位置，页面实例不销毁，
 * 因此详情、搜索结果、收藏夹、阅读进度都不会因为换栏而重载。
 *
 * **手机 / 分不了栏的窗口（[splitPanes] 为 false）不用双栏**：二级页压在主栈上，
 * 与平板双栏之前的手机行为一致（见 [openChild]）。
 */
private class CarouselNavigator(
    private val primaryNav: NavHostController,
    private val secondaryNav: NavHostController,
    private val primaryOnLeftState: MutableState<Boolean>,
    private val activeTabState: MutableState<String>,
    /** 当前窗口是否够宽、真的能用"左列表 + 右页面"双栏。 */
    private val splitPanes: Boolean,
) {
    val primaryOnLeft: Boolean get() = primaryOnLeftState.value
    val activeTabRoute: String get() = activeTabState.value

    private fun navOf(host: CarouselHost): NavHostController =
        if (host == CarouselHost.Primary) primaryNav else secondaryNav

    private fun other(host: CarouselHost): CarouselHost =
        if (host == CarouselHost.Primary) CarouselHost.Secondary else CarouselHost.Primary

    private fun routeOf(host: CarouselHost): String? =
        navOf(host).currentBackStackEntry?.destination?.route

    private fun isLeft(host: CarouselHost): Boolean =
        (host == CarouselHost.Primary) == primaryOnLeft

    private fun leftHost(): CarouselHost =
        if (primaryOnLeft) CarouselHost.Primary else CarouselHost.Secondary

    private fun rightHost(): CarouselHost =
        if (primaryOnLeft) CarouselHost.Secondary else CarouselHost.Primary

    private fun isTab(route: String?): Boolean =
        route != null && Tab.entries.any { it.route == route }

    /** 辅栈里有没有页面（空态 [SPLIT_PANE_EMPTY] 不算）。 */
    private fun secondaryHasPage(): Boolean {
        val route = routeOf(CarouselHost.Secondary)
        return route != null && route != SPLIT_PANE_EMPTY
    }

    /**
     * 从 [from] 页面打开 [route]。
     *
     * - **手机 / 分不了栏的窗口**：只有一页可见，二级页照旧压在主栈上（[from] 不重要）；
     * - [from] 是左栏：右栏有内容就替换，没有就直接推进去；
     * - [from] 是右栏（当前页）：左右换位，再把新页推进另一个栈（此时它在右边）。
     */
    fun openChild(from: CarouselHost, route: String) {
        // 手机只有一页可见，别再"放进另一栏 + 把那一栏压成 0 宽"：
        // 0 宽的那一栏仍活在组合里（分类页的抽屉、返回键回调、手势都在），
        // 既会把抽屉的锚点算错（页面重新变宽时抽屉自己展开），
        // 也让跨栏切换没有入场/退场动画可放（两个宿主各自 animate 自己的内容）。
        // 例外：辅栈里还真的有页面——平板分栏后把窗口缩窄 / pad 竖起来就是这种状态，
        // 那时它才是屏幕上那一页，交给下面的双栏逻辑处理。
        if (!splitPanes && !secondaryHasPage()) {
            primaryOnLeftState.value = true
            navOf(CarouselHost.Primary).navigate(route)
            return
        }
        if (isLeft(from)) {
            val right = other(from)
            val rightRoute = routeOf(right)
            if (rightRoute != null && rightRoute != SPLIT_PANE_EMPTY) {
                navOf(right).popBackStack()
            }
            navOf(right).navigate(route)
        } else {
            primaryOnLeftState.value = !primaryOnLeftState.value
            navOf(other(from)).navigate(route)
        }
    }

    /** 书库「继续阅读」：先开详情、再开阅读器，得到"左详情 + 右阅读器"。 */
    fun openReadingFromTab(albumId: Long, chapterId: Long) {
        openChild(CarouselHost.Primary, Routes.detail(albumId))
        openChild(CarouselHost.Secondary, Routes.reader(albumId, chapterId))
    }

    /** 退掉右栏当前页；右栏还剩内容就把左右换位。 */
    fun back(): Boolean {
        val right = rightHost()
        val route = routeOf(right)
        if (route == null || route == SPLIT_PANE_EMPTY) return false
        navOf(right).popBackStack()
        val remaining = routeOf(right)
        if (remaining != null && remaining != SPLIT_PANE_EMPTY) {
            primaryOnLeftState.value = !primaryOnLeftState.value
        }
        return true
    }

    /** 登录页永远压在主栈上（布局临时只显示主栈）。 */
    fun openLogin(next: String) {
        primaryNav.navigate(Routes.login(next))
    }

    /** 登录 / 注册成功：摘掉表单页，再按落点把目标页放进右栏。 */
    fun finishAuthTo(next: String) {
        var guard = 0
        while (isAuthRoute(primaryNav.currentBackStackEntry?.destination?.route) && guard++ < 4) {
            primaryNav.popBackStack()
        }
        if (next.isBlank()) return
        val right = rightHost()
        if (routeOf(right) == routePatternOf(next)) return
        openChild(leftHost(), next)
    }

    /** 切 tab：主栈回到该 tab，辅栈清空，左栏变回主栈。 */
    fun switchTab(tab: Tab) {
        activeTabState.value = tab.route
        primaryOnLeftState.value = true
        // 右栏的页面不属于任何 tab：切走时直接退掉（不保存），免得切回来还残留上一本
        secondaryNav.popBackStack(SPLIT_PANE_EMPTY, inclusive = false)
        // 主栈里压在 tab 上的子页面（详情 / 阅读器 / 二级页）同样先退掉。
        // 关键：**不能带着它们去 saveState**——否则切回这个 tab 时 restoreState
        // 会把阅读器恢复出来，变成"左阅读、右空态"（用户反馈的 bug）。
        while (!isTab(primaryNav.currentBackStackEntry?.destination?.route)) {
            if (!primaryNav.popBackStack()) break
        }
        primaryNav.navigate(tab.route) {
            popUpTo(Routes.LIBRARY) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * 是否需要接管系统返回键。
     *
     * - 登录页：接管（退回上一页 / 上一个验证码步骤由页面内部先处理）；
     * - 右栏有内容：接管（退右栏当前页）；
     * - 右栏空、左栏是非 tab 的整页（极少见）：接管（退回 tab）；
     * - 右栏空、左栏是 tab：不接管，交给系统退出 App。
     */
    fun canBack(): Boolean {
        if (isAuthRoute(routeOf(CarouselHost.Primary))) return true
        val rightRoute = routeOf(rightHost())
        if (rightRoute != null && rightRoute != SPLIT_PANE_EMPTY) return true
        val leftRoute = routeOf(leftHost())
        return leftRoute != null && leftRoute != SPLIT_PANE_EMPTY && !isTab(leftRoute)
    }

    fun backAction() {
        if (isAuthRoute(routeOf(CarouselHost.Primary))) {
            primaryNav.popBackStack()
            return
        }
        if (back()) return
        navOf(leftHost()).popBackStack()
    }
}

/**
 * 把目标路由还原成它的 destination pattern，用来和当前右栏页比较。
 *
 * 只用于登录成功后的落点（账号页 / 详情页），不需要覆盖所有路由。
 */
private fun routePatternOf(route: String): String {
    val path = route.substringBefore('?')
    return when {
        path.startsWith("jm/album/") -> Routes.DETAIL
        path.startsWith("jm/account/favorites/") -> Routes.ACCOUNT_FAVORITE_ALBUMS
        else -> path
    }
}

/**
 * 两个导航宿主的布局：始终都参与组合，只改宽度与左右位置。
 *
 * 这样"当前页换到左边"只是布局变化，NavHost 和页面 ViewModel 都不会重建。
 */
@Composable
private fun CarouselPanes(
    mode: CarouselMode,
    primaryOnLeft: Boolean,
    leftWidth: Dp,
    dividerColor: Color,
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val leftPx = with(density) { leftWidth.roundToPx() }
    val dividerPx = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)
    Layout(
        content = {
            primary()
            secondary()
        },
        modifier = modifier.drawBehind {
            if (mode == CarouselMode.Split) {
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(leftPx.toFloat(), 0f),
                    size = Size(dividerPx.toFloat(), size.height),
                )
            }
        },
    ) { measurables, constraints ->
        val totalWidth = constraints.maxWidth
        val totalHeight = constraints.maxHeight
        var primaryWidth = 0
        var secondaryWidth = 0
        var primaryX = 0
        var secondaryX = 0
        when (mode) {
            CarouselMode.OnlyPrimary -> {
                primaryWidth = totalWidth
                primaryX = 0
                secondaryWidth = 0
                secondaryX = totalWidth
            }

            CarouselMode.OnlyLeft -> {
                if (primaryOnLeft) {
                    primaryWidth = totalWidth
                    secondaryWidth = 0
                    secondaryX = totalWidth
                } else {
                    secondaryWidth = totalWidth
                    primaryWidth = 0
                    primaryX = totalWidth
                }
            }

            CarouselMode.OnlyRight -> {
                if (primaryOnLeft) {
                    secondaryWidth = totalWidth
                    secondaryX = 0
                    primaryWidth = 0
                    primaryX = totalWidth
                } else {
                    primaryWidth = totalWidth
                    primaryX = 0
                    secondaryWidth = 0
                    secondaryX = totalWidth
                }
            }

            CarouselMode.Split -> {
                val rightWidth = (totalWidth - leftPx - dividerPx).coerceAtLeast(0)
                if (primaryOnLeft) {
                    primaryWidth = leftPx
                    primaryX = 0
                    secondaryWidth = rightWidth
                    secondaryX = leftPx + dividerPx
                } else {
                    secondaryWidth = leftPx
                    secondaryX = 0
                    primaryWidth = rightWidth
                    primaryX = leftPx + dividerPx
                }
            }
        }
        val primaryPlaceable = measurables[0].measure(
            Constraints.fixed(primaryWidth, totalHeight),
        )
        val secondaryPlaceable = measurables[1].measure(
            Constraints.fixed(secondaryWidth, totalHeight),
        )
        layout(totalWidth, totalHeight) {
            primaryPlaceable.placeRelative(primaryX, 0)
            secondaryPlaceable.placeRelative(secondaryX, 0)
        }
    }
}


/**
 * 手写 ViewModel 工厂（P0 决策 D-3：不用 Hilt）。
 *
 * `albumId` / `chapterId` / `searchEntry` 只有对应页面需要，所以做成可选参数：
 * 传 [OnlineSearchEntry] 的是搜索结果页（进页面就搜 / 回显快照），
 * 不传的是发现页（只发历史）。
 */
class ViewModelFactory(
    private val container: AppContainer,
    private val albumId: Long? = null,
    private val chapterId: Long? = null,
    private val searchEntry: OnlineSearchEntry? = null,
    /** 账号收藏夹内页用：null = 全部收藏。 */
    private val accountFolderId: String? = null,
    private val accountFolderName: String = "",
    /** 注册页用：true = 从「已有验证码」进来，直接停在验证码步骤。 */
    private val signUpVerifyOnly: Boolean = false,
    /** 详情页进来时停在哪一个 tab（「留言记录」点进来是评论）。 */
    private val initialTab: AlbumDetailTab = AlbumDetailTab.DEFAULT,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
            LibraryViewModel(
                repo = container.jmRepository,
                reading = container.jmReading,
                cleaner = container.jmAlbumCleaner,
                exports = container.jmExports,
            ) as T

        modelClass.isAssignableFrom(RecentViewModel::class.java) ->
            RecentViewModel(container.jmRepository, container.jmAlbumCleaner) as T

        modelClass.isAssignableFrom(OnlineSearchViewModel::class.java) ->
            OnlineSearchViewModel(
                repo = container.jmRepository,
                cleaner = container.jmAlbumCleaner,
                entry = searchEntry,
            ) as T

        // 分类页：分类树与列表的缓存在容器上（跨页面存活），ViewModel 只是读写者；
        // 接口语言的热流也传进去，语言一变这一页就按新语言重拉（分类名是服务端按 lang 给的）
        modelClass.isAssignableFrom(CategoryViewModel::class.java) ->
            CategoryViewModel(
                repo = container.jmRepository,
                cache = container.jmCategoryCache,
                language = container.apiLanguage,
            ) as T

        modelClass.isAssignableFrom(FavoritesViewModel::class.java) ->
            FavoritesViewModel(container.jmRepository) as T

        modelClass.isAssignableFrom(TaskViewModel::class.java) ->
            TaskViewModel(container.downloadTasks, container.jmDownloads, container.jmExports) as T

        modelClass.isAssignableFrom(MyViewModel::class.java) ->
            MyViewModel(
                account = container.accountRepository,
                repo = container.jmRepository,
            ) as T

        modelClass.isAssignableFrom(AccountAuthViewModel::class.java) ->
            AccountAuthViewModel(container.accountRepository) as T

        modelClass.isAssignableFrom(AccountSignUpViewModel::class.java) ->
            AccountSignUpViewModel(
                account = container.accountRepository,
                verifyOnly = signUpVerifyOnly,
            ) as T

        modelClass.isAssignableFrom(AccountForgotViewModel::class.java) ->
            AccountForgotViewModel(container.accountRepository) as T

        modelClass.isAssignableFrom(AccountFavoritesViewModel::class.java) ->
            AccountFavoritesViewModel(
                account = container.accountRepository,
                cache = container.accountCache,
            ) as T

        modelClass.isAssignableFrom(AccountAlbumsViewModel::class.java) ->
            AccountAlbumsViewModel(
                account = container.accountRepository,
                cache = container.accountCache,
                folderId = accountFolderId,
                title = accountFolderName,
            ) as T

        modelClass.isAssignableFrom(AccountProfileViewModel::class.java) ->
            AccountProfileViewModel(
                account = container.accountRepository,
                cache = container.accountCache,
            ) as T

        modelClass.isAssignableFrom(AccountProfileEditViewModel::class.java) ->
            AccountProfileEditViewModel(container.accountRepository) as T

        // 留言记录：读/删都走账号接口；评论缓存传进去是为了删完把对应本子的详情页缓存清掉
        modelClass.isAssignableFrom(AccountCommentsViewModel::class.java) ->
            AccountCommentsViewModel(
                account = container.accountRepository,
                commentCache = container.jmCommentCache,
            ) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(
                container.settings,
                container.secrets,
                container.apiLineProbe,
                container.imageLineProbe,
                // 2026-10-20 用户要求：**切换接口语言不清理任何缓存**（分类树 / 列表 / 评论都保留），
                // 所以这段接线先注释掉——不传就走 SettingsViewModel 里的默认空实现（什么都不做）。
                // 以后需要清理时放开下面几行即可（接口本身没动）。
                // onApiLanguageChanged = {
                //     container.jmCategoryCache.clearCatalogAndLists()
                //     container.jmCommentCache.clearAll()
                // },
            ) as T

        // 关于页：存储统计扫描器 + 更新检查器 + 系统下载收口
        modelClass.isAssignableFrom(AboutViewModel::class.java) ->
            AboutViewModel(
                repo = container.jmRepository,
                exports = container.jmExports,
                collector = container.storageStatsCollector,
                releaseChecker = container.releaseChecker,
                downloads = container.appDownloadGateway,
            ) as T

        modelClass.isAssignableFrom(ReaderViewModel::class.java) ->
            ReaderViewModel(
                repo = container.jmRepository,
                reading = container.jmReading,
                store = container.jmImageStore,
                settings = container.settings,
                downloads = container.jmDownloads,
                albumId = albumId ?: error("阅读器必须传 albumId"),
                startChapterId = chapterId,
            ) as T

        modelClass.isAssignableFrom(AlbumDetailViewModel::class.java) ->
            AlbumDetailViewModel(
                repo = container.jmRepository,
                downloads = container.jmDownloads,
                exports = container.jmExports,
                tasks = container.downloadTasks,
                secrets = container.secrets,
                cleaner = container.jmAlbumCleaner,
                reading = container.jmReading,
                // 评论缓存挂在容器上：退出页面再进来还在，退出 App 才清
                commentCache = container.jmCommentCache,
                // 移出书库的删除挂在应用级作用域上：页面退出不该把它取消掉
                //（详情页不再自动返回，用户可能删到一半就自己退）
                appScope = container.appScope,
                account = container.accountRepository,
                albumId = albumId ?: error("详情页必须传 albumId"),
                initialTab = initialTab,
            ) as T

        else -> error("未知的 ViewModel：${modelClass.name}")
    }
}
