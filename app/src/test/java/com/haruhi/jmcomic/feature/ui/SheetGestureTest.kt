package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 底部面板手势守门人的判定单测。
 *
 * 这里**只测规则**、不测手势本身：这台设备上 Compose UI 测试拿不到窗口焦点、
 * `adb shell input` 被禁（见 `docs/PROGRESS.md` 的「P2 待验证」），
 * 手势只能真机手测；能自动化的就是 [shouldConsumeSheetSwipeDown] 这张真值表。
 *
 * 四条行为约定（对应 `SettingsScreen` 的 `LinePickerSheet` KDoc）：
 * 1. 长列表（能滚）+ 已经在顶部 + 向下 → **吃掉**（面板不许动）；
 * 2. 长列表但还没到顶（还能往回滚）→ 放行（列表自己滚，面板本来也收不到余量）；
 * 3. 短列表（一屏放得下，滚不动）→ **不介入**，行为与改动前一致（在那上面下滑照样关面板）；
 * 4. 向上 → 永远放行。
 */
class SheetGestureTest {

    @Test
    fun `scrollable list at the top swallows the downward swipe`() {
        assertThat(
            shouldConsumeSheetSwipeDown(
                deltaY = 42f,
                listScrollable = true,
                canScrollBackward = false,
            ),
        ).isTrue()
    }

    @Test
    fun `scrollable list that can still scroll back lets the gesture through`() {
        assertThat(
            shouldConsumeSheetSwipeDown(
                deltaY = 42f,
                listScrollable = true,
                canScrollBackward = true,
            ),
        ).isFalse()
    }

    @Test
    fun `short list that fits on one screen is left alone`() {
        // 接口线路面板只有 6 条：守门人不介入，列表区下滑仍能关面板（旧行为）
        assertThat(
            shouldConsumeSheetSwipeDown(
                deltaY = 42f,
                listScrollable = false,
                canScrollBackward = false,
            ),
        ).isFalse()
    }

    @Test
    fun `upward swipe is never swallowed`() {
        assertThat(
            shouldConsumeSheetSwipeDown(
                deltaY = -42f,
                listScrollable = true,
                canScrollBackward = false,
            ),
        ).isFalse()
    }

    @Test
    fun `zero delta is not a downward swipe`() {
        assertThat(
            shouldConsumeSheetSwipeDown(
                deltaY = 0f,
                listScrollable = true,
                canScrollBackward = false,
            ),
        ).isFalse()
    }
}
