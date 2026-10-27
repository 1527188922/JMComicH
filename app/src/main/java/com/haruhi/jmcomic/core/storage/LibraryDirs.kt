package com.haruhi.jmcomic.core.storage

import java.io.File

/**
 * 本地目录规划。
 *
 * 计划 §8 的约束：**库文件全部放应用私有目录**，避免分区存储限制；
 * 导出成品（zip/pdf）走 MediaStore/SAF，不落在私有目录里当"成品库"。
 *
 * 目录结构（`root` = `filesDir/library`，见 `AppContainer.LIBRARY_DIR_NAME`）：
 * ```
 * <root>/
 *   library/                   漫画库
 *     <本子文件夹>/             <清洗后的标题>_JM<aid>
 *       <章节文件夹>/           第N话
 *         00001.webp …
 *   covers/                    封面缓存（Coil 磁盘缓存）
 *   reader-cache/               **旧版**阅读缓存（升级用户可能还有；新版本不再写入）
 *     <aid>/<cid>/00001.webp
 *   tmp/                       下载中转（.tmp / .part）＋ PDF 生成临时文件（PDFBox*.tmp），可随时整体清理
 *   export/                    导出中转（打包完成后交给 SAF/MediaStore，随后删除）
 *   crash/                     崩溃日志
 * ```
 *
 * 2026-10 起 `library/` 本身就是**站点原始字节**（阅读/导出时现场解扰），所以新版本
 * **不再产生 `reader-cache`**：这个目录只作为旧安装的遗留缓存保留——统计页还能看到、
 * 「清理缓存」还能一键清掉，但新代码一行都不往里写。
 *
 * `tmp` 与 `export` 是"可以随便删"的目录；`library` / `covers` 是用户资产，
 * 只有用户显式删除才会动它们（见计划 §9.1「清理缓存不删除漫画及导出成品」）。
 */
class LibraryDirs(root: File) {

    val root: File = root

    /** 漫画库根目录。 */
    val library: File = File(root, LIBRARY_DIR_NAME)

    /** 封面缓存根目录（不计入用户资产）。 */
    val covers: File = File(root, "covers")

    /**
     * **旧版**阅读器缓存根目录：`reader-cache/<aid>/<cid>/<文件名>`。
     *
     * 2026-10 起新版本不再写入：`library/` 直接存站点原始字节，阅读时现场解扰。
     * 这里保留属性只为两件事：给升级用户一个**清理入口**（统计页 / `clearCache`），
     * 以及下载时把旧缓存当作本地种子复用一次。
     */
    val readerCache: File = File(root, "reader-cache")

    /** 临时目录：下载中转（`.tmp` / `.part`）与 PDF 生成临时文件（`PDFBox*.tmp`），可整体清理。 */
    val tmp: File = File(root, "tmp")

    /** 导出中转目录（zip/pdf 生成中），可整体清理。 */
    val export: File = File(root, "export")

    /** 崩溃日志目录。 */
    val crash: File = File(root, "crash")

    /** 本子目录：`library/<albumFolderName>`。 */
    fun albumDir(albumFolderName: String): File = File(library, albumFolderName)

    /** 章节目录：`library/<albumFolderName>/<chapterFolder>`。 */
    fun chapterDir(albumFolderName: String, chapterFolder: String): File =
        File(albumDir(albumFolderName), chapterFolder)

    /** 漫画封面缓存：`covers/jm/<aid>.jpg`。 */
    fun jmCoverFile(albumId: Long): File = File(File(covers, "jm"), "$albumId.jpg")

    /**
     * 某本的**旧版**阅读缓存目录：`reader-cache/<aid>`。
     *
     * 新版本不再写它；保留只为统计/清理旧安装的遗留缓存。
     */
    fun readerCacheAlbumDir(albumId: Long): File = File(readerCache, albumId.toString())

    /** 某一话的**旧版**阅读缓存目录：`reader-cache/<aid>/<cid>`。 */
    fun readerCacheChapterDir(albumId: Long, chapterId: Long): File =
        File(readerCacheAlbumDir(albumId), chapterId.toString())

    /** 一键建目录，App 启动时调用一次。**不再创建 `reader-cache`**（旧版遗留目录按需清理）。 */
    fun ensureAll(): LibraryDirs {
        listOf(root, library, covers, tmp, export, crash).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                error("无法创建目录：${dir.absolutePath}")
            }
        }
        return this
    }

    /** 临时文件目录（每次启动清一次），下载中转都放这里。 */
    fun newTempFile(prefix: String, suffix: String = ".tmp"): File = File.createTempFile(prefix, suffix, tmp.also { it.mkdirs() })

    /**
     * 清理可释放空间：`tmp`、`export` 与**旧版** `reader-cache`（如果升级用户还留着）。
     * 返回释放的字节数。**不动** library / covers（用户资产）。
     *
     * 新版本不写 `reader-cache`；清掉它只是删掉旧缓存，不丢任何用户资产。
     */
    fun clearCache(): Long {
        var freed = 0L
        for (dir in listOf(tmp, export, readerCache)) {
            freed += sizeOf(dir)
            dir.deleteRecursively()
        }
        return freed
    }

    /** 统计某目录占用（字节）。目录不存在返回 0。 */
    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    companion object {
        /** 与参考实现 `FileUtil.getJmcomicDir()` 的语义对应，只是根换成了应用私有目录。 */
        const val LIBRARY_DIR_NAME = "library"
    }
}
