package com.haruhi.jmcomic.feature.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 竖直滚动条：跟着 [LazyListState] 画一根圆角胶囊，**内容没超出视口时什么都不画**。
 *
 * ## 为什么自己画
 *
 * 工程只依赖 `material3`（见 `app/build.gradle.kts`），没有 `androidx.compose.material`，
 * 所以用不了那个包里的 `VerticalScrollbar` + `rememberScrollbarAdapter`；
 * 另外那个实现固定是"轨道 + 方形滑块"，改外观还得另配一套。这里直接按
 * `LazyListState.layoutInfo` 的比例画一根胶囊，外观、宽度、颜色都能跟着主题走。
 *
 * ## 位置怎么算
 *
 * 1. **滑块高度占比** = 视口高 / 内容总高。`layoutInfo` 里没有"内容总高"这个数，
 *    用**可见项的平均高度 × 总条数**估（线路面板的每一行等高，估出来是准的；
 *    行高不齐时也只差零点几个像素）。
 * 2. **进度** = 已经滚过多少 / 还能滚多少。滚过多少 = `首项下标 × 平均行高 - 首项偏移`，
 *    `首项偏移` 在往下滚之后是负数，减掉正好是"滚过去的像素"。
 * 3. 首尾两端**以 `canScrollBackward / canScrollForward` 为准**：估算有误差时
 *    滑块也能稳稳贴到顶 / 贴到底，不会差一小截。
 *
 * ## 外观约定（用户要求"美观、跟风格相符"）
 *
 * - 一根 **3dp 宽的圆角胶囊**贴在右边缘，不加轨道底板——面板本身是白底，
 *   `onSurfaceVariant` 的半透明黑一压就已经够看了，加底板反而显脏；
 * - **静止时淡（[IdleAlpha]）、滑动时加深（[ActiveAlpha]）**，用 200ms 的
 *   `animateFloatAsState` 过渡，既有存在感又不会一直抢视线；
 * - 颜色取主题的 `onSurfaceVariant`，深浅色/动态取色都不用改这里。
 *
 * ## 用法
 *
 * 它是一层**盖在列表上的装饰**，不参与手势（没有 pointerInput），所以放在
 * `Box` 里 `matchParentSize()` 即可：
 *
 * ```kotlin
 * Box(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
 *     LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) { … }
 *     VerticalScrollbar(state = listState, modifier = Modifier.matchParentSize())
 * }
 * ```
 *
 * ⚠ 不要给它 `fillMaxWidth()`：它要贴着右边缘画，宽出去就会把整行内容盖住
 * （虽然不挡手势，但 3dp 的胶囊画在左边会很怪）。宽度由 `thickness + rightInset` 决定。
 *
 * @param state 列表状态（**必须**是本列表自己的，滚动位置从它读）。
 * @param thickness 胶囊粗细，3dp 在 400dp 级面板上刚好，再粗就像系统滚动条了。
 * @param rightInset 距右边缘的距离，留一点缝比贴着边更透气。
 * @param verticalInset 上下各留的空白：胶囊端头不要顶到列表第一行/最后一行。
 * @param minThumbHeight 滑块最短长度：内容特别长时按比例算出来会细成一条线，
 *   给个下限保证它还看得见（长列表会滑得快一点，但不会再变细）。
 */
@Composable
fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    thickness: Dp = 3.dp,
    rightInset: Dp = 3.dp,
    verticalInset: Dp = 6.dp,
    minThumbHeight: Dp = 32.dp,
) {
    // 列表每一项的高度都在变（滚动时首项会变成半行），所以这里**不缓存** metrics：
    // 每次 layoutInfo 变化重算一遍，反正只有几次算术运算。
    val metrics = scrollbarMetrics(state)

    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) ActiveAlpha else IdleAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "verticalScrollbarAlpha",
    )

    // 不溢出 / 还没量出高度：整根滚动条都不画（不是画成全透明，省得白占一层 Canvas）
    if (metrics == null) return

    Canvas(modifier = modifier) {
        val inset = verticalInset.toPx()
        val trackHeight = size.height - inset * 2f
        if (trackHeight <= 0f) return@Canvas

        val barWidth = thickness.toPx()
        val thumbHeight = (trackHeight * metrics.thumbFraction)
            .coerceAtLeast(minThumbHeight.toPx().coerceAtMost(trackHeight))
            .coerceAtMost(trackHeight)
        val thumbTop = inset + (trackHeight - thumbHeight) * metrics.progress
        // 贴着右边缘：x 从「总宽 - 右边距 - 胶囊宽」开始
        val thumbLeft = size.width - rightInset.toPx() - barWidth

        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(thumbLeft, thumbTop),
            size = Size(barWidth, thumbHeight),
            // 圆角取宽度的一半 → 两端是半圆（胶囊形）
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}

/** 静止时滑块的透明度：能看见"这页还有内容"，又不至于像一条黑杠。 */
private const val IdleAlpha = 0.32f

/** 滑动时滑块的透明度：手势进行中加深，松手后自己淡回去。 */
private const val ActiveAlpha = 0.64f

/** 滑块在轨道上的长度占比 + 当前位置（0f 在顶、1f 在底）。 */
private data class ScrollbarMetrics(val thumbFraction: Float, val progress: Float)

/**
 * 从 [LazyListState] 估出滑块的比例与位置；**不需要滚动**（内容没溢出）时返回 null。
 *
 * 纯函数、没有副作用，方便以后要写单元测试时直接拿 state 之外的数据喂进来
 * （现在没有对应测试：`layoutInfo` 只有真跑布局才有值，见 `docs/PROGRESS.md` 的「P2」）。
 */
private fun scrollbarMetrics(state: LazyListState): ScrollbarMetrics? {
    val layoutInfo = state.layoutInfo
    val visible = layoutInfo.visibleItemsInfo
    val total = layoutInfo.totalItemsCount
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()

    // 所有条目都露出来了 = 没有溢出，不需要滚动条
    if (visible.isEmpty() || total <= visible.size || viewport <= 0f) return null

    val averageItemSize = visible.sumOf { it.size }.toFloat() / visible.size
    if (averageItemSize <= 0f) return null

    val contentSize = averageItemSize * total
    val thumbFraction = (viewport / contentSize).coerceIn(0f, 1f)

    val first = visible.first()
    val scrolled = first.index * averageItemSize - first.offset
    val maxScroll = (contentSize - viewport).coerceAtLeast(1f)
    // 两端以"还能不能滚"为准：估算误差不会让滑块在顶端/底端差出一小截
    val progress = when {
        !state.canScrollBackward -> 0f
        !state.canScrollForward -> 1f
        else -> (scrolled / maxScroll).coerceIn(0f, 1f)
    }

    return ScrollbarMetrics(thumbFraction = thumbFraction, progress = progress)
}
