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
 *   reader-cache/              阅读器**原图**缓存（新取图管线：站点原始字节，未解扰）
 *     <aid>/<cid>/00001.webp
 *   tmp/                       下载中转（.tmp / .part）＋ PDF 生成临时文件（PDFBox*.tmp），可随时整体清理
 *   export/                    导出中转（打包完成后交给 SAF/MediaStore，随后删除）
 *   crash/                     崩溃日志
 * ```
 *
 * `reader-cache` 与 `tmp` / `export` 一样是"可以随便删"的目录：里面存的是**站点原始字节**
 * （解扰前），删掉只是下次再联网取一次，不丢任何用户资产。它和 `library` 分开正是为了
 * 让两套取图管线互不污染——`library` 里永远是**解扰后的成品**（下载产物、可直接导出），
 * `reader-cache` 里永远是原始字节（见 `core/settings/ReaderCacheMode.kt`）。
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
     * 阅读器**原图**缓存根目录（新取图管线）：`reader-cache/<aid>/<cid>/<文件名>`。
     *
     * 存的是站点原始字节（未解扰、未重编码）。这是纯缓存，不参与"是否已下载"的判断，
     * 也不给导出用；`tmp` / `export` 一样随时可整体清理。
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
     * 某本的阅读原图缓存目录：`reader-cache/<aid>`。
     *
     * 只用**本子号**（不用书库目录名）：原图缓存不参与"是否已下载"，也不该被
     * 重命名 / 移出书库之类的动作影响。
     */
    fun readerCacheAlbumDir(albumId: Long): File = File(readerCache, albumId.toString())

    /** 某一话的阅读原图缓存目录：`reader-cache/<aid>/<cid>`。 */
    fun readerCacheChapterDir(albumId: Long, chapterId: Long): File =
        File(readerCacheAlbumDir(albumId), chapterId.toString())

    /** 一键建目录，App 启动时调用一次。 */
    fun ensureAll(): LibraryDirs {
        listOf(root, library, covers, readerCache, tmp, export, crash).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                error("无法创建目录：${dir.absolutePath}")
            }
        }
        return this
    }

    /** 临时文件目录（每次启动清一次），下载中转都放这里。 */
    fun newTempFile(prefix: String, suffix: String = ".tmp"): File = File.createTempFile(prefix, suffix, tmp.also { it.mkdirs() })

    /**
     * 清理可释放空间：`tmp`、`export` 与 `reader-cache`（原图缓存）。
     * 返回释放的字节数。**不动** library / covers（用户资产）。
     *
     * `reader-cache` 里是解扰前的原始字节，删掉只是下次再联网取一次，所以算"可缓存"。
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
