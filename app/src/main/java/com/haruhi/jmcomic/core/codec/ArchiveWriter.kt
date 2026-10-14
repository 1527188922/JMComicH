package com.haruhi.jmcomic.core.codec

import com.haruhi.jmcomic.core.storage.FileNaming
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * ZIP 打包（JM 本子 → 单个压缩包）。
 *
 * 行为对齐参考实现 `JmcomicService.generateLocalAlbumZipWithoutLock`（第 557～584 行）：
 * - 用 **zip4j**；
 * - **`includeRootFolder = false`**：压缩包里直接是 `第N话/00001.webp`，
 *   不再套一层本子目录（用户解压出来就是一个本子文件夹的内容）；
 * - 文件名用 **UTF-8**，否则中文/日文标题在 Windows 上会变乱码；
 * - 设了密码就用 **AES** 加密；**没设密码就完全不加密**
 *   （参考实现恒加密，本工程按用户要求支持"不设密码"，见计划 §9 第 7 条）。
 *
 * 逐章add：zip4j 的 `addFolder` 没有进度回调，所以这里按**章节**调用
 * `addFiles` 并显式指定 `defaultFolderPath`，既能报进度，压缩包内的路径也
 * 与"整目录打包"完全一致。
 */
class ArchiveWriter {

    data class Result(
        val file: File,
        val entryCount: Int,
        /** 是否为加密封缩包。 */
        val encrypted: Boolean,
    )

    /**
     * 把 [albumDir] 下的章节打成 zip。
     *
     * @param albumDir 本子目录，直接子目录视为章节
     * @param out      输出文件（已存在会被覆盖）
     * @param password 打开密码；`null`/空串表示不加密（用户点「不设密码」）
     * @param onProgress `(已打包张数, 总张数, 当前章节名)`
     */
    fun albumToZip(
        albumDir: File,
        out: File,
        password: String?,
        onProgress: ((done: Int, total: Int, chapter: String) -> Unit)? = null,
    ): Result {
        val chapters = chapterDirs(albumDir)
        require(chapters.isNotEmpty()) { "本子目录下没有章节文件夹：${albumDir.absolutePath}" }

        val chapterImages = chapters.map { it to imagesOf(it) }.filter { it.second.isNotEmpty() }
        val total = chapterImages.sumOf { it.second.size }
        require(total > 0) { "本子目录下没有图片：${albumDir.absolutePath}" }

        out.parentFile?.mkdirs()
        // 覆盖旧产物：zip4j 是"追加"语义，不删会得到两个同名条目
        if (out.exists()) out.delete()

        val encrypted = !password.isNullOrEmpty()
        val zip = if (encrypted) ZipFile(out, password!!.toCharArray()) else ZipFile(out)
        try {
            zip.setCharset(StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // 老版本 zip4j 只认字符串编码名；失败也不影响正确性（默认 UTF-8）
        }

        val parameters = ZipParameters().apply {
            isIncludeRootFolder = false
            if (encrypted) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
            }
        }

        var done = 0
        zip.use { file ->
            for ((chapterDir, images) in chapterImages) {
                // 显式指定压缩包内的目录名：entries 就是 `第N话/00001.webp`
                parameters.defaultFolderPath = chapterDir.name
                file.addFiles(images, parameters)
                done += images.size
                onProgress?.invoke(done, total, chapterDir.name)
            }
        }

        return Result(file = out, entryCount = total, encrypted = encrypted)
    }

    /** 章节目录：按 `第N话` 序号升序（与 PDF 用同一套规则）。 */
    fun chapterDirs(albumDir: File): List<File> {
        val dirs = albumDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.sortedBy { FileNaming.extractChapterNumber(it.name) }
    }

    /** 章节图片：按去扩展名后的数字升序。 */
    fun imagesOf(chapterDir: File): List<File> {
        val files = chapterDir.listFiles { file -> file.isFile && isImage(file.name) } ?: return emptyList()
        return files.sortedBy { FileNaming.extractImageNumber(FileNaming.baseName(it.name)) }
    }

    private fun isImage(fileName: String) =
        FileNaming.extension(fileName) in IMAGE_EXTENSIONS

    private companion object {
        val IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "gif", "bmp")
    }
}
