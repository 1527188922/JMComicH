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
 * 在 [PointerEventPass.Initial] 上处理，且**双指一旦成立就消费这次手势的剩余事件**。
 * 只旁听不消费的写法看似安全，实际会让第二根手指照样参与子节点手势：
 * 双指落在卡片上会点开卡片、落在列表上会继续滚动、落在按钮上会触发 ripple 甚至点击，
 * 这正是用户反馈的误触。
 *
 * 消费范围刻意压到最小：
 * - 第二根手指按下之前**完全不消费**，单指点击、滚动、长按选字都不受影响
 *   （根部 `FocusOnTap.kt` 的单击失焦也照常工作）；
 * - 多指阶段一直消费到所有手指抬起，**包括触发回调之后**。若触发后立刻结束检测，
 *   第一根手指的 up 会漏给子节点，已经按下的 clickable 仍可能完成一次点击。
 *
 * 阈值判定用的是**整次手势的累积缩放**，不是单帧变化：
 * 双指刚放下时的轻微抖动不会触发，必须真的收拢/张开到一定程度。
 * 手指数量变化（第二根按下、一根抬起、第三根加入）时重置累积值，
 * 避免中间插进来的手指把比例算歪。同一次手势只切换一次状态。
 *
 * 阅读器路由由调用方传 `enabled = false` 禁用：阅读器自己用双指缩放，
 * 从 2x 缩回 1x 的缩放比例必然跨过阈值，会误开隐私模式；而且这里会消费事件，
 * 留着也会破坏阅读器的缩放手势。
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
            // 本次手势里是否已经出现过两根手指；一旦出现，剩余事件不再交给子节点。
            var multiTouch = false
            // 同一次手势只切换一次状态，避免捏住不放时反复写盘。
            var triggered = false

            // requireUnconsumed = false：第一根手指可能已经被子节点（输入框、列表）消费，
            // 这里仍要开始跟踪，否则收不到后面的双指事件。
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedCount = event.changes.count { it.pressed }

                if (pressedCount != lastPressedCount) {
                    accumulatedZoom = 1f
                    lastPressedCount = pressedCount
                }

                if (pressedCount >= 2) multiTouch = true

                // 先算缩放再消费：消费标记可能让部分版本的比例计算退化成 1。
                if (pressedCount >= 2 && !triggered) {
                    accumulatedZoom *= event.calculateZoom()
                }

                // 双指成立后独占手势：在 Initial pass 消费，子节点在 Main pass 看到的就是
                // 「已消费」，点击 / 长按 / 滚动都会自行取消，不会再误触。
                if (multiTouch) {
                    event.changes.forEach { it.consume() }
                }

                if (pressedCount >= 2 && !triggered) {
                    when {
                        accumulatedZoom <= PRIVACY_PINCH_IN_THRESHOLD -> {
                            triggered = true
                            onPinchIn()
                        }

                        accumulatedZoom >= PRIVACY_PINCH_OUT_THRESHOLD -> {
                            triggered = true
                            onPinchOut()
                        }
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
