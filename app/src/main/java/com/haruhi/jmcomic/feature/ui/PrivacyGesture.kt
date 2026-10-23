package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 全局双指手势：聚拢 = 开启隐私模式，张开 = 关闭。
 *
 * 只观察、**不消费任何事件**（在 [PointerEventPass.Initial] 上读取）。
 * 根节点如果消费 down 或移动，列表滚动、长按选字、阅读器缩放都会被破坏；
 * 这一点与 `FocusOnTap.kt` 里根节点手势的约束一致。
 *
 * 阈值判定用的是**整次手势的累积缩放**，不是单帧变化：
 * 双指刚放下时的轻微抖动不会触发，必须真的收拢/张开到一定程度。
 * 手指数量变化（第二根按下、一根抬起、第三根加入）时重置累积值，
 * 避免中间插进来的手指把比例算歪。
 *
 * 触发一次后立即结束本次检测（`awaitEachGesture` 会等所有手指抬起再重启），
 * 所以一次捏合只会切换一次状态。
 *
 * 阅读器路由由调用方传 `enabled = false` 禁用：阅读器自己用双指缩放，
 * 从 2x 缩回 1x 的缩放比例必然跨过阈值，会误开隐私模式。
 */
internal fun Modifier.privacyPinchGesture(
    enabled: Boolean,
    onPinchIn: () -> Unit,
    onPinchOut: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled) {
        awaitEachGesture {
            var accumulatedZoom = 1f
            var lastPressedCount = 0

            // requireUnconsumed = false：子节点（列表、输入框、选择手势）先处理也没关系，
            // 这里只是旁听，不对事件做任何消费。
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedCount = event.changes.count { it.pressed }

                if (pressedCount != lastPressedCount) {
                    accumulatedZoom = 1f
                    lastPressedCount = pressedCount
                }

                if (pressedCount >= 2) {
                    accumulatedZoom *= event.calculateZoom()
                    if (accumulatedZoom <= PRIVACY_PINCH_IN_THRESHOLD) {
                        onPinchIn()
                        return@awaitEachGesture
                    }
                    if (accumulatedZoom >= PRIVACY_PINCH_OUT_THRESHOLD) {
                        onPinchOut()
                        return@awaitEachGesture
                    }
                }

                if (!event.changes.any { it.pressed }) return@awaitEachGesture
            }
        }
    }
}

/** 累积缩小到原跨度的 70% 视为「聚拢」。 */
private const val PRIVACY_PINCH_IN_THRESHOLD = 0.7f

/** 累积放大到原跨度的 140% 视为「张开」。 */
private const val PRIVACY_PINCH_OUT_THRESHOLD = 1.4f
