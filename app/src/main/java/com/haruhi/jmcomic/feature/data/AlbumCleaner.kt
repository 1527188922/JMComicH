package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.core.storage.OfficialZipPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "把一本漫画从这台设备上彻底删掉"的编排。
 *
 * 一本漫画在本机有**八处**痕迹，缺一处就会留下"删不干净"的观感：
 *
 * | 位置 | 内容 | 谁负责 |
 * |---|---|---|
 * | `files/library/<本子目录>` | 章节图片 | [Repository.deleteAlbumFiles] |
 * | `files/library/reader-cache/<aid>` | **旧版**阅读缓存 | [deleteReaderCache] |
 * | `files/library/export/<目录名>.zip|pdf` | 未发布完的导出**半成品** | [ExportManager.deleteTemps] |
 * | `files/library/tmp/official_zip_<aid>_*` | 官方 ZIP 的 `.zip` / `.part` 中转文件 | [deleteOfficialZipTemps] |
 * | 系统下载目录 `Download/JMComicH` | 导出的 zip / pdf **成品** | [ExportManager.deleteOutputs]（仅"删除本地文件"） |
 * | 系统下载目录 `Download/JMComicH/官方zip/<本子>` | 官方 ZIP **成品**（只含直接 `.zip`，解压目录不动） | [OfficialZipPublisher.deleteAlbum]（仅"删除本地文件"） |
 * | `t_download_task` | 这本的下载 / 导出任务行 | [stopTasksAndTemps] |
 * | 数据库 | 书库成员 / 章节清单 / 收藏关联 / 阅读位置 | [Repository.deleteAlbums]（仅"移出书库"） |
 *
 * 注意：**漫画基础数据表（`t_jm_album`）不在这里删**——它是永久缓存，
 * 移出书库后详情页/阅读器仍能离线打开。
 *
 * **封面缓存也不在这里删**：它是 Coil 的磁盘缓存（按 URL 哈希存文件，与头像等混存），
 * 无法可靠地按本归属。全应用只在「关于 → 存储占用 → 封面缓存」里整体清除，
 * 其余任何删除动作都不动它。
 *
 * 两个入口的差别：
 * - **删除本地文件**：图片 + 导出成品 + 官方 ZIP 成品 + 任务行 + 半成品/中转文件都清，
 *   保留书库记录、封面与最近浏览；
 * - **从书库移除**：清图片 + 半成品/中转文件 + 任务行 + 库表成员关系（`t_jm_library` 等），
 *   但**导出成品与官方 ZIP 成品保留**（用户拍板：已经落到系统下载目录的包不该被顺手删掉），
 *   封面与最近浏览同样保留。
 */

/** 删除结果，用于给用户一句准确的提示。 */
data class AlbumCleanup(
    val freedBytes: Long,
    val removedExports: Int,
    val removedRecords: Boolean,
)

/**
 * 删除编排。
 *
 * 删除前要先停掉这本还在跑的任务（导出停 Job、图片下载落 `PAUSED`、
 * 官方 ZIP 等协程退出），否则下载/导出会继续往刚被删掉的目录里写东西。
 */
