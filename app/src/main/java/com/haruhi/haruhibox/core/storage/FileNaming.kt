package com.haruhi.haruhibox.core.storage

import java.util.Locale

/**
 * 文件名/扩展名工具，移植自参考实现 `shared/util/FileUtil.java`：
 * - `getBaseName`（第 159 行起）：去扩展名
 * - `getFileExtension`（第 149 行起）：取扩展名（小写，无扩展名或以点结尾返回 null）
 * - `sanitizeFileName`（第 197 行起）：生成同时兼容 Windows 与 Linux 的文件名
 *
 * 参考实现的注释明确要求：`sanitizeFileName` 只处理**单个名字**（不含目录部分），
 * 且必须与 Linux 落盘名称保持一致，所以这里统一按更严格的 Windows 规则处理。
 */
object FileNaming {

    /** Windows 非法文件名字符：`\ / : * ? " < > |` */
    private val INVALID_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

    /** Windows 保留设备名，不能作为文件（夹）名，不区分大小写 */
    private val WINDOWS_RESERVED_FILE_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** 去扩展名；无扩展名时原样返回。等价于参考实现 `FileUtil.getBaseName`。 */
    fun baseName(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex == -1) return fileName
        return fileName.substring(0, dotIndex)
    }

    /** 取扩展名（小写）；无扩展名或以点结尾时返回 null。等价于 `FileUtil.getFileExtension`。 */
    fun extension(fileName: String): String? {
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex == -1 || dotIndex == fileName.length - 1) return null
        return fileName.substring(dotIndex + 1).lowercase(Locale.ROOT)
    }

    /**
     * 生成同时兼容 Windows 和 Linux 的文件（夹）名。
     *
     * 处理内容：非法字符替换为 `-`、去掉控制字符、去掉首尾空白、去掉 Windows 下不能作为
     * 结尾的点和空格、规避 Windows 保留设备名；结果最多保留 [maxBytes] 个 UTF-8 字节，
     * 按**字符边界**截断（不会截出半个字符）。
     *
     * @param name     原始名字
     * @param fallback 清洗后没有可用字符时的兜底名字（兜底名字同样会做清洗与去尾）
     * @param maxBytes 结果最大字节数（UTF-8），必须大于 0，否则不截断
     */
    fun sanitize(name: String?, fallback: String?, maxBytes: Int): String {
        val cleaned = if (name.isNullOrBlank()) "" else INVALID_FILE_NAME_CHARS.replace(name, "-")

        val sb = StringBuilder(cleaned.length)
        var bytes = 0
        var hasMeaningfulChar = false
        var i = 0
        while (i < cleaned.length) {
            val codePoint = cleaned.codePointAt(i)
            i += Character.charCount(codePoint)
            // 控制字符（含 \u0000）无法落盘
            if (Character.isISOControl(codePoint)) continue
            val charBytes = utf8Length(codePoint)
            if (maxBytes > 0 && bytes + charBytes > maxBytes) break
            sb.appendCodePoint(codePoint)
            bytes += charBytes
            // 只有非法字符（已替换为 '-'）和空白时，名字没有意义，应走兜底
            if (codePoint != '-'.code && !Character.isWhitespace(codePoint)) {
                hasMeaningfulChar = true
            }
        }

        var result = trimEndDotsAndBlank(sb.toString())
        if (!hasMeaningfulChar) {
            result = trimEndDotsAndBlank(if (!fallback.isNullOrBlank()) fallback else "unnamed")
        }
        if (WINDOWS_RESERVED_FILE_NAMES.contains(result.uppercase(Locale.ROOT))) {
            result += "_"
        }
        return result
    }

    /**
     * 本子文件夹名：`<清洗后的标题>_JM<aid>`。
     *
     * 移植自参考实现 `JmcomicService.buildAlbumFolderName`（第 1183～1189 行）：
     * **后缀不参与截断**，先按总数上限扣除后缀占用的字节数，避免整个名字超出文件系统单名上限。
     */
    fun albumFolderName(albumName: String?, aid: String, maxBytes: Int = DEFAULT_ALBUM_NAME_MAX_BYTES): String {
        val suffix = "_JM$aid"
        val base = sanitize(albumName, aid, maxBytes - suffix.toByteArray(Charsets.UTF_8).size)
        return base + suffix
    }

    /** 参考实现 `jm.album.name_max_length` 默认值（字节）。 */
    const val DEFAULT_ALBUM_NAME_MAX_BYTES = 150

    /**
     * 去掉首尾空白，并去掉末尾的点（末尾是点或空格时 Windows 无法创建该文件）。
     */
    private fun trimEndDotsAndBlank(text: String): String {
        var end = text.length
        while (end > 0 && (text[end - 1] == '.' || Character.isWhitespace(text[end - 1]))) {
            end--
        }
        var start = 0
        while (start < end && Character.isWhitespace(text[start])) {
            start++
        }
        return text.substring(start, end)
    }

    /** 单个码点编码为 UTF-8 后的字节数。 */
    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** 章节与图片排序用的序号提取（参考实现 `extractImageNumber`）。 */
    fun extractImageNumber(imageName: String): Int = imageName.toIntOrNull() ?: 0

    /** 章节排序用的序号提取（参考实现 `extractChapterNumber`，正则 `第(\d+)话`）。 */
    fun extractChapterNumber(folderName: String): Int {
        val head = folderName.substringBefore('_')
        val match = CHAPTER_NUMBER_REGEX.find(head) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private val CHAPTER_NUMBER_REGEX = Regex("第(\\d+)话")

    /** 参考实现 `JmcomicService.sortImageFiles`：按去扩展名后的数字升序，空串过滤。 */
    fun sortImageFiles(imageFiles: List<String>): List<String> =
        imageFiles.filter { it.isNotBlank() }
            .sortedBy { extractImageNumber(baseName(it)) }

    /** 参考实现 `JmcomicService.sortFolders`：按 `第N话` 的数字升序。 */
    fun sortChapterFolders(folderNames: List<String>): List<String> =
        folderNames.sortedBy { extractChapterNumber(it) }
}
