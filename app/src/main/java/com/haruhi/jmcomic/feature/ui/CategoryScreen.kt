package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.feature.data.CategoryGroup
import com.haruhi.jmcomic.feature.data.CategorySub
import com.haruhi.jmcomic.feature.data.OnlineViewMode
import com.haruhi.jmcomic.feature.data.updateLabel
import kotlinx.coroutines.launch

/**
 * 分类页（用户 2026-10-19 要求，底栏顺序：书库 / **分类** / 发现 / 收藏 / 更多）。
 *
 * 布局（用户口述的排版，从上往下）：
 * 1. 标题「分类」；
 * 2. **同一行**：左边 `☰ 同人 · 漢化`（当前分类，点开左侧双层抽屉）、右边排序菜单
 *    （与收藏页同一个交互：`排序：最新 ▾` + 下拉菜单）；
 * 3. 正文：漫画列表，**下拉刷新**只刷当前分类的漫画；**展示方式可切换**（列表 / 卡片，
 *    卡片每行三个，**默认卡片**），切换按钮与「共 N 条 · 第 x/y 页」那一行**同一行**；
 * 4. 列表底部**不自动翻页**（用户要求）：一页 80 条全部滚到底之后，点「加载更多」
 *    才请求下一页。
 *
 * 抽屉（`ModalNavigationDrawer`，从左侧滑出）：**双层**。
 * - 第一层主分类（`最新A漫 / 同人 / 單本 / …`），**白底**、选中项主色 + 加粗 + 左侧竖条，
 *   右侧显示该分类的漫画总数（`1.0k` / `1.0w`，见 `formatCompactCount`）；
 * - 第二层是该主分类的二级分类（`漢化 / 日語 / …`，第一项固定是"全部"）。
 * 抽屉本体**不带圆角**（用户要求：右上角、右下角都不要圆）。
 *
 * 点主分类 → 立刻切到"该主分类 + 全部"（用户要求：主类没二级类就用「全部」虚拟一个，
 * 且选中主类时默认选中「全部」）；只有「全部」一项的主分类选完直接收起抽屉，
 * 还有二级分类的停在第二层让用户继续挑。
 *
 * 状态全部来自 [CategoryViewModel]（分类树与列表都缓存，退出 App 清空）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    state: CategoryUiState,
    onSelectMain: (CategoryGroup) -> Unit,
    onSelectSub: (CategorySub) -> Unit,
    onSortChange: (CategorySort) -> Unit,
    onToggleViewMode: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onConsumeMessage: () -> Unit = {},
) {
    // 提示走根部 Scaffold 的 Snackbar（本页自己没有宿主，与发现页一致）
    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 用**本页实际可用宽度**判断，而不是整窗宽度：平板双栏时分类页只占左栏 420dp，
    // 那时再塞一个 320dp 的常驻抽屉，正文就只剩几十 dp 了。
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 平板上把分类树常驻在左侧（真正的平板：短边 >= 600dp 且本页宽度 >= 840dp）。
        // 手机仍是原来的 ModalNavigationDrawer，所有交互保持不变。
        val permanentDrawer = isWideWindow() && maxWidth >= PERMANENT_DRAWER_MIN_WIDTH

        // 抽屉不跟着页面走：切到别的页面（底栏切 tab / 进详情页 / App 退到后台）时要自己收起来（用户要求）。
        // 不这么做会有两个后果：切走时抽屉跟着旧页面一起滑出去；而 `rememberDrawerState` 内部是
        // `rememberSaveable`，会把"展开"存进这个 tab 的存档里，下次切回分类页抽屉又自己弹开。
        // 用 `snapTo` 而不是 `close()`：切页动画一开始就把状态落定，收起动作不依赖动画跑完
        // （旧页面的组合在退场动画结束时就销毁，这里的协程也会被取消）。
        // 常驻抽屉没有"收起"概念，不挂这个效果。
        if (!permanentDrawer) {
            OnPauseEffect {
                if (drawerState.isOpen) scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            }
        }

        if (permanentDrawer) {
            Row(modifier = Modifier.fillMaxSize()) {
                CategoryDrawer(
                    state = state,
                    drawerState = drawerState,
                    onSelectMain = onSelectMain,
                    onSelectSub = onSelectSub,
                    onRetry = onRetry,
                    onClose = {},
                    permanent = true,
                )
                // 常驻面板与正文都是白/灰拼色，靠这条 1dp 竖线把边界画出来
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                CategoryPageContent(
                    state = state,
                    permanentDrawer = true,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onOpenDrawer = {},
                    onSortChange = onSortChange,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    onToggleViewMode = onToggleViewMode,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    CategoryDrawer(
                        state = state,
                        drawerState = drawerState,
                        onSelectMain = { group ->
                            onSelectMain(group)
                            // 只有「全部」一项的主分类：没有第二层可挑，选完直接收起
                            if (!group.hasRealSubs) scope.launch { drawerState.close() }
                        },
                        onSelectSub = { sub ->
                            onSelectSub(sub)
                            scope.launch { drawerState.close() }
                        },
                        onRetry = onRetry,
                        onClose = { scope.launch { drawerState.close() } },
                    )
                },
            ) {
                CategoryPageContent(
                    state = state,
                    permanentDrawer = false,
                    modifier = Modifier,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSortChange = onSortChange,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    onToggleViewMode = onToggleViewMode,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
    }
}

/** 分类页正文（标题 / 操作行 / 灰色内容区），抽屉与常驻抽屉两种外壳共用。 */
@Composable
private fun CategoryPageContent(
    state: CategoryUiState,
    permanentDrawer: Boolean,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit,
    onSortChange: (CategorySort) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        // 标题下的那一行才是操作行（用户要求：排序菜单与分类按钮**同一行**）
        Text(
            text = "分类",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp),
            // 常驻抽屉已经显示了当前分类，选择器按钮隐藏，排序菜单靠右
            horizontalArrangement = if (permanentDrawer) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!permanentDrawer) {
                CategorySelectorButton(
                    label = state.selectorLabel,
                    onClick = onOpenDrawer,
                    modifier = Modifier.weight(1f),
                )
            }
            CategorySortMenu(
                selected = state.sort,
                options = state.sorts,
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = it },
                onSelect = onSortChange,
            )
        }

        // 正文区铺灰色底；标题 / 操作行留在根部 Scaffold 的白底上（全 App 统一）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            CategoryBody(
                state = state,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onToggleViewMode = onToggleViewMode,
                onOpenAlbum = onOpenAlbum,
            )
        }
    }
}

