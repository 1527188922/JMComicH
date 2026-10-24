package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.BuildConfig
import com.haruhi.jmcomic.feature.data.AlbumUsage
import com.haruhi.jmcomic.feature.data.SegmentStats
import com.haruhi.jmcomic.feature.data.StorageKind
import com.haruhi.jmcomic.ui.theme.StorageCovers
import com.haruhi.jmcomic.ui.theme.StorageExportPdf
import com.haruhi.jmcomic.ui.theme.StorageExportZip
import com.haruhi.jmcomic.ui.theme.StorageLibrary
import com.haruhi.jmcomic.ui.theme.StorageReaderCache

/**
 * 「关于」页（「我的 → 关于」，二级页：自带返回键、底栏隐藏）。
 *
 * 两块内容：
 * 1. **应用信息卡**——当前版本 + 检查更新（Gitee 最新发行版 → 应用内下载 → 唤起安装，
 *    状态机见 [UpdateState]；debug 包点击检查无反应）；
 * 2. **存储统计卡**——五类本地占用的分段条（引线指向中文名），点段看按本明细
 *    （大小降序）；封面缓存只有总量不钻取（Coil 磁盘缓存按 URL 哈希命名，无法按本归属）。
 *
 * 布局上整页是**一个 LazyColumn**：明细行可能有几百条，交给 Lazy 复用，
 * 也避免 LazyColumn 嵌 verticalScroll 的滚动冲突。
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    onBack: () -> Unit,
    onSelectSegment: (StorageKind) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onCheckUpdate: () -> Unit,
    onRetryScan: () -> Unit,
    onReopenUpdateDialog: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: (Long) -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onUpdateToBackground: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onOpenReleasesPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 二级页标题行（与最近浏览页同一套）
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
            Text("关于", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { AppInfoCard(state, onCheckUpdate, onReopenUpdateDialog, onInstallUpdate) }
                item { StorageCard(state, onRetryScan, onSelectSegment) }

                state.selected?.let { kind ->
                    val segment = state.storage?.segment(kind)
                    item(key = "usage-header") {
                        UsageHeader(kind, segment, loading = state.storage == null)
                    }
                    val usages = segment?.items
                    when {
                        // 封面缓存：不按本钻取（用户拍板）
                        usages == null -> item(key = "usage-covers") {
                            CoversNote(segment?.totalBytes ?: 0L)
                        }

                        usages.isEmpty() -> item(key = "usage-empty") {
                            Text(
                                text = "这一类暂时没有内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }

                        else -> items(usages) { usage ->
                            UsageRow(usage, onOpenAlbum)
                        }
                    }
                }
            }
        }
    }

    if (state.showUpdateDialog) {
        UpdateDialog(
            state = state,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            onCancelDownload = onCancelUpdateDownload,
            onBackground = onUpdateToBackground,
            onDismiss = onDismissUpdateDialog,
            onOpenReleasesPage = onOpenReleasesPage,
        )
    }

    // 下载完成且弹框还开着 → 直接唤起安装。只认「Downloading → Ready」这一次迁移：
    // 用户取消安装后弹框停在 Ready，重新打开弹框（查看说明）不再自动弹安装器。
    var previousUpdate by remember { mutableStateOf<UpdateState?>(null) }
    LaunchedEffect(state.update) {
        val previous = previousUpdate
        previousUpdate = state.update
        val ready = state.update as? UpdateState.Ready
        if (ready != null && previous is UpdateState.Downloading && state.showUpdateDialog) {
            onInstallUpdate(ready.id)
        }
    }
}

// ======================================================================
// 应用信息卡
// ======================================================================

@Composable
private fun AppInfoCard(
    state: AboutUiState,
    onCheckUpdate: () -> Unit,
    onReopenUpdateDialog: () -> Unit,
    onInstallUpdate: (Long) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("JMComicH", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                // debug 的 VERSION_NAME 带 -debug 后缀，展示时去掉（比较逻辑不受影响）
                text = "当前版本 v${BuildConfig.VERSION_NAME.substringBefore('-')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            when (val update = state.update) {
                UpdateState.Idle -> {
                    TextButton(onClick = onCheckUpdate) { Text("检查更新") }
                }

                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在检查…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UpdateState.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "发现新版本 ${update.tag}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReopenUpdateDialog) { Text("查看") }
                }

                is UpdateState.Downloading -> UpdateProgressRow(update)

                is UpdateState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "新版本 ${update.tag} 已下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onInstallUpdate(update.id) }) { Text("安装") }
                }
            }
        }
    }
}

/** 下载进度（应用信息卡与更新弹框共用）：标题行 + 百分比进度条。 */
@Composable
private fun UpdateProgressRow(update: UpdateState.Downloading) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "正在下载新版本 ${update.tag}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    update.percent != null -> "${update.percent}%"
                    update.totalBytes > 0 -> formatBytes(update.totalBytes)
                    else -> "…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        val progress = update.percent?.let { it / 100f }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 服务器还没给总大小：只知道在动，不知道多远
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// ======================================================================
// 存储统计卡
// ======================================================================

