package com.haruhi.jmcomic.core.codec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 解扰块数：阅读 / ZIP / PDF 共用的唯一算法。 */
class ImageScrambleTest {

    /** `chapterId >= scrambleId` 且 `chapterId < 268850` → 固定 10 块。 */
    @Test
    fun `old chapters use a fixed block count`() {
        assertThat(ImageScramble.blockNumOf(scrambleId = 1L, chapterId = 123_456L, fileName = "00001.webp"))
            .isEqualTo(10)
    }

    @Test
    fun `a non webp image is never scrambled`() {
        assertThat(ImageScramble.blockNumOf(scrambleId = 1L, chapterId = 123_456L, fileName = "00001.jpg"))
            .isEqualTo(0)
    }

    @Test
    fun `a chapter below the scramble id keeps the original bytes`() {
        assertThat(
            ImageScramble.blockNumOf(
                scrambleId = 999_999_999L,
                chapterId = 123_456L,
                fileName = "00001.webp",
            ),
        ).isEqualTo(0)
    }

    /** 文件名要用**逻辑名**（不是 `.part` / `.descrambled`）：后者会让块数算错。 */
    @Test
    fun `block count depends on the logical file name`() {
        val normal = ImageScramble.blockNumOf(scrambleId = 1L, chapterId = 123_456L, fileName = "00001.webp")
        val physical = ImageScramble.blockNumOf(scrambleId = 1L, chapterId = 123_456L, fileName = "00001.webp.part")
        assertThat(physical).isNotEqualTo(normal)
    }
}
