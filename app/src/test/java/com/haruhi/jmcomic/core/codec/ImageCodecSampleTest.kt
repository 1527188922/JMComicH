package com.haruhi.jmcomic.core.codec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 显示端降采样倍数（`ImageCodec.decodeSampleSize`）的打表测试。
 *
 * 这条规则决定"阅读器把多大的位图解进内存"，纯算术、不碰 android.graphics，
 * 所以放在普通 JVM 单测里；真机上的像素结果由阅读器自测（见 `docs/PROGRESS.md`）。
 */
class ImageCodecSampleTest {

    @Test
    fun `an image narrower than the screen is decoded at full size`() {
        // 681×946 是实测的常见页（几 MB 的手机屏也比它宽）：不许缩，否则白糊
        assertThat(ImageCodec.decodeSampleSize(681, 946, targetWidthPx = 1080)).isEqualTo(1)
        assertThat(ImageCodec.decodeSampleSize(800, 1200, targetWidthPx = 1080)).isEqualTo(1)
    }

    @Test
    fun `a much wider image is halved until it fits the screen width`() {
        // 3000 宽 / 1080 屏：1→2 后 1500 ≥ 1080（还放得下，更清晰），再折半 750 < 1080 就停
        assertThat(ImageCodec.decodeSampleSize(3000, 2000, targetWidthPx = 1080)).isEqualTo(2)
        // 4000 宽 / 1080 屏：4 倍后 1000 已小于 1080，所以停在 2（2000）
        assertThat(ImageCodec.decodeSampleSize(4000, 2000, targetWidthPx = 1080)).isEqualTo(2)
        // 4800 宽 / 1080 屏：2 倍后 2400 仍 ≥ 1080，4 倍后 1200 仍 ≥ 1080 → 4
        assertThat(ImageCodec.decodeSampleSize(4800, 3200, targetWidthPx = 1080)).isEqualTo(4)
    }

    @Test
    fun `very tall pages are downsampled to the pixel budget`() {
        // 800×12000 = 9.6 MP，在 12 MP 预算内：保住原宽度（长条页按宽度展示，缩了就是糊）
        assertThat(ImageCodec.decodeSampleSize(800, 12000, targetWidthPx = 1080)).isEqualTo(1)
        // 800×20000 = 16 MP 超预算：折半到 400×10000 = 4 MP
        assertThat(ImageCodec.decodeSampleSize(800, 20000, targetWidthPx = 1080)).isEqualTo(2)
        // 极端长条 800×80000 = 64 MP：2 倍还有 16 MP，4 倍才是 200×20000 = 4 MP
        assertThat(ImageCodec.decodeSampleSize(800, 80000, targetWidthPx = 1080)).isEqualTo(4)
    }

    @Test
    fun `unknown dimensions never crash and never sample`() {
        assertThat(ImageCodec.decodeSampleSize(0, 0, targetWidthPx = 1080)).isEqualTo(1)
        assertThat(ImageCodec.decodeSampleSize(-1, 100, targetWidthPx = 1080)).isEqualTo(1)
        // 拿不到屏幕宽度（0）时按原宽处理 = 不降采样
        assertThat(ImageCodec.decodeSampleSize(2000, 3000, targetWidthPx = 0)).isEqualTo(1)
    }

    @Test
    fun `a zero pixel budget is treated as no budget`() {
        assertThat(ImageCodec.decodeSampleSize(800, 20000, targetWidthPx = 1080, maxPixels = 0))
            .isEqualTo(1)
    }
}