@Composable
private fun StorageCard(
    state: AboutUiState,
    onRetryScan: () -> Unit,
    onSelectSegment: (StorageKind) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("存储占用", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        state.storageFailed -> "读取失败"
                        state.storage == null -> "统计中…"
                        else -> "共 ${formatBytes(state.storage.totalBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val stats = state.storage
            when {
                state.storageFailed -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "本地文件读取失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetryScan) { Text("重试") }
                    }
                }

                // 全 0（刚安装 / 全清过）：不画空条
                stats != null && stats.totalBytes <= 0 -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "暂无本地占用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Spacer(Modifier.height(12.dp))
                    SegmentedStorageBar(
                        segments = stats?.segments.orEmpty(),
                        selected = state.selected,
                        onSelect = onSelectSegment,
                    )
                    if (state.selected == null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "点击图中的一个类型查看明细",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ======================================================================
// 分段条 + 引线标签
// ======================================================================

/** 条高 20dp，但触控区上下各加 10dp（Material 最小触控 48dp 的折中）。 */
private val BAR_HEIGHT = 20.dp
private val BAR_TAP_PADDING = 10.dp

/** 段与标签之间留给引线的走廊高度。 */
private val CORRIDOR_HEIGHT = 16.dp

/** 标签行的最小高度（两行文字）。 */
private val LABEL_MIN_HEIGHT = 34.dp

/** 占比小于这个值的段不画引线标签（窄屏会挤成一团）；段本身仍可点。 */
private const val MIN_LABEL_FRACTION = 0.06f

/** 未选中的段降透明度，衬托当前选中段。 */
private const val UNSELECTED_ALPHA = 0.4f

/**
 * 存储分段条：各段宽度按字节占比分配，引线上下交错指向中文名标签。
 *
 * 实现上全是常规组件 + 一个画线 Canvas：段和标签各自用 `onGloballyPositioned`
 * 把 `boundsInRoot()` 记进 SnapshotStateMap，Canvas 覆盖全区（matchParentSize）
 * 按两组 bounds 画 elbow 折线；滚动 / 重组时 bounds 自动刷新，不需要自研测量。
 */
@Composable
private fun SegmentedStorageBar(
    segments: List<SegmentStats>,
    selected: StorageKind?,
    onSelect: (StorageKind) -> Unit,
) {
    val visible = segments.filter { it.totalBytes > 0 }
    val total = visible.sumOf { it.totalBytes }.coerceAtLeast(1L)
    if (visible.isEmpty()) {
        // 统计中：先画占位空条，数据回来只是变宽（不做整页结构交换）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        )
        return
    }

    val labeled = visible.filter { it.totalBytes.toFloat() / total >= MIN_LABEL_FRACTION }
    // 上/下交错：序号偶数在上、奇数在下；只有一两个标签时全放上面，不留空行
    val topKinds = labeled.mapIndexedNotNull { index, segment ->
        if (index % 2 == 0 || labeled.size <= 2) segment.kind else null
    }.toSet()

    // 引线坐标系：全部取 boundsInRoot，画线时减去外层 Box 的原点换算回局部
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    val segmentBounds = remember { mutableStateMapOf<StorageKind, Rect>() }
    val labelBounds = remember { mutableStateMapOf<StorageKind, Rect>() }
    val idleLineColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { boxOrigin = it.positionInRoot() },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LabelsRow(
                segments = labeled.filter { it.kind in topKinds },
                selected = selected,
                alignment = Alignment.Bottom,
                onSelect = onSelect,
                bounds = labelBounds,
            )
            Spacer(Modifier.height(CORRIDOR_HEIGHT))
            BarRow(visible, selected, onSelect, segmentBounds)
            val bottoms = labeled.filter { it.kind !in topKinds }
            if (bottoms.isNotEmpty()) {
                Spacer(Modifier.height(CORRIDOR_HEIGHT))
                LabelsRow(
                    segments = bottoms,
                    selected = selected,
                    alignment = Alignment.Top,
                    onSelect = onSelect,
                    bounds = labelBounds,
                )
            }
        }
        Canvas(
            modifier = Modifier.matchParentSize(),
        ) {
            drawLeaderLines(labeled, topKinds, segmentBounds, labelBounds, boxOrigin, selected, idleLineColor)
        }
    }
}