/**
 * `☰ 同人 · 漢化` 那颗"分类选择器"。
 *
 * 刻意做成**文字 + 图标的可点区域**而不是 `Button`：它是页面的"当前分类状态"，
 * 不该长得像个动作按钮；与收藏页那张「排序」文字按钮放在同一行时高矮也协调。
 */
@Composable
private fun CategorySelectorButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "选择分类",
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 右上角排序菜单：与收藏页（[FavoritesScreen]）完全同一套写法。
 *
 * 只摆出**当前主分类支持**的排序项（官方 `Header.tsx` 的规则：只有「最新A漫」给週排行），
 * 所以换主分类之后 `options` 会变短。
 */
@Composable
private fun CategorySortMenu(
    selected: CategorySort,
    options: List<CategorySort>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (CategorySort) -> Unit,
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Text("排序：${selected.label}")
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "选择排序方式",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (option == selected) "✓ ${option.label}" else option.label,
                            color = if (option == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        onExpandedChange(false)
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}

/** 正文：加载态 / 列表 / 卡片 / 错误态 / 空态，全部包在"下拉刷新"里。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryBody(
    state: CategoryUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    PullToRefreshBox(
        // 只有"已经有列表、正在重拉"时才转顶部下拉圈：首屏加载由居中的 LoadingBox 表达，
        // 两个圈同时转会像卡住了（搜索结果页踩过同款问题）
        isRefreshing = state.refreshing,
        onRefresh = {
            if (!state.loading && !state.refreshing && !state.loadingMore) onRefresh()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            // 首屏：分类树或第一页列表还在路上
            state.loading || (state.catalogLoading && state.albums.isEmpty()) -> LoadingBox()

            state.albums.isNotEmpty() -> when (state.viewMode) {
                OnlineViewMode.LIST -> CategoryAlbumList(
                    state = state,
                    onLoadMore = onLoadMore,
                    onToggleViewMode = onToggleViewMode,
                    onOpenAlbum = onOpenAlbum,
                )

                OnlineViewMode.CARD -> CategoryAlbumGrid(
                    state = state,
                    onLoadMore = onLoadMore,
                    onToggleViewMode = onToggleViewMode,
                    onOpenAlbum = onOpenAlbum,
                )
            }

            // 分类树都拉不到：列表无从谈起，给"重试分类树"
            state.catalogError != null -> CategoryMessageState(
                title = "分类加载失败",
                hint = state.catalogError,
                onRetry = onRetry,
            )

            // 分类树有了、列表失败：重拉当前分类第一页
            state.error != null -> CategoryMessageState(
                title = "没能加载这个分类",
                hint = state.error,
                onRetry = onRetry,
            )

            else -> ScrollableFullScreenBox {
                EmptyBox(title = "这个分类下还没有漫画", hint = "下拉刷新试试")
            }
        }
    }
}

/**
 * 「共 N 条 · 第 x/y 页」+ 右端的**展示方式切换按钮**。
 *
 * 这一行本身（`OnlineCountBar`）与下面的卡片（`OnlineGridCard`）都在 `Screens.kt` 里：
 * 搜索结果的列表 / 卡片页要用同一套（用户 2026-10-20 要求），两页共用一个实现，
 * 观感与切换行为才不会各改各的跑偏。
 */
@Composable
private fun CategoryCountBar(
    state: CategoryUiState,
    onToggleViewMode: () -> Unit,
) {
    OnlineCountBar(
        text = buildString {
            append("共 ${state.total} 条")
            if (state.page > 0) append(" · 第 ${state.page}/${state.totalPages} 页")
        },
        viewMode = state.viewMode,
        onToggleViewMode = onToggleViewMode,
    )
}

/**
 * 列表模式：一行一本。
 *
 * 每一行都带**更新时间**（[updateLabel]：优先 `update_at`，没有就用 `adddate`）。
 */
@Composable
private fun CategoryAlbumList(
    state: CategoryUiState,
    onLoadMore: () -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    // 换分类 / 换排序之后列表内容整批换掉，滚动位置要回到顶部；
    // 但**切到别的 tab 再切回来不算换分类**，那种情况要留在原地（用户要求"还是上次的样子"）。
    SelectionChangeEffect(state.selection.cacheKey) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            CategoryCountBar(state = state, onToggleViewMode = onToggleViewMode)
        }

        items(state.albums, key = { it.id }) { item ->
            // 分类页只做"浏览"：卡片右下角不摆"加入书库"（那是搜索结果页的动作），
            // 点进详情页再加/移出书库，交互上更不容易误触。
            OnlineResultRow(
                item = item,
                onOpen = { onOpenAlbum(item.id) },
                updateLabel = item.updateLabel(),
            )
        }

        item {
            CategoryFooter(state = state, onLoadMore = onLoadMore)
        }
    }
}

