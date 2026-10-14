package com.haruhi.haruhibox.core.codec

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 发布前处理（解扰）的验收：解扰参数必须按**逻辑文件名**算。
 *
 * 处理发生在中转文件上（`FileDownloader` 的 `beforePublish` 收到的就是 `<目标>.part`），
 * 物理名是 `00001.webp.part`。要是拿 `file.name` 推扩展名就会得到 `part` →
 * **静默跳过解扰**，于是加扰的字节被当成成品发布出去——用户看到的正是"没解扰的图"，
 * 而且它会被永久当成缓存命中。块数同理：用物理名算会算出 `00001.webp` 的哈希。
 *
 * 这里关心的是"有没有走到解扰"，不关心像素：Robolectric 的 `BitmapFactory` 对无法解析的
 * 数据会造一个占位位图，所以用任意字节就能把这条分支跑通。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageDownloadTransformTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** `chapterId >= scrambleId` 且 `chapterId < 268850` → 块数固定 10，一定需要解扰。 */
    private fun scrambledChapter() = ImageDownloadTransform(scrambleId = 1L, chapterId = 123_456L)

    @Test
    fun `a part file is descrambled according to its logical name`() {
        val part = File(temp.newFolder("第1话"), "00001.webp.part")
        part.writeBytes(ByteArray(64) { it.toByte() })

        val handled = scrambledChapter().transform(part, "00001.webp")

        // 物理名的扩展名是 `part`，但解扰要按逻辑名 `webp` 判定，并且真的执行
        assertThat(handled).isTrue()
    }

    @Test
    fun `a non webp image is kept untouched`() {
        val part = File(temp.newFolder("第1话"), "00001.jpg.part")
        val raw = ByteArray(64) { (it * 3).toByte() }
        part.writeBytes(raw)

        assertThat(scrambledChapter().transform(part, "00001.jpg")).isFalse()
        assertThat(part.readBytes()).isEqualTo(raw)
    }

    @Test
    fun `an unscrambled chapter keeps the downloaded bytes`() {
        val part = File(temp.newFolder("第1话"), "00001.webp.part")
        val raw = ByteArray(64) { (it * 5).toByte() }
        part.writeBytes(raw)

        // chapterId < scrambleId → 未加扰（与参考实现一致），原样保留、不重新编码
        val transform = ImageDownloadTransform(scrambleId = 999_999_999L, chapterId = 123_456L)
        assertThat(transform.transform(part, "00001.webp")).isFalse()
        assertThat(part.readBytes()).isEqualTo(raw)
    }
}
