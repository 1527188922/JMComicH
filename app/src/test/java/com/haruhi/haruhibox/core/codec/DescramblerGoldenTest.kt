package com.haruhi.haruhibox.core.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.testing.GoldenData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 spike 3 的验收测试：解扰结果必须与参考实现（AWT `WritableRaster` 逐行搬运）
 * **逐像素一致**。
 *
 * 参考实现产物是 PNG（无损），黄金文件在 `app/src/test/resources/golden/descramble/`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DescramblerGoldenTest {

    @Test
    fun `block layout matches the reference implementation formula`() {
        // height=43, blockNum=8：43 = 8*5 + 3，第 0 块 5+3=8 行，其余 5 行
        val plan = Descrambler.plan(height = 43, blockNum = 8)

        assertThat(plan).hasSize(8)
        assertThat(plan[0]).isEqualTo(Descrambler.BlockMove(srcY = 35, dstY = 0, height = 8))
        assertThat(plan[1]).isEqualTo(Descrambler.BlockMove(srcY = 30, dstY = 8, height = 5))
        assertThat(plan[7]).isEqualTo(Descrambler.BlockMove(srcY = 0, dstY = 38, height = 5))

        // 所有源行恰好被覆盖一次，且目标行也是紧凑排列
        val srcRows = plan.flatMap { it.srcY until (it.srcY + it.height) }
        val dstRows = plan.flatMap { it.dstY until (it.dstY + it.height) }
        assertThat(srcRows.sorted()).isEqualTo((0 until 43).toList())
        assertThat(dstRows.sorted()).isEqualTo((0 until 43).toList())
    }

    @Test
    fun `block layout covers every row for exact multiples`() {
        val plan = Descrambler.plan(height = 40, blockNum = 8)
        assertThat(plan).hasSize(8)
        assertThat(plan.all { it.height == 5 }).isTrue()
        assertThat(plan[0]).isEqualTo(Descrambler.BlockMove(srcY = 35, dstY = 0, height = 5))
        assertThat(plan[7]).isEqualTo(Descrambler.BlockMove(srcY = 0, dstY = 35, height = 5))
    }

    @Test
    fun `block layout is empty when blockNum is zero or negative`() {
        assertThat(Descrambler.plan(height = 100, blockNum = 0)).isEmpty()
        assertThat(Descrambler.plan(height = 100, blockNum = -1)).isEmpty()
    }

    @Test
    fun `block layout keeps total height when blockNum exceeds height`() {
        val plan = Descrambler.plan(height = 7, blockNum = 22)
        assertThat(plan).hasSize(22)
        val dstRows = plan.flatMap { it.dstY until (it.dstY + it.height) }
        assertThat(dstRows).hasSize(7)
        assertThat(dstRows.sorted()).isEqualTo((0 until 7).toList())
    }

    @Test
    fun `descrambled bitmap matches reference golden images pixel by pixel`() {
        val cases = GoldenData.of("G7")
        assertThat(cases).isNotEmpty()

        var compared = 0
        for (row in cases) {
            val width = row.int(0)
            val height = row.int(1)
            val blockNum = row.int(2)

            val src = BitmapFactory.decodeStream(
                GoldenData.goldenImage("descramble_src_${width}x${height}_b$blockNum.png")
                    .inputStream(),
            )
            val expected = BitmapFactory.decodeStream(
                GoldenData.goldenImage("descramble_dst_${width}x${height}_b$blockNum.png")
                    .inputStream(),
            )
            assertThat(src).isNotNull()
            assertThat(expected).isNotNull()
            assertThat(src.width).isEqualTo(width)
            assertThat(src.height).isEqualTo(height)
            assertThat(expected.width).isEqualTo(width)
            assertThat(expected.height).isEqualTo(height)

            val actual = descramble(src, blockNum)
            try {
                assertPixelsEqual(expected, actual, "$width x $height blockNum=$blockNum")
                compared++
            } finally {
                src.recycle()
                expected.recycle()
                actual.recycle()
            }
        }
        assertThat(compared).isEqualTo(cases.size)
    }

    private fun assertPixelsEqual(expected: Bitmap, actual: Bitmap, label: String) {
        assertThat(actual.width).isEqualTo(expected.width)
        assertThat(actual.height).isEqualTo(expected.height)

        val w = expected.width
        val expectedRow = IntArray(w)
        val actualRow = IntArray(w)
        for (y in 0 until expected.height) {
            expected.getPixels(expectedRow, 0, w, 0, y, w, 1)
            actual.getPixels(actualRow, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (expectedRow[x] != actualRow[x]) {
                    throw AssertionError(
                        "$label: pixel mismatch at ($x, $y): " +
                            "expected=${expectedRow[x].toUInt().toString(16)} " +
                            "actual=${actualRow[x].toUInt().toString(16)}",
                    )
                }
            }
        }
    }

    /**
     * 生产实现还没落到 main source set（P3 才用），这里先放一份与参考实现等价的实现，
     * 用来验证"块布局 + 整行搬运"这条路在 Android 上能产出逐像素一致的结果。
     *
     * 搬运方式用 `getPixels/setPixels` **逐行**拷贝，而不是 `Canvas.drawBitmap`：
     * 语义与参考实现的 `Raster.getDataElements/setDataElements` 完全对应（都是 ARGB int 拷贝），
     * 不经过 Skia 采样，也不受 Canvas 阴影实现差异影响。
     *
     * 注意：Robolectric 的 `setPixels` 对 `height > 1` 的矩形区域只写第一行
     * （已用 `PixelProbeTest` 确认），所以这里必须一行一行搬。
     * 真机上多行 `setPixels` 是正常的，但为了保证同一份代码在单测与真机行为一致，
     * 生产实现（P3）也用逐行搬运。
     */
    private fun descramble(src: Bitmap, blockNum: Int): Bitmap {
        if (blockNum < 1) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        val dst = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val row = IntArray(src.width)
        for (move in Descrambler.plan(src.height, blockNum)) {
            for (offset in 0 until move.height) {
                src.getPixels(row, 0, src.width, 0, move.srcY + offset, src.width, 1)
                dst.setPixels(row, 0, src.width, 0, move.dstY + offset, src.width, 1)
            }
        }
        return dst
    }
}
