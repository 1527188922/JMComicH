package com.haruhi.jmcomic.feature.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * 漫画阅读器（计划 §9 第 8 条）。
 *
 * 已实现的部分：
 * - **纵向连续**（默认）与**横向分页**两种方式，翻页方向可选右到左；
 * - 单击显隐控件栏；双指缩放、双击放大/还原；缩放后单指拖动看细节，
 *   此时**不带动列表滚动**；
 * - 控件栏：返回、章节名、章节选择、进度、上一话/下一话（未下载的下一话给"下载"出口）；
 * - 位置自动持久化（本子 + 章节 + 图片 + 页内比例），离开页面立刻落库；
 * - 长图用固定的宽高比占位，图片加载完成前后**布局不跳**；
 * - 缺图显示具体页码与重试，不静默跳过。
 *
 * 阅读偏好（方式/方向/背景/亮度/常亮）与 JM 设置共用同一份存储。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onPositionChanged: (pageIndex: Int, offsetRatio: Float) -> Unit,
    onToggleControls: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onShowChapterPicker: () -> Unit,
    onShowSettings: () -> Unit,
    onDismissSheets: () -> Unit,
    /** 某一页进入可视范围，请按需取图（本地优先）。 */
    onRequestPage: (Int) -> Unit,
    onRetryPage: (Int) -> Unit,
    onDownloadChapter: () -> Unit,
    onToggleCollected: () -> Unit,
    onSetReadingMode: (ReadingMode) -> Unit,
    onSetDirection: (ReadingDirection) -> Unit,
    onSetBackground: (ReadingBackground) -> Unit,
    onSetBrightnessFollowSystem: (Boolean) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = readerBackgroundColor(state.preferences.background)
    val contentColor = readerContentColor(state.preferences.background)

    // 屏幕常亮与亮度：直接作用在 Activity 窗口上，离开阅读器立刻还原
    ReaderWindowEffect(state.preferences)

    CompositionLocalProvider(LocalLayoutDirection provides state.preferences.layoutDirection) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(background),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.pages.isEmpty() -> ReaderEmptyState(
                    state = state,
                    contentColor = contentColor,
                    onBack = onBack,
                    onDownloadChapter = onDownloadChapter,
                )

                state.preferences.mode == ReadingMode.HORIZONTAL -> HorizontalReader(
                    state = state,
                    onPositionChanged = onPositionChanged,
                    onToggleControls = onToggleControls,
                    onRequestPage = onRequestPage,
                    onTapRetry = onRetryPage,
                )

                else -> VerticalReader(
                    state = state,
                    onPositionChanged = onPositionChanged,
                    onToggleControls = onToggleControls,
                    onRequestPage = onRequestPage,
                    onTapRetry = onRetryPage,
                )
            }

            // 控件层：单击显隐，隐藏时完全不占位
            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
            ) {
                ReaderTopBar(
                    state = state,
                    contentColor = contentColor,
                    background = background,
                    onBack = onBack,
                    onShowChapterPicker = onShowChapterPicker,
                    onShowSettings = onShowSettings,
                )
            }
            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
            ) {
                ReaderBottomBar(
                    state = state,
                    contentColor = contentColor,
                    background = background,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onDownloadChapter = onDownloadChapter,
                    onSetReadingMode = onSetReadingMode,
                )
            }

            state.message?.let { message ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .zIndex(3f),
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (state.showChapterPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val listState = rememberLazyListState()
        // 内容区（标题、章节列表）不响应上下滑动，只有把手能拖着关（详见 SheetGestures.kt）
        HandleDragOnlyBottomSheet(
            onDismissRequest = onDismissSheets,
            sheetState = sheetState,
        ) {
            ChapterPickerContent(state = state, onOpenChapter = onOpenChapter, listState = listState)
        }
    }

    if (state.showSettings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // 内容区（各项设置）不响应上下滑动，只有把手能拖着关（详见 SheetGestures.kt）
        HandleDragOnlyBottomSheet(onDismissRequest = onDismissSheets, sheetState = sheetState) {
            ReaderSettingsContent(
                preferences = state.preferences,
                onSetDirection = onSetDirection,
                onSetBackground = onSetBackground,
                onSetBrightnessFollowSystem = onSetBrightnessFollowSystem,
                onSetBrightness = onSetBrightness,
                onSetKeepScreenOn = onSetKeepScreenOn,
            )
        }
    }
}

