package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.core.database.entity.FavoriteEntity
import com.haruhi.jmcomic.core.storage.FileNaming
import com.haruhi.jmcomic.feature.data.ExportedFormat
import com.haruhi.jmcomic.feature.data.ExportFormat
import com.haruhi.jmcomic.ui.theme.DownloadIcon
import com.haruhi.jmcomic.ui.theme.EyeIcon
import com.haruhi.jmcomic.ui.theme.JmTheme
import com.haruhi.jmcomic.ui.theme.WarningOrangeDark
import com.haruhi.jmcomic.ui.theme.WarningOrangeLight

/** 简介收起时显示的行数。 */
private const val DESCRIPTION_COLLAPSED_LINES = 3

/**
 * 章节列表每多少话分成一组。
 *
 * 单行本动辄几十上百话，全铺出来会把「导出文件」和简介压到看不见的地方，
 * 进页面第一眼看到的应该是最前面几话。所以每 [CHAPTER_GROUP_SIZE] 话一组、
 * 一组一行组头，点组头展开/收起（用户要求）。
 * 总话数不超过一组时不分组——只有一个分组、还恒为展开的组头纯属多占地方。
 */
private const val CHAPTER_GROUP_SIZE = 10

/**
 * 章节分组展开状态的存档器（`Set<展开的分组下标>`）。
 *
 * 为什么不用 `rememberSaveable` 默认的自动存档：默认那张表只认 Bundle 认得的类型，
 * `Set<Int>` 不在里面（存的时候会抛"值不可保存"）。`listSaver` 把它折成 `ArrayList`，
 * 这是 Bundle 认识的，旋转/从阅读器回来时展开状态就不会丢。
 */
