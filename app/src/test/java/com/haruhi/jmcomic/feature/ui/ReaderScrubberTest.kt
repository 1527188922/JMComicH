package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 阅读器右侧「上下拖动跳页」控件的换算（`ReaderScreen.kt` 里的三个纯函数）。
 *
 * 这几个数决定"手指拖到哪儿、跳到第几页"，也是这套控件最容易出错的地方：
 * 两端够不够得着、拖到底是不是最后一页、按在拇指上会不会莫名跳走几页。
 * 没有设备也能把它们锁死，所以不放进 Compose 交互测试。
 */
class ReaderScrubberTest {

    /** 第一页贴着顶端、最后一页贴着底端：两端都拖得到，不会"拖到底还差一截"。 */
    @Test
    fun `the first page sits at the top and the last page at the bottom`() {
        assertThat(scrubFractionOf(0, PAGES)).isEqualTo(0f)
        assertThat(scrubFractionOf(PAGES - 1, PAGES)).isEqualTo(1f)
        assertThat(scrubPageAt(0f, PAGES)).isEqualTo(0)
        assertThat(scrubPageAt(1f, PAGES)).isEqualTo(PAGES - 1)
    }

    /** 每一页都有自己的位置且单调递增：不会两页挤在一起，也不会跳着走。 */
    @Test
    fun `every page has its own position and the order is monotonic`() {
        var previousFraction = -1f
        for (page in 0 until PAGES) {
            val fraction = scrubFractionOf(page, PAGES)
            assertThat(fraction).isAtLeast(0f)
            assertThat(fraction).isAtMost(1f)
            assertThat(scrubPageAt(fraction, PAGES)).isEqualTo(page)
            assertThat(fraction).isGreaterThan(previousFraction)
            previousFraction = fraction
        }
        // 最后一页再往后没有"第 60 页"可去
        assertThat(scrubPageAt(1.5f, PAGES)).isEqualTo(PAGES - 1)
        assertThat(scrubPageAt(-0.5f, PAGES)).isEqualTo(0)
    }

    /** 手指按在拇指正中时页码不变：抓住拇指再拖，不会先跳走几页。 */
    @Test
    fun `pressing the centre of the thumb keeps the page`() {
        for (page in 0 until PAGES) {
            val thumbTop = (TRACK - THUMB) * scrubFractionOf(page, PAGES)
            val fingerY = thumbTop + THUMB / 2f
            assertThat(scrubPageAt(scrubFractionAtY(fingerY, TRACK, THUMB), PAGES)).isEqualTo(page)
        }
    }

    /** 手指拖到轨道外面时收敛到第一页 / 最后一页，不会算出越界页码。 */
    @Test
    fun `pressing outside the track clamps to the ends`() {
        assertThat(scrubPageAt(scrubFractionAtY(-200f, TRACK, THUMB), PAGES)).isEqualTo(0)
        assertThat(scrubPageAt(scrubFractionAtY(TRACK + 200f, TRACK, THUMB), PAGES))
            .isEqualTo(PAGES - 1)
    }

    /** 手指往下拖，页码只会变大（取整不会让它来回跳）。 */
    @Test
    fun `dragging downwards never goes backwards`() {
        var previous = 0
        var y = 0f
        while (y <= TRACK) {
            val page = scrubPageAt(scrubFractionAtY(y, TRACK, THUMB), PAGES)
            assertThat(page).isAtLeast(previous)
            previous = page
            y += 1f
        }
        // 指到最底部就是最后一页（控件不会"拖到底还停在倒数第二页"）
        assertThat(previous).isEqualTo(PAGES - 1)
    }

    /** 只有一页、或清单还没回来（0 页）时不跳、也不崩：恒定落在第一页。 */
    @Test
    fun `a single page chapter stays on page zero`() {
        assertThat(scrubPageAt(0f, 1)).isEqualTo(0)
        assertThat(scrubPageAt(1f, 1)).isEqualTo(0)
        assertThat(scrubPageAt(0.5f, 1)).isEqualTo(0)
        assertThat(scrubFractionOf(0, 1)).isEqualTo(0f)

        assertThat(scrubPageAt(0.7f, 0)).isEqualTo(0)
        assertThat(scrubFractionOf(5, 0)).isEqualTo(0f)
        assertThat(scrubPageAt(scrubFractionAtY(300f, TRACK, THUMB), 1)).isEqualTo(0)
    }

    private companion object {
        /** 一话的页数：手机上一话常见的量级，够验证分配比例。 */
        const val PAGES = 60

        /** 轨道与拇指的像素尺寸（测试里用固定值，真机上按 dp 换算）。 */
        const val TRACK = 400f
        const val THUMB = 44f
    }
}
