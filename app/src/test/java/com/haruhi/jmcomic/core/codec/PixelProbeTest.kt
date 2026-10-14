package com.haruhi.jmcomic.core.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.testing.GoldenData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 探针：确认 Robolectric 下 PNG 解码 + 整行像素拷贝的行为。
 * 结论写进 `docs/p0/README.md`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PixelProbeTest {

    @Test
    fun `source bitmap has meaningful pixels on every row`() {
        val bitmap = BitmapFactory.decodeStream(
            GoldenData.goldenImage("descramble_src_16x43_b2.png").inputStream(),
        )
        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(16)
        assertThat(bitmap.height).isEqualTo(43)
        println("PROBE config=${bitmap.config}")

        val row = IntArray(16)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, 16, 0, y, 16, 1)
            println("PROBE row=$y first=${row[0].toUInt().toString(16)} last=${row[15].toUInt().toString(16)}")
        }
    }

    @Test
    fun `descramble on a decoded bitmap produces non zero rows`() {
        val src = BitmapFactory.decodeStream(
            GoldenData.goldenImage("descramble_src_16x40_b2.png").inputStream(),
        )
        val plan = Descrambler.plan(src.height, 2)
        println("PROBE plan=$plan")

        val dst = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        println("PROBE dst config=${dst.config} w=${dst.width} h=${dst.height}")
        val row = IntArray(src.width)
        for (move in plan) {
            src.getPixels(row, 0, src.width, 0, move.srcY, src.width, 1)
            println("PROBE copy srcY=${move.srcY} -> dstY=${move.dstY} h=${move.height} first=${row[0].toUInt().toString(16)}")
            dst.setPixels(row, 0, src.width, 0, move.dstY, src.width, 1)
        }
        val read = IntArray(src.width)
        for (y in 0 until dst.height) {
            dst.getPixels(read, 0, src.width, 0, y, src.width, 1)
            println("PROBE out row=$y first=${read[0].toUInt().toString(16)}")
        }
    }
}
