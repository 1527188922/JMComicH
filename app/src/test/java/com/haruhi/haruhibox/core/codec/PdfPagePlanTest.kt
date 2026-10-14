package com.haruhi.haruhibox.core.codec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PDF 单页尺寸规划（计划 §7 的"14400×14400 上限"那一行）。
 *
 * 参考实现直接拿图片像素当页面尺寸、不处理上限，超长图在多数阅读器里会渲染异常。
 * 这里改成**超过上限才按高度均匀切片**，所以两条都要锁住：
 * - 没超限的图必须仍然是"一页一张、尺寸等于像素尺寸"（与参考实现一致）；
 * - 超限的图切片必须**连续、无重叠、无遗漏**，且宽度超限时等比缩小。
 */
class PdfPagePlanTest {

    private val max = PdfPagePlan.MAX_PAGE_SIDE

    @Test
    fun `a normal image becomes exactly one page at pixel size`() {
        val plan = PdfPagePlan.plan(800, 1200)

        assertThat(plan.pageWidth).isEqualTo(800)
        assertThat(plan.slices).hasSize(1)
        assertThat(plan.slices[0]).isEqualTo(
            PdfPageSlice(pageY = 0, pageHeight = 1200, sourceY = 0, sourceHeight = 1200),
        )
    }

    @Test
    fun `the limit itself is still a single page`() {
        val plan = PdfPagePlan.plan(1000, max)
        assertThat(plan.slices).hasSize(1)
        assertThat(plan.slices[0].pageHeight).isEqualTo(max)
    }

    @Test
    fun `one pixel over the limit splits into two pages`() {
        val plan = PdfPagePlan.plan(1000, max + 1)

        assertThat(plan.slices).hasSize(2)
        assertThat(plan.slices.all { it.pageHeight <= max }).isTrue()
        assertThat(plan.slices.sumOf { it.pageHeight }).isEqualTo(max + 1)
    }

    @Test
    fun `a very tall image is split evenly without gaps or overlaps`() {
        val height = max * 3
        val plan = PdfPagePlan.plan(800, height)

        assertThat(plan.slices).hasSize(3)
        // 均匀切：每段一样高（整除时）
        assertThat(plan.slices.map { it.pageHeight }).containsExactly(max, max, max)
        // 页内纵向坐标连续
        var expected = 0
        plan.slices.forEach { slice ->
            assertThat(slice.pageY).isEqualTo(expected)
            expected += slice.pageHeight
        }
        assertThat(expected).isEqualTo(height)
        // 原图区间也连续且覆盖整图
        assertThat(plan.slices.first().sourceY).isEqualTo(0)
        assertThat(plan.slices.last().let { it.sourceY + it.sourceHeight }).isEqualTo(height)
    }

    @Test
    fun `an odd height still covers everything with at most one pixel of difference`() {
        val height = max * 2 + 7
        val plan = PdfPagePlan.plan(900, height)

        assertThat(plan.slices).hasSize(3)
        val heights = plan.slices.map { it.pageHeight }
        assertThat(heights.max() - heights.min()).isAtMost(1)
        assertThat(heights.sum()).isEqualTo(height)
        assertThat(plan.slices.last().let { it.sourceY + it.sourceHeight }).isEqualTo(height)
        // 不能出现碎页
        assertThat(heights.min()).isGreaterThan(max / 2)
    }

    @Test
    fun `a too wide image is scaled down to the limit and slices map back to source`() {
        // 宽 30000：按宽度缩到 14400，高度也跟着缩一半
        val plan = PdfPagePlan.plan(30_000, 2_000)

        assertThat(plan.pageWidth).isEqualTo(max)
        assertThat(plan.slices).hasSize(1)
        // 缩放后页高 = 2000 * (14400/30000) = 960
        assertThat(plan.slices[0].pageHeight).isEqualTo(960)
        // 原图区间仍然是整张
        assertThat(plan.slices[0].sourceY).isEqualTo(0)
        assertThat(plan.slices[0].sourceHeight).isEqualTo(2_000)
    }

    @Test
    fun `scale and slicing combine`() {
        // 宽超限 + 高超限：先缩宽，再按缩放后的高度切片
        val plan = PdfPagePlan.plan(30_000, 60_000)

        assertThat(plan.pageWidth).isEqualTo(max)
        // 缩放后高度 = 28800 -> 两页
        assertThat(plan.slices).hasSize(2)
        assertThat(plan.slices.sumOf { it.pageHeight }).isEqualTo(28_800)
        // 原图区间覆盖整张、且不超界
        assertThat(plan.slices.first().sourceY).isEqualTo(0)
        assertThat(plan.slices.last().let { it.sourceY + it.sourceHeight }).isEqualTo(60_000)
        plan.slices.forEach { slice ->
            assertThat(slice.sourceY + slice.sourceHeight).isAtMost(60_000)
        }
    }

    @Test
    fun `slice count is capped so a pathological image cannot explode`() {
        val plan = PdfPagePlan.plan(10, max * 100)
        assertThat(plan.slices).hasSize(PdfPagePlan.MAX_SLICES)
    }
}