// ======================================================================
// 两种阅读方式
// ======================================================================

@Composable
private fun VerticalReader(
    state: ReaderUiState,
    onPositionChanged: (Int, Float) -> Unit,
    onToggleControls: () -> Unit,
    onRequestPage: (Int) -> Unit,
    onTapRetry: (Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.startPage.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0)),
    )

    // 恢复到"页内位置"：布局完成之后才知道这一页多高，所以先滚到页首再按比例补偿
    LaunchedEffect(state.currentChapter?.chapterId) {
        val target = state.startPage
        if (target <= 0 && state.startOffsetRatio <= 0f) return@LaunchedEffect
        listState.scrollToItem(target.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0)))
        if (state.startOffsetRatio > 0f) {
            val height = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == target }?.size ?: 0
            if (height > 0) {
                listState.scrollToItem(target, (height * state.startOffsetRatio).toInt())
            }
        }
    }

    // 当前页与页内比例：用 snapshotFlow 主动观察，而不是在 composition 里读 layoutInfo
    // （读 layoutInfo 拿到的是过期快照，只有在别的重组顺带读它时才会更新）。
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    LaunchedEffect(listState, state.currentChapter?.chapterId) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val ratio = if (info == null || info.size <= 0) {
                0f
            } else {
                (listState.firstVisibleItemScrollOffset.toFloat() / info.size).coerceIn(0f, 1f)
            }
            listState.firstVisibleItemIndex to ratio
        }
            .distinctUntilChanged()
            .collect { (index, ratio) -> currentOnPositionChanged(index, ratio) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(state.pages, key = { _, page -> page.imageFile }) { _, page ->
            ReaderPageItem(
                page = page,
                onToggleControls = onToggleControls,
                onRequest = { onRequestPage(page.index) },
                onRetry = { onTapRetry(page.index) },
            )
        }
    }
}

@Composable
private fun HorizontalReader(
    state: ReaderUiState,
    onPositionChanged: (Int, Float) -> Unit,
    onToggleControls: () -> Unit,
    onRequestPage: (Int) -> Unit,
    onTapRetry: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = state.startPage.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0)),
        pageCount = { state.pages.size },
    )

    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    LaunchedEffect(pagerState, state.currentChapter?.chapterId) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> currentOnPositionChanged(page, 0f) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        pageSpacing = 4.dp,
    ) { pageIndex ->
        val page = state.pages.getOrNull(pageIndex) ?: return@HorizontalPager
        ReaderPageItem(
            page = page,
            onToggleControls = onToggleControls,
            onRequest = { onRequestPage(page.index) },
            onRetry = { onTapRetry(page.index) },
            fillHeight = true,
        )
    }
}

/**
 * 一页图。
 *
 * 关键点：
 * - 占位用 [ReaderPage.aspectRatio]（图还没加载完时也一样高），长图滚动**不跳**；
 * - **本地没有的图会自动联网取回来**（用户要求第 2 点）：进入可视范围时触发
 *   [onRequest]。`LazyColumn` / `HorizontalPager` 只组合可见项，
 *   所以不会一进阅读器就把整话几十张图全拉下来；
 * - 取的过程中显示进度圈，只有真的失败才显示"取不回来 + 重试"；
 * - 手势：单击显隐控件、双击放大/还原、双指缩放、放大后单指拖动。
 */
@Composable
private fun ReaderPageItem(
    page: ReaderPage,
    onToggleControls: () -> Unit,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
    fillHeight: Boolean = false,
) {
    // 进入可视范围（被组合）就取图；本地已有或正在取时 requestPage 是空操作
    LaunchedEffect(page.imageFile, page.state) {
        if (!page.available && page.state == ReaderPage.PageState.Pending) onRequest()
    }

    val zoom = remember(page.imageFile) { PageZoomState() }
    val modifier = Modifier
        .fillMaxWidth()
        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
        .pageGestures(zoom = zoom, onTap = onToggleControls)

    if (!page.available) {
        Box(
            modifier = modifier
                .aspectRatio(1f / ReaderPage.DEFAULT_ASPECT)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (page.state == ReaderPage.PageState.Failed) {
                    Text(
                        text = "第 ${page.index + 1} 页没取回来",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    page.error?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onRetry) { Text("重试") }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在加载第 ${page.index + 1} 页…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = page.file,
            contentDescription = "第 ${page.index + 1} 页",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / page.aspectRatio)
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
        )
    }
}

