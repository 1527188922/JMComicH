package com.haruhi.jmcomic.core.codec

import com.haruhi.jmcomic.core.storage.FileNaming
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * ZIP 打包（JM 本子 → 单个压缩包）。
 *
 * 2026-10 起 `library/` 里是**站点原始字节**（加扰），所以打包时必须当场解扰：
 * 需要解扰的图片走 [ImageCodec.descrambleToBytes] 重新编码（有损 / 无损由设置决定）；
 * 旧版的解扰成品与未加扰图片在**有损模式**下也会统一重压到官方量级，
 * **无损模式**则原样流进压缩包，不重复编码。
 *
 * 其余行为对齐参考实现 `JmcomicService.generateLocalAlbumZipWithoutLock`（第 557～584 行）：
 * - 用 **zip4j**；
 * - **`includeRootFolder = false`**：压缩包里直接是 `第N话/00001.webp`，
 *   不再套一层本子目录（用户解压出来就是一个本子文件夹的内容）；
 * - 文件名用 **UTF-8**，否则中文/日文标题在 Windows 上会变乱码；
 * - 设了密码就用 **AES** 加密；**没设密码就完全不加密**
 *   （参考实现恒加密，本工程按用户要求支持"不设密码"，见计划 §9 第 7 条）。
 *
 * 逐张 `addStream`：既不用为每张图写一个中转文件，也能逐张报进度。
 * **一次只处理一张图**，解扰位图与编码字节用完即丢，不随张数累积。
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
     * @param mode     解扰后重新编码的方式（有损 / 无损）；旧成品与未加扰图片不受影响
     * @param chapterPlans 按章节目录名索引的解扰信息；缺省表示全是旧成品。
     *        非空时同时当**章节白名单**用（见 [exportChapterDirs]），
     *        磁盘上遗留的、已不属于任何一话的目录不会被打进包里
     * @param onProgress `(已打包张数, 总张数, 当前章节名)`
     */
    fun albumToZip(
        albumDir: File,
        out: File,
        password: String?,
        mode: ImageEncodeMode = ImageEncodeMode.LOSSLESS,
        chapterPlans: Map<String, ChapterImagePlan> = emptyMap(),
        onProgress: ((done: Int, total: Int, chapter: String) -> Unit)? = null,
    ): Result {
        val chapters = exportChapterDirs(chapterDirs(albumDir), chapterPlans)
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
                val plan = chapterPlans[chapterDir.name]
                for (image in images) {
                    // 只有"新版本下载的原始字节"需要现场解扰；旧成品 / 未加扰章节原样打包。
                    val blockNum = plan?.let { p ->
                        if (image.name in p.rawFiles) {
                            ImageScramble.blockNumOf(p.scrambleId, p.chapterId, image.name)
                        } else {
                            0
                        }
                    } ?: 0
                    // 压缩包内的路径：`第N话/00001.webp`（不套本子目录）
                    parameters.fileNameInZip = "${chapterDir.name}/${image.name}"
                    // 有损模式连"本来就是普通图"的 webp 也统一压到官方量级；
                    // 无损模式下未加扰 / 旧成品直接流进去，保持字节与像素不变。
                    val reencode = blockNum > 0 ||
                        (mode == ImageEncodeMode.LOSSY && image.name.endsWith(".webp", ignoreCase = true))
                    if (reencode) {
                        val bytes = if (blockNum > 0) {
                            ImageCodec.descrambleToBytes(image, blockNum, mode)
                        } else {
                            ImageCodec.reencodeToBytes(image, mode)
                        }
                        ByteArrayInputStream(bytes).use { file.addStream(it, parameters) }
                    } else {
                        image.inputStream().use { file.addStream(it, parameters) }
                    }
                    done++
                    onProgress?.invoke(done, total, chapterDir.name)
                }
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
