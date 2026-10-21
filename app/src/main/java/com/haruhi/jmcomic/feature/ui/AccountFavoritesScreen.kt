package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.feature.data.AccountAlbum
import com.haruhi.jmcomic.feature.data.AccountFolder
import com.haruhi.jmcomic.feature.data.AccountRepository

/** 列表不满一屏时自动续一页的阈值（与书库同一套做法）。 */
private const val AUTO_FILL_MIN_ITEMS = 12

/**
 * 账号收藏（`docs/account-design.md` §5）。
 *
 * 结构**照抄本机收藏页**：收藏夹卡片列表 → 点进 3 列网格。
 * 退化点都在界面上：非空夹「删除」置灰（L4）、没有夹排序（L5）、
 * 夹的「N 本 / 封面」是补拉出来的（L6，拉不到显示「…」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFavoritesScreen(
    state: AccountFavoritesUiState,
    onBack: () -> Unit,
    onOpenFolder: (String?, String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onMessage: (String) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AccountFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountFolder?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TopBar(
            title = "收藏",
            onBack = onBack,
            actions = {
                // 刷新入口只留"下拉释放"，顶栏不再放刷新按钮（用户 2026-10-21 要求）
                if (state.signedIn) {
                    TextButton(onClick = { showCreate = true }) { Text("新建") }
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 下拉刷新：只有"已经有内容、正在重拉"时才转下拉圈；首屏加载由 LoadingBox 表达。
            // 进页面**不再自动联网**（用进程内快照铺），所以不会每次进来都闪一下。
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    if (!state.loading && !state.refreshing) onRefresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !state.signedIn -> ScrollableFullScreenBox {
                        EmptyWithAction(
                            title = "还没有登录",
                            hint = "登录后可以使用账号收藏夹",
                            actionText = "去登录",
                            onAction = onLogin,
                        )
                    }

                    state.loading -> LoadingBox()
                    state.error != null -> ScrollableFullScreenBox {
                        EmptyWithAction(
                            title = "账号收藏加载失败",
                            hint = state.error,
                            actionText = "重试",
                            onAction = onRefresh,
                        )
                    }

                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 「全部收藏」= 虚拟卡（含所有夹内的 + 未归类的），等价本机的「默认收藏夹」
                        item(key = "all") {
                            FavoriteFolderCard(
                                name = "全部收藏",
                                count = state.total,
                                coverAlbumId = state.allCoverAlbumId,
                                subtitleSuffix = "含未归类",
                                onOpen = { onOpenFolder(null, "全部收藏") },
                                onRename = null,
                                onDelete = null,
                            )
                        }
                        items(state.folders, key = { it.fid }) { folder ->
                            FavoriteFolderCard(
                                name = folder.name,
                                count = folder.count,
                                coverAlbumId = folder.coverAlbumId,
                                subtitleSuffix = null,
                                busy = state.busyFolderId == folder.fid,
                                onOpen = { onOpenFolder(folder.fid, folder.name) },
                                onRename = { renameTarget = folder },
                                // L4：非空夹不能删（服务端会拒绝），直接不给点；
                                // 数量未知（还没补拉到）时也不给删，避免点了才发现有漫画
                                onDelete = if (folder.count == 0) {
                                    { deleteTarget = folder }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameInputDialog(
            title = "新建收藏夹",
            initial = "",
            onConfirm = { name ->
                showCreate = false
                onCreate(name)
            },
            onDismiss = { showCreate = false },
        )
    }
    renameTarget?.let { target ->
        NameInputDialog(
            title = "重命名收藏夹",
            initial = target.name,
            onConfirm = { name ->
                renameTarget = null
                onRename(target.fid, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除「${target.name}」？",
            text = "只删除这个空收藏夹；夹里有漫画时官方不允许删除。",
            confirmText = "删除",
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onConfirm = {
                deleteTarget = null
                onDelete(target.fid)
            },
            onDismiss = { deleteTarget = null },
        )
    }
    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)
}

// ======================================================================
// 夹内：3 列网格
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountAlbumsScreen(
    state: AccountAlbumsUiState,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onOrderChange: (String) -> Unit,
    onRemove: (Long) -> Unit,
    onRefresh: () -> Unit,
    onMessage: (String) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var orderExpanded by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AccountAlbum?>(null) }

    // 数量并进标题（用户要求：`← 收藏夹1（178本）`），不再单独占一行
    val titleText = if (state.loading && state.total == 0) {
        state.title
    } else {
        "${state.title}（${state.total}本）"
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopBar(
            title = titleText,
            onBack = onBack,
            actions = {
                Box {
                    TextButton(onClick = { orderExpanded = true }) {
                        Text(if (state.order == AccountRepository.ORDER_BY_UPDATE) "更新时间" else "收藏时间")
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = orderExpanded, onDismissRequest = { orderExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("收藏时间") },
                            onClick = {
                                orderExpanded = false
                                onOrderChange(AccountRepository.ORDER_BY_TIME)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("更新时间") },
                            onClick = {
                                orderExpanded = false
                                onOrderChange(AccountRepository.ORDER_BY_UPDATE)
                            },
                        )
                    }
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 夹内内容基本不变：进页面先用缓存铺，只有数据被动过或用户下拉时才请求
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    if (!state.loading && !state.refreshing && !state.loadingMore) onRefresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> LoadingBox()
                    state.error != null -> ScrollableFullScreenBox {
                        EmptyWithAction(
                            title = "加载失败",
                            hint = state.error,
                            actionText = "重试",
                            onAction = onRefresh,
                        )
                    }

                    state.albums.isEmpty() -> ScrollableFullScreenBox {
                        EmptyWithAction(
                            title = "这里还没有漫画",
                            hint = "在漫画详情页点「收藏」就能加进来",
                            actionText = null,
                            onAction = {},
                        )
                    }

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(GRID_CONTENT_PADDING),
                            horizontalArrangement = Arrangement.spacedBy(GRID_CARD_SPACING),
                            verticalArrangement = Arrangement.spacedBy(GRID_CARD_SPACING),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.albums, key = { it.id }) { album ->
                                AccountAlbumCard(
                                    album = album,
                                    onOpen = { onOpenAlbum(album.id) },
                                    onLongPress = { removing = album },
                                )
                            }
                            item(key = "__more__", span = { GridItemSpan(maxLineSpan) }) {
                                AccountGridFooter(
                                    loadingMore = state.loadingMore,
                                    hasMore = state.hasMore,
                                    onLoadMore = onLoadMore,
                                )
                            }
                        }
                        // 一页可能只有几条：列表不满一屏时自动续一页（与书库同一套做法）
                        LoadMoreOnScroll(
                            shouldLoadMore = state.hasMore && !state.loading &&
                                !state.loadingMore && state.albums.size < AUTO_FILL_MIN_ITEMS,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }
    }

    removing?.let { album ->
        ConfirmDialog(
            title = "从收藏里移出？",
            text = "「${album.name ?: "JM${album.id}"}」会从账号收藏里彻底移除" +
                "（官方没有「只移出收藏夹」的接口）。",
            confirmText = "移出",
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onConfirm = {
                removing = null
                onRemove(album.id)
            },
            onDismiss = { removing = null },
        )
    }
    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)
}

// ======================================================================
// 详情页的收藏面板（单夹版，见 docs/account-design.md §4.4）
// ======================================================================

/**
 * 详情页「收藏」面板。
 *
 * 与本机那套**长得一样**（说明行 + 收藏夹列表 + 新建），语义按官方单夹模型退化：
 * - 未收藏 → 点某个夹 = 加入并放进该夹；点「全部收藏」= 只加入、不归类；
 * - 已收藏 → 点某个夹 = 搬过去；点「全部收藏」= 取消收藏。
 *
 * **不预选任何夹**：`GET /favorite` 的列表项不带所属夹，要标出"现在在哪个夹"
 * 得把每个夹的第一页都翻一遍（还可能翻不到），所以顶部只用一行字说明当前状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFavoriteSheet(
    signedIn: Boolean,
    inFavorites: Boolean,
    folders: List<AccountFolder>,
    /** 正在读收藏夹列表（**局部** loading：只转这一段，说明行/新建照常显示）。 */
    foldersLoading: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChoose: (folderId: String?, folderName: String?) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    HandleDragOnlyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("收藏夹", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !signedIn -> "登录后可以把漫画收进账号收藏夹。"
                    inFavorites -> "这本已在收藏里。点某个收藏夹就会移动过去（一本只能放一个夹）；" +
                        "点「移出收藏」会取消收藏。"
                    else -> "点一下就加入并放进该夹；点「全部收藏」只收藏、不归类。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (signedIn) {
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (foldersLoading && folders.isEmpty()) {
                    // 收藏夹还没读出来：只在这一段转圈（说明行与「新建」照常可用）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在读取收藏夹…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                            // 已收藏时这一行是"移出收藏"（危险动作，用主题的 error 色）；
                            // 未收藏时是"全部收藏"（只加入、不归类）
                            item(key = "__all__") {
                                FolderChoiceRow(
                                    label = if (inFavorites) "移出收藏" else "全部收藏",
                                    trailingText = if (inFavorites) null else "只收藏，不归类",
                                    labelColor = if (inFavorites) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Color.Unspecified
                                    },
                                    iconTint = if (inFavorites) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    enabled = !busy,
                                    onClick = { onChoose(null, null) },
                                )
                            }
                            items(folders, key = { it.fid }) { folder ->
                                FolderChoiceRow(
                                    label = folder.name,
                                    // 已收藏时这些行的语义是"移动至该夹"，右侧直接写出来
                                    trailingText = if (inFavorites) {
                                        "移动至"
                                    } else {
                                        folder.count?.let { "$it 本" }
                                    },
                                    enabled = !busy,
                                    onClick = { onChoose(folder.fid, folder.name) },
                                )
                            }
                            if (folders.isEmpty()) {
                                item(key = "__empty__") {
                                    Text(
                                        text = "还没有收藏夹，可以在下面新建一个。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 12.dp),
                                    )
                                }
                            }
                        }
                        VerticalScrollbar(state = listState, modifier = Modifier.matchParentSize())
                    }
                }

                Spacer(Modifier.height(4.dp))
                if (showCreate) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("新收藏夹名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showCreate = false; newName = "" }) { Text("取消") }
                        TextButton(
                            onClick = {
                                val name = newName.trim()
                                if (name.isNotEmpty()) onCreateAndAdd(name)
                                showCreate = false
                                newName = ""
                            },
                            enabled = !busy,
                        ) { Text("创建并加入") }
                    }
                } else {
                    TextButton(onClick = { showCreate = true }, enabled = !busy) { Text("新建收藏夹") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onRequireLogin) { Text("去登录") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ======================================================================
// 小组件
// ======================================================================

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 12.dp)
            .defaultMinSize(minHeight = ButtonDefaults.MinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** 空态 + 一个动作按钮（`EmptyBox` 没有内容槽，这里补一个）。 */
@Composable
private fun EmptyWithAction(
    title: String,
    hint: String?,
    actionText: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionText != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** 收藏夹卡片（本机收藏页那套观感的账号版）。 */
@Composable
private fun FavoriteFolderCard(
    name: String,
    count: Int?,
    coverAlbumId: Long?,
    subtitleSuffix: String?,
    onOpen: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    busy: Boolean = false,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderCover(coverAlbumId = coverAlbumId, name = name)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = buildString {
                        append(count?.toString() ?: "…")
                        append(" 本")
                        subtitleSuffix?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                onRename?.let { TextButton(onClick = it) { Text("改名") } }
                onDelete?.let {
                    TextButton(
                        onClick = it,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("删除") }
                }
            }
        }
    }
}

/** 夹封面：夹内第一本；没有就给同尺寸占位（与本机收藏页一致）。 */
@Composable
private fun FolderCover(coverAlbumId: Long?, name: String) {
    val shape = RoundedCornerShape(8.dp)
    val size = Modifier.size(width = 52.dp, height = 72.dp)
    if (coverAlbumId != null) {
        Cover(
            albumId = coverAlbumId,
            contentDescription = name,
            modifier = size.clip(shape).background(MaterialTheme.colorScheme.surface),
        )
    } else {
        Box(
            modifier = size
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 列表底部：加载中 / 加载更多 / 到底了。 */
@Composable
private fun AccountGridFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多") }
            else -> Text(
                text = "没有更多了",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一张漫画卡：封面 + 两行标题 + 作者（收藏列表项不带 `update_at`，没有时间行）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountAlbumCard(
    album: AccountAlbum,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Cover(
                albumId = album.id,
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = album.name.orEmpty().ifBlank { "JM${album.id}" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            album.author?.takeIf { it.isNotBlank() }?.let { author ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 面板里的一行：左侧名字，右侧一句动作提示（"12 本" / "移动至"）+ 箭头。
 *
 * [labelColor] / [iconTint] 用 `MaterialTheme.colorScheme.*` 传进来，
 * 这样"移出收藏"那行的红色跟着主题走（深色模式下也是主题里那个 error 色），
 * 不写死 `Color.Red`。
 */
@Composable
private fun FolderChoiceRow(
    label: String,
    trailingText: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    labelColor: Color = Color.Unspecified,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = trailingColor,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = iconTint,
        )
    }
}