// ======================================================================
// 手势
// ======================================================================

/**
 * 单页缩放状态。
 *
 * 为什么自己写而不是用 `Modifier.transformable`：`transformable` 会把单指拖动
 * 也当成平移并消费掉，于是**没放大时列表就再也滚不动了**。
 * 这里只在"双指缩放"或"已放大后的拖动"时才消费事件，其余交给列表。
 */
private class PageZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /**
     * 应用一次缩放/平移。
     *
     * 缩放锚点用**手势中心**：`offset' = centroid - (centroid - offset) * ratio + pan`，
     * 这样手指按住的那个点在图上的位置不动，符合直觉（否则放大时内容会"跑掉"）。
     */
    fun apply(zoomChange: Float, panChange: Offset, centroid: Offset) {
        val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = next / scale
        offset = if (next <= 1f) Offset.Zero else centroid - (centroid - offset) * ratio + panChange
        scale = next
    }

    /** 双击：直接在 1x 与 [target] 之间切换。 */
    fun zoomTo(target: Float, focus: Offset) {
        val next = target.coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = next / scale
        offset = if (next <= 1f) Offset.Zero else focus - (focus - offset) * ratio
        scale = next
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
    }
}

/**
 * 页面手势：单击显隐控件、双击在 1x 与 2.5x 之间切换、双指缩放、放大后单指拖动。
 *
 * 两个 `pointerInput` 分工：第一个只认点击，第二个处理缩放/拖动。
 * 第二个在不缩放时的拖动**不消费**事件，所以列表照常滚动。
 */
private fun Modifier.pageGestures(zoom: PageZoomState, onTap: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { position ->
                if (zoom.scale > 1.01f) zoom.reset() else zoom.zoomTo(DOUBLE_TAP_SCALE, position)
            },
        )
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event: PointerEvent = awaitPointerEvent()
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val zooming = abs(zoomChange - 1f) > ZOOM_EPSILON
                val panning = zoom.scale > 1.01f && panChange != Offset.Zero
                if (zooming || panning) {
                    zoom.apply(zoomChange, panChange, event.calculateCentroid())
                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                }
                if (!event.changes.any { it.pressed }) break
            }
        }
    }

private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_EPSILON = 0.01f

// ======================================================================
// 控件栏
// ======================================================================

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    contentColor: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
    onShowChapterPicker: () -> Unit,
    onShowSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(background.copy(alpha = 0.92f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = contentColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.chapterTitle,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.progressLabel,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
        IconButton(onClick = onShowChapterPicker) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "选择章节", tint = contentColor)
        }
        IconButton(onClick = onShowSettings) {
            Icon(Icons.Default.Settings, contentDescription = "阅读设置", tint = contentColor)
        }
    }
}

/**
 * 阅读器的底部控件（用户要求第 3 点）。
 *
 * 三层信息：
 * 1. **进度条**：当前页 / 总页数，滑动时实时跟着走；
 * 2. 章节名与位置（"第 3 话 · 12/34 页"）；
 * 3. 操作：上一话 / **下载本话** / **阅读方式** / 下一话。
 *
 * 阅读方式按钮直接切换横向 / 纵向两种翻页方式，与阅读设置里共用同一份偏好。
 */
