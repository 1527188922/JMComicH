package com.haruhi.haruhibox.core.codec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0 spike 2：用 `com.tom-roush:pdfbox-android` 生成 PDF 的可行性验证。
 *
 * 这里覆盖**不涉及密码学**的部分（Robolectric 可以跑）：
 * 页面数、页面尺寸 = 图片像素尺寸、章节目签、`PageMode`、"不设密码"确实不加密。
 *
 * **加密相关的用例在 `app/src/androidTest/.../PdfEncryptionInstrumentedTest.kt`**，
 * 原因：PDFBox 的 AES 走 BouncyCastle，而 Robolectric 沙箱里
 * `javax.crypto.Cipher.getInstance` 会去验签 BC 的 JAR，Conscrypt 排在 provider
 * 首位导致 `JarVerifier` 初始化失败：
 *   AES via SunJCE = ok    AES via BC = FAIL: NoClassDefFoundError: javax.crypto.JarVerifier
 * 这是 JDK 侧类加载与 JAR 验签的产物，真机上不存在（APK 里的 BC 类无需 JAR 验签）。
 * 详见 `docs/p0/spike2-pdf.md`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfWriterSpikeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val writer = PdfWriter()

    @Test
    fun `album is converted to one page per image with pixel sized pages`() {
        val albumDir = buildAlbum(
            "第1话" to listOf("00001" to (40 to 100), "00002" to (40 to 120)),
            "第2话" to listOf("00001" to (30 to 80)),
        )
        val out = File(temp.root, "album.pdf")

        val result = writer.albumToPdf(albumDir, out, password = null)

        assertThat(out.exists()).isTrue()
        assertThat(result.pageCount).isEqualTo(3)
        assertThat(result.chapterCount).isEqualTo(2)
        assertThat(result.encrypted).isFalse()

        PDDocument.load(out).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(3)
            // 页面尺寸 = 图片像素尺寸（参考实现用 PDRectangle(image.getWidth(), image.getHeight())）
            assertThat(doc.getPage(0).mediaBox.width).isEqualTo(40f)
            assertThat(doc.getPage(0).mediaBox.height).isEqualTo(100f)
            assertThat(doc.getPage(2).mediaBox.width).isEqualTo(30f)
            assertThat(doc.getPage(2).mediaBox.height).isEqualTo(80f)
        }
    }

    @Test
    fun `chapter bookmarks are written in chapter order with a root outline`() {
        val albumDir = buildAlbum(
            // 故意乱序放，验证按 "第N话" 序号排序
            "第10话" to listOf("00001" to (20 to 20)),
            "第2话" to listOf("00001" to (20 to 20)),
            "第1话" to listOf("00001" to (20 to 20)),
        )
        val out = File(temp.root, "bookmarks.pdf")

        writer.albumToPdf(albumDir, out, password = null)

        PDDocument.load(out).use { doc ->
            val outline = doc.documentCatalog.documentOutline
            assertThat(outline).isNotNull()

            val root = outline.firstChild
            assertThat(root.title).isEqualTo("目录")

            val chapterTitles = mutableListOf<String>()
            var child = root.firstChild
            while (child != null) {
                chapterTitles += child.title
                child = child.nextSibling
            }
            assertThat(chapterTitles).containsExactly("第1话", "第2话", "第10话").inOrder()
        }
    }

    @Test
    fun `empty password means no protection at all`() {
        val albumDir = buildAlbum("第1话" to listOf("00001" to (20 to 20)))
        val out = File(temp.root, "plain.pdf")

        val result = writer.albumToPdf(albumDir, out, password = "")

        assertThat(result.encrypted).isFalse()
        PDDocument.load(out).use { doc ->
            assertThat(doc.isEncrypted).isFalse()
        }
    }

    @Test
    fun `page mode is use outlines`() {
        val albumDir = buildAlbum("第1话" to listOf("00001" to (20 to 20)))
        val out = File(temp.root, "pagemode.pdf")

        writer.albumToPdf(albumDir, out, password = null)

        PDDocument.load(out).use { doc ->
            assertThat(doc.documentCatalog.pageMode.name).isEqualTo("USE_OUTLINES")
        }
    }

    // ------------------------------------------------------------------

    /** 构造一个本子目录：`第N话/00001.webp` …，图片是纯色 PNG（内容不重要，尺寸才重要）。 */
    private fun buildAlbum(vararg chapters: Pair<String, List<Pair<String, Pair<Int, Int>>>>): File {
        val albumDir = temp.newFolder("album")
        var hue = 0
        for ((chapterName, images) in chapters) {
            val chapterDir = File(albumDir, chapterName).apply { mkdirs() }
            for ((imageName, size) in images) {
                writePng(File(chapterDir, "$imageName.webp"), size.first, size.second, hue++)
            }
        }
        return albumDir
    }

    /**
     * 写一个**PNG 字节流但用 .webp 后缀**的文件。
     * 理由：P0 只验证 PDF 能力，`BitmapFactory` 在 Robolectric 下对 WebP 支持有限；
     * 而 `PdfWriter` 只按扩展名判断"是不是图片"，解码交给 `BitmapFactory`，
     * 真机上的真实 WebP 解码是 P4 的验收项。
     */
    private fun writePng(file: File, width: Int, height: Int, hue: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(40 + hue * 20, 80, 120))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