class AlbumCleaner(
    private val repo: Repository,
    private val exports: ExportManager,
    private val tasks: com.haruhi.jmcomic.core.download.DownloadTaskRepository,
    /**
     * 目录规划：用来清掉这本的**旧版阅读缓存**（`reader-cache/<aid>`）与官方 ZIP 中转文件。
     *
     * 旧版阅读缓存新版本不再产生；升级用户可能还有，所以"删除本地文件 / 移出书库"
     * 时一起清掉，免得旧缓存白占空间。
     */
    private val dirs: LibraryDirs,
    /** 官方 ZIP 成品落盘器；null = 当前构建不支持官方 ZIP（只影响"删除本地文件"）。 */
    private val officialZips: OfficialZipPublisher? = null,
    /**
     * 同步停掉一个官方 ZIP 任务并等它的执行协程退出。
     *
     * 注入而不是直接依赖 `DownloadManager`，是为了让本类保持可单测；
     * 生产环境接 [com.haruhi.jmcomic.feature.data.DownloadManager.cancelAndWait]。
     */
    private val stopOfficialZipTask: suspend (Long) -> Unit = {},
) {

    /**
     * 只删本地文件：图片 + 旧版阅读缓存 + 导出成品 + 官方 ZIP 成品 + 任务行 + 半成品/中转文件，
     * **保留记录、封面与最近浏览**。
     *
     * 会先把这本**在跑的任务**停掉（下载落 `PAUSED`、导出停 Job、官方 ZIP 等协程退出）
     * 再删任务行与中转文件，否则任务会继续往刚被删掉的目录里写东西。
     * 官方 ZIP 只删 `官方zip/<本子>/` 下**直接放置的 `.zip`**，用户解压出来的文件夹不动。
     */
    suspend fun deleteFiles(albums: List<AlbumEntity>): AlbumCleanup = withContext(Dispatchers.IO) {
        var freed = 0L
        var removedExports = 0
        albums.forEach { album ->
            val folder = album.folderNameOrFallback()
            stopTasksAndTemps(album)
            freed += runCatching { repo.deleteAlbumFiles(folder) }
                .onFailure { Timber.tag("jm").w(it, "删除本子目录失败：%s", folder) }
                .getOrDefault(0L)
            freed += deleteReaderCache(album)
            removedExports += exports.deleteOutputs(album)
            freed += runCatching { officialZips?.deleteAlbum(folder) ?: 0L }
                .onFailure { Timber.tag("jm").w(it, "删除官方 ZIP 失败：%s", folder) }
                .getOrDefault(0L)
        }
        AlbumCleanup(freed, removedExports, removedRecords = false)
    }

    /**
     * 从书库移除：**记录 + 本地图片 + 任务行 + 半成品/中转文件**全清，
     * **导出成品、官方 ZIP 成品、封面与最近浏览都保留**。
     *
     * 与「删除本地文件」的差别在于**要不要连记录一起删**、以及**系统下载目录里的成品保不保留**；
     * 移出书库只是把这本书从书库里摘掉，已经导出 zip/pdf 或官方 ZIP 不该被顺手删。
     *
     * 「最近浏览」本身**不动**（用户第八轮要求第 5 点）：移出书库影响的是书库与本地文件，
     * "刚看过什么"是另一件事——保留后最近列表里还能看到这本、点进去也能重新加入书库。
     */
    suspend fun removeFromLibrary(albums: List<AlbumEntity>): AlbumCleanup = withContext(Dispatchers.IO) {
        var freed = 0L
        albums.forEach { album ->
            val folder = album.folderNameOrFallback()
            stopTasksAndTemps(album)
            freed += runCatching { repo.deleteAlbumFiles(folder) }
                .onFailure { Timber.tag("jm").w(it, "删除本子目录失败：%s", folder) }
                .getOrDefault(0L)
            freed += deleteReaderCache(album)
        }

        // 删书库记录（成员 / 章节清单 / 收藏关联 / 阅读位置）
        val ids = albums.map { it.id }
        runCatching { repo.deleteAlbums(ids) }
            .onFailure { Timber.tag("jm").w(it, "删除书库记录失败：%s", ids) }

        AlbumCleanup(freed, removedExports = 0, removedRecords = true)
    }

    /**
     * 停掉这本在跑的任务、删掉任务行（含章节明细），并清理私有目录里的中转文件。
     *
     * - 导出任务：调 [ExportManager.cancel] 停 Job，再删任务行；
     * - 官方 ZIP 任务：先 [stopOfficialZipTask] 等执行协程退出（它内部会清 `.part`），
     *   再删任务行、按本子号清一遍 `official_zip_<aid>_*` 中转文件（兜住异常中断留下的孤儿）；
     * - 图片下载任务：落 `PAUSED` 后删任务行。执行器每个安全检查点都读任务状态，
     *   状态落库 / 行被删之后协程自会停下，不会继续往刚被删掉的目录里写东西。
     *
     * 官方 ZIP 只清中转文件，**不碰公共目录里的成品**；解压出来的文件夹也不动。
     */
    private suspend fun stopTasksAndTemps(album: AlbumEntity) {
        runCatching {
            tasks.tasksByBizKey(album.id.toString()).forEach { task ->
                when {
                    task.type.isExport -> exports.cancel(task.id)
                    task.type == DownloadType.JM_OFFICIAL_ZIP -> stopOfficialZipTask(task.id)
                    else -> tasks.markPaused(task.id)
                }
                tasks.delete(task.id)
            }
        }.onFailure { Timber.tag("jm").w(it, "清理任务失败：%s", album.id) }
        deleteOfficialZipTemps(album.id)
        runCatching { exports.deleteTemps(album) }
            .onFailure { Timber.tag("jm").w(it, "清理导出半成品失败：%s", album.folderNameOrFallback()) }
    }

    /** 清空最近浏览：只清记录，**不动任何文件与封面缓存**。 */
    suspend fun clearRecent(): Int = repo.clearRecent()

    /**
     * 删掉这本的**旧版**阅读缓存（`reader-cache/<aid>`），返回释放的字节数。
     *
     * 只在本子被"删除本地文件 / 移出书库"时调用；新版本不再产生它。
     */
    private fun deleteReaderCache(album: AlbumEntity): Long {
        val dir = dirs.readerCacheAlbumDir(album.id)
        val freed = dirs.sizeOf(dir)
        runCatching { dir.deleteRecursively() }
            .onFailure { Timber.tag("jm").w(it, "清理旧版阅读缓存失败：%d", album.id) }
        return freed
    }

    /** 删除一条最近浏览记录：只清记录，**不动任何文件与封面缓存**。 */
    suspend fun removeRecent(albumId: Long) = repo.removeRecent(albumId)

    /**
     * 清掉这本官方 ZIP 的全部中转文件（`files/library/tmp/official_zip_<aid>_*.zip` 与其 `.part`）。
     *
     * 按本子号扫目录而不是只按任务里的章节清，是为了兜住异常中断后任务行已不在、
     * 中转文件却留在 `tmp/` 的孤儿。解压出来的文件夹在公共下载目录，不在 `tmp/`，不受影响。
     */
    private fun deleteOfficialZipTemps(albumId: Long) {
        val prefix = "official_zip_${albumId}_"
        runCatching {
            dirs.tmp.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(prefix) }
                .forEach { file -> file.delete() }
        }.onFailure { Timber.tag("jm").w(it, "清理官方 ZIP 中转文件失败：%d", albumId) }
    }

    /** 与下载/导出落盘时的兜底一致：库记录没有目录名时按 `JM<id>` 找目录。 */
    private fun AlbumEntity.folderNameOrFallback(): String =
        albumFolderName?.takeIf { it.isNotBlank() } ?: "JM$id"
}