/**
 * 卡片模式：**每行三个**（用户要求）。
 *
 * 卡片由 `Screens.kt` 的 `OnlineGridCard` 画（封面 + 两行标题 + 更新时间），
 * 与搜索结果页的卡片是**同一个实现**，两处观感一致。
 */
@Composable
private fun CategoryAlbumGrid(
    state: CategoryUiState,
    onLoadMore: () -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val gridState = rememberLazyGridState()
    SelectionChangeEffect(state.selection.cacheKey) { gridState.scrollToItem(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(adaptiveGridColumns(maxWidth, minColumns = ONLINE_GRID_COLUMNS)),
            state = gridState,
            // 与书库 / 最近浏览 / 搜索结果页共用同一组间距（用户要求卡片之间更紧、卡片更大）
            contentPadding = PaddingValues(GRID_CONTENT_PADDING),
            horizontalArrangement = Arrangement.spacedBy(GRID_CARD_SPACING),
            verticalArrangement = Arrangement.spacedBy(GRID_CARD_SPACING),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CategoryCountBar(state = state, onToggleViewMode = onToggleViewMode)
            }

            items(state.albums, key = { it.id }) { item ->
                OnlineGridCard(item = item, onOpen = { onOpenAlbum(item.id) })
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                CategoryFooter(state = state, onLoadMore = onLoadMore)
            }
        }
    }
}