private val ExpandedChapterGroupsSaver = listSaver<Set<Int>, Int>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * 漫画详情页（核心中转页，计划 §9 第 7 条）。
 *
 * 关键交互：
 * - **本地优先**：先展示本地（书库或最近浏览）的章节，联网刷新只作为增强；
 *   刷新失败不阻断本地阅读；
 * - **点详情不等于加入书库**（用户要求第 2 点）：入库与否由封面下方那颗按钮负责——
 *   未入库写「未入库」、点了入库；已入库写「已入库」（带 ✓，主色调），
 *   再点一次是**移出书库**，且必须过二次确认（它会连本地图片、收藏夹、阅读进度一起删）；
 * - 封面区作者下面显示**点赞 / 观看数**（爱心 / 眼睛图标，`likes` / `total_views`），
 *   本地占用体积归到章节区标题（"章节 · 0/1 已下载（7.4 MB）"），同一屏里不重复说两遍；
 * - 封面右侧只放"书名 / JM 号 / 作者 / 点赞观看"，**不放任何按钮**：
 *   右栏最高 148dp（3 行书名 + 2 行作者），永远矮于 150dp 的封面，
 *   不会出现"按钮掉到封面底边以下"的破图（用户本轮反馈）；
 * - 章节行显示"已下载 / 部分下载 x/y / 未下载"；
 * - **章节每 10 话一组**，点组头展开/收起，默认只展开第一组（用户要求）；
 *   总话数不超过 10 话时不分组、直接平铺——只有一个分组时组头只会白占一行；
 * - 「收藏夹」面板支持加入/移出/移动（用户要求第 6 点）；
 * - 更多菜单承接下载面板、ZIP/PDF 导出（P4 接）、删除文件、从书库移除。
 *
 * ## 下载入口只有一个（用户要求第 3 点）
 *
 * 下载入口是更多菜单里的第一项「下载漫画」，点开面板选章节；面板里**已经在下载队列中的章节**
 * 用次要颜色标出来并且点不动，避免重复提交。章节行与章节区标题上都不再放下拉/按钮
 * （用户本轮要求：原先占着封面下方那颗「下载」按钮的位置让给入库按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleReversed: () -> Unit,
    onToggleDescription: () -> Unit,
    onAddToLibrary: () -> Unit,
    onMoveToFavorite: (Long) -> Unit,
    onAddToFavorite: (Long) -> Unit,
    onRemoveFromFavorite: (Long) -> Unit,
    onCreateFavoriteAndAdd: (String) -> Unit,
    /** 点章节行进阅读器（读到哪一话由点的那一行为准）。 */
    onReadChapter: (Long) -> Unit,
    /** 头部「阅读」按钮：**从第一章开始读**（用户要求第 4 点）。 */
    onReadFromStart: () -> Unit,
    /** 下载面板提交（选中的章节 id）。 */
    onDownloadChapters: (List<Long>) -> Unit,
    onDeleteChapterFiles: (Long) -> Unit,
    onDeleteAlbumFiles: () -> Unit,
    onDeleteAlbumRecord: () -> Unit,
    onConsumeMessage: () -> Unit,
    /** 点「导出 ZIP / PDF」：上层先做缺图预检，再决定弹缺图框还是密码框。 */
    onExport: (ExportFormat) -> Unit,
    /** 缺图对话框里点「先补下载」。 */
    onDownloadMissing: () -> Unit,
    onDismissExportPrecheck: () -> Unit,
    /** 密码框：确认（留空等同不设密码）/ 不设密码 / 取消。 */
    onConfirmExport: (String) -> Unit,
    onExportWithoutPassword: () -> Unit,
    onCancelExport: () -> Unit,
    /** 导出成品的「打开至 / 发送至」：交给上层发系统 Intent（用户第九轮要求第 2 点）。 */
    onOpenOutput: (String) -> Unit,
    onSendOutput: (String) -> Unit,
    /** 导出成品的「删除」：删系统下载目录里的那个文件。 */
    onDeleteExport: (String) -> Unit,
    /** 「打开文件夹」：让系统文件管理器定位到成品目录（用户反馈第 2 点）。 */
    onOpenFolder: () -> Unit,
    /**
     * 点作者：**直接打开搜索结果页**，进页面就按作者名搜（用户要求）。
     */
    onSearchAuthor: (String) -> Unit = {},
    /**
     * 提示文案的**上层**回调。
     *
     * 为什么不在这里自己弹 Snackbar：收藏夹是 `ModalBottomSheet`，
     * 会盖住宿主 `Scaffold` 的 Snackbar（用户反馈"提示语被弹框挡住看不见"）。
     * 把提示交给根部的 Scaffold 弹，才能浮在面板之上。
     */
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteFilesDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }
    var showFavoriteSheet by remember { mutableStateOf(false) }
    // 面板的展开状态由本页自己管：它是纯界面状态，不需要活过页面重建
    var showDownloadSheet by remember { mutableStateOf(false) }
    // 待删除的导出成品文件名（null = 没有待确认的删除）
    var pendingDeleteExport by remember { mutableStateOf<String?>(null) }

    // 章节分组：哪些组的章节是展开的，默认只有第一组（用户要求：默认展示 10 话）。
    //
    // 记的是**分组下标**、与章节内容无关：下载进度的每一次通知都会让 ViewModel
    // 重算整个列表（`loadLocalStats`），只要状态挂在列表本身上（比如拿 `chapters`
    // 当 remember 的 key），就会被那次重算冲掉，正一边下一边看的用户会看到分组自己折回去。
    // 纯界面状态，交给 rememberSaveable 扛住旋转/重建；换本子走的是新的导航 entry
    //（新的 ViewModel 与新的 composition），天然从"只展开第一组"开始。
    var expandedChapterGroups by rememberSaveable(stateSaver = ExpandedChapterGroupsSaver) {
        mutableStateOf(setOf(0))
    }

    // 一次性提示统一走 MessageEffect：先消费、再交给根部弹（切页不会重放）
    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)

    Scaffold(
        modifier = modifier,
        // 同 ModeRoot：Scaffold 默认是 `background` 灰。本页的固定区（TopAppBar 与它下面的
        // 标题栏）要纯白，与底栏 NavigationBar 一致。
        containerColor = MaterialTheme.colorScheme.surface,
        // **不要再套一层系统栏内边距**：本页在 `ModeRoot` 的 Scaffold 内容里，
        // 外层已经把状态栏高度作为 padding 给了 NavHost。这里若再让 Scaffold 与
        // TopAppBar 各加一次 windowInsets，标题上方就会空出约两倍状态栏的高度
        // （用户反馈第 5 点）。所以内层全部声明成 0。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = state.album?.name?.takeIf { it.isNotBlank() }
                            ?: state.album?.id?.let { "JM$it" }
                            ?: "漫画详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
//                    IconButton(onClick = onRefresh) {
//                        Icon(Icons.Default.Refresh, contentDescription = "刷新详情")
//                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // 下载入口搬到菜单里（用户本轮要求）：占位让给封面下方那颗"入库"按钮。
                        // 仍然只有这一个入口，面板里**已经在下载队列中的章节**用次要颜色标出来
                        // 并且点不动，避免重复提交。
                        DropdownMenuItem(
                            text = { Text("下载漫画") },
                            enabled = state.chapters.isNotEmpty(),
                            onClick = { menuExpanded = false; showDownloadSheet = true },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 ZIP") },
                            enabled = state.chapters.isNotEmpty(),
                            onClick = { menuExpanded = false; onExport(ExportFormat.ZIP) },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 PDF") },
                            enabled = state.chapters.isNotEmpty(),
                            onClick = { menuExpanded = false; onExport(ExportFormat.PDF) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除文件") },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                            ),
                            onClick = { menuExpanded = false; showDeleteFilesDialog = true },
                        )
//                        DropdownMenuItem(
//                            text = { Text("移出书库") },
//                            colors = MenuDefaults.itemColors(
//                                textColor = MaterialTheme.colorScheme.error,
//                            ),
//                            enabled = state.inLibrary,
//                            onClick = { menuExpanded = false; showDeleteRecordDialog = true },
//                        )
                    }
                },
            )
        },
    ) { padding ->
        // 下拉刷新（用户要求：把 TopAppBar 上那颗刷新按钮换成"下拉释放触发刷新"）。
        //
        // 为什么用 PullToRefreshBox 而不是在 LazyColumn 外再套一层滚动容器：
        // 它就是 Material3 的 nested-scroll 方案，在"列表已经滑到顶部"时接管继续下拉，
        // 列表没到顶时完全不动它——列表本身的滚动、fling 都不受影响。
        //
        // `isRefreshing` 用 `state.refreshing`：它与 ViewModel 里 loadFromNetwork 的
        // refreshing 是同一个字段，所以"下拉转圈"和列表顶部那条 LinearProgressIndicator
        // 同时开始、同时结束；失败时 refreshing 也会归 false，转圈不会卡住不退。
        // 已经在刷新时不再重复发起（refresh() 里没有去重）。
        //
        // ⚠ 外圈必须是**可滚动**的内容：`PullToRefreshBox` 完全靠 nested-scroll 拿手势，
        // 而 "加载中 / 本地没数据" 这两支（LoadingBox / EmptyBox）是普通的 Box、不参与
        // nested-scroll，直接放进去是拉不动的。所以给它们套一层 `verticalScroll`
        // ——纵向滚动容器会把没被消费的下拉派发给外层的 PullToRefreshBox。
        // 高度必须显式给到"整屏"：`verticalScroll` 发给子项的是**无限高约束**
        // （foundation/Scroll.kt 里 maxHeight = Infinity），LoadingBox / EmptyBox
        // 内部都用 fillMaxSize，直接把无限高传下去会崩；而给成固定 dp，
        // 空状态在平板上就不会居中了。所以用 BoxWithConstraints 读真实视口高度再填回去。
        //
        // ⚠⚠ `isRefreshing` 必须以 `state.album != null` 为前提：首屏联网加载时
        // `loading` 与 `refreshing` 是**同时为 true** 的（见 loadFromNetwork），
        // 直接把 refreshing 交给它，屏幕上会同时出现"居中的加载圈"和"顶部下拉圈"
        // 两个 loading（用户报的 bug）。有内容时两者互斥，才轮到下拉圈出场。
        PullToRefreshBox(
            isRefreshing = state.refreshing && !state.loading,
            onRefresh = { if (!state.refreshing && !state.loading) onRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading || state.album == null -> BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(modifier = Modifier.heightIn(min = maxHeight)) {
                        if (state.loading) {
                            LoadingBox()
                        } else {
                            EmptyBox(
                                title = "本地没有这个本子的数据",
                                hint = "请检查网络后下拉重试",
                            )
                        }
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        AlbumHeader(
                            state = state,
                            onAddToLibrary = onAddToLibrary,
                            // 已入库时那颗按钮是"移出书库"：走和菜单里同一段逻辑、
                            // 同一个二次确认框（会删本地图片，不能点一下就执行）
                            onRemoveFromLibrary = { showDeleteRecordDialog = true },
                            onOpenFavoriteSheet = { showFavoriteSheet = true },
                            onToggleDescription = onToggleDescription,
                            onRead = onReadFromStart,
                            onSearchAuthor = onSearchAuthor,
                        )
                    }
                    if (state.refreshing) {
                        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    }
                    // 已经导出过的 zip/pdf：放在章节前面——它属于"这本在我设备上有什么"，
                    // 与章节区标题里的占用体积是同一类信息，不该被长长的章节列表压到最下面
                    // （用户第九轮要求第 2 点）。正在导出的也算（用户第十一轮要求第 3 点）。
                    if (state.exports.isNotEmpty() || state.activeExports.isNotEmpty()) {
                        item {
                            ExportSection(
                                exports = state.exports,
                                active = state.activeExports,
                                canOpenFolder = state.canOpenFolder,
                                onOpen = onOpenOutput,
                                onSend = onSendOutput,
                                onDelete = { name -> pendingDeleteExport = name },
                                onOpenFolder = onOpenFolder,
                            )
                        }
                    }
                    // 离线 / 刷新失败**不再挂横幅**（用户本轮要求第 1 点）：
                    // 失败原因由 ViewModel 走 `message` 弹一句提示，用户想重试自己下拉释放一次。
                    // 页面上的章节列表本来就不受刷新失败影响（本地优先，计划 §9 第 7 条），
                    // 横幅只是把这块地方永久占住了。
                    item {
                        ChapterSectionHeader(
                            total = state.totalChapters,
                            downloaded = state.downloadedChapters,
                            localSizeBytes = state.localSizeBytes,
                            reversed = state.reversed,
                            onToggleReversed = onToggleReversed,
                        )
                    }
                    if (state.chapters.isEmpty()) {
                        item {
                            EmptyBox(
                                title = "还没有章节数据",
                                hint = "下拉刷新试试",
                                modifier = Modifier.height(160.dp),
                            )
                        }
                    } else if (state.chapters.size <= CHAPTER_GROUP_SIZE) {
                        // 一屏就放得下（≤ 10 话）：不分组，直接平铺。
                        // 只有一个分组、而且它永远是展开的，组头就是白占一行。
                        items(state.chapters, key = { it.chapterId }) { chapter ->
                            ChapterRowItem(
                                chapter = chapter,
                                onOpen = { onReadChapter(chapter.chapterId) },
                                onDownload = { onDownloadChapters(listOf(chapter.chapterId)) },
                                onDeleteFiles = { onDeleteChapterFiles(chapter.chapterId) },
                            )
                        }
                    } else {
                        // 每 CHAPTER_GROUP_SIZE 话一组：组头一行 +（展开时）组内的章节行。
                        // 先分组再往 LazyColumn 里塞，所以一组展开与否完全由 expandedChapterGroups
                        // 决定——折叠的组只是"没往列表里加行"，不会为它多做一份测量。
                        val groups = state.chapters.chunked(CHAPTER_GROUP_SIZE)
                        groups.forEachIndexed { groupIndex, group ->
                            // 组与组之间压一条分割：章节行是一张张长得几乎一样的卡片，
                            // 上一组的最后一话和下一组的组头只隔 6dp 时，整片会糊成一块，
                            // 连着往下读很累。给足行距再补一条细线，视线才有落点。
                            if (groupIndex > 0) {
                                item(key = chapterGroupSeparatorKey(groupIndex)) {
                                    ChapterGroupSeparator()
                                }
                            }
                            val expanded = groupIndex in expandedChapterGroups
                            item(key = chapterGroupKey(groupIndex)) {
                                ChapterGroupHeader(
                                    chapters = group,
                                    expanded = expanded,
                                    onToggle = {
                                        expandedChapterGroups = if (expanded) {
                                            expandedChapterGroups - groupIndex
                                        } else {
                                            expandedChapterGroups + groupIndex
                                        }
                                    },
                                )
                            }
                            if (expanded) {
                                items(group, key = { it.chapterId }) { chapter ->
                                    ChapterRowItem(
                                        chapter = chapter,
                                        onOpen = { onReadChapter(chapter.chapterId) },
                                        onDownload = { onDownloadChapters(listOf(chapter.chapterId)) },
                                        onDeleteFiles = { onDeleteChapterFiles(chapter.chapterId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDownloadSheet) {
        DownloadSheet(
            state = state,
            onDownload = { chapterIds ->
                showDownloadSheet = false
                onDownloadChapters(chapterIds)
            },
            onDismiss = { showDownloadSheet = false },
        )
    }

    if (showFavoriteSheet) {
        FavoritePickerSheet(
            allFavorites = state.allFavorites,
            currentFavoriteIds = state.favoriteIds,
            onDismiss = { showFavoriteSheet = false },
            onMoveTo = { onMoveToFavorite(it); showFavoriteSheet = false },
            onAdd = { onAddToFavorite(it) },
            onRemove = { onRemoveFromFavorite(it) },
            onCreate = { onCreateFavoriteAndAdd(it) },
        )
    }
    if (showDeleteFilesDialog) {
        ConfirmDialog(
            title = "删除本地文件？",
            text = "会删除这本漫画所有下载的图片（约 ${formatBytes(state.localSizeBytes)}）和导出的 zip/pdf。",
            confirmText = "删除文件",
            onConfirm = { showDeleteFilesDialog = false; onDeleteAlbumFiles() },
            confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onDismiss = { showDeleteFilesDialog = false },
        )
    }
    if (showDeleteRecordDialog) {
        ConfirmDialog(
            title = "从书库移出？",
            text = "移出书库后会删除本地图片、收藏夹、阅读进度。（不会删除已导出的 zip/pdf）。",
            confirmText = "移出",
            onConfirm = { showDeleteRecordDialog = false; onDeleteAlbumRecord() },
            confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onDismiss = { showDeleteRecordDialog = false },
        )
    }

    // 删导出成品是不可撤销的（重新生成一份 20MB 的 PDF 要花时间），所以先确认一次
    pendingDeleteExport?.let { name ->
        ConfirmDialog(
            title = "删除这个导出文件？",
            text = "会从下载目录里删掉「$name」。",
            confirmText = "删除",
            onConfirm = {
                pendingDeleteExport = null
                onDeleteExport(name)
            },
            confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onDismiss = { pendingDeleteExport = null },
        )
    }

    // 缺图：先列明缺哪些话、缺多少张，提供补下载后再导出（计划 §9 第 7 条）
    state.exportPrecheck?.let { precheck ->
        // "没下过"的话（连图片清单都没有）和"下了一部分"的话要分开说：
        // 前者说不出缺几张，混在一起写"共缺 0 张"会让人以为不用补（用户反馈的 bug）
        val neverDownloaded = precheck.missingChapters.count { !it.hasImageList }
        val partlyDownloaded = precheck.missingChapters.size - neverDownloaded
        AlertDialog(
            onDismissRequest = onDismissExportPrecheck,
            title = { Text("还有图片没下载完") },
            text = {
                Column {
                    Text(
                        text = buildString {
                            if (partlyDownloaded > 0) {
                                append("有 $partlyDownloaded 话没下完（共缺 ${precheck.missingImages} 张）；")
                            }
                            if (neverDownloaded > 0) {
                                append("有 $neverDownloaded 话还没有下载过；")
                            }
                            append("导出前请先补下载，否则成品会缺页。")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(precheck.missingChapters, key = { it.title }) { row ->
                            Text(
                                text = if (row.hasImageList) {
                                    "${row.title}　缺 ${row.missing}/${row.total} 张"
                                } else {
                                    "${row.title}　未下载"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDownloadMissing) { Text("先补下载") }
            },
            dismissButton = {
                TextButton(onClick = onDismissExportPrecheck) { Text("取消") }
            },
        )
    }

    // 导出密码框：预填设置里的默认密码（设置里留空就是空框），保留 [确认] / [不设密码]
    state.exportFormat?.let { format ->
        ExportPasswordDialog(
            format = format,
            defaultPassword = state.exportDefaultPassword,
            targetExists = state.exportTargetExists,
            onConfirm = onConfirmExport,
            onWithoutPassword = onExportWithoutPassword,
            onDismiss = onCancelExport,
        )
    }
}

/**
 * 「导出文件」区块：这本漫画已经导出到系统下载目录的 zip / pdf（用户第九轮要求第 2 点）。
 *
 * 设计取舍：
 * - **默认折叠**（用户第十轮要求第 1 点）：这一块是"补充信息"，不是每次进详情页都要看的，
 *   展开着会白占掉半屏、把章节列表挤到下面；折叠后只留一行标题，
 *   标题右侧直接写明"ZIP · PDF"，不展开也知道有哪些成品；
 * - 点头部整行展开/收起（"能点的是一整行"，不用瞄准小箭头）；
 * - **导出中的格式也算在标题里**（用户第十一轮要求第 3 点）：刚点完导出，折叠状态下
 *   就能看到"正在导出 PDF"，不用展开也不用去任务页确认；
 * - 展开后**一个卡片装下所有成品**，每行一个格式：最多两行，
 *   行内三段式（格式徽标 → 文件名+大小+生成时间 → 三个操作），
 *   左到右正好是"是什么 → 什么状态 → 能对它做什么"，与任务页的成品行一致；
 * - 一个成品都没有、也没有在跑的任务时整块不显示（不留空壳）。
 */
@Composable
private fun ExportSection(
    exports: List<ExportedFormat>,
    active: List<ActiveExport>,
    canOpenFolder: Boolean,
    onOpen: (String) -> Unit,
    onSend: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenFolder: () -> Unit,
) {
    // 展开状态是纯界面状态：默认收起，退出页面即忘（下次进来还是收起）。
    // 但**正在导出时会自动展开一次**，让用户马上看到进度（用户第十一轮要求第 3 点）。
    var expanded by remember { mutableStateOf(false) }
    var autoExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(active.isNotEmpty()) {
        if (active.isNotEmpty() && !autoExpanded) {
            expanded = true
            autoExpanded = true
        }
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            // 「按下态」底色是全局 token，定义在 ui/theme/ExtendedColors.kt。
            containerColor = JmTheme.colors.pressedCardContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 10.dp),
            ) {
                Text("导出文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                // 折叠状态下也能一眼看到有哪几种成品 / 哪几种正在导出
                Text(
                    text = buildString {
                        append(exports.joinToString(" · ") { it.format.label })
                        active.forEach { row ->
                            if (isNotEmpty()) append(" · ")
                            append(row.format.label)
                            append(" 导出中")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起导出文件" else "展开导出文件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                exports.forEachIndexed { index, exported ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ExportedFileRow(
                        exported = exported,
                        onOpen = { onOpen(exported.file.displayName) },
                        onSend = { onSend(exported.file.displayName) },
                        onDelete = { onDelete(exported.file.displayName) },
                    )
                }
                /*
                active.forEach { row ->
                    if (exports.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ActiveExportRow(row)
                }
                // 「打开文件夹」（用户反馈第 2 点）：让系统文件管理器直接定位到成品目录。
                // 老系统上成品在应用私有目录里、别的应用看不到，这时整条不显示
                // （由 canOpenFolder 控制，见 ExportPublisher.folderUri 的注释）。
                if (canOpenFolder) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = onOpenFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开文件夹") }
                }
                Spacer(Modifier.height(4.dp))
                 */
            }
        }
    }
}

/**
 * 正在导出的那一行：格式徽标 + 状态文案 + 进度条。
 *
 * 没有操作按钮——这一份还没生成出来，能做的只有等（取消在任务页）。
 */
@Composable
private fun ActiveExportRow(row: ActiveExport) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outline)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = row.format.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = row.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.progress > 0f) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 成品的一行：格式徽标 + 文件名 + 大小/生成时间 + 打开至/发送至/删除。 */
@Composable
private fun ExportedFileRow(
    exported: ExportedFormat,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(IntrinsicSize.Min),) {
        // 格式徽标：一眼分清哪个是 zip 哪个是 pdf，不用去读扩展名
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(42.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = exported.format.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exported.file.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(formatBytes(exported.file.sizeBytes))
                    append(" · 生成于 ")
                    append(
                        if (exported.file.createdAt > 0) {
                            formatTime(exported.file.createdAt)
                        } else {
                            "时间未知"
                        },
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
        TextButton(onClick = onOpen) { Text("打开至") }
        TextButton(onClick = onSend) { Text("发送至") }
        TextButton(onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除文件",
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Text("删除")
        }
    }
}

/**
 * 导出密码框（计划 §9 第 7 条）。
 *
 * - 默认**预填**该格式的密码（来自凭据存储里设置页那一项）；
 *   **设置里留空/没设过就是空串**——不留任何参考实现的默认值兜底
 *   （用户 2026-10-15 反馈：设置里清空后这里不该再默认填上密码）；
 * - 有显示/隐藏切换；
 * - 底部是 **[确认] / [不设密码]**，留空等同不加密；
 * - 同名成品已存在时，用橙色提醒"会被覆盖"（用户要求第 5 点）；
 * - 取消/返回不创建任务。
 */
@Composable
private fun ExportPasswordDialog(
    format: ExportFormat,
    defaultPassword: String,
    targetExists: Boolean,
    onConfirm: (String) -> Unit,
    onWithoutPassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(format) { mutableStateOf(defaultPassword) }
//    var visible by remember(format) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出 ${format.label}") },
        text = {
            Column {
                Text(
                    text = "给导出的 ${format.label} 设置打开密码；" +
                        "选「不设密码」或留空则不加密，任何人都能打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (targetExists) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "⚠ 下载目录里已经有同名文件，再次生成会直接覆盖它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSystemInDarkTheme()) WarningOrangeDark else WarningOrangeLight,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("密码") },
//                    visualTransformation = if (visible) {
//                        VisualTransformation.None
//                    } else {
//                        PasswordVisualTransformation()
//                    },
                    visualTransformation = VisualTransformation.None,
//                    trailingIcon = {
//                        TextButton(onClick = { visible = !visible }) {
//                            Text(if (visible) "隐藏" else "显示")
//                        }
//                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("确认") } },
        dismissButton = {
            Row {
                TextButton(onClick = onWithoutPassword) { Text("不设密码") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 封面区的点赞 / 观看数：爱心图标 + 点赞数、眼睛图标 + 观看数。
 *
 * 两个字段都来自在线详情接口（`likes` / `total_views`），可能是 null 或空串
 * （还没联网刷新过、或接口没给这一项）。这时**整行不占位**——宁可不显示，
 * 也不要在封面旁边挂一对"♥ 0 👁 0"的假数字。
 */
@Composable
private fun AlbumStatRow(likes: String?, views: String?) {
    val likeText = formatStatCount(likes)
    val viewText = formatStatCount(views)
    if (likeText == null && viewText == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        likeText?.let { StatItem(icon = Icons.Default.Favorite, text = it, description = "点赞数") }
        viewText?.let { StatItem(icon = EyeIcon, text = it, description = "观看数") }
    }
}

/** 一项统计：小图标 + 数字（图标只起"这是哪种数字"的作用，读屏靠 [description]）。 */
@Composable
private fun StatItem(icon: ImageVector, text: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 点赞 / 观看数的展示文案。
 *
 * 空白（null、空串、只有空格）返回 null = 这一项不显示。数字本身原样展示：
 * 接口给的就是 `"12345"` 这样的字符串，自己折算成"1.2 万"反而会跟网页端对不上。
 */
fun formatStatCount(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

@Composable
private fun AlbumHeader(
    state: AlbumDetailUiState,
    onAddToLibrary: () -> Unit,
    /** 已入库时点那颗按钮 = 移出书库：只负责弹二次确认，真正执行由上层 [AlbumDetailScreen]。 */
    onRemoveFromLibrary: () -> Unit,
    onOpenFavoriteSheet: () -> Unit,
    onToggleDescription: () -> Unit,
    onRead: () -> Unit,
    /** 点作者：**直接打开搜索结果页**，进页面就按作者名搜（用户要求）。 */
    onSearchAuthor: (String) -> Unit = {},
) {
    val album = state.album ?: return
    Column(modifier = Modifier.padding(12.dp)) {
        Row {
            Cover(
                albumId = album.id,
                contentDescription = album.name,
                modifier = Modifier
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 名称 / JM 号 / 作者行**各一个**可选区块（用户要求：这几段都要能复制）。
                // Android 原生复制 = SelectionContainer + 长按起选 → 系统工具条上的「复制」。
                //
                // 每段各一个容器，是为了让「全选」只拿到**这一段自己的全文**（收起简介 / 被截断的书名
                // 复制出来都是全文，见下方简介那段注释），不会把别的段落一起带上。
                // 一个 SelectionState 只能绑一个 SelectionContainer（共用会抛 IllegalStateException），
                // 所以每处都用默认 state，各管各的。
                SelectionContainer {
                    Text(
                        text = album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = "JM${album.id}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val authors = parseStringArray(album.author)
                if (authors.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    SelectionContainer {
                        if (authors.size == 2) {
                            Text(
                                text = authorLinkText(
                                    authors = authors,
                                    authorColor = MaterialTheme.colorScheme.primary,
                                    separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onAuthorClick = onSearchAuthor,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }else{
                            val authorText = authors.joinToString(AUTHOR_SEPARATOR)
                            Text(
                                text = authorText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onSearchAuthor(authorText) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 点赞数 / 观看数
                AlbumStatRow(likes = album.likes, views = album.totalViews)
                // 右栏到这里为止：书名(≤3 行 72dp) + 4 + JM 号(16) + 2 + 作者(≤2 行 32)
                // + 6 + 统计(16) = 148dp，永远矮于 150dp 的封面，底下不会再多出东西。
                //
                // 这里原来还挂着一行「加入书库 / 已在书库 + 未收藏」：那颗 TextButton 触发
                // 最小可点尺寸后占 48dp，3 行书名时整颗掉到封面底边以下（用户反馈）。
                // 现在入库动作搬到了下面按钮行（未入库 / 已入库 + ✓），收藏状态由旁边的
                // 「收藏 / 已收藏」按钮自己表达，这一行就没有存在的必要了。
            }
        }

        // 三颗按钮**另起一行、占满整宽**（用户第十一轮要求第 2 点）。
        // 挤在封面右边的窄栏里时，"继续阅读 / 未入库 / 已收藏"在 360dp 屏上放不下会掉行：
        // 主按钮吃掉剩余宽度（weight），两颗次要按钮自己定宽，标签再长也不会换行。
        // 两颗次要按钮的文案都控制在 3 个字以内（未入库/已入库、收藏/已收藏），
        // 状态翻转时宽度只差一颗 ✓ 图标，主按钮吃掉这点差量，不会有人换行。
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            // 「继续阅读」按上次读到的位置续读；没读过才是「阅读」= 从第一话第一张开始。
            Button(
                onClick = onRead,
                enabled = state.chapters.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (state.progress?.isUsable == true) "继续阅读" else "阅读",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 入库 / 已入库（用户本轮要求）：占的是原来那颗「下载」按钮的位置
            //（下载入口搬进了更多菜单的「下载漫画」）。
            //
            // 一颗按钮表达两种状态，靠文案 + 图标说清楚，**不做实心底**（用户要求）：
            // - 未入库：描边按钮「未入库」，点一下入库；
            // - 已入库：同一个描边按钮，主色调文字 + ✓ + 「已入库」，点一下是**移出书库**，
            //   而且是"危险动作"——它会连本地图片、收藏夹、阅读进度一起删，
            //   所以先弹二次确认（onRemoveFromLibrary），确认后才真的执行。
            OutlinedButton(
                onClick = { if (state.inLibrary) onRemoveFromLibrary() else onAddToLibrary() },
                // 显式写主色调：描边按钮的默认内容色就是 primary，这里写出来是为了让
                // "已入库 = 主色调"这条约定有据可查，将来主题调整也不会被悄悄改掉
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (state.inLibrary) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        // 只是给"已入库"再加一层视觉确认，文字已经说清楚了，读屏不必念
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (state.inLibrary) "已入库" else "未入库")
            }
            // 「收藏」直接打开收藏夹面板选夹（用户要求第 5 点：不再单独放「收藏夹」按钮）
            OutlinedButton(onClick = onOpenFavoriteSheet) {
                Text(if (album.collected == true) "已收藏" else "收藏")
            }
        }

        // 有进度时补一句"读到哪儿了"：按钮上的"继续阅读"只说了一半，
        // 用户想知道的是"继续到哪一话"（用户第十一轮要求第 1 点）。
        state.progressText?.let { text ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        parseStringArray(album.tags).takeIf { it.isNotEmpty() }?.let { tags ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = tags.filter { it.isNotBlank() }.joinToString(" · ") { "#${it.trim()}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        album.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(8.dp))
            // 只有真的被截断时才显示"展开简介"（用户反馈：内容本来就能全部显示时
            // 按钮是多余的）。判断方式是用 onTextLayout 看行数是否被 maxLines 限制。
            var descriptionOverflows by remember(description) { mutableStateOf(false) }
            // 简介单独一个可选区块：收起时只画 3 行（末尾省略号），但**选择是按完整文本记的** ——
            // Compose 复制取的是这个可选段落整段 AnnotatedString 的子串（不是可见的那几行），
            // 所以长按后按「全选」复制出来的是整篇简介；把结束手柄拖到文字下方也一样能补到全文。
            // 高亮只画在可见行上（SelectionController 会按 lastVisibleOffset 裁剪），
            // 这是 Compose 的既有行为，不是漏了。
            SelectionContainer {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (state.descriptionExpanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        // hasVisualOverflow 表示文本被 maxLines 截断了
                        if (!state.descriptionExpanded) {
                            descriptionOverflows = result.hasVisualOverflow ||
                                result.lineCount > DESCRIPTION_COLLAPSED_LINES
                        }
                    },
                )
            }
            if (descriptionOverflows || state.descriptionExpanded) {
                TextButton(onClick = onToggleDescription) {
                    Text(if (state.descriptionExpanded) "收起简介" else "展开简介")
                }
            }
        }
    }
}

/**
 * 收藏夹面板（用户要求第 6 点：支持修改收藏夹）。
 *
 * - 点未加入的夹 -> 加入；点已加入的夹 -> 移出（支持一个本子属于多个夹）；
 * - 「移动到」把本子只保留在该夹里，对应"修改收藏夹"的直觉用法；
 * - 底部可直接新建夹并加入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritePickerSheet(
    allFavorites: List<FavoriteEntity>,
    currentFavoriteIds: List<Long>,
    onDismiss: () -> Unit,
    onMoveTo: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("收藏夹", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点一下加入，再点一下移出；「移动到」会让本子只保留在该收藏夹里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            allFavorites.forEach { favorite ->
                val joined = favorite.id in currentFavoriteIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (joined) onRemove(favorite.id) else onAdd(favorite.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (joined) "✓ ${favorite.name}" else favorite.name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!joined) {
                        TextButton(onClick = { onMoveTo(favorite.id) }) { Text("移动到") }
                    }
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
                            if (newName.isNotBlank()) onCreate(newName.trim())
                            showCreate = false
                            newName = ""
                        },
                    ) { Text("创建并加入") }
                }
            } else {
                TextButton(onClick = { showCreate = true }) { Text("新建收藏夹") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChapterSectionHeader(
    total: Int,
    downloaded: Int,
    localSizeBytes: Long,
    reversed: Boolean,
    onToggleReversed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = chapterSectionTitle(total, downloaded, localSizeBytes),
            style = MaterialTheme.typography.titleSmall,
        )
        if (total > 1) {
            TextButton(onClick = onToggleReversed) {
                Text(if (reversed) "倒序" else "正序")
            }
        }
    }
}

/**
 * 章节区标题，例如 `章节 · 0/1 已下载（7.4 MB）`。
 *
 * 本地占用体积原来写在封面区的元信息里（"共 x 话 · 已下载 y 话 · 占用 z"），
 * 那与这里的下载进度本来就是同一件事，在同一屏里说了两遍；现在统一收到这里
 * （用户要求）。本地没有文件时不加括号，不写"（0 B）"这种没有信息量的括号；
 * 章节清单还没拿到（total == 0）时不写"0/0 已下载"，只留"章节"，本地占用照说。
 */
fun chapterSectionTitle(total: Int, downloaded: Int, localSizeBytes: Long): String = buildString {
    append("章节")
    if (total > 0) append(" · $downloaded/$total 已下载")
    if (localSizeBytes > 0) append("（${formatBytes(localSizeBytes)}）")
}

/**
 * 章节行。
 *
 * 整行**只做一件事：进阅读器**；其余动作收进行尾菜单（菜单只在这一话下载过东西时才出现）：
 * 「下载本话」与「删除图片」。
 *
 * 关于"下载入口"：更多菜单里的「下载漫画」（多选面板）仍是**批量**入口，这里的是
 * **单话快捷**入口，两者不冲突——用户在章节列表上看见某一话缺图，不必再开面板去勾。
 * 这一话**部分下载**时点它会接着把缺的补齐（`downloadChapters` 只提交章节 id，
 * 从头下过的图由下载引擎按落盘结果跳过）。
 *
 * ⚠ 行尾那颗 ⋮ 目前只在 `downloadedImages > 0`（下过一些、或已下完）时才显示，
 * 所以**完全没过的一话看不见这个菜单**，那种情况仍然走更多菜单的「下载漫画」。
 * 这是既有行为，本轮没有跟着改。
 *
 * @param onDownload 下载这一话（缺图补下，ViewModel 内部走同一套提交）。
 */
@Composable
private fun ChapterRowItem(
    chapter: ChapterRow,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDeleteFiles: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapter.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        chapter.isDownloaded -> MaterialTheme.colorScheme.primary
                        chapter.isPartial -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // 图标与菜单放在同一个 Box 里：DropdownMenu 是相对**它所在的父布局**定位的，
            // 直接放在 Row 里当兄弟节点时锚点会落到整行上，菜单看起来"飘"到了别处
            // （用户反馈第 6 点）。包一层 Box 之后锚点才是那颗按钮。
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "章节操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // 两行都带 leadingIcon：菜单项文字短、动作都是"对文件做什么"，
                    // 靠图标区分比靠读字快（删除图标与成品行的「删除文件」同一支）。
                    // 图标**不显式上色**：DropdownMenuItem 会把内容色写进 LocalContentColor，
                    // 下面那条的 textColor = error 会自动带着图标一起变红。
                    DropdownMenuItem(
                        text = { Text("下载本话") },
                        leadingIcon = {
                            Icon(
                                imageVector = DownloadIcon,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                        },
                        onClick = { menuExpanded = false; onDownload() },
                    )
                    if (chapter.downloadedImages > 0) {
                        DropdownMenuItem(
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                            ),
                            text = { Text("删除图片") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                            },
                            onClick = { menuExpanded = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除「${chapter.title}」的图片？",
            text = "删除这一话已经下载的图片。",
            confirmText = "删除图片",
            onConfirm = { confirmDelete = false; onDeleteFiles() },
            confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * 分组在 LazyColumn 里的 key：组头 `chapter_group_1`、组间分割 `chapter_group_1_sep`。
 *
 * 提成函数是为了让它**出现在一个地方**：这些 key 和章节行的 `chapterId` 混在同一个列表里，
 * 手写字符串迟早会和别处的 key 撞上，撞了之后 LazyColumn 的表现是"滚动位置乱跳"，
 * 而且不会报错。分割线跟着组头的 key 走，两者的下标永远对得上。
 */
private fun chapterGroupKey(index: Int): String = "chapter_group_$index"

private fun chapterGroupSeparatorKey(index: Int): String = "${chapterGroupKey(index)}_sep"

/**
 * 组与组之间的分割样式：一段留白 + 一条细线（线与库里"已选"操作条是同一支
 * `outlineVariant`）。
 *
 * 章节行本身是十张几乎一样的卡片，上下只差 6dp，分不出"这一组到哪儿结束"；
 * 这里把间距撑到 20dp 再压一条线，滑到组边界时视线有明确的落点，
 * 配合组头的分组色，整页才有"一节一节"的节奏而不是一长条。
 * 线跟着卡片的左右边距缩进，不横跨整宽——它不是页面的分区线，是列表内部的分组线。
 */
@Composable
private fun ChapterGroupSeparator() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 分组标题：两端的章节名拼成 `第1话 - 第10话`（用户要求：每 10 话一组）。
 *
 * 为什么用章节名而不是行号：倒序时列表第一行是**最后一话**，
 * 写"第 1 - 10 话"就把人指到反方向去了。章节名（接口/库里统一成 `第N话`，
 * 见 `Repository.chapterTitleOf`）不管顺序怎么切都指得准。
 * 只有一话（或首尾同名）时不写成 `第1话 - 第1话`。
 */
fun chapterGroupTitle(chapters: List<ChapterRow>): String {
    val first = chapters.firstOrNull()?.title ?: return "章节"
    val last = chapters.last().title
    return if (first == last) first else "$first - $last"
}

/**
 * 章节分组头：`第1话 - 第10话` + 组内下载进度 + 展开/收起箭头。
 *
 * 交互取舍：
 * - **整行可点**，与「导出文件」的折叠头同一个套路：能点的是一整行，不用瞄准小箭头；
 * - 箭头沿用「导出文件」的约定——展开时向上、收起时向下，两种状态一眼可分；
 *   收起时组内是哪几话仍然写在标题两端，折叠不等于把信息藏起来；
 * - 组内下载进度只在**确实有下完的话**时才写：整本都没下载时，六个组头齐刷刷
 *   "已下载 0/10" 只是噪音；下完了用主题色、只下了一部分用 tertiary，
 *   与章节行本身的状态配色是同一套；
 * - 用 `surfaceVariant` 卡片而不是章节行那种白卡片：层级上一眼分得清
 *   "这是分组的壳"还是"这是一话"。
 */
@Composable
private fun ChapterGroupHeader(
    chapters: List<ChapterRow>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val downloaded = chapters.count { it.isDownloaded }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (expanded) "收起这一组" else "展开这一组",
                    onClick = onToggle,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = chapterGroupTitle(chapters),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (downloaded > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "已下载 $downloaded/${chapters.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (downloaded >= chapters.size) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 下载面板（计划 §9 第 7 条）。
 *
 * - **默认勾选未完成的章节**；支持"全选未下载"和逐话自选；
 * - 已经下完的章节默认不勾，避免用户重复提交；
 * - 提交后不强制跳任务页，只给短提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(
    state: AlbumDetailUiState,
    onDownload: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(state.chapters) {
        mutableStateOf(state.defaultDownloadSelection)
    }
    val queued = state.queuedChapterIds

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "下载章节",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "默认勾选还没下完的章节；已经在下载队列里的章节不能再选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { selected = state.defaultDownloadSelection }) {
                    Text("全选可下载")
                }
                TextButton(onClick = { selected = emptySet() }) { Text("全不选") }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(state.chapters, key = { it.chapterId }) { chapter ->
                    val inQueue = chapter.chapterId in queued
                    val checked = !inQueue && chapter.chapterId in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 已在队列里的章节**不可选**：重复提交只会得到"已有任务"的提示
                            .clickable(enabled = !inQueue) {
                                selected = if (checked) {
                                    selected - chapter.chapterId
                                } else {
                                    selected + chapter.chapterId
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !inQueue)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                // 队列中的章节用次要颜色区分（用户要求第 3 点）
                                color = if (inQueue) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (inQueue) "已在下载队列" else chapter.statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (inQueue) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onDownload(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (selected.isEmpty()) "请选择章节" else "下载选中的 ${selected.size} 话")
                }
                OutlinedButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmButtonColors: ButtonColors = ButtonDefaults.textButtonColors(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm,colors = confirmButtonColors) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.trim().isNotEmpty(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把库里存的 JSON 字符串数组还原成列表；解析失败返回空列表（不抛）。 */
fun parseStringArray(json: String?): List<String> {
    if (json.isNullOrBlank() || json == "[]") return emptyList()
    return runCatching {
        com.haruhi.jmcomic.feature.api.decodeStringList(json)
    }.getOrDefault(emptyList())
}

/**
 * 作者之间的分隔符。
 *
 * 显示上是次要色的一小段 ` / `，但它**属于要复制的整体文案**（`作者A / 作者B`），
 * 所以它和作者名在同一个 AnnotatedString 里，而不是两个 Text 拼出来的。
 */
internal const val AUTHOR_SEPARATOR = " / "

/**
 * 作者行的富文本：每位作者各挂一个 [LinkAnnotation.Clickable]（点谁搜谁），
 * 作者之间是次要色的 [AUTHOR_SEPARATOR]。
 *
 * ## 为什么是 AnnotatedString + LinkAnnotation（用户方案）
 *
 * 这样"逐作者可点"与"整体可复制"不打架：**一份**文本既是若干个链接，又是完整的一串
 * `作者A / 作者B`。换成"每位作者一个 Text"就会把整串切成几段，长按选中只能拿到其中一段。
 *
 * ## 一个必须知道的 Compose 限制
 *
 * 链接的点击区域不是我们自己画的，是 `TextLinkScope` 为每段链接生成的一个 `Box`
 * （`combinedClickable`）。它在**按下时就消费掉 down 事件**，而文字选择的手势
 * （`SelectionController`）要求一个没被消费的 down 才会启动，所以：
 *
 * - **长按作者名本身不会起选，而是点击作者**（跳发现页）——与点一下等价，符合"这行可点"的预期；
 * - 多个作者时，长按作者之间的「 / 」（不是链接）仍能起选，之后按「全选」拿到整串；
 * - **只有一个作者时整行都是链接，这一行就长按不了**。这是已确认接受的取舍：作者行"可点"优先，
 *   且四段各占一个 SelectionContainer，不借别的段落把它带出来（用户要的是每段只复制自己这一段）。
 *
 * @param authors 作者名列表（详情接口 / 库里 JSON 数组解析来的）
 * @param authorColor 作者名颜色（当前是主色，暗示可点）
 * @param separatorColor 分隔符颜色（次要色）
 * @param onAuthorClick 点某位作者：拿这个名字去在线搜索（上层切到发现页并填搜索框，不自动搜）
 */
fun authorLinkText(
    authors: List<String>,
    authorColor: Color,
    separatorColor: Color,
    onAuthorClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    // 每位作者共用同一套链接样式，不要在循环里反复构造
    val linkStyles = TextLinkStyles(style = SpanStyle(color = authorColor))
    authors.forEachIndexed { index, author ->
        if (index > 0) {
            withStyle(SpanStyle(color = separatorColor)) { append(AUTHOR_SEPARATOR) }
        }
        withLink(
            LinkAnnotation.Clickable(
                tag = "author:$author",
                styles = linkStyles,
                linkInteractionListener = { onAuthorClick(author) },
            ),
        ) {
            append(author)
        }
    }
}

/** 人类可读的占用体积。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

/** 本子目录名（详情页展示用）。 */
fun albumFolderLabel(state: AlbumDetailUiState): String =
    state.album?.albumFolderName?.takeIf { it.isNotBlank() }
        ?: state.album?.let { FileNaming.albumFolderName(it.name, it.id.toString()) }
        ?: ""
