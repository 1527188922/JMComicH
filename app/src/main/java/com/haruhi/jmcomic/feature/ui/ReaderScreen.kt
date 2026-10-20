package com.haruhi.jmcomic.feature.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
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
 * - 单击显隐控件栏；**双指缩放整块阅读区域**（不是单张图），放大后**仍能正常
 *   上下滑动 / 左右翻页**（单指主轴交给列表），翻页时保持放大倍数；
 *   双指拖动或交叉轴拖动用来查看放大后被裁掉的边缘；
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
    /** 图片清单都没拿到时，重新加载当前话。 */
    onRetryLoad: () -> Unit,
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
        // 双指缩放作用于**整块阅读区域**（用户要求：不是单张图片各自放大）。
        // 状态放在这一层：外层手势层修改它，内层 graphicsLayer 把整块内容一起变换。
        val zoom = remember { ReaderZoomState() }
        // 换话 / 换阅读方式时回到 1x，免得新的一话一进来就是放大的
        LaunchedEffect(state.currentChapter?.chapterId, state.preferences.mode) { zoom.reset() }
        // 放大后左右翻页：倍数**保持**（用户报过"翻到另一页大小复原"），
        // 只把平移量归零，让新的一页仍然从正中开始看。
        LaunchedEffect(state.currentPage, state.preferences.mode) {
            if (state.preferences.mode == ReadingMode.HORIZONTAL) zoom.resetPan()
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(background),
        ) {
            // 手势层**不带缩放**：坐标就是屏幕坐标，缩放锚点（手指中心）才好算；
            // 内容层再加 graphicsLayer，整块（列表 / 分页器）一起放大、一起平移。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readerGestures(
                        zoom = zoom,
                        // 内容自己滚动的方向：纵向列表上下滚、横向分页器左右翻
                        scrollAxis = if (state.preferences.mode == ReadingMode.HORIZONTAL) {
                            Orientation.Horizontal
                        } else {
                            Orientation.Vertical
                        },
                        onTap = onToggleControls,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        },
                ) {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        state.pages.isEmpty() -> ReaderEmptyState(
                            state = state,
                            contentColor = contentColor,
                            onBack = onBack,
                            onRetry = onRetryLoad,
                        )

                        state.preferences.mode == ReadingMode.HORIZONTAL -> HorizontalReader(
                            state = state,
                            onPositionChanged = onPositionChanged,
                            onRequestPage = onRequestPage,
                            onTapRetry = onRetryPage,
                        )

                        else -> VerticalReader(
                            state = state,
                            onPositionChanged = onPositionChanged,
                            onRequestPage = onRequestPage,
                            onTapRetry = onRetryPage,
                        )
                    }
                }
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
 * - 点击 / 缩放这类手势**不在这里**：由阅读器外层统一处理，缩放作用于整块阅读区域
 *   （用户要求：双指放大是整个页面一起放大，不是单张图片单独放大）。
 */
@Composable
private fun ReaderPageItem(
    page: ReaderPage,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
    fillHeight: Boolean = false,
) {
    // 进入可视范围（被组合）就取图；本地已有或正在取时 requestPage 是空操作
    LaunchedEffect(page.imageFile, page.state) {
        if (!page.available && page.state == ReaderPage.PageState.Pending) onRequest()
    }

    val modifier = Modifier
        .fillMaxWidth()
        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)

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
                .aspectRatio(1f / page.aspectRatio),
        )
    }
}

// ======================================================================
// 手势
// ======================================================================

/**
 * 阅读器整体的缩放状态（用户要求：双指放大是**整块阅读区域**一起放大，
 * 不是单张图片各自放大）。
 *
 * 缩放状态**跨页保持**：放大后左右翻页，新的一页仍是同样的倍数（不会"大小复原"），
 * 只有换话 / 换阅读方式才回到 1x（见 `ReaderScreen` 里的 `LaunchedEffect`）。
 *
 * 为什么自己写而不是用 `Modifier.transformable`：`transformable` 会把单指拖动
 * 也当成平移并消费掉，于是**没放大时列表就再也滚不动了**。
 * 这里只在"双指缩放 / 双指拖动"时才消费事件，其余交给列表。
 */
