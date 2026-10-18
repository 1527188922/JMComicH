package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 底部面板「只有把手能关」的判定单测。
 *
 * 这里**只测规则**、不测手势本身：这台设备上 Compose UI 测试拿不到窗口焦点、
 * `adb shell input` 被禁（见 `docs/PROGRESS.md` 的「P2 待验证」），
 * 手势只能真机手测；能自动化的就是 [shouldDismissByHandleDrag] 这条阈值规则。
 *
 * 阈值沿用材质内部那两个常量：位移 56dp、速度 125dp/s。测试里用 px 直接给数，
 * 与 dp 无关（阈值由调用方按 density 换算后传进来）。
 *
 * 行为约定（对应 `SheetGestures.kt` 的 `HandleDragOnlyBottomSheet` KDoc）：
 * 1. 往下拖过位移阈值 → 关；
 * 2. 往下甩过速度阈值 → 关（位移不到也算）；
 * 3. 拖得太近、也没速度 → 不关；
 * 4. 往上拖（位移为 0、速度为负）→ 不关。
 */
class SheetGestureTest {

    private val distanceThreshold = 140f // 约 56dp @ 2.5x
    private val velocityThreshold = 312f // 约 125dp/s @ 2.5x

    @Test
    fun `dragging past the distance threshold dismisses`() {
        assertThat(
            shouldDismissByHandleDrag(
                draggedDownPx = distanceThreshold + 1f,
                velocityPxPerSecond = 0f,
                distanceThresholdPx = distanceThreshold,
                velocityThresholdPxPerSecond = velocityThreshold,
            ),
        ).isTrue()
    }

    @Test
    fun `flinging down past the velocity threshold dismisses`() {
        assertThat(
            shouldDismissByHandleDrag(
                draggedDownPx = 1f,
                velocityPxPerSecond = velocityThreshold + 1f,
                distanceThresholdPx = distanceThreshold,
                velocityThresholdPxPerSecond = velocityThreshold,
            ),
        ).isTrue()
    }

    @Test
    fun `a short slow drag does not dismiss`() {
        assertThat(
            shouldDismissByHandleDrag(
                draggedDownPx = distanceThreshold - 1f,
                velocityPxPerSecond = velocityThreshold - 1f,
                distanceThresholdPx = distanceThreshold,
                velocityThresholdPxPerSecond = velocityThreshold,
            ),
        ).isFalse()
    }

    @Test
    fun `dragging the handle up does not dismiss`() {
        // 向上拖：累计位移被钳成 0（向上不累计），速度是负的
        assertThat(
            shouldDismissByHandleDrag(
                draggedDownPx = 0f,
                velocityPxPerSecond = -500f,
                distanceThresholdPx = distanceThreshold,
                velocityThresholdPxPerSecond = velocityThreshold,
            ),
        ).isFalse()
    }
}