/**
 * 列表/卡片的页脚：加载中 / 加载更多 / 已经到底。
 *
 * **不自动翻页**（用户要求"滑到底部不用自动加载下一页"）：只有这一颗按钮能拉下一页。
 */
@Composable
private fun CategoryFooter(
    state: CategoryUiState,
    onLoadMore: () -> Unit,
) {
    when {
        state.loadingMore -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }

        state.hasMore -> TextButton(
            onClick = onLoadMore,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("加载更多（第 ${state.page + 1} 页）")
        }

        state.page > 0 -> Text(
            text = "已经到底了",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

/**
 * 选中项变化时把列表滚回顶部。
 *
 * 只在**组合持续存在**时比较前后两次 key：切 tab 回来时 `rememberSaveable` 会被还原成
 * 同一个 key（不滚），换分类 / 换排序才滚回顶部。
 */
@Composable
private fun SelectionChangeEffect(selectionKey: String, onChanged: suspend () -> Unit) {
    var lastSelection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectionKey) {
        if (lastSelection != null && lastSelection != selectionKey) onChanged()
        lastSelection = selectionKey
    }
}

/** 居中的"标题 + 说明 + 重试"，外层包一层可滚动容器好让下拉刷新手势有落点。 */
@Composable
private fun CategoryMessageState(
    title: String,
    hint: String?,
    onRetry: () -> Unit,
) {
    ScrollableFullScreenBox {
        // 必须 `fillMaxWidth()`：`ScrollableFullScreenBox` 里那层 Box 只约束了高度，
        // 宽度会收缩到内容宽度并靠左摆——这里让整列铺满宽度，才能真的左右居中。
        // （`EmptyBox` 没这个问题，因为它自己带了 `fillMaxSize()`。）
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 抽屉第一层（主分类）的宽度：放得下「English Manga」两行 + 右侧总数。 */
private val MAIN_PANE_WIDTH: Dp = 140.dp

/**
 * 左侧双层抽屉。
 *
 * 手机（默认）：用 Material3 的 [ModalNavigationDrawer] + [ModalDrawerSheet]，从边缘滑进，
 * 返回键关闭、点遮罩关闭，宽度按窗口的 88% 算、上限 360dp，与原来完全一致。
 *
 * 平板常驻（permanent=true）：不套 ModalDrawerSheet，纯一个占 [PERMANENT_DRAWER_WIDTH] 的面板，
 * 永远展开，没有关闭/收起动画，图标栏隐藏「选择分类」标题行与关闭按钮。
 * 分类树直接钉在左侧，不用每次点「☰ 同人 · 漢化」再弹一遍。
 */
@Composable
private fun CategoryDrawer(
    state: CategoryUiState,
    drawerState: DrawerState,
    onSelectMain: (CategoryGroup) -> Unit,
    onSelectSub: (CategorySub) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    permanent: Boolean = false,
) {
    if (permanent) {
        // 平板常驻模式：纯面板，不套 ModalDrawerSheet，宽度固定为 PERMANENT_DRAWER_WIDTH
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(PERMANENT_DRAWER_WIDTH)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            CategoryDrawerContent(
                state = state,
                showTitleBar = false,
                onSelectMain = onSelectMain,
                onSelectSub = onSelectSub,
                onRetry = onRetry,
                onClose = onClose,
            )
        }
    } else {
        // 手机 Modal 模式：原逻辑完全不动
        val density = LocalDensity.current
        val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
        val drawerWidth = (windowWidth * 0.88f).coerceAtMost(MAX_DRAWER_WIDTH)

        ModalDrawerSheet(
            drawerState = drawerState,
            modifier = Modifier.width(drawerWidth),
            drawerShape = RectangleShape,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            CategoryDrawerContent(
                state = state,
                showTitleBar = true,
                onSelectMain = onSelectMain,
                onSelectSub = onSelectSub,
                onRetry = onRetry,
                onClose = onClose,
            )
        }
    }
}

/** 抽屉内部内容（手机 Modal 与平板常驻共用）。 */
@Composable
private fun CategoryDrawerContent(
    state: CategoryUiState,
    showTitleBar: Boolean,
    onSelectMain: (CategoryGroup) -> Unit,
    onSelectSub: (CategorySub) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (showTitleBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择分类", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭分类抽屉")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        when {
            state.groups.isEmpty() && state.catalogError != null -> CategoryDrawerError(
                message = state.catalogError,
                onRetry = onRetry,
            )

            state.groups.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> Row(modifier = Modifier.fillMaxSize()) {
                CategoryMainPane(
                    groups = state.groups,
                    selectedSlug = state.mainSlug,
                    onSelect = onSelectMain,
                )
                // 两级之间的分隔线：两栏都是白底，靠这条 1dp 的线分界
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                CategorySubPane(
                    subs = state.main?.subs.orEmpty(),
                    selectedSlug = state.subSlug,
                    mainSlug = state.mainSlug,
                    onSelect = onSelectSub,
                )
            }
        }
    }
}

/**
 * 第一层：主分类。
 *
 * 用户要求：**整栏白底**、分类名右侧显示总数（`1.0k` / `1.0w`）。
 * 选中项靠主色 + 加粗 + 左侧竖条区分——底已经是白的，不再用底色区分。
 */
@Composable
private fun CategoryMainPane(
    groups: List<CategoryGroup>,
    selectedSlug: String,
    onSelect: (CategoryGroup) -> Unit,
) {
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .width(MAIN_PANE_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(groups, key = { it.slug }) { group ->
                val selected = group.slug == selectedSlug
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(group) },
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(3.dp)
                                .height(18.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp),
                                ),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        group.countLabel?.let { count ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = count,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(state = listState, modifier = Modifier.matchParentSize())
    }
}

/** 第二层：当前主分类的二级分类（第一项固定是「全部」）。 */
@Composable
private fun RowScope.CategorySubPane(
    subs: List<CategorySub>,
    selectedSlug: String,
    mainSlug: String,
    onSelect: (CategorySub) -> Unit,
) {
    val listState = rememberLazyListState()
    // 换主分类之后二级列表整批换掉，滚动位置要回到顶部（否则会停在上一批的中段）
    LaunchedEffect(mainSlug) { listState.scrollToItem(0) }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(subs, key = { it.slug }) { sub ->
                val selected = sub.slug == selectedSlug
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(sub) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sub.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        VerticalScrollbar(state = listState, modifier = Modifier.matchParentSize())
    }
}

/** 抽屉里分类树加载失败时的兜底：一句原因 + 重试（不用退出去再进一次）。 */
@Composable
private fun CategoryDrawerError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("分类加载失败", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 抽屉最宽多少：Material 的 `ModalDrawerSheet` 本身就封顶 360dp，这里对齐那个值。 */
private val MAX_DRAWER_WIDTH: Dp = 360.dp
