package com.haruhi.jmcomic.feature.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 平板（窗口短边 >= 600dp）用侧栏替代底栏；手机横屏的短边仍只有 300~400dp，
    // 因此不会被误判成平板，底栏保持原样。
    val wideShell = isWideWindow()
    // 双栏：左侧 + 右侧。除了平板判定，还要求整窗宽度够放下
    // "420dp 左栏 + 右栏"，所以再卡一道 840dp；手机永远不满足，UI 保持原样。
    val splitCapable = wideShell && windowWidthDp() >= SPLIT_MIN_WIDTH
    // 阅读器的左右分栏只在**横屏**启用：pad 竖起来时（用户要求）阅读页占满屏幕。
    val landscape = windowWidthDp() > windowHeightDp()
    // 右栏是**独立**的导航栈：详情 / 搜索结果 / 「我的」二级页都在右栏里进出，
    // 系统返回键先退右栏，不会把左栏的页面一起退掉。
    val paneNavController = rememberNavController()
    val paneEntry by paneNavController.currentBackStackEntryAsState()
    val paneRoute = paneEntry?.destination?.route
    // 底栏里的四个 tab（左栏的根页面）
    val tabRoutes = remember { Tab.entries.map { it.route }.toSet() }
    val leftRouteIsTab = currentRoute in tabRoutes
    // 登录 / 注册 / 找回密码：这三个页面永远整页显示，登录成功后按落点回到左栏或右栏。
    var authReturnRoute by rememberSaveable { mutableStateOf<String?>(null) }
    // 阅读时左栏临时变成"这一本的详情"，右栏放阅读器（用户 2026-10-27 要求）
    val readingSplit = splitCapable && landscape &&
        currentRoute == Routes.DETAIL && paneRoute == Routes.READER
    // 阅读中把 pad 竖起来（或窗口被缩到不再横屏分栏）：阅读器**整屏覆盖**（用户要求）。
    // 不把它挪回主栈：挪回去会换一个 ViewModel，当前章节 / 阅读位置都会丢；
    // 这里只是把右栏的宿主铺满整块内容区，旋转回横屏又会自然回到左右分栏。
    val readerFullscreen = paneRoute == Routes.READER && !(splitCapable && landscape)
    // 右栏有内容、但窗口已经窄到分不了栏（分屏 / 小窗）时：让右栏里的页面整屏接管，
    // 与手机上"二级页整页显示"的观感一致；左栏保持 0 宽但**不退出组合**，返回栈不丢。
    val paneHasContent = paneRoute != null && paneRoute != SPLIT_PANE_EMPTY
    // 只有左栏是 tab 时才允许右栏整屏接管：根栈在登录页 / 详情页时不能把右栏盖上去。
    val paneExpanded = readerFullscreen ||
        (!splitCapable && paneHasContent && leftRouteIsTab)
    // 只有两栏都有意义时才真的分栏：
    // - 左栏是 tab（书库 / 分类 / 发现 / 我的），右栏可以是详情、结果或二级页；
    // - 或者左栏是阅读中的详情、右栏是阅读器。
    // 其余情况（登录页、设置里的二级页在手机上等）仍然整页显示，手机 UI 完全不变。
    val splitActive = splitCapable && (leftRouteIsTab || readingSplit)
    // 右栏当前是否露出来（分栏，或整屏接管）。
    val paneVisible = splitActive || paneExpanded
    // 底栏/侧栏：分栏时侧栏一直保留（即使左栏临时变成了详情），手机行为不变。
    // 阅读器 / 右栏页面整屏接管时不显示底栏/侧栏，保证它真正占满屏幕。
    // 详情 / 阅读器 / 二级页在非分栏时仍然隐藏底部导航（计划 §9 第 2 条）。
    val showBottomBar = !paneExpanded && (leftRouteIsTab || splitActive)
    // 底栏/侧栏共用同一套切换逻辑：各 tab 保留独立滚动位置与筛选（§9 第 2 条）
    val switchTab: (Tab) -> Unit = { tab ->
        if (currentRoute != tab.route) {
            // 详情/二级页不属于任何 tab：切 tab 时把右栏退回空态，免得切回来还残留上一本
            paneNavController.popBackStack(SPLIT_PANE_EMPTY, inclusive = false)
            navController.navigate(tab.route) {
                popUpTo(Routes.LIBRARY) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // 打开一个二级页：分栏时进右栏，其余情况（手机）与以前一样整页入栈。
    // 右栏里只保留**当前这一个**二级页（点另一项是替换，不是往栈上叠）：
    // 否则「收藏 → 最近浏览」按返回会先回收藏，与「我的」的直觉不符。
    val openChildRoute: (String) -> Unit = { route ->
        if (splitCapable) {
            if (paneRoute != route) {
                paneNavController.navigate(route) {
                    popUpTo(SPLIT_PANE_EMPTY)
                }
            }
        } else {
            navController.navigate(route)
        }
    }
    // 左栏列表点一本：分栏时进右栏（右栏只留这一本），手机整页打开详情。
    val openAlbumFromLeft: (Long) -> Unit = { albumId ->
        if (splitCapable) {
            paneNavController.navigate(Routes.detail(albumId)) {
                popUpTo(SPLIT_PANE_EMPTY)
            }
        } else {
            navController.navigate(Routes.detail(albumId))
        }
    }
    // 搜索结果页：分栏时进右栏（发现页留在左栏），每次搜索都换一个新的结果页
    // （不能带 `launchSingleTop`：同一个 destination 命中后会复用旧 entry 与旧 ViewModel，
    // 新关键字被忽略，用户会看到上一次的结果）；手机保持原来的整页入栈。
    val openSearchResults: (String) -> Unit = { route ->
        if (splitCapable) {
            paneNavController.navigate(route) {
                popUpTo(SPLIT_PANE_EMPTY)
            }
        } else {
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    // 点「阅读」/ 点章节：
    // - 横屏分栏：左栏变成这一本的详情、右栏变成阅读器（用户 2026-10-27 要求）；
    // - 竖屏 / 手机：整页进入阅读器（与以前完全一致）。
    val openReader: (Long, Long) -> Unit = { albumId, chapterId ->
        if (splitCapable && landscape) {
            if (currentRoute != Routes.DETAIL) {
                navController.navigate(Routes.detail(albumId))
            }
            paneNavController.navigate(Routes.reader(albumId, chapterId)) {
                popUpTo(SPLIT_PANE_EMPTY)
            }
        } else {
            navController.navigate(Routes.reader(albumId, chapterId))
        }
    }
    // 登录/注册页始终整页（表单不适合塞进右栏）。落点由 finishAuthTo 决定。
    val openLogin: (String) -> Unit = { next ->
        if (!isAuthRoute(currentRoute)) authReturnRoute = currentRoute
        navController.navigate(Routes.login(next))
    }
    // 登录 / 注册成功后：把登录页（注册时还压着一层登录页）一起摘掉，
    // 再按落点把页面放回右栏或整页。手机上的观感与改动前一致。
    val finishAuthTo: (String) -> Unit = { next ->
        var guard = 0
        while (isAuthRoute(navController.currentBackStackEntry?.destination?.route) && guard++ < 4) {
            navController.popBackStack()
        }
        val under = authReturnRoute
        authReturnRoute = null
        if (next.isNotBlank()) {
            when {
                // 从详情页点的收藏：登录完直接回详情（详情 VM 自己订阅账号状态）
                under == Routes.DETAIL -> Unit
                // 平板：落点大多是右栏的二级页
                splitCapable -> paneNavController.navigate(next) { launchSingleTop = true }
                else -> navController.navigate(next) { launchSingleTop = true }
            }
        }
    }

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
                        enabled = currentRoute != Routes.READER && paneRoute != Routes.READER,
                        onPinchIn = { setPrivacyMode(true) },
                        onPinchOut = { setPrivacyMode(false) },
                    ),
                // Scaffold 默认容器色是 `colorScheme.background`（本主题下是 #F5F5F5 灰）。
                // 但状态栏那条（topBar 槽为空、由 contentWindowInsets 让出来）画的正是这个颜色，
                // 必须是纯白才能和底栏 NavigationBar（surfaceContainer = #FFFFFF）一致。
                // 各页真正的**滚动内容区**由各 Screen 自己铺 `background` 灰底。
                containerColor = MaterialTheme.colorScheme.surface,
                bottomBar = {
                    if (showBottomBar && !wideShell) {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
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
                    // 平板上底栏换成左侧导航栏；详情/阅读器/二级页与手机一样隐藏
                    if (showBottomBar && wideShell) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            // 系统栏内边距已经由外层 Scaffold 的 padding 让出，这里不再叠加
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            Tab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = currentRoute == tab.route,
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
                    // 手机 / 非分栏：NavHost 占满剩余宽度；
                    // 平板双栏：左栏固定 420dp，右栏另挂一个独立的详情栈；
                    // 阅读器 / 右栏页面整屏时左栏收成 0 宽——**不把它移出组合**，
                    // 否则 NavHost 重新挂载会把主栈/右栏的返回栈重建，状态全丢。
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(
                                when {
                                    paneExpanded -> Modifier.width(0.dp)
                                    splitActive -> Modifier.width(SPLIT_LIST_WIDTH)
                                    else -> Modifier.weight(1f)
                                }
                            )
                            // 必须裁到本栏范围内：分类页的 `ModalNavigationDrawer`
                            // 即使**关着**也把抽屉画在自己的左边界外（Compose 默认不裁剪），
                            // 平板分栏时左栏左边就是导航栏，抽屉会整块盖住导航栏，
                            // 只剩关闭 ✕ 和二级分类的 ✓ 两个图标露在栏里
                            // ——用户看到的"最左侧分类侧栏文字消失"。手机全屏时裁不裁一样。
                            .clipToBounds(),
                    ) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LIBRARY,
                        modifier = Modifier.fillMaxSize(),
                    // 页面切换动画。三种情形分开处理：
                    // 1. **底栏 tab 之间**：按目标 tab 在底栏里的位置决定方向——
                    //    往右边的 tab 切就从右侧滑入、往左边切就从左侧滑入，
                    //    与"底栏本身是一条从左到右的轴"一致，手指和视线都不用重新找方向；
                    // 2. **进入详情页/阅读器**（push）：新页面从右侧滑入，返回时反向；
                    // 3. 其余情况（例如从详情页返回底栏）走同一套反向动画。
                    enterTransition = {
                        val shift = tabShift(initialState.destination.route, targetState.destination.route)
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> if (shift < 0) -fullWidth else fullWidth },
                            animationSpec = tween(PAGE_TRANSITION_MS),
                        ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))
                    },
                    exitTransition = {
                        val shift = tabShift(initialState.destination.route, targetState.destination.route)
                        slideOutHorizontally(
                            // 旧页面往反方向退，两页像一条传送带
                            targetOffsetX = { fullWidth -> if (shift < 0) fullWidth / 4 else -fullWidth / 4 },
                            animationSpec = tween(PAGE_TRANSITION_MS),
                        ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_MS))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> -fullWidth / 4 },
                            animationSpec = tween(PAGE_TRANSITION_MS),
                        ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_MS))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(PAGE_TRANSITION_MS),
                        ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_MS))
                    },
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
                            onOpenAlbum = openAlbumFromLeft,
                            onToggleCollected = vm::setCollected,
                            // 「继续阅读」卡片（§9 第 4 条）：直接回到保存的位置。
                            // 平板横屏时同样走"左详情 + 右阅读器"（用户 2026-10-27 要求）。
                            onContinueReading = { albumId -> openReader(albumId, 0L) },
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
                                    openChildRoute(Routes.ACCOUNT_FAVORITES)
                                } else {
                                    openLogin(Routes.ACCOUNT_FAVORITES)
                                }
                            },
                            onOpenRecent = { openChildRoute(Routes.RECENT) },
                            onOpenComments = {
                                if (accountState.isSignedIn) {
                                    openChildRoute(Routes.ACCOUNT_COMMENTS)
                                } else {
                                    openLogin(Routes.ACCOUNT_COMMENTS)
                                }
                            },
                            onOpenTasks = { openChildRoute(Routes.TASKS) },
                            onOpenSettings = { openChildRoute(Routes.SETTINGS) },
                            onOpenAbout = { openChildRoute(Routes.ABOUT) },
                            onOpenProfile = {
                                if (accountState.isSignedIn) {
                                    openChildRoute(Routes.ACCOUNT_PROFILE)
                                } else {
                                    openLogin(Routes.ACCOUNT_PROFILE)
                                }
                            },
                            onLogin = { openLogin(Routes.ACCOUNT_FAVORITES) },
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
                                finishAuthTo(next)
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
                                finishAuthTo(next)
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
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { folderId, name ->
                                navController.navigate(Routes.accountFavoriteAlbums(folderId, name))
                            },
                            onLogin = { openLogin(Routes.ACCOUNT_FAVORITES) },
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
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = openAlbumFromLeft,
                        )
                    }

                    centeredComposable(Routes.ACCOUNT_PROFILE) { entry ->
                        AccountProfileDestination(
                            entry = entry,
                            factory = factory,
                            onBack = { navController.popBackStack() },
                            onEdit = {
                                // 连点两下不要压两个编辑页（这条路由没有参数，复用同一个 entry 是安全的）
                                navController.navigate(Routes.ACCOUNT_PROFILE_EDIT) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }

                    // 个人资料编辑：进页面先 GET 最新资料，加载完再渲染表单（避免官方 App 的回显 bug）
                    centeredComposable(Routes.ACCOUNT_PROFILE_EDIT) { entry ->
                        AccountProfileEditDestination(
                            entry = entry,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() },
                        )
                    }

                    // 留言记录：我发过的留言（10/页）。删一条就地消失，点一行跳那本的评论 tab。
                    centeredComposable(Routes.ACCOUNT_COMMENTS) { entry ->
                        AccountCommentsDestination(
                            entry = entry,
                            factory = factory,
                            showMessage = showMessage,
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = { albumId ->
                                navController.navigate(Routes.detail(albumId, AlbumDetailTab.COMMENTS))
                            },
                            onLogin = { openLogin(Routes.ACCOUNT_COMMENTS) },
                        )
                    }

                    centeredComposable(Routes.RECENT) { entry ->
                        RecentDestination(
                            entry = entry,
                            factory = factory,
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = openAlbumFromLeft,
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
                            onOpenAlbum = openAlbumFromLeft,
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
                            // 平板分栏时**右栏**打开结果页，左栏仍停在发现页（用户 2026-10-27 要求）。
                            onSearch = {
                                val keyword = state.keyword.trim()
                                if (keyword.isEmpty()) {
                                    showMessage("请输入搜索关键字")
                                } else {
                                    openSearchResults(Routes.searchResults(keyword, state.sort))
                                }
                            },
                            // 点历史记录：带 historyId 进结果页，那边**只回显快照、不重搜**（用户要求）
                            onOpenHistory = { history ->
                                openSearchResults(
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
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = openAlbumFromLeft,
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
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = openAlbumFromLeft,
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
                            onBack = { navController.popBackStack() },
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
                            onBack = { navController.popBackStack() },
                            onOpenAlbum = openAlbumFromLeft,
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

                    // 详情页：手机 / 非分栏时整页入栈（主栈）。
                    // 平板双栏时同一份实现也挂在右栏（见 SplitDetailPane），
                    // 阅读时主栈会把它推到左栏（右栏换成阅读器）。
                    centeredComposable(
                        route = Routes.DETAIL,
                        arguments = detailArguments(),
                    ) { entry ->
                        AlbumDetailDestination(
                            entry = entry,
                            container = container,
                            detailNavController = navController,
                            // 平板分栏时登录完直接回到详情（详情 VM 自己订阅账号状态）；
                            // 手机保持原来的"登录成功后重新打开详情页"。
                            loginNext = if (splitCapable) {
                                ""
                            } else {
                                Routes.detail(entry.arguments?.getLong("albumId") ?: 0L)
                            },
                            openReader = openReader,
                            openLogin = openLogin,
                            onSearchAuthor = { author ->
                                navController.navigate(Routes.searchResults(author)) {
                                    launchSingleTop = true
                                }
                            },
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
                            onBack = { navController.popBackStack() },
                        )
                    }
                    }
                    }

                    // 右栏：平板双栏 / 阅读器或右栏页面整屏时都挂在这里。
                    // **只有一个调用点**：旋转 / 登录页压栈时只改宽度、不把 NavHost 挪走，
                    // 否则重建 NavHost 会重置右栏（阅读器 / 详情）与主栈的返回栈。
                    // 不可见但有内容时仍以 0 宽留在组合里，登录回来内容还在。
                    if (paneVisible || paneHasContent) {
                        // 1dp 分隔线（与分类页常驻抽屉同一种画法）；整屏时不需要
                        if (paneVisible && !paneExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                        SplitDetailPane(
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
                            openReader = openReader,
                            openLogin = openLogin,
                            visible = paneVisible,
                            modifier = if (paneVisible) {
                                Modifier.weight(1f).fillMaxHeight()
                            } else {
                                Modifier.width(0.dp).fillMaxHeight()
                            },
                        )
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
 * 平板双栏的右栏：详情 / 搜索结果 / 「我的」二级页 / 阅读器。
 *
 * 它是一个**独立**的 NavHost：左栏页面（列表、发现、我的、阅读中的详情）不动，
 * 右栏自己进出；系统返回键也先退右栏（见方法末尾的 `BackHandler`）。
 * 主栈的页面切换动画与右栏无关，所以右栏直接用默认的淡入淡出。
 *
 * 交互（用户 2026-10-27 要求）：
 * - 左栏列表点一本 → 详情进右栏；
 * - 发现页搜索 / 点历史 → 搜索结果进右栏（左栏仍是发现页）；
 * - 「我的」点具体项 → 二级页进右栏（左栏仍是我的）；
 * - 详情页点「阅读」/ 点章节 → 左栏变成这一本的详情、右栏变成阅读器
 *   （竖屏 / 手机时仍整页进入阅读器，见 [ModeRoot] 的 openReader）。
 */
@Composable
private fun SplitDetailPane(
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
    openReader: (Long, Long) -> Unit,
    openLogin: (String) -> Unit,
    /** 右栏是否真的露在外面。为 false 时它只是以 0 宽留在组合里保温，不能再抢返回键。 */
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = paneNavController,
        startDestination = SPLIT_PANE_EMPTY,
        modifier = modifier,
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
                detailNavController = paneNavController,
                loginNext = "",
                openReader = openReader,
                openLogin = openLogin,
                onSearchAuthor = { author ->
                    paneNavController.navigate(Routes.searchResults(author)) {
                        launchSingleTop = true
                    }
                },
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
                // 关掉阅读器只退右栏：左栏的详情还在，用户可以接着挑章节
                onBack = { paneNavController.popBackStack() },
                // 右栏被登录页等整页藏起来时不接管窗口亮度 / 屏幕常亮
                windowEffectsEnabled = visible,
            )
        }
        // ---- 发现页 → 搜索结果（右栏）----
        composable(
            route = Routes.SEARCH_RESULTS,
            arguments = searchResultsArguments(),
        ) { entry ->
            SearchResultsDestination(
                entry = entry,
                container = container,
                factory = factory,
                showMessage = showMessage,
                onBack = { paneNavController.popBackStack() },
                onOpenAlbum = { albumId ->
                    paneNavController.navigate(Routes.detail(albumId))
                },
            )
        }
        // ---- 「我的」→ 各二级页（右栏）----
        centeredComposable(Routes.ACCOUNT_FAVORITES) { entry ->
            AccountFavoritesDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                onBack = { paneNavController.popBackStack() },
                onOpenFolder = { folderId, name ->
                    paneNavController.navigate(Routes.accountFavoriteAlbums(folderId, name))
                },
                onLogin = { openLogin(Routes.ACCOUNT_FAVORITES) },
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
                onBack = { paneNavController.popBackStack() },
                onOpenAlbum = { albumId ->
                    paneNavController.navigate(Routes.detail(albumId))
                },
            )
        }
        centeredComposable(Routes.ACCOUNT_PROFILE) { entry ->
            AccountProfileDestination(
                entry = entry,
                factory = factory,
                onBack = { paneNavController.popBackStack() },
                onEdit = {
                    paneNavController.navigate(Routes.ACCOUNT_PROFILE_EDIT) {
                        launchSingleTop = true
                    }
                },
            )
        }
        centeredComposable(Routes.ACCOUNT_PROFILE_EDIT) { entry ->
            AccountProfileEditDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                onBack = { paneNavController.popBackStack() },
                onSaved = { paneNavController.popBackStack() },
            )
        }
        centeredComposable(Routes.ACCOUNT_COMMENTS) { entry ->
            AccountCommentsDestination(
                entry = entry,
                factory = factory,
                showMessage = showMessage,
                onBack = { paneNavController.popBackStack() },
                onOpenAlbum = { albumId ->
                    paneNavController.navigate(Routes.detail(albumId, AlbumDetailTab.COMMENTS))
                },
                onLogin = { openLogin(Routes.ACCOUNT_COMMENTS) },
            )
        }
        centeredComposable(Routes.RECENT) { entry ->
            RecentDestination(
                entry = entry,
                factory = factory,
                onBack = { paneNavController.popBackStack() },
                onOpenAlbum = { albumId ->
                    paneNavController.navigate(Routes.detail(albumId))
                },
            )
        }
        centeredComposable(Routes.TASKS) {
            TasksDestination(
                taskViewModel = taskViewModel,
                taskState = taskState,
                context = context,
                scope = scope,
                showMessage = showMessage,
                onBack = { paneNavController.popBackStack() },
                onOpenAlbum = { albumId ->
                    paneNavController.navigate(Routes.detail(albumId))
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
                onBack = { paneNavController.popBackStack() },
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
                onBack = { paneNavController.popBackStack() },
                onOpenAlbum = { albumId ->
                    paneNavController.navigate(Routes.detail(albumId))
                },
            )
        }
    }
    // 放在 NavHost **之后**注册：右栏有内容时，系统返回先退右栏，而不是退掉左栏的页面。
    // 阅读器在右栏时同样生效：返回键先关阅读器，左栏的详情保留下来。
    val paneEntry by paneNavController.currentBackStackEntryAsState()
    BackHandler(
        enabled = visible &&
            paneEntry != null &&
            paneEntry?.destination?.route != SPLIT_PANE_EMPTY,
    ) {
        paneNavController.popBackStack()
    }
}

/**
 * 详情页的唯一实现：主栈（手机整页 / 平板上的全屏详情）与平板右栏共用。
 *
 * 导航分工：
 * - 返回 → [detailNavController] 退一层（右栏退到空态，手机退回列表）；
 * - 点推荐漫画（详情 → 详情）→ [detailNavController] 入栈，返回能回到上一本；
 * - 阅读器 → [openReader]：横屏由外层安排成"左详情 + 右阅读器"，手机整页入栈；
 * - 登录 → [openLogin]（整页），登录完回落到本页；
 * - 作者搜索 → [onSearchAuthor]（在详情所在的这一栏里打开结果页）。
 */
@Composable
private fun AlbumDetailDestination(
    entry: NavBackStackEntry,
    container: AppContainer,
    detailNavController: NavHostController,
    loginNext: String,
    openReader: (Long, Long) -> Unit,
    openLogin: (String) -> Unit,
    onSearchAuthor: (String) -> Unit,
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
        onBack = { detailNavController.popBackStack() },
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
                openLogin(loginNext)
            }
        },
        onDismissAccountFavoriteSheet = vm::dismissAccountFavoriteSheet,
        onChooseAccountFavorite = vm::chooseAccountFavorite,
        onCreateAccountFolderAndAdd = vm::createAccountFolderAndAdd,
        onRequireLogin = {
            openLogin(loginNext)
        },
        // 点章节进阅读器：**先把这个本子的进度改写成这一话**，
        // 阅读器再按"从这一话第一张开始"打开（用户第十一轮要求第 1 点）。
        // 平板横屏时由外层把左栏换成详情、右栏放阅读器（用户 2026-10-27 要求）。
        onReadChapter = { chapterId ->
            vm.onChapterOpened(chapterId)
            openReader(albumId, chapterId)
        },
        // 主按钮：有进度就按存下来的位置续读（不带章节号 = 续读），
        // 没进度才从第一话第一张开始（用户第十一轮要求第 1 点）
        onReadFromStart = {
            val position = state.progress
            if (position?.isUsable == true) {
                openReader(albumId, 0L)
            } else {
                val first = state.chapters.firstOrNull()
                if (first != null) {
                    vm.onChapterOpened(first.chapterId)
                    openReader(albumId, first.chapterId)
                }
            }
        },
        onDownloadChapters = vm::downloadChapters,
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
        // 在详情所在的这一栏里打开（右栏详情 → 右栏结果页；手机 → 整页）。
        onSearchAuthor = { author -> onSearchAuthor(author) },
        // 点评论里的「我推荐这本书 <jmid>」：打开那本漫画的详情页。
        //
        // ⚠ 不能带 `launchSingleTop`（用户 2026-10-22 反馈"点了跳不过去"的根因）：
        // DETAIL 只有一个 destination，singleTop 只比 destination id，命中后走的是
        // `NavBackStackEntry(oldEntry, newArgs)`——**复用同一个 entry id 与
        // ViewModelStore**。参数换成新本子号、`AlbumDetailViewModel` 还是原来那本的
        // 实例，页面看着纹丝不动。普通入栈就对了：新 entry → 新 ViewModel → 真换本子。
        //
        // 点的是**当前这本**（推荐评论里很常见，实测 aid=1246359 就在推荐 1246359）：
        // 按用户要求**保持无反应**，什么都不做 —— 已经站在它的详情页上了，
        // 再重开一次只会白丢草稿、滚动位置和 tab。
        onOpenAlbum = { targetId ->
            if (targetId != albumId) {
                detailNavController.navigate(Routes.detail(targetId))
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
        // 取图管线：原图缓存（默认，快）/ 解扰后落盘（旧，可随时切回）
        onSetReaderCacheMode = vm::setReaderCacheMode,
        // 隐私模式：与全局双指手势共用同一个存储值
        privacyMode = privacyMode,
        onSetPrivacyMode = onSetPrivacyMode,
        // 文字隐私：只在隐私模式开启时显示，单独保存
        textPrivacy = textPrivacy,
        onSetTextPrivacy = onSetTextPrivacy,
        // 下载
        onSetDownloadThreads = vm::setDownloadThreads,
        onSetDownloadNetwork = vm::setDownloadNetwork,
        // 语言：接口返回的中文用简中还是繁中（默认简中）
        onSetApiLanguage = vm::setApiLanguage,
        // 导出默认密码（原文写进加密凭据存储，页面上默认用圆点遮住）
        onSaveZipPassword = vm::saveZipPassword,
        onSavePdfPassword = vm::savePdfPassword,
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

/**
 * 手写 ViewModel 工厂（P0 决策 D-3：不用 Hilt）。
 *
 * `albumId` / `chapterId` / `searchEntry` 只有对应页面需要，所以做成可选参数：
 * 传 [OnlineSearchEntry] 的是搜索结果页（进页面就搜/回显快照），不传的是发现页（只发历史）。
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
