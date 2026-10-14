package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "把一本漫画从这台设备上彻底删掉"的编排。
 *
 * 一本漫画在本机有**六处**痕迹，缺一处就会留下"删不干净"的观感：
 *
 * | 位置 | 内容 | 谁负责 |
 * |---|---|---|
 * | `files/library/jm/<本子目录>` | 章节图片 | [Repository.deleteAlbumFiles] |
 * | `files/library/export/<目录名>.zip|pdf` | 未发布完的导出**半成品** | [ExportManager.deleteTemps] |
 * | 系统下载目录 `Download/JMComicH` | 导出的 zip / pdf **成品** | [ExportManager.deleteOutputs]（仅"删除本地文件"） |
 * | `t_download_task` | 这本的下载 / 导出任务行 | [deleteFiles] / [removeFromLibrary] 内联清理 |
 * | Coil 磁盘缓存 `files/covers` | 封面 | [CoverCache.evict] |
 * | 数据库 | 书库成员 / 章节清单 / 收藏关联 / 阅读位置 | [Repository.deleteAlbums] |
 *
 * 注意：**漫画基础数据表（`t_jm_album`）不在这里删**——它是永久缓存，
 * 移出书库后详情页/阅读器仍能离线打开；封面缓存也不以它为依据
 * （它不做展示，展示来自书库、收藏夹、最近预览三个入口）。
 *
 * 两个入口的差别：
 * - **删除本地文件**：图片 + 导出成品 + 任务行 + 半成品都清，保留书库记录与封面；
 * - **从书库移除**：在上面的基础上多清库表成员关系（`t_jm_library` 等），
 *   但**导出成品保留**（用户拍板：已导出到系统下载目录的包不该被顺手删掉），
 *   封面按"最近浏览里还有没有这本"决定。
 *
 * ## 封面什么时候才该清（用户第八轮要求第 3 点）
 *
 * 封面缓存的唯一用途是"列表里显示这本的封面"，而**三个页面都会用到它：书库、收藏夹、最近浏览**。
 * 所以只要这本还会出现在任何一个页面上，封面就不能删：
 *
 * | 动作 | 记录变化 | 封面 |
 * |---|---|---|
 * | 删除本地文件 | 不变 | **保留**（三个页面都还要显示它） |
 * | 从书库移除 | 书库/章节/收藏关联/阅读位置 | 最近浏览里**还有**就保留，没有才清 |
 * | 清空/删除最近浏览 | 最近浏览 | 书库里**还有**就保留，没有才清 |
 *
 * 判断方式就是删除后去问一句另一张表还在不在（[Repository.findAlbum] / [Repository.findRecent]），
 * 而不是猜"用户大概还会不会看"。
 *
 * 封面缓存是 Coil 的磁盘缓存（按 URL 哈希存文件），所以只能按 key 逐条失效，
 * 不能"删掉某个文件"——这也是它单独抽一个接口的原因。
 */
interface CoverCache {
    /** 让这张封面从内存与磁盘缓存里失效；下次显示会重新联网取。 */
    fun evict(albumId: Long)
}

/** 删除结果，用于给用户一句准确的提示。 */
data class AlbumCleanup(
    val freedBytes: Long,
    val removedExports: Int,
    val removedRecords: Boolean,
)

/**
 * 删除编排。
 *
 * 从书库移除时要先停掉这本还在跑的任务（导出停 Job、下载落 `PAUSED`），
 * 否则下载/导出会继续往刚被删掉的目录里写东西。
 */
