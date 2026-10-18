package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * 底部面板里「列表优先」的手势守门人：**列表还能滚的时候，纵向滑动只滚列表，不关面板**。
 *
 * ## 为什么需要它
 *
 * `ModalBottomSheet` 的内容 `Column` 上挂了两套手势（material3 1.4.0 的
 * `ModalBottomSheetContent`）：一个是 `draggable`（按住面板直接拖），另一个是
 * `nestedScroll(ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection)`。
 * 后者对**向下**的残余增量是"直接 `dispatchRawDelta` 进面板的拖拽状态"——
 * 也就是**列表一滚到顶，之后每一像素向下位移都变成面板位移**。
 *
 * 而 panel 的关闭门槛很低（`BottomSheetDefaults.PositionalThreshold = 56.dp`、
 * `VelocityThreshold = 125.dp/s`）：手指往回收一点（约 1 厘米）或轻轻甩一下就够。
 * 表现就是用户想往上翻找更多线路，结果面板先关掉了。
 *
 * ## 它做什么
 *
 * 挂在 `ModalBottomSheet(modifier = …)` 上（比 material3 内部那条连接**更外层**，
 * 所以 `onPreScroll` / `onPreFling` 会**先**拿到增量）：
 *
 * - 向下（`deltaY > 0`）且列表已到顶 → 全部吃掉，面板收不到，纹丝不动；
 * - 其余情况（向上、列表还能滚、滚动来自惯性动画）→ 原样放行。
 *
 * `ModalBottomSheet` 的 `draggable` 是**指针输入**、不走嵌套滚动，所以标题/提示/
 * 把手这些**非滚动区域**仍然可以下滑关面板 —— 正好是"想关就摸上面那段，想翻就划列表"。
 *
 * ## 只在"列表真的能滚"时才生效
 *
 * [shouldConsumeSheetSwipeDown] 里有一条 `listScrollable`：一屏就放得下的短列表
 * （例如设置页的接口线路面板，6 条）守门人完全不介入，行为和以前一模一样
 * （在那上面下滑照样关面板）。这样"防误关"只作用在真正需要滚动的长列表上。
 *
 * ## 用法
 *
 * ```kotlin
 * val listState = rememberLazyListState()
 * ModalBottomSheet(
 *     onDismissRequest = onDismiss,
 *     sheetState = sheetState,
 *     modifier = Modifier.sheetSwipeGuard(listState),
 * ) {
 *     LazyColumn(state = listState) { … }
 * }
 * ```
 *
 * 注意 `listState` 必须**同时**交给守门人和那个 `LazyColumn`，否则守门人不知道
 * 列表滚到哪儿了。
 */
@Composable
fun Modifier.sheetSwipeGuard(listState: LazyListState): Modifier {
    val connection = remember(listState) { SheetSwipeGuard(listState) }
    return this.nestedScroll(connection)
}

/**
 * 这次向下的纵向增量该不该由守门人吃掉（吃掉 = 不给 `ModalBottomSheet`）。
 *
 * 抽成纯函数只为一个目的：**手势本身没法写自动化测试**（这台设备上 Compose UI 测试
 * 拿不到窗口焦点、`adb shell input` 被禁，见 `docs/PROGRESS.md` 的「P2 待验证」），
 * 但这条判定规则可以单独测，见 `SheetGestureTest`。
 *
 * @param deltaY 本次增量（正 = 向下）
 * @param listScrollable 列表内容是否超出可视区（`canScrollForward || canScrollBackward`）
 * @param canScrollBackward 列表是否还能往回滚（即不在顶部）
 */
internal fun shouldConsumeSheetSwipeDown(
    deltaY: Float,
    listScrollable: Boolean,
    canScrollBackward: Boolean,
): Boolean = listScrollable && deltaY > 0f && !canScrollBackward

/** [shouldConsumeSheetSwipeDown] 的薄封装：把 `LazyListState` 的实时状态代进去。 */
private class SheetSwipeGuard(private val listState: LazyListState) : NestedScrollConnection {

    private fun consumeDown(deltaY: Float): Boolean = shouldConsumeSheetSwipeDown(
        deltaY = deltaY,
        // canScrollForward 反映"下面还有内容"，canScrollBackward 反映"上面还有内容"；
        // 两个都是 false = 一屏放得下，此时不介入。
        listScrollable = listState.canScrollForward || listState.canScrollBackward,
        canScrollBackward = listState.canScrollBackward,
    )

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 只处理手指拖动；惯性滚动的增量（SideEffect）一律放行。
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        return if (consumeDown(available.y)) Offset(0f, available.y) else Offset.Zero
    }

    /**
     * 同样的条件也要挡"甩一下"：手指在列表上快速向下一甩时，位移可能没到阈值，
     * 但速度会走 `onPreFling` 交给面板的 `settle`，一样会关掉。
     */
    override suspend fun onPreFling(available: Velocity): Velocity =
        if (consumeDown(available.y)) available else Velocity.Zero
}