@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    contentColor: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onDownloadChapter: () -> Unit,
    onSetReadingMode: (ReadingMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 顶部放一条细进度条：翻页时进度一眼可见
        LinearProgressIndicator(
            progress = {
                if (state.totalPages <= 0) {
                    0f
                } else {
                    (state.currentPage + 1).toFloat() / state.totalPages.toFloat()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.25f),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${state.chapterTitle} · ${state.pageLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    state.pendingCount > 0 -> "加载中 ${state.pendingCount}"
                    state.failedCount > 0 -> "有 ${state.failedCount} 页失败"
                    state.cachedCount >= state.totalPages && state.totalPages > 0 -> "已缓存"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onPreviousChapter, enabled = state.hasPreviousChapter) {
                Text("上一话", color = contentColor)
            }
            TextButton(onClick = onDownloadChapter) {
                Text("下载本话", color = contentColor)
            }
            TextButton(
                onClick = {
                    onSetReadingMode(
                        if (state.preferences.mode == ReadingMode.HORIZONTAL) {
                            ReadingMode.VERTICAL
                        } else {
                            ReadingMode.HORIZONTAL
                        },
                    )
                },
            ) {
                Text(
                    text = state.preferences.mode.label,
                    color = contentColor,
                )
            }
            TextButton(onClick = onNextChapter, enabled = state.hasNextChapter) {
                Text("下一话", color = contentColor)
            }
        }
    }
}

@Composable
private fun ReaderEmptyState(
    state: ReaderUiState,
    contentColor: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
    onDownloadChapter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.error ?: state.message ?: "这一话还没有可读的图片",
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回", color = contentColor) }
            if (state.currentChapter != null) {
                TextButton(onClick = onDownloadChapter) { Text("下载这一话", color = contentColor) }
            }
        }
    }
}

@Composable
private fun ChapterPickerContent(
    state: ReaderUiState,
    onOpenChapter: (Int) -> Unit,
    /** 列表状态由外层持有：面板关掉再打开时滚动位置还在。 */
    listState: LazyListState,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = "选择章节（共 ${state.chapters.size} 话）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(360.dp)) {
            itemsIndexed(state.chapters, key = { _, chapter -> chapter.chapterId }) { index, chapter ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChapter(index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (index == state.chapterIndex) {
                        Text("当前", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * 阅读设置。
 *
 * 计划 §9 第 8 条要求的四项（亮度、背景、阅读方向、屏幕常亮）都在这里，
 * 且**写入 JM 作用域的同一份偏好**，与 JM 设置页共享。
 */
@Composable
private fun ReaderSettingsContent(
    preferences: ReaderPreferences,
    onSetDirection: (ReadingDirection) -> Unit,
    onSetBackground: (ReadingBackground) -> Unit,
    onSetBrightnessFollowSystem: (Boolean) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("阅读设置", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))

        if (preferences.mode == ReadingMode.HORIZONTAL) {
            SettingChoiceRow(
                label = "翻页方向",
                options = ReadingDirection.entries.map { it to it.label },
                selected = preferences.direction,
                onSelect = onSetDirection,
            )
        }
        SettingChoiceRow(
            label = "背景",
            options = ReadingBackground.entries.map { it to it.label },
            selected = preferences.background,
            onSelect = onSetBackground,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("亮度跟随系统", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = preferences.brightnessFollowSystem, onCheckedChange = onSetBrightnessFollowSystem)
        }
        if (!preferences.brightnessFollowSystem) {
            Text("亮度", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = preferences.brightnessValue,
                onValueChange = onSetBrightness,
                valueRange = 0.05f..1f,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("阅读时保持亮屏", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = preferences.keepScreenOn, onCheckedChange = onSetKeepScreenOn)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun <T> SettingChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                TextButton(onClick = { onSelect(value) }) {
                    Text(
                        text = text,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 屏幕常亮与亮度：作用在宿主 Activity 的窗口上，退出阅读器立刻还原。 */
@Composable
private fun ReaderWindowEffect(preferences: ReaderPreferences) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    if (activity == null) return

    LaunchedEffect(activity, preferences.keepScreenOn) {
        val window = activity.window
        if (preferences.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(activity, preferences.brightnessFollowSystem, preferences.brightnessValue) {
        val window = activity.window
        val params = window.attributes
        params.screenBrightness = if (preferences.brightnessFollowSystem) {
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            preferences.brightnessValue.coerceIn(0.05f, 1f)
        }
        window.attributes = params
    }

    androidx.compose.runtime.DisposableEffect(activity) {
        onDispose {
            val window = activity.window
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window.attributes
            params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
    }
}

/** 从任意 Context 找到宿主 Activity（Compose 的 LocalContext 可能是 ContextWrapper）。 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}