class AlbumCleaner(
    private val repo: Repository,
    private val exports: ExportManager,
    private val tasks: com.haruhi.jmcomic.core.download.DownloadTaskRepository,
    private val coverCache: CoverCache,
) {

    /**
     * 只删本地文件：图片 + 导出成品 + 任务行 + 导出半成品，**保留记录与封面**。
     *
     * 会先把这本**在跑的任务**停掉（下载落 `PAUSED`、导出停 Job）再删任务行，
     * 否则任务会继续往刚被删掉的目录里写东西；导出**成品**在系统下载目录，这里一并删。
     */
    suspend fun deleteFiles(albums: List<AlbumEntity>): AlbumCleanup = withContext(Dispatchers.IO) {
        var freed = 0L
        var removedExports = 0
        albums.forEach { album ->
            stopTasksAndTemps(album)
            freed += runCatching { repo.deleteAlbumFiles(album.albumFolderName) }
                .onFailure { Timber.tag("jm").w(it, "删除本子目录失败：%s", album.albumFolderName) }
                .getOrDefault(0L)
            removedExports += exports.deleteOutputs(album)
        }
        AlbumCleanup(freed, removedExports, removedRecords = false)
    }

    /**
     * 从书库移除：**记录 + 本地图片 + 任务行 + 半成品**全清，**导出成品保留**。
     *
     * 与「删除本地文件」的差别在于**要不要连记录一起删**、以及**导出成品保不保留**；
     * 移出书库只是把这本书从书库里摘掉，已经导出到系统下载目录的 zip/pdf 不该被顺手删。
     *
     * 「最近浏览」本身**不动**（用户第八轮要求第 5 点）：移出书库影响的是书库与本地文件，
     * "刚看过什么"是另一件事——保留后最近列表里还能看到这本、点进去也能重新加入书库。
     * 既然它还在最近页上露面，封面就不能删（否则最近页上那块会变成重新联网拉图）。
     */
    suspend fun removeFromLibrary(albums: List<AlbumEntity>): AlbumCleanup = withContext(Dispatchers.IO) {
        var freed = 0L
        albums.forEach { album ->
            stopTasksAndTemps(album)
            // 删本地图片目录
            freed += runCatching { repo.deleteAlbumFiles(album.albumFolderName) }
                .onFailure { Timber.tag("jm").w(it, "删除本子目录失败：%s", album.albumFolderName) }
                .getOrDefault(0L)
        }

        // 删书库记录（成员 / 章节清单 / 收藏关联 / 阅读位置）
        val ids = albums.map { it.id }
        runCatching { repo.deleteAlbums(ids) }
            .onFailure { Timber.tag("jm").w(it, "删除书库记录失败：%s", ids) }

        // 封面：书库与最近浏览里都没有了才清
        evictCoversIfUnused(ids)

        AlbumCleanup(freed, removedExports = 0, removedRecords = true)
    }

    /**
     * 停掉这本在跑的任务、删掉任务行（含章节明细），并清理私有目录里的导出半成品。
     *
     * 下载任务用 `DownloadTaskRepository.markPaused` 落库、导出任务用
     * [ExportManager.cancel] 停 Job：执行器每个安全检查点都读任务状态，
     * 状态落库 / 行被删之后协程自会停下，不会继续往刚被删掉的目录里写东西。
     */
    private suspend fun stopTasksAndTemps(album: AlbumEntity) {
        runCatching {
            tasks.tasksByBizKey(album.id.toString()).forEach { task ->
                if (task.type.isExport) exports.cancel(task.id) else tasks.markPaused(task.id)
                tasks.delete(task.id)
            }
        }.onFailure { Timber.tag("jm").w(it, "清理任务失败：%s", album.id) }
        runCatching { exports.deleteTemps(album) }
            .onFailure { Timber.tag("jm").w(it, "清理导出半成品失败：%s", album.albumFolderName) }
    }

    /** 清空最近浏览：记录清掉，**不在书库里的**那些封面也一起失效。 */
    suspend fun clearRecent(): Int {
        val ids = repo.recentAlbumIds()
        val removed = repo.clearRecent()
        evictCoversIfUnused(ids)
        return removed
    }

    /** 删除一条最近浏览记录（同上：书库里没有才清封面）。 */
    suspend fun removeRecent(albumId: Long) {
        repo.removeRecent(albumId)
        evictCoversIfUnused(listOf(albumId))
    }

    /**
     * 这些本子如果**书库与最近浏览里都没有了**，封面缓存就没用了，清掉。
     *
     * 必须在记录删完之后调用——判断依据正是"两张表里还在不在"。
     */
    private suspend fun evictCoversIfUnused(albumIds: List<Long>) {
        albumIds.forEach { id ->
            val stillUsed = runCatching {
                repo.findAlbum(id) != null || repo.findRecent(id) != null
            }.getOrDefault(true) // 查不动就当还在用，宁可留一份缓存也别误删
            if (!stillUsed) coverCache.evict(id)
        }
    }
}
