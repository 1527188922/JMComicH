package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.List as CategoryListIcon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.haruhi.jmcomic.feature.data.CategoryViewMode
import com.haruhi.jmcomic.feature.data.OnlineSearchItem
import com.haruhi.jmcomic.feature.data.updateLabel
import com.haruhi.jmcomic.ui.theme.GridViewIcon
import kotlinx.coroutines.launch

/**
 * 分类页（用户 2026-10-19 要求，底栏顺序：书库 / **分类** / 发现 / 收藏 / 更多）。
 *
 * 布局（用户口述的排版，从上往下）：
 * 1. 标题「分类」；
 * 2. **同一行**：左边 `☰ 同人 · 漢化`（当前分类，点开左侧双层抽屉）、右边排序菜单
 *    （与收藏页同一个交互：`排序：最新 ▾` + 下拉菜单）；
 * 3. 正文：漫画列表，**下拉刷新**只刷当前分类的漫画；**展示方式可切换**（列表 / 卡片，
 *    卡片每行三个），切换按钮与「共 N 条 · 第 x/y 页」那一行**同一行**；
 * 4. 列表底部**不自动翻页**（用户要求）：一页 80 条全部滚到底之后，点「加载更多」
 *    才请求下一页。
 *
 * 抽屉（`ModalNavigationDrawer`，从左侧滑出）：**双层**。
 * - 第一层主分类（`最新A漫 / 同人 / 單本 / …`），**白底**、选中项主色 + 加粗 + 左侧竖条，
 *   右侧显示该分类的漫画总数（`1.0k` / `1.0w`，见 `formatCategoryCount`）；
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
    var sortExpanded by remember { mutableStateOf(false) }

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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategorySelectorButton(
                    label = state.selectorLabel,
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.weight(1f),
                )
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
                CategoryViewMode.LIST -> CategoryAlbumList(
                    state = state,
                    onLoadMore = onLoadMore,
                    onToggleViewMode = onToggleViewMode,
                    onOpenAlbum = onOpenAlbum,
                )

                CategoryViewMode.CARD -> CategoryAlbumGrid(
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
 * 「共 N 条 · 第 x/y 页」+ 右端的**展示方式切换按钮**（用户要求：同一行）。
 *
 * 两种模式共用这一行，所以抽出来；列表模式下它是 `LazyColumn` 的第一项，
 * 卡片模式下是跨满三列的 `item(span = full)`。
 */
@Composable
private fun CategoryCountBar(
    state: CategoryUiState,
    onToggleViewMode: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append("共 ${state.total} 条")
                if (state.page > 0) append(" · 第 ${state.page}/${state.totalPages} 页")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onToggleViewMode,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                // 图标画的是**点下去会切到**的那个模式：列表时显示网格（切卡片），
                // 卡片时显示列表（切列表）——和大多数文件管理器一致
                imageVector = when (state.viewMode) {
                    CategoryViewMode.LIST -> GridViewIcon
                    CategoryViewMode.CARD -> Icons.AutoMirrored.Filled.CategoryListIcon
                },
                contentDescription = when (state.viewMode) {
                    CategoryViewMode.LIST -> "切换为卡片"
                    CategoryViewMode.CARD -> "切换为列表"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
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
 * 卡片结构与书库的 `AlbumGridCard` 一致（白卡 + 封面 + 两行标题），
 * 只在标题下面多一行更新时间。
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            CategoryCountBar(state = state, onToggleViewMode = onToggleViewMode)
        }

        items(state.albums, key = { it.id }) { item ->
            CategoryGridCard(item = item, onOpen = { onOpenAlbum(item.id) })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            CategoryFooter(state = state, onLoadMore = onLoadMore)
        }
    }
}

/** 卡片模式下的一张卡：封面 + 两行标题 + 更新时间。 */
@Composable
private fun CategoryGridCard(
    item: OnlineSearchItem,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // 封面统一走 Cover（CONVENTIONS 第 1 条）：应用级加载器 + covers 磁盘缓存
            Cover(
                albumId = item.id,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.name.orEmpty().ifBlank { "JM${item.id}" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.updateLabel()?.let { label ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "更新 $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

/** 卡片每行几个（用户要求三个）。 */
private const val GRID_COLUMNS = 3

/** 卡片封面的宽高比：与书库网格一致（0.72 ≈ 3:4.2）。 */
private const val COVER_ASPECT_RATIO = 0.72f

/** 抽屉第一层（主分类）的宽度：放得下「English Manga」两行 + 右侧总数。 */
private val MAIN_PANE_WIDTH: Dp = 140.dp

/**
 * 左侧双层抽屉。
 *
 * 用 Material3 的 [ModalNavigationDrawer] + [ModalDrawerSheet]，而不是自己画一层遮罩：
 * 返回键关闭、点遮罩关闭、左边缘滑开、系统手势的 predictive back 都是它自带的，
 * 自己实现这些细节只会漏掉其中一两条。
 *
 * 宽度按窗口宽度的 88% 算、上限 [MAX_DRAWER_WIDTH]（Material 自己也是 360dp 封顶）：
 * 手机上两层都够宽，平板上不会被拉成一整屏。**抽屉本体不带圆角**（用户要求）。
 */
@Composable
private fun CategoryDrawer(
    state: CategoryUiState,
    drawerState: DrawerState,
    onSelectMain: (CategoryGroup) -> Unit,
    onSelectSub: (CategorySub) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    // 抽屉宽度按**窗口**宽度算（不是 `Configuration.screenWidthDp`：分屏 / 小窗下后者是整块屏幕，
    // 抽屉会比窗口还宽，lint 的 ConfigurationScreenWidthHeight 也是这个原因）
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val drawerWidth = (windowWidth * 0.88f).coerceAtMost(MAX_DRAWER_WIDTH)

    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.width(drawerWidth),
        // 用户要求：右上角 / 右下角不要圆角（默认是 16dp 的 large 形状）
        drawerShape = RectangleShape,
        // **不再加一遍系统栏内边距**：外层 `ModeRoot` 的 Scaffold 已经把状态栏那块让出来了
        // （NavHost 带 `padding`），面板自己再加一层会把「选择分类」顶到屏幕中间
        // （详情页的 TopAppBar 踩过同一件事）。底部由外层底栏挡住，也不需要面板再避让。
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
