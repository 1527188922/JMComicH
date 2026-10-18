package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 底部面板的**统一外壳**：内容区完全不带手势，**只有顶部把手能下滑关闭**。
 *
 * ## 规则
 *
 * 1. 所有面板都用本组件（`HandleDragOnlyBottomSheet`），别在调用点直接写 `ModalBottomSheet { … }`。
 * 2. 想改面板的 `dragHandle` / `properties` 之类，往本组件加参数，不要在调用点退回材质的原版。
 * 3. 关面板的出口：**拖顶部把手下滑** / 点遮罩 / 返回键 / 面板里自己给的「关闭」「取消」按钮。
 *
 * ## 为什么这么实现（2026-10-19 用户要求）
 *
 * 用户要求：**取消弹框上的上下滑动手势**（所有弹框都要），因为会和面板里列表的上下翻动抢手势；
 * 但**保留"托把手下滑关闭"**。
 *
 * material3 1.4.0 的 `ModalBottomSheet` 把面板拖动挂在**整块内容**（Surface）上，两条通道：
 *
 * | 通道 | 什么时候生效 |
 * |---|---|
 * | `draggable`（指针手势） | 手指落在没有被下面消费掉的地方——标题、提示、按钮行，以及滚不动的短列表 |
 * | `nestedScroll(ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection)` | 列表消费完之后的**残余增量**直接 `dispatchRawDelta` 给面板 |
 *
 * 上一版（`sheetSwipeGuard` / 内层嵌套滚动拦截）只堵了其中一段：向下、且只在"长列表 + 到顶"时才堵。
 * 结果**向上**的手势仍然会走到面板那套逻辑里，列表的上下翻动时不时被抢。
 * 只要面板还开着这套手势，"靠消费事件去抢回来"的打法就永远是一半一半——
 * 命中判定、惯性、拖拽状态机都会插一脚。
 *
 * 所以现在改成**不给面板挂手势**：`sheetGesturesEnabled = false` 会把 Surface 上的
 * `nestedScroll` 和 `draggable` **整套去掉**，内容区（标题 / 按钮 / 列表）上的任何上下滑动
 * 都到不了面板，只归列表自己；上下两个方向都一样。
 *
 * ## 把手怎么还能拖
 *
 * 材质那个 `sheetGesturesEnabled` 会把**把手**的拖动一起关掉，所以把手由本组件自己接管：
 * 在 `dragHandle` 槽里放一个 `Modifier.draggable`，累计向下的位移 / 松手速度，
 * 过了阈值就调 `sheetState.hide()`（材质自己的收起动画），动画结束后再 `onDismissRequest()`。
 * 阈值沿用材质内部的 `PositionalThreshold = 56.dp` / `VelocityThreshold = 125.dp/s`。
 *
 * 把手那一行照旧画 `BottomSheetDefaults.DragHandle()`，外观不变；手指往下拖、松手即关。
 * 点遮罩、返回键、面板里的「关闭」按钮都不变。
 *
 * **代价**：拖动把手时，面板不会实时跟手移动（跟手移动只能由材质内部那套手势提供，
 * 而那正是会抢列表手势的东西）。要恢复跟手就得自己画面板皮肤并手动平移，属于另一件事。
 *
 * ## 内容太高时怎么办
 *
 * 面板本身不是滚动容器（material 的 sheet 内容不可滚），内容比窗口高就会被**裁掉**。
 * 所以内容外面包了一层"兜底滚动"：`weight(1f, fill = false) + verticalScroll`——
 * 内容不高时按内容收缩（观感不变），真的放不下时（超小屏 / 系统大字号）整块内容可以滚，
 * 底部的按钮不会被裁出屏幕。列表自己有上限（`heightIn(max = …)`），
 * 平时只有列表内部在滚；外层只在"整块都放不下"时才接管。
 *
 * ## 用法
 *
 * ```kotlin
 * HandleDragOnlyBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
 *     Column(Modifier.fillMaxWidth()) {
 *         … // 标题、按钮行、LazyColumn 照旧
 *     }
 * }
 * ```
 *
 * 列表状态（`rememberLazyListState()`）该提到面板外面还是照旧提：面板一关就整个移出组合，
 * 提到 `LineSection` 那一层才能在"关掉再打开"之后回到上次看到的那几条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandleDragOnlyBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val distanceThresholdPx = with(density) { HandleDismissDistance.toPx() }
    val velocityThresholdPxPerSecond = with(density) { HandleDismissVelocity.toPx() }
    // 这一次把手拖动累计往下了多少（px）：只有向下的位移计入。
    var draggedDownPx by remember { mutableFloatStateOf(0f) }

    fun dismissAnimated() {
        // 与材质自己在"点遮罩关闭"时做的一致：先播收起动画，动画结束再让调用方移除面板
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // 关键一行：面板自己不再带任何滑动手势（内容区上下滑都不会被面板截走）。
        sheetGesturesEnabled = false,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = rememberDraggableState { delta ->
                            draggedDownPx = (draggedDownPx + delta).coerceAtLeast(0f)
                        },
                        orientation = Orientation.Vertical,
                        onDragStarted = { draggedDownPx = 0f },
                        onDragStopped = { velocity ->
                            val shouldDismiss = shouldDismissByHandleDrag(
                                draggedDownPx = draggedDownPx,
                                velocityPxPerSecond = velocity,
                                distanceThresholdPx = distanceThresholdPx,
                                velocityThresholdPxPerSecond = velocityThresholdPxPerSecond,
                            )
                            draggedDownPx = 0f
                            if (shouldDismiss) dismissAnimated()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 外观与材质默认把手完全一致
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        // 兜底：整块内容比"窗口高度 - 已占"还高时（超小屏 / 系统大字号），
        // 让内容整体可滚，别把底部的按钮裁出屏幕。
        // `weight(1f, fill = false)` 的作用是"最多占满剩余高度，但内容不高时不撑满"——
        // 所以正常尺寸下面板还是按内容收缩，观感不变；只有真的放不下时才会出现外层滚动。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/** 把手下滑多远算"要关"（沿用材质内部 `BottomSheetDefaults.PositionalThreshold` 的 56dp）。 */
private val HandleDismissDistance = 56.dp

/** 把手下滑多快算"要关"（沿用材质内部 `BottomSheetDefaults.VelocityThreshold` 的 125dp/s）。 */
private val HandleDismissVelocity = 125.dp

/**
 * 这次把手拖动该不该关面板。
 *
 * 抽成纯函数只为一个目的：**手势本身没法写自动化测试**（这台设备上 Compose UI 测试拿不到
 * 窗口焦点、`adb shell input` 被禁，见 `docs/PROGRESS.md` 的「P2 待验证」），
 * 但这条判定可以单独测，见 `SheetGestureTest`。
 *
 * @param draggedDownPx 本次拖动累计向下的位移（px，向上为负、已被钳成 0）
 * @param velocityPxPerSecond 松手时的纵向速度（px/s，向下为正）
 * @param distanceThresholdPx 位移阈值
 * @param velocityThresholdPxPerSecond 速度阈值
 */
internal fun shouldDismissByHandleDrag(
    draggedDownPx: Float,
    velocityPxPerSecond: Float,
    distanceThresholdPx: Float,
    velocityThresholdPxPerSecond: Float,
): Boolean = draggedDownPx > distanceThresholdPx || velocityPxPerSecond > velocityThresholdPxPerSecond
