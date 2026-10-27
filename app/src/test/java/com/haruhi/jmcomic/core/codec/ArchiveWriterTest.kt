package com.haruhi.jmcomic.core.codec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import net.lingala.zip4j.ZipFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ZIP 导出的验收（计划 §10 P4）：
 * 「zip 用密码能解压、错误密码打不开、选'不设密码'时无密码可直接解压」。
 *
 * 同时锁住与参考实现一致的结构：**压缩包里不套本子目录**，
 * 直接是 `第N话/00001.webp`（`includeRootFolder = false`）。
 *
 * **加密相关的两个用例在 `ExportArchiveInstrumentedTest`（真机）**：
 * zip4j 的 AES 走 PBKDF2 + `Mac`，在 Robolectric 沙箱里会撞上 JDK 的
 * provider 验签问题（`javax.crypto.ProviderVerifier` → `JarVerifier`），
 * 与 PDFBox 的 AES 是同一个原因，真机上不存在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val writer = ArchiveWriter()

    @Test
    fun `zip has no password when the user chooses not to set one`() {
        val albumDir = buildAlbum("第1话" to listOf("00001", "00002"), "第2话" to listOf("00001"))
        val out = File(temp.root, "album.zip")

        val result = writer.albumToZip(albumDir, out, password = null)

        assertThat(result.encrypted).isFalse()
        assertThat(result.entryCount).isEqualTo(3)

        val zip = ZipFile(out)
        assertThat(zip.isEncrypted).isFalse()
        // 不做任何处理就能列出内容
        assertThat(zip.fileHeaders.map { it.fileName })
            .containsExactly("第1话/00001.webp", "第1话/00002.webp", "第2话/00001.webp")
    }

    @Test
    fun `an empty password also means no encryption`() {
        val albumDir = buildAlbum("第1话" to listOf("00001"))
        val out = File(temp.root, "empty.zip")

        val result = writer.albumToZip(albumDir, out, password = "")

        assertThat(result.encrypted).isFalse()
        assertThat(ZipFile(out).isEncrypted).isFalse()
    }

    @Test
    fun `chapters are packed in chapter order and images in numeric order`() {
        val albumDir = buildAlbum(
            "第10话" to listOf("00010", "00002"),
            "第2话" to listOf("00001"),
        )
        val out = File(temp.root, "ordered.zip")

        writer.albumToZip(albumDir, out, password = null)

        val names = ZipFile(out).fileHeaders.map { it.fileName }
        assertThat(names).containsExactly(
            "第2话/00001.webp",
            "第10话/00002.webp",
            "第10话/00010.webp",
        ).inOrder()
    }

    @Test
    fun `progress is reported per image`() {
        val albumDir = buildAlbum("第1话" to listOf("00001", "00002"), "第2话" to listOf("00001"))
        val out = File(temp.root, "progress.zip")
        val seen = mutableListOf<Pair<Int, Int>>()

        writer.albumToZip(albumDir, out, password = null) { done, total, _ -> seen += done to total }

        assertThat(seen).containsExactly(1 to 3, 2 to 3, 3 to 3).inOrder()
    }

    @Test
    fun `regenerating overwrites the previous archive instead of appending`() {
        val albumDir = buildAlbum("第1话" to listOf("00001"))
        val out = File(temp.root, "again.zip")

        writer.albumToZip(albumDir, out, password = null)
        writer.albumToZip(albumDir, out, password = null)

        // zip4j 是追加语义，不先删旧文件就会有两个同名条目
        assertThat(ZipFile(out).fileHeaders).hasSize(1)
    }

    // ------------------------------------------------------------------

    /** 构造 `第N话/00001.webp`；内容是纯色 PNG（zip 不关心内容）。 */
    private fun buildAlbum(vararg chapters: Pair<String, List<String>>): File {
        val albumDir = temp.newFolder("album")
        var hue = 0
        for ((chapterName, imageNames) in chapters) {
            val chapterDir = File(albumDir, chapterName).apply { mkdirs() }
            for (name in imageNames) {
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.rgb(40 + hue * 20, 80, 120))
                File(chapterDir, "$name.webp").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                hue++
            }
        }
        return albumDir
    }
}
