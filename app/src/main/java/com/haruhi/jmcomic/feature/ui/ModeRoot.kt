package com.haruhi.jmcomic.feature.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
// 别名：直接 import `List` 会把 kotlin.collections.List 这个名字在文件里遮住，
// 以后在这里写 `List<…>` 会莫名其妙解析到图标属性上。
import androidx.compose.material.icons.automirrored.filled.List as CategoryListIcon
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.haruhi.jmcomic.core.di.AppContainer
import com.haruhi.jmcomic.core.protocol.SearchSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
     * 「更多」tab（用户 2026-10-15 定的底栏项）。
     *
     * 底栏固定为 **书库 / 分类 / 发现 / 收藏 / 更多**；「最近浏览」「下载任务」「应用设置」
     * 都收在这一页里，所以底栏不因为它们变长。
     */
    const val MORE = "jm/more"

    /**
     * 应用设置页（「更多」→ 应用设置）。
     *
     * **设置入口只有这一处**：模式首页不再单独挂顶栏齿轮（底栏已经能到设置，
     * 再挂一个齿轮是重复入口）。阅读器控制栏里的那个小齿轮**保留**——
     * 它和这个页面读写的是同一份 `Jm.READING_*` 偏好，两处入口、一份存储。
     */
    const val SETTINGS = "jm/settings"
    const val FAVORITE_ALBUMS = "jm/favorites/{favoriteId}?name={name}"
    const val DETAIL = "jm/album/{albumId}"
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
    FAVORITES(Routes.FAVORITES, "收藏", Icons.Default.Favorite),
    MORE(Routes.MORE, "更多", Icons.Default.MoreVert),
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
    // 详情页与阅读器隐藏底部导航（计划 §9 第 2 条）
    val showBottomBar = currentRoute in Tab.entries.map { it.route }

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
    CompositionLocalProvider(
        LocalImageLoader provides container.imageLoader,
        LocalImageDomain provides imageDomain,
    ) {
        Scaffold(
            // 「点输入框以外的地方就失焦、收起软键盘」挂在**根节点**上，一处覆盖所有页面
            // （实现与两条踩坑约束见 `FocusOnTap.kt`）。挂根节点而不是喂给每个输入框，
            // 是为了让以后新增的页面/输入框自动具备这个行为，不用每处再挂一次。
            modifier = modifier.fillMaxSize().clearFocusOnTap(),
            // Scaffold 默认容器色是 `colorScheme.background`（本主题下是 #F5F5F5 灰）。
            // 但状态栏那条（topBar 槽为空、由 contentWindowInsets 让出来）画的正是这个颜色，
            // 必须是纯白才能和底栏 NavigationBar（surfaceContainer = #FFFFFF）一致。
            // 各页真正的**滚动内容区**由各 Screen 自己铺 `background` 灰底。
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    if (currentRoute != tab.route) {
                                        // 各 tab 保留独立滚动位置与筛选（§9 第 2 条）
                                        navController.navigate(tab.route) {
                                            popUpTo(Routes.LIBRARY) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    if (tab == Tab.MORE) {
                                        TaskBadgeIcon(
                                            badgeCount = taskState.badgeCount,
                                            hasFailure = taskState.failedCount > 0,
                                            icon = tab.icon,
                                        )
                                    } else {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.padding(padding),
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
                        onOpenAlbum = { navController.navigate(Routes.detail(it)) },
                        onToggleCollected = vm::setCollected,
                        // 「继续阅读」卡片（§9 第 4 条）：直接回到保存的位置
                        onContinueReading = { albumId -> navController.navigate(Routes.reader(albumId)) },
                        // 卡片右上角 ✕：清掉「继续阅读」指针，卡片消失
                        onDismissContinueReading = vm::dismissContinueReading,
                        // 长按多选（用户要求第 1 点）
                        onDeleteFiles = vm::deleteFiles,
                        onRemoveFromLibrary = vm::removeFromLibrary,
                        // 批量"修改收藏夹"：多选 + 就地新建，点确认才执行（用户要求第 3 点）
                        onModifyFavorites = vm::modifyFavorites,
                        onCreateFavorite = vm::createFavoriteForSelection,
                        // 往下翻页（用户第十轮要求第 4 点：筛选也要能翻到后面的页）
                        onLoadMore = vm::loadMore,
                    )
                }

                composable(Routes.MORE) { entry ->
                    val vm: MoreViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val recentCount by vm.recentCount.collectAsStateWithLifecycle()
                    // 本页自己没有 Snackbar 宿主，也不产生一次性提示，所以不需要 MessageEffect
                    MoreScreen(
                        recentCount = recentCount,
                        taskState = taskState,
                        onOpenRecent = { navController.navigate(Routes.RECENT) },
                        onOpenTasks = { navController.navigate(Routes.TASKS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                composable(Routes.RECENT) { entry ->
                    val vm: RecentViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    RecentScreen(
                        state = state,
                        // 二级页自己带返回按钮（底栏在这一层是隐藏的）
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { navController.navigate(Routes.detail(it)) },
                        onClearAll = vm::clearAll,
                        onRemoveAlbum = vm::remove,
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
                        onOpenAlbum = { navController.navigate(Routes.detail(it)) },
                        onMessage = showMessage,
                        onConsumeMessage = vm::consumeMessage,
                    )
                }

                composable(Routes.DISCOVER) { entry ->
                    val vm: OnlineSearchViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    OnlineSearchScreen(
                        state = state,
                        onKeywordChange = vm::onKeywordChange,
                        onSortChange = vm::onSortChange,
                        // 点「搜索」：**先跳结果页，由结果页自己发请求**（用户要求）。
                        // 关键字为空就不跳、原地提示——结果页没有输入框，空关键字跳过去没法改。
                        onSearch = {
                            val keyword = state.keyword.trim()
                            if (keyword.isEmpty()) {
                                showMessage("请输入搜索关键字")
                            } else {
                                navController.navigate(Routes.searchResults(keyword, state.sort)) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        // 点历史记录：带 historyId 进结果页，那边**只回显快照、不重搜**（用户要求）
                        onOpenHistory = { history ->
                            navController.navigate(
                                Routes.searchResults(
                                    keyword = history.keyword,
                                    sort = SearchSort.fromCode(history.sort),
                                    historyId = history.id,
                                ),
                            ) {
                                launchSingleTop = true
                            }
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
                    arguments = listOf(
                        navArgument("keyword") { type = NavType.StringType; defaultValue = "" },
                        navArgument("sort") { type = NavType.StringType; defaultValue = SearchSort.DEFAULT.code },
                        navArgument("historyId") { type = NavType.LongType; defaultValue = 0L },
                    ),
                ) { entry ->
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
                        // 结果页现在自带输入框（用户要求）：改完关键字点右侧按钮/回车是**原地重搜**，
                        // 不再往导航栈上再压一层结果页（`SearchResultsScreen` 内部也不再跳转）。
                        onKeywordChange = vm::onKeywordChange,
                        onSearch = vm::search,
                        onBack = { navController.popBackStack() },
                        onRefresh = vm::refresh,
                        // 换排序立刻重搜：只改芯片不重搜的话，选中的芯片会和列表内容对不上
                        onSortChange = vm::changeSort,
                        onLoadMore = vm::loadMore,
                        onRevealMore = vm::showMoreItems,
                        // 卡片 / 列表切换（默认卡片，与分类页同一套；纯界面状态，不重搜）
                        onToggleViewMode = vm::toggleViewMode,
                        onOpenAlbum = { navController.navigate(Routes.detail(it)) },
                        onAddToLibrary = vm::addToLibrary,
                        // 点卡片上的「已入库」= 移出书库（页面内已二次确认）
                        onRemoveFromLibrary = vm::removeFromLibrary,
                        // 本页自己没有 Snackbar 宿主，提示统一交给根部弹
                        onMessage = showMessage,
                        onConsumeMessage = vm::consumeMessage,
                    )
                }

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

                composable(Routes.TASKS) {
                    MessageEffect(
                        taskState.message,
                        onConsume = taskViewModel::consumeMessage,
                        onShow = showMessage,
                    )
                    TaskScreen(
                        state = taskState,
                        // 二级页自己带返回按钮（底栏在这一层是隐藏的）
                        onBack = { navController.popBackStack() },
                        onToggleExpanded = taskViewModel::toggleExpanded,
                        onPause = taskViewModel::pause,
                        onResume = taskViewModel::resume,
                        onCancel = taskViewModel::cancel,
                        onRetry = taskViewModel::retry,
                        onRemove = taskViewModel::remove,
                        onClearFinished = taskViewModel::clearFinished,
                        // 点封面进详情页（用户要求第 1 点）
                        onOpenAlbum = { albumId -> navController.navigate(Routes.detail(albumId)) },
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

                composable(Routes.SETTINGS) { entry ->
                    val vm: SettingsViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                    val state by vm.state.collectAsStateWithLifecycle()
                    MessageEffect(state.message, onConsume = vm::consumeMessage, onShow = showMessage)
                    SettingsScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        // 阅读与显示：阅读背景与亮度放在阅读器的设置面板里（跟着环境变，进阅读器再改）
                        onSetReadingMode = vm::setReadingMode,
                        onSetReadingDirection = vm::setReadingDirection,
                        onSetKeepScreenOn = vm::setKeepScreenOn,
                        // 取图管线：原图缓存（默认，快）/ 解扰后落盘（旧，可随时切回）
                        onSetReaderCacheMode = vm::setReaderCacheMode,
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
                        // 恢复默认：只重置偏好，不动书库与文件、不动凭据
                        onResetSettings = vm::resetSettings,
                    )
                }

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

                composable(
                    route = Routes.DETAIL,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
                ) { entry ->
                    val albumId = entry.arguments?.getLong("albumId") ?: return@composable
                    val vm: AlbumDetailViewModel = viewModel(
                        viewModelStoreOwner = entry,
                        factory = ViewModelFactory(container, albumId),
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
                        onBack = { navController.popBackStack() },
                        onRefresh = { vm.refresh() },
                        onToggleReversed = vm::toggleReversed,
                        onToggleDescription = vm::toggleDescription,
                        onAddToLibrary = vm::addToLibrary,
                        onMoveToFavorite = vm::moveToFavorite,
                        onAddToFavorite = vm::addToFavorite,
                        onRemoveFromFavorite = vm::removeFromFavorite,
                        onCreateFavoriteAndAdd = vm::createFavoriteAndAdd,
                        // 点章节进阅读器：**先把这个本子的进度改写成这一话**，
                        // 阅读器再按"从这一话第一张开始"打开（用户第十一轮要求第 1 点）
                        onReadChapter = { chapterId ->
                            vm.onChapterOpened(chapterId)
                            navController.navigate(Routes.reader(albumId, chapterId))
                        },
                        // 主按钮：有进度就按存下来的位置续读（不带章节号 = 续读），
                        // 没进度才从第一话第一张开始（用户第十一轮要求第 1 点）
                        onReadFromStart = {
                            val position = state.progress
                            if (position?.isUsable == true) {
                                navController.navigate(Routes.reader(albumId))
                            } else {
                                val first = state.chapters.firstOrNull()
                                if (first != null) {
                                    vm.onChapterOpened(first.chapterId)
                                    navController.navigate(Routes.reader(albumId, first.chapterId))
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
                        //
                        // 普通入栈、不做 popUpTo：详情页留在栈里，从结果页返回就回到详情页；
                        // `launchSingleTop` 只是防止连点两下叠出两层一样的结果页。
                        onSearchAuthor = { author ->
                            navController.navigate(Routes.searchResults(author)) {
                                launchSingleTop = true
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

                composable(
                    route = Routes.READER,
                    arguments = listOf(
                        navArgument("albumId") { type = NavType.LongType },
                        navArgument("chapterId") { type = NavType.LongType; defaultValue = 0L },
                    ),
                ) { entry ->
                    val albumId = entry.arguments?.getLong("albumId") ?: return@composable
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
                        // 退出前把去抖窗口里的位置立刻落库，重启后才能接着读
                        onBack = {
                            vm.flushPosition()
                            navController.popBackStack()
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
                    )
                }
            }
        }
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

        modelClass.isAssignableFrom(MoreViewModel::class.java) ->
            MoreViewModel(container.jmRepository) as T

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
                albumId = albumId ?: error("详情页必须传 albumId"),
            ) as T

        else -> error("未知的 ViewModel：${modelClass.name}")
    }
}