private class ReaderZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /** 放大过没有：只有放大状态下才处理平移（否则交给列表滚动）。 */
    val isZoomed: Boolean get() = scale > ZOOMED_THRESHOLD

    /**
     * 应用一次缩放/平移。
     *
     * [centroid] 传的是**相对视口中心**的手势中心（`graphicsLayer` 的默认
     * transformOrigin 就是图层中心，两边用同一个原点才对得上）：
     * `offset' = centroid - (centroid - offset) * ratio + pan`，
     * 这样手指按住的那个点在屏幕上的位置不动，符合直觉（否则放大时内容会"跑掉"）。
     *
     * 平移量按放大倍数夹住，放大后内容不会被拖出屏幕。
     */
    fun apply(zoomChange: Float, panChange: Offset, centroid: Offset, viewport: Size) {
        val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = next / scale
        offset = if (next <= MIN_SCALE) {
            Offset.Zero
        } else {
            (centroid - (centroid - offset) * ratio + panChange).clampToViewport(next, viewport)
        }
        scale = next
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 只把平移量归零（缩放倍数保留）：翻到新的一页时让内容重新居中。 */
    fun resetPan() {
        offset = Offset.Zero
    }

    /**
     * 限制平移量：放大 `s` 倍后，内容边缘正好贴着视口边缘，
     * 所以每个方向最多能平移 `(s - 1) * 尺寸 / 2`。
     */
    private fun Offset.clampToViewport(scale: Float, viewport: Size): Offset {
        val maxX = ((scale - 1f) * viewport.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - 1f) * viewport.height / 2f).coerceAtLeast(0f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
    }
}

/**
 * 阅读区域的手势：单击显隐控件、双指缩放整块内容、双指拖动平移。
 *
 * **没有双击放大**（用户要求去掉）。
 *
 * 关键点（上一版把单指拖动全消费掉，结果**放大后既滑不动也翻不了页**，用户报的 bug）：
 * - 缩放由内层 `graphicsLayer` 作用在整块内容上，不是单张图；
 * - 事件在 [PointerEventPass.Initial] 读，但**单指拖动一律不消费**：
 *   主轴（纵向模式 = 上下、横向模式 = 左右）原样交给列表 / 分页器，
 *   所以放大后照样能往下滑、能左右翻页；我们只把**交叉轴**的位移当成平移
 *   （纵向模式里左右拖、横向模式里上下拖），拿来查看放大后被裁掉的边缘；
 * - 双指拖动 = 两个方向都能平移；缩放和双指拖动都要消费，否则列表会跟着滚。
 *
 * [scrollAxis] 是内容自己滚动的方向（纵向列表 / 横向分页器），用来区分主轴与交叉轴。
 */
private fun Modifier.readerGestures(
    zoom: ReaderZoomState,
    scrollAxis: Orientation,
    onTap: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onTap = { onTap() })
    }
    .pointerInput(scrollAxis) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val viewport = Size(size.width.toFloat(), size.height.toFloat())
            val viewportCenter = Offset(size.width / 2f, size.height / 2f)
            while (true) {
                val event: PointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val pressed = event.changes.count { it.pressed }
                val zooming = abs(zoomChange - 1f) > ZOOM_EPSILON
                val twoFingerPanning = pressed > 1 && panChange != Offset.Zero
                if (zooming || twoFingerPanning) {
                    zoom.apply(
                        zoomChange = zoomChange,
                        panChange = panChange,
                        centroid = event.calculateCentroid() - viewportCenter,
                        viewport = viewport,
                    )
                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                } else if (pressed == 1 && panChange != Offset.Zero && zoom.isZoomed) {
                    // 单指：主轴留给翻页 / 滚动（**不消费**），只平移交叉轴
                    val crossPan = when (scrollAxis) {
                        Orientation.Vertical -> Offset(panChange.x, 0f)
                        Orientation.Horizontal -> Offset(0f, panChange.y)
                    }
                    if (crossPan != Offset.Zero) {
                        // zoomChange = 1、centroid = 0：只加平移量，不动缩放
                        zoom.apply(1f, crossPan, Offset.Zero, viewport)
                    }
                }
                if (!event.changes.any { it.pressed }) break
            }
        }
    }

private const val ZOOM_EPSILON = 0.01f

