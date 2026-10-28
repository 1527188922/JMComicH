package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import com.haruhi.jmcomic.ui.theme.StorageOfficialZip
import com.haruhi.jmcomic.ui.theme.StorageReaderCache

/**
 * 「关于」页（「我的 → 关于」，二级页：自带返回键、底栏隐藏）。
 *
 * 两块内容：
 * 1. **应用信息卡**——当前版本 + 检查更新（Gitee 最新发行版 → 应用内下载 → 唤起安装，
 *    状态机见 [UpdateState]；debug 包点击检查无反应）；
 * 2. **存储统计卡**——五类本地占用的分段条（**每一类都画引线 + 名字 + 大小**，
 *    含只占百分之几的 zip / pdf），点段看按本明细（大小降序，明细是一张带分隔线的
 *    白卡，操作按钮在每一行里）；封面缓存只有总量不钻取
 *    （Coil 磁盘缓存按 URL 哈希命名，无法按本归属）。
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
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: (Long) -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onUpdateToBackground: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onOpenReleasesPage: () -> Unit,
    /** 明细行操作：zip / pdf 的「打开至」（发系统 Intent，上层接线）。 */
    onOpenOutput: (String) -> Unit,
    /** 明细行操作：zip / pdf 的「发送至」。 */
    onSendOutput: (String) -> Unit,
    /** 明细行操作：删除某一行的内容（界面已弹过确认框）。 */
    onDeleteUsage: (StorageKind, AlbumUsage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待确认的删除目标（弹框是整页一份；确认后才调 ViewModel）
    var pendingDelete by remember { mutableStateOf<Pair<StorageKind, AlbumUsage>?>(null) }

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
                item { AppInfoCard(state, onCheckUpdate) }
                item { StorageCard(state, onRetryScan, onSelectSegment) }

                state.selected?.let { kind ->
                    val segment = state.storage?.segment(kind)
                    val usages = segment?.items

                    // 明细是一张白卡：表头 + 若干行，行间画分隔线。
                    // 行数可能上百，只能一行一个 Lazy item（见 UsageCardItem 的注释），
                    // 所以卡片的圆角按"第一行圆上角、最后一行圆下角"拼出来。
                    item(key = "usage-header") {
                        UsageCardItem(
                            top = true,
                            divider = true,
                            modifier = Modifier.padding(top = 20.dp),
                        ) {
                            UsageSectionHeader(
                                kind = kind,
                                segment = segment,
                                loading = state.storage == null,
                            )
                        }
                    }

                    when {
                        // 封面缓存：不按本钻取（用户拍板）
                        usages == null -> item(key = "usage-covers") {
                            UsageCardItem(bottom = true) {
                                UsageNote(
                                    text = if (segment == null) {
                                        "统计中…"
                                    } else {
                                        "封面缓存共 ${formatBytes(segment.totalBytes)}（含头像等图片）"
                                    },
                                )
                            }
                        }

                        usages.isEmpty() -> item(key = "usage-empty") {
                            UsageCardItem(bottom = true) {
                                UsageNote("这一类暂时没有内容")
                            }
                        }

                        else -> itemsIndexed(usages) { index, usage ->
                            val last = index == usages.lastIndex
                            UsageCardItem(bottom = last, divider = !last) {
                                UsageRow(
                                    usage = usage,
                                    kind = kind,
                                    onOpenAlbum = onOpenAlbum,
                                    onOpenOutput = onOpenOutput,
                                    onSendOutput = onSendOutput,
                                    onRequestDelete = { pendingDelete = kind to usage },
                                )
                            }
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

    // 删除确认：与书库批量删除同一款 ConfirmDialog
    pendingDelete?.let { (kind, usage) ->
        val title = if (usage.albumId != null) privacyAlbumTitle(usage.albumId, usage.title) else usage.title
        val fileName = usage.detail?.let { privacyExportName(it, usage.albumId) }
        ConfirmDialog(
            title = when (kind) {
                StorageKind.LIBRARY -> "删除这本的下载图片？"
                StorageKind.READER_CACHE -> "删除这本的阅读缓存？"
                StorageKind.OFFICIAL_ZIP -> "删除这本的官方 ZIP？"
                else -> "删除导出文件？"
            },
            text = when (kind) {
                StorageKind.LIBRARY ->
                    "会删除《$title》的本地图片（约 ${formatBytes(usage.sizeBytes)}）。" +
                        "导出的 zip / pdf 与阅读缓存不受影响。"
                StorageKind.READER_CACHE ->
                    "会删除《$title》的阅读器缓存（约 ${formatBytes(usage.sizeBytes)}）。" +
                        "下次阅读时重新联网获取。"
                StorageKind.OFFICIAL_ZIP ->
                    "会删除《$title》在官方zip目录里的 ${usage.detail ?: "zip 包"}" +
                        "（约 ${formatBytes(usage.sizeBytes)}）。解压出来的文件夹不受影响。"
                else ->
                    "会删除 ${fileName ?: usage.title}（约 ${formatBytes(usage.sizeBytes)}）。此操作不可撤销。"
            },
            confirmText = "删除",
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onConfirm = {
                pendingDelete = null
                onDeleteUsage(kind, usage)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ======================================================================
// 应用信息卡
// ======================================================================

@Composable
private fun AppInfoCard(
    state: AboutUiState,
    onCheckUpdate: () -> Unit,
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

            // 版本行：外部动作永远只有「检查更新」（检查中换成转圈文案）。
            // 「发现新版本 / 已下载 / 安装」都只出现在检查后的弹框里，
            // 避免下载完不装时页面长期挂着一个安装入口。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前版本 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.checking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                } else {
                    TextButton(onClick = onCheckUpdate) { Text("检查更新") }
                }
            }

            // 下载进度要整行进度条，这一行放不下：放到版本行下面独占一行。
            // 进度条不是按钮，外部仍然只有「检查更新」。
            val downloading = state.update as? UpdateState.Downloading
            if (downloading != null) {
                Spacer(Modifier.height(6.dp))
                UpdateProgressRow(downloading)
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

/** 同一侧（上 / 下）多条引线的水平段彼此错开的量：全叠在同一个 Y 上会糊成一条线。 */
private val LINE_STAGGER = 4.dp

/** 未选中的段降透明度，衬托当前选中段。 */
private const val UNSELECTED_ALPHA = 0.4f

/**
 * 存储分段条：各段宽度按字节占比分配，引线上下交错指向中文名标签。
 *
 * **每一段都有标签**（不按占比过滤）：zip / pdf 这类小段常常只占几个百分点，
 * 早先按阈值过滤掉之后，用户只看得到一截色段、看不到它是什么、有多大。
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

    // **每一段都要引线标签**：zip / pdf 这类小段常常只占百分之几，
    // 早先按"占比 < 6% 不画"过滤掉，结果用户只看到那一小截色段、
    // 既没有名字也没有大小。宽度不再参与过滤，挤不挤由 LabelsRow 的等分布局兜着。
    val labeled = visible
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

/**
 * 一排引线标签（名字 + 大小，都可点，与点段等价）。
 *
 * 每个标签**等分整行宽度**（不是按文字宽度顺次摆）：一侧最多挂 3 个标签，
 * 文字宽度累加在窄屏上会顶出右边；等分之后标签必然放得下，
 * 引线端点（标签中心）也均匀分布，几条线不会挤在一起。
 */
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
        verticalAlignment = alignment,
    ) {
        segments.forEach { segment ->
            val isSelected = selected == segment.kind
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { bounds[segment.kind] = it.boundsInRoot() }
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(segment.kind) }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                Text(
                    text = segment.kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) storageColorOf(segment.kind) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(segment.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    val stagger = LINE_STAGGER.toPx()

    // 同一侧的引线都从条的同一条边出发、也都在同一条标签边收口，拐弯的 Y 也就一样高；
    // 五段全画之后，几条水平段会完全重合叠成一条。按这一侧的顺序把拐弯高度错开，
    // 看起来才像一把扇形引线（每个标签仍在走廊范围内，不会压到段或标签）。
    val topOrder = labeled.filter { it.kind in topKinds }.map { it.kind }
    val bottomOrder = labeled.filterNot { it.kind in topKinds }.map { it.kind }

    labeled.forEach { segment ->
        val segmentRect = segmentBounds[segment.kind] ?: return@forEach
        val labelRect = labelBounds[segment.kind] ?: return@forEach
        val isTop = segment.kind in topKinds
        val isSelected = selected == segment.kind
        val sideOrder = if (isTop) topOrder else bottomOrder
        val sideIndex = sideOrder.indexOf(segment.kind).coerceAtLeast(0)

        val segmentCenterX = segmentRect.center.x - boxOrigin.x
        val segmentEdgeY = (if (isTop) segmentRect.top else segmentRect.bottom) - boxOrigin.y
        val labelCenterX = labelRect.center.x - boxOrigin.x
        val labelEdgeY = (if (isTop) labelRect.bottom else labelRect.top) - boxOrigin.y
        // 以这一侧的中位序号为 0 向两边铺开，整组引线仍然落在走廊中间
        val staggerY = (sideIndex - (sideOrder.size - 1) / 2f) * stagger
        val corridorMidY = (segmentEdgeY + labelEdgeY) / 2f + staggerY

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

/** 六类各一色，色值在 `ui/theme/Color.kt`（浅色主题专用，见那里的注释）。 */
private fun storageColorOf(kind: StorageKind): Color = when (kind) {
    StorageKind.LIBRARY -> StorageLibrary
    StorageKind.COVERS -> StorageCovers
    StorageKind.EXPORT_ZIP -> StorageExportZip
    StorageKind.EXPORT_PDF -> StorageExportPdf
    StorageKind.OFFICIAL_ZIP -> StorageOfficialZip
    StorageKind.READER_CACHE -> StorageReaderCache
}

// ======================================================================
// 明细列表（钻取）
// ======================================================================

/** 明细卡片的圆角（与 `MaterialTheme.shapes.medium` 的 12dp 一致）。 */
private val CARD_CORNER = 12.dp

/**
 * 明细列表的白卡片段。
 *
 * 明细多的时候有几百行，只能是 LazyColumn 里的独立 item，没法用一张 `Card` 把整段包住；
 * 于是每一行自己画白底，只让**第一行圆上角、最后一行圆下角**，拼起来仍是一张完整的卡。
 * [divider] 在行底部画一条分隔线（卡片最后一行不画）。
 */
@Composable
private fun UsageCardItem(
    top: Boolean = false,
    bottom: Boolean = false,
    divider: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = if (top) CARD_CORNER else 0.dp,
        topEnd = if (top) CARD_CORNER else 0.dp,
        bottomStart = if (bottom) CARD_CORNER else 0.dp,
        bottomEnd = if (bottom) CARD_CORNER else 0.dp,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(color = MaterialTheme.colorScheme.surface, shape = shape),
    ) {
        content()
        if (divider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 明细卡片表头：类型色点 + 类型名 + 总量（排版与「存储占用」卡的标题行一致）。 */
@Composable
private fun UsageSectionHeader(kind: StorageKind, segment: SegmentStats?, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 色点取分段条里那一段的颜色，一眼能对上刚才点的是哪一块
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(storageColorOf(kind)),
        )
        Spacer(Modifier.width(8.dp))
        Text(kind.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            text = if (loading) "统计中…" else "共 ${formatBytes(segment?.totalBytes ?: 0L)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 明细卡里的一行纯文字（封面缓存的说明、空态）：不再单独划一个区域出来。 */
@Composable
private fun UsageNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/**
 * 明细里的一行：封面 + 标题 / 文件名 / 大小 + 右侧操作按钮，全在同一张白卡的一条里。
 *
 * 排版要点：
 * - **顶部对齐**（`Alignment.Top`）：标题贴着封面顶边走，不再相对封面上下居中——
 *   省出来的垂直空间正好放操作按钮，按钮因此落在这一行的范围内
 *   （以前是内容行 + 行下面另起一排按钮，按钮看着像挂在别的行上）；
 * - 大小挪到标题同一行的右端，跟标题基线对齐，不再单独占一块；
 * - 操作按段给（与详情页「导出文件」区块同一款按钮）：zip / pdf 是打开至 / 发送至 / 删除；
 *   漫画图片 / 阅读缓存只有删除（每行只删它代表的那一块，删除前弹确认框）。
 */
@Composable
private fun UsageRow(
    usage: AlbumUsage,
    kind: StorageKind,
    onOpenAlbum: (Long) -> Unit,
    onOpenOutput: (String) -> Unit,
    onSendOutput: (String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val navigable = usage.albumId != null
    // 文字隐私：能归属到本子的走统一出口；文件名行单独走 privacyExportName
    val title = if (navigable) {
        privacyAlbumTitle(usage.albumId, usage.title)
    } else {
        usage.title
    }
    val detail = usage.detail?.let { privacyExportName(it, usage.albumId) }
    val exportRow = kind == StorageKind.EXPORT_ZIP || kind == StorageKind.EXPORT_PDF

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // enabled 已经挡掉了不可点的行，`?.let` 只是把 Long? 收成 Long
            .clickable(enabled = navigable) { usage.albumId?.let(onOpenAlbum) }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (usage.albumId != null) {
            Cover(
                albumId = usage.albumId,
                contentDescription = title,
                modifier = Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // 白卡上的封面占位：outlineVariant 是浅灰，surfaceVariant 在浅色主题里就是白
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        } else {
            // 无法归属的"其他"行：同尺寸占位块，保持列表对齐
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatBytes(usage.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // TextButton 自带 12dp 内容左/右内边距：补上这 12dp，大小才和下面
                    // 按钮的文字右边缘对齐（否则会往外多出 12dp，看着像没对齐）
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 操作按钮：右对齐、就在这一行里，样式与详情页「导出文件」区块一致
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                if (exportRow) {
                    // 打开/发送要传**真实文件名**（privacyExportName 只换显示，不动操作）
                    usage.detail?.let { fileName ->
                        TextButton(onClick = { onOpenOutput(fileName) }) { Text("打开至") }
                        TextButton(onClick = { onSendOutput(fileName) }) { Text("发送至") }
                    }
                }
                TextButton(
                    onClick = onRequestDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text("删除")
                }
            }
        }
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
        title = {
            val tag = when (val update = state.update) {
                is UpdateState.Available -> update.tag
                is UpdateState.Downloading -> update.tag
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (tag.isNullOrBlank()) "发现新版本" else "发现新版本 $tag",
                    modifier = Modifier.weight(1f, fill = false),
                )
                if ((state.update as? UpdateState.Available)?.prerelease == true) {
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
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val update = state.update) {
                    is UpdateState.Available -> {
                        if (update.name.isNotBlank()) {
                            Text(
                                text = update.name,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        if (update.body.isNotBlank()) {
                            if (update.name.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = update.body.replace("\r\n", "\n"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (update.downloadedId != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "安装包已下载完成，可直接安装。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is UpdateState.Downloading -> {
                        UpdateProgressRow(update)
                    }

                    // Idle 不会开着弹框，防御性留空
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (val update = state.update) {
                is UpdateState.Available -> {
                    // 同一次检查结果里，按钮按“安装包是否已下载”切换：下载 → 安装
                    val downloadedId = update.downloadedId
                    val apkUrl = update.apkUrl
                    when {
                        downloadedId != null ->
                            TextButton(onClick = { onInstall(downloadedId) }) { Text("安装") }

                        apkUrl != null ->
                            TextButton(onClick = onDownload) { Text("下载") }

                        else ->
                            // 发行版没带 APK 资产：退回浏览器打开发布页
                            TextButton(onClick = onOpenReleasesPage) { Text("打开发布页") }
                    }
                }

                is UpdateState.Downloading ->
                    TextButton(onClick = onBackground) { Text("后台") }

                else -> Unit
            }
        },
        dismissButton = {
            when (state.update) {
                // 下载中收起弹框 = 后台下载；取消是独立的破坏性动作放这里
                is UpdateState.Downloading ->
                    TextButton(onClick = onCancelDownload) { Text("取消") }

                is UpdateState.Available ->
                    TextButton(onClick = onDismiss) { Text("以后再说") }

                else -> Unit
            }
        },
    )
}

