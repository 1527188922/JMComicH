package com.haruhi.haruhibox.core.codec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 导出能力的**真机验证**（zip 加密 + PDF 超长图切片）。
 *
 * 为什么必须在设备上跑：
 * 1. zip4j 的 AES 用 PBKDF2 + `Mac`，Robolectric 沙箱里会撞上 JDK 的 provider
 *    验签问题（与 `PdfEncryptionInstrumentedTest` 同一个原因）；
 * 2. PDF 切片走 `BitmapRegionDecoder`，那是 Android 平台 API。
 *
 * 运行：
 * ```powershell
 * .\gradlew.bat :app:runDeviceTests "-PclassFilter=com.haruhi.haruhibox.core.codec.ExportInstrumentedTest"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {

    private val writer = ArchiveWriter()
    private val pdfWriter = PdfWriter()

    // ------------------------------------------------------------------
    // zip
    // ------------------------------------------------------------------

    @Test
    fun zipWithPasswordUsesAesAndOpensWithTheRightPassword() {
        val albumDir = buildAlbum("第1话" to listOf("00001", "00002"))
        val out = File(workDir(), "secret.zip")

        val result = writer.albumToZip(albumDir, out, password = "1234")

        assertThat(result.encrypted).isTrue()
        val zip = ZipFile(out, "1234".toCharArray())
        assertThat(zip.isEncrypted).isTrue()
        assertThat(zip.fileHeaders.first().encryptionMethod).isEqualTo(EncryptionMethod.AES)
        assertThat(zip.fileHeaders.map { it.fileName })
            .containsExactly("第1话/00001.webp", "第1话/00002.webp")
        // 真的能把内容读出来（不只是能列出条目）
        zip.getInputStream(zip.fileHeaders.first()).use { stream ->
            assertThat(stream.readBytes().size).isGreaterThan(0)
        }
    }

    @Test
    fun wrongPasswordCannotReadTheEntries() {
        val albumDir = buildAlbum("第1话" to listOf("00001"))
        val out = File(workDir(), "secret-wrong.zip")
        writer.albumToZip(albumDir, out, password = "1234")

        val wrong = ZipFile(out, "9999".toCharArray())
        val failed = runCatching {
            wrong.getInputStream(wrong.fileHeaders.first()).use { it.readBytes() }
        }.isFailure

        assertThat(failed).isTrue()
    }

    @Test
    fun zipWithoutPasswordIsReadableDirectly() {
        val albumDir = buildAlbum("第1话" to listOf("00001"))
        val out = File(workDir(), "plain.zip")

        val result = writer.albumToZip(albumDir, out, password = null)

        assertThat(result.encrypted).isFalse()
        val zip = ZipFile(out)
        assertThat(zip.isEncrypted).isFalse()
        zip.getInputStream(zip.fileHeaders.first()).use { stream ->
            assertThat(stream.readBytes().size).isGreaterThan(0)
        }
    }

    // ------------------------------------------------------------------
    // PDF 超长图
    // ------------------------------------------------------------------

    /**
     * 超过 PDF 单页上限（14400）的长图会被切成多页，每页都不超上限，
     * 且普通尺寸的图仍然一页一张。
     *
     * 用**窄**长图（100×20000）而不是 800×30000：前者解码只要几 MB，
     * 后者 ARGB_8888 要 96MB，在模拟器上会 OOM——而这条用例要验证的是切片逻辑，
     * 不是内存上限。
     */
    @Test
    fun aLongImageIsSlicedIntoPagesWithinThePdfLimit() {
        val albumDir = workDir().let { dir ->
            File(dir, "long-album").apply { mkdirs() }
        }
        val chapterDir = File(albumDir, "第1话").apply { mkdirs() }
        // 100 x 20000：高度超过 14400 上限，必须切
        writePng(File(chapterDir, "00001.webp"), 100, 20_000)
        // 普通尺寸的图仍然是一页
        writePng(File(chapterDir, "00002.webp"), 60, 90)

        val out = File(workDir(), "long.pdf")
        val result = pdfWriter.albumToPdf(albumDir, out, password = null)

        assertThat(result.pageCount).isEqualTo(3)
        assertThat(result.slicedImageCount).isEqualTo(1)

        PDDocument.load(out).use { doc ->
            assertThat(doc.numberOfPages).isEqualTo(result.pageCount)
            for (index in 0 until doc.numberOfPages) {
                val box = doc.getPage(index).mediaBox
                assertThat(box.width).isAtMost(PdfPagePlan.MAX_PAGE_SIDE.toFloat())
                assertThat(box.height).isAtMost(PdfPagePlan.MAX_PAGE_SIDE.toFloat())
            }
            // 两页合计覆盖整张长图的高度
            val first = doc.getPage(0).mediaBox
            val second = doc.getPage(1).mediaBox
            assertThat(first.height + second.height).isEqualTo(20_000f)
            // 最后一张普通图仍然按像素尺寸出页
            val last = doc.getPage(doc.numberOfPages - 1).mediaBox
            assertThat(last.width).isEqualTo(60f)
            assertThat(last.height).isEqualTo(90f)
        }
    }

    @Test
    fun aNormalAlbumKeepsOnePagePerImage() {
        val albumDir = File(workDir(), "normal-album").apply { mkdirs() }
        val chapterDir = File(albumDir, "第1话").apply { mkdirs() }
        writePng(File(chapterDir, "00001.webp"), 120, 300)
        writePng(File(chapterDir, "00002.webp"), 120, 400)

        val out = File(workDir(), "normal.pdf")
        val result = pdfWriter.albumToPdf(albumDir, out, password = null)

        assertThat(result.pageCount).isEqualTo(2)
        assertThat(result.slicedImageCount).isEqualTo(0)
    }

    // ------------------------------------------------------------------

    private fun workDir(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "export-test")
            .apply { mkdirs() }

    private fun buildAlbum(vararg chapters: Pair<String, List<String>>): File {
        val albumDir = File(workDir(), "album-${System.nanoTime()}").apply { mkdirs() }
        var hue = 0
        for ((chapterName, imageNames) in chapters) {
            val chapterDir = File(albumDir, chapterName).apply { mkdirs() }
            for (name in imageNames) {
                writePng(File(chapterDir, "$name.webp"), 8, 8, hue++)
            }
        }
        return albumDir
    }

    /** 写 PNG 字节但用 `.webp` 后缀：导出只按扩展名判断，内容是不是真 webp 无关紧要。 */
    private fun writePng(file: File, width: Int, height: Int, hue: Int = 0) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(40 + (hue % 5) * 20, 80, 120))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