/** 分段条本体：`weight(字节数)` 直接按占比分宽，0 段已在上游过滤。 */
@Composable
private fun BarRow(
    segments: List<SegmentStats>,
    selected: StorageKind?,
    onSelect: (StorageKind) -> Unit,
    bounds: MutableMap<StorageKind, Rect>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            // 外层负责热区（上下各扩 10dp），内层才是画出来那 20dp 的色段
            Box(
                modifier = Modifier
                    .weight(segment.totalBytes.coerceAtLeast(1L).toFloat())
                    .height(BAR_HEIGHT + BAR_TAP_PADDING * 2)
                    .clickable { onSelect(segment.kind) },
                contentAlignment = Alignment.Center,
            ) {
                val color = storageColorOf(segment.kind)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .onGloballyPositioned { bounds[segment.kind] = it.boundsInRoot() }
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            color.copy(
                                alpha = if (selected == null || selected == segment.kind) 1f else UNSELECTED_ALPHA,
                            ),
                        ),
                )
            }
        }
    }
}

/** 一排引线标签（名字 + 大小，都可点，与点段等价）。 */
@Composable
private fun LabelsRow(
    segments: List<SegmentStats>,
    selected: StorageKind?,
    alignment: Alignment.Vertical,
    onSelect: (StorageKind) -> Unit,
    bounds: MutableMap<StorageKind, Rect>,
) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LABEL_MIN_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = alignment,
    ) {
        segments.forEach { segment ->
            val isSelected = selected == segment.kind
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .onGloballyPositioned { bounds[segment.kind] = it.boundsInRoot() }
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(segment.kind) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = segment.kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) storageColorOf(segment.kind) else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatBytes(segment.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 画引线：段中点 → 垂直进走廊 → 水平到标签中心 → 垂直到标签边缘，段端画小圆点。 */
private fun DrawScope.drawLeaderLines(
    labeled: List<SegmentStats>,
    topKinds: Set<StorageKind>,
    segmentBounds: Map<StorageKind, Rect>,
    labelBounds: Map<StorageKind, Rect>,
    boxOrigin: Offset,
    selected: StorageKind?,
    idleLineColor: Color,
) {
    val idleStroke = Stroke(width = 1.dp.toPx())
    val activeStroke = Stroke(width = 2.dp.toPx())
    val dotRadius = 2.5.dp.toPx()

    labeled.forEach { segment ->
        val segmentRect = segmentBounds[segment.kind] ?: return@forEach
        val labelRect = labelBounds[segment.kind] ?: return@forEach
        val isTop = segment.kind in topKinds
        val isSelected = selected == segment.kind

        val segmentCenterX = segmentRect.center.x - boxOrigin.x
        val segmentEdgeY = (if (isTop) segmentRect.top else segmentRect.bottom) - boxOrigin.y
        val labelCenterX = labelRect.center.x - boxOrigin.x
        val labelEdgeY = (if (isTop) labelRect.bottom else labelRect.top) - boxOrigin.y
        val corridorMidY = (segmentEdgeY + labelEdgeY) / 2f

        val color = if (isSelected) storageColorOf(segment.kind) else idleLineColor
        val path = Path().apply {
            moveTo(segmentCenterX, segmentEdgeY)
            lineTo(segmentCenterX, corridorMidY)
            lineTo(labelCenterX, corridorMidY)
            lineTo(labelCenterX, labelEdgeY)
        }
        drawPath(path, color = color, style = if (isSelected) activeStroke else idleStroke)
        drawCircle(color = color, radius = dotRadius, center = Offset(segmentCenterX, segmentEdgeY))
    }
}

/** 五类各一色，色值在 `ui/theme/Color.kt`（浅色主题专用，见那里的注释）。 */
private fun storageColorOf(kind: StorageKind): Color = when (kind) {
    StorageKind.LIBRARY -> StorageLibrary
    StorageKind.COVERS -> StorageCovers
    StorageKind.EXPORT_ZIP -> StorageExportZip
    StorageKind.EXPORT_PDF -> StorageExportPdf
    StorageKind.READER_CACHE -> StorageReaderCache
}

// ======================================================================
// 明细列表（钻取）
// ======================================================================

@Composable
private fun UsageHeader(kind: StorageKind, segment: SegmentStats?, loading: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    ) {
        Text(
            text = when {
                loading -> "${kind.label} · 统计中…"
                else -> "${kind.label} · 共 ${formatBytes(segment?.totalBytes ?: 0L)}"
            },
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** 封面缓存不支持钻取时的说明（代替明细列表）。 */
@Composable
private fun CoversNote(totalBytes: Long) {
    Text(
        text = "封面缓存共 ${formatBytes(totalBytes)}，由图片加载器统一管理" +
            "（含头像等图片），不区分单本漫画。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun UsageRow(usage: AlbumUsage, onOpenAlbum: (Long) -> Unit) {
    val navigable = usage.albumId != null
    // 文字隐私：能归属到本子的走统一出口；文件名行单独走 privacyExportName
    val title = if (navigable) {
        privacyAlbumTitle(usage.albumId, usage.title)
    } else {
        usage.title
    }
    val detail = usage.detail?.let { privacyExportName(it, usage.albumId) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = navigable) { onOpenAlbum(usage.albumId) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (usage.albumId != null) {
            Cover(
                albumId = usage.albumId,
                contentDescription = title,
                modifier = Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            // 无法归属的"其他"行：同尺寸占位块，保持列表对齐
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = formatBytes(usage.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ======================================================================
// 更新弹框
// ======================================================================

@Composable
private fun UpdateDialog(
    state: AboutUiState,
    onDownload: () -> Unit,
    onInstall: (Long) -> Unit,
    onCancelDownload: () -> Unit,
    onBackground: () -> Unit,
    onDismiss: () -> Unit,
    onOpenReleasesPage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val update = state.update) {
                    is UpdateState.Available -> {
                        UpdateHeaderRow(update.tag, update.name, update.prerelease)
                        if (update.body.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = update.body.replace("\r\n", "\n"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    is UpdateState.Downloading -> {
                        Text("新版本 ${update.tag}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        UpdateProgressRow(update)
                    }

                    is UpdateState.Ready -> {
                        Text("新版本 ${update.tag}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "安装包已下载完成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Checking / Idle 不会开着弹框，防御性留空
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (val update = state.update) {
                is UpdateState.Available ->
                    if (update.apkUrl != null) {
                        TextButton(onClick = onDownload) { Text("下载") }
                    } else {
                        // 发行版没带 APK 资产：退回浏览器打开发布页
                        TextButton(onClick = onOpenReleasesPage) { Text("打开发布页") }
                    }

                is UpdateState.Downloading ->
                    TextButton(onClick = onBackground) { Text("后台") }

                is UpdateState.Ready ->
                    TextButton(onClick = { onInstall(update.id) }) { Text("安装") }

                else -> Unit
            }
        },
        dismissButton = {
            when (state.update) {
                // 下载中收起弹框 = 后台下载；取消是独立的破坏性动作放这里
                is UpdateState.Downloading ->
                    TextButton(onClick = onCancelDownload) { Text("取消") }

                is UpdateState.Available, is UpdateState.Ready ->
                    TextButton(onClick = onDismiss) { Text("以后再说") }

                else -> Unit
            }
        },
    )
}

/** 弹框内容头部：发行版标题（name）+ 预览版徽标 + 版本号（tag）。 */
@Composable
private fun UpdateHeaderRow(tag: String, name: String, prerelease: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name.ifBlank { tag },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (prerelease) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "预览版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    Spacer(Modifier.height(2.dp))
    Text(
        text = tag,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
