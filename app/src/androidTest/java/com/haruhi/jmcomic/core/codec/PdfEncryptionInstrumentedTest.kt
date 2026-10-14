package com.haruhi.jmcomic.core.codec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * JM PDF 加密的真机验证（**必须在设备/模拟器上跑**）。
 *
 * 为什么不放在单元测试里：PDFBox 的 AES 走 BouncyCastle，而 Robolectric 沙箱中
 * `javax.crypto.Cipher.getInstance` 会去验签 BC 的 JAR；Conscrypt 排在 provider
 * 首位导致 `javax.crypto.JarVerifier` 初始化失败：
 * ```
 * AES via SunJCE = ok
 * AES via BC     = FAIL: NoClassDefFoundError: Could not initialize class javax.crypto.JarVerifier
 * ```
 * 这是 JDK 侧类加载与 JAR 验签的产物，真机上不存在。
 * 详细记录见 `docs/p0/spike2-pdf.md`。
 *
 * 运行：
 * ```powershell
 * .\gradlew.bat :app:connectedDebugAndroidTest
 * ```
 *
 * 覆盖 2026-10-08 用户对 PDF 的两项决策：
 * 1. 加密算法改为 **AES-128**（参考实现实际是 40 位 RC4，见 `PdfWriter` 类注释）；
 * 2. 访问控制**只看能否打开**，owner/user 同密码，权限位不作为安全承诺。
 */
@RunWith(AndroidJUnit4::class)
class PdfEncryptionInstrumentedTest {

    private val writer = PdfWriter()

    @Test
    fun albumPdfWithPasswordUsesAes128() {
        val out = buildAndWrite(password = "1234", name = "aes128.pdf")

        PDDocument.load(out, "1234").use { doc ->
            val encryption = doc.encryption ?: error("文档没有加密字典")

            // 缺 preferAES 或 encryptionKeyLength 任意一项都会静默降级成 40 位 RC4，
            // 所以这四项必须逐个断言（实测数据见 PdfWriter.applyProtection 的注释）。
            assertThat(readKeyLength(encryption)).isEqualTo(128)
            assertThat(encryption.version).isEqualTo(4)
            assertThat(encryption.revision).isEqualTo(4)

            // 硬证据：加密字典里存在 AESV2 的 crypt filter（RC4 时这里是 (none)）
            val stdFilter = encryption.stdCryptFilterDictionary
                ?: error("缺少 StdCF，说明降级成了 RC4")
            assertThat(stdFilter.getCryptFilterMethod()?.name).isEqualTo("AESV2")

            assertThat(doc.isEncrypted).isTrue()
            assertThat(doc.numberOfPages).isEqualTo(3)
        }
    }

    @Test
    fun wrongPasswordCannotOpenThePdf() {
        val out = buildAndWrite(password = "1234", name = "wrong-password.pdf")

        var rejected = false
        try {
            PDDocument.load(out, "9999").close()
        } catch (_: InvalidPasswordException) {
            rejected = true
        }
        assertThat(rejected).isTrue()
    }

    @Test
    fun emptyPasswordMeansNoEncryption() {
        val out = buildAndWrite(password = "", name = "plain.pdf")

        PDDocument.load(out).use { doc ->
            assertThat(doc.isEncrypted).isFalse()
        }
    }

    @Test
    fun permissionBitsAreWrittenButOwnerIdentityBypassesThem() {
        val out = buildAndWrite(password = "1234", name = "permissions.pdf")

        PDDocument.load(out, "1234").use { doc ->
            val encryption = doc.encryption ?: error("文档没有加密字典")
            val pValue = (encryption.getCOSObject().getItem("P") as COSNumber).intValue()
            val permission = AccessPermission(pValue)

            // P 位按参考实现写入
            assertThat(permission.canModify()).isFalse()
            assertThat(permission.canExtractContent()).isFalse()
            assertThat(permission.canExtractForAccessibility()).isFalse()
            assertThat(permission.canPrint()).isTrue()

            // **已确认接受的行为**（用户决策："能打开就不管其他权限"）：
            // owner 与 user 同密码 → 打开者被判定为 owner，权限位因此不生效。
            // 这条断言是**有意固定该事实**，不是期望的正确行为。
            // 将来若要真正限制权限，必须拆分 owner/user 两个密码并同步改这里。
            assertThat(doc.currentAccessPermission.isOwnerPermission).isTrue()
        }
    }

    // ------------------------------------------------------------------

    private fun buildAndWrite(password: String, name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "pdf-test").apply { mkdirs() }
        val albumDir = File(root, "album").apply { deleteRecursively(); mkdirs() }

        // 两个章节、共 3 张图；尺寸刻意不同，便于校验"页面尺寸 = 图片像素尺寸"
        val chapters = listOf(
            "第1话" to listOf(40 to 100, 40 to 120),
            "第2话" to listOf(30 to 80),
        )
        var hue = 0
        for ((chapterName, images) in chapters) {
            val chapterDir = File(albumDir, chapterName).apply { mkdirs() }
            for ((width, height) in images) {
                writePng(File(chapterDir, "%05d.webp".format(++hue)), width, height, hue)
            }
        }

        val out = File(root, name)
        writer.albumToPdf(albumDir, out, password = password)
        return out
    }

    /**
     * 写 PNG 字节流但用 `.webp` 后缀。
     * `PdfWriter` 只按扩展名判断"是不是图片"，解码交给 `BitmapFactory`；
     * 真实 WebP 解码是 P4 的验收项，这里只验证 PDF 与加密。
     */
    private fun writePng(file: File, width: Int, height: Int, hue: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(40 + hue * 20, 80, 120))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun readKeyLength(
        encryption: com.tom_roush.pdfbox.pdmodel.encryption.PDEncryption,
    ): Int = encryption.javaClass.getMethod("getLength").invoke(encryption) as Int
}