/** 超过这个倍数才算"放大状态"（浮点误差容差），放大后才处理平移。 */
private const val ZOOMED_THRESHOLD = 1.01f

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
 *
 * 箭头用 `Icons.Default.KeyboardArrowLeft / KeyboardArrowRight`（用户点名要这两个）：
 * 它们表达的是"上一话 / 下一话"的固定语义，**不跟着阅读方向镜像**，
 * 所以不换成 AutoMirrored 版本（那会在 RTL 下左右翻转）。
 */
@Suppress("DEPRECATION")
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
        // 四个按钮位**固定各占 1/4**：没有上一话 / 下一话时只隐藏按钮本身，
        // 槽位照样留着，否则「下载本话 / 阅读方式」会顶过去（用户要求第 1 点）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ReaderBarSlot {
                if (state.hasPreviousChapter) {
                    TextButton(onClick = onPreviousChapter, contentPadding = ReaderBarButtonPadding) {
                        // 图标在文字左侧，间距与「下一话」用同一份 ReaderBarButtonGap
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(ReaderBarIconSize),
                        )
                        Spacer(Modifier.width(ReaderBarButtonGap))
                        ReaderBarButtonText("上一话", contentColor)
                    }
                }
            }
            ReaderBarSlot {
                TextButton(onClick = onDownloadChapter, contentPadding = ReaderBarButtonPadding) {
                    ReaderBarButtonText("下载本话", contentColor)
                }
            }
            ReaderBarSlot {
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
                    contentPadding = ReaderBarButtonPadding,
                ) {
                    ReaderBarButtonText(state.preferences.mode.label, contentColor)
                }
            }
            ReaderBarSlot {
                if (state.hasNextChapter) {
                    TextButton(onClick = onNextChapter, contentPadding = ReaderBarButtonPadding) {
                        // 图标在文字右侧，间距与「上一话」用同一份 ReaderBarButtonGap
                        ReaderBarButtonText("下一话", contentColor)
                        Spacer(Modifier.width(ReaderBarButtonGap))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(ReaderBarIconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底栏的一个按钮槽位：宽度固定为 1/4，内容可以缺席（按钮隐藏），但槽位不会塌。
 *
 * 这样四个槽位的位置永远不变，上一话 / 下一话隐藏时其余按钮不会顶过去。
 */
@Composable
private fun RowScope.ReaderBarSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 底栏按钮文字：单行 + 收紧左右内边距。
 *
 * 四等分之后单槽最多约 80dp，默认的 12dp 内边距下「下载本话」「上下翻页」这类
 * 四字文案在窄屏上会折行把底栏顶高，所以这里把内边距收到 2dp 并限制单行。
 */
@Composable
private fun ReaderBarButtonText(text: String, contentColor: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = contentColor,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/** 底栏按钮内边距：四等分槽位里要留够图标 + 文字宽度（见 [ReaderBarButtonText]）。 */
private val ReaderBarButtonPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)

/**
 * 上一话 / 下一话按钮里**文字与箭头的间距**。
 *
 * 两个按钮共用这一个值，所以「上一话◀」和「下一话▶」的图标距离完全一致。
 */
private val ReaderBarButtonGap = 2.dp

/** 底栏箭头图标尺寸：四等分槽位里要留够文字宽度。 */
private val ReaderBarIconSize = 20.dp

@Composable
private fun ReaderEmptyState(
    state: ReaderUiState,
    contentColor: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
    onRetry: () -> Unit,
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
            TextButton(onClick = onRetry) { Text("重试", color = contentColor) }
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
        // 列表 + 右侧滚动条（几百话时得让人一眼看出"下面还有"）；
        // 高度沿用固定的 360dp，面板尺寸每次打开都一样，不会因为章节数忽高忽低。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
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
            // 内容没超出时它什么都不画（见 Scrollbar.kt）
            VerticalScrollbar(state = listState, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * 阅读设置。
 *
 * 计划 §9 第 8 条要求的四项（亮度、背景、阅读方向、屏幕常亮）都在这里，
 * 且**写入 JM 作用域的同一份偏好**，与 JM 设置页共享。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
            // 与应用设置页共用同一套空心圆点滑块 + 轨道样式（DotSlider，见 SettingsScreen.kt）
            DotSlider(
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
