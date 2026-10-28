package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.storage.ExportPublisher
import com.haruhi.jmcomic.core.storage.FileNaming
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.core.storage.OfficialZipPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 「关于」页存储分段条的一段。声明顺序 = 条上从左到右的段序。 */
enum class StorageKind(val label: String) {
    /** 已下载的漫画图片（`library/<本子目录>/`）。 */
    LIBRARY("图片缓存"),//现在不分下载的图片和阅读缓存了，下载的图片其实就是阅读缓存，都是原始图片字节

    /** 封面磁盘缓存（Coil 管理，含头像等，不按本归属）。 */
    COVERS("封面缓存"),

    EXPORT_ZIP("导出 ZIP"),

    EXPORT_PDF("导出 PDF"),

    /** 官方 App 的整话 ZIP：`Download/JMComicH/官方zip/<本子>/`。 */
    OFFICIAL_ZIP("官方 ZIP"),

    /** **旧版**阅读器缓存（`reader-cache/<aid>/`）；新版本不再产生，只保留清理入口。 */
    READER_CACHE("阅读缓存"),
}

/** 某一本（或无法归属的条目）在某类存储下的占用。 */
data class AlbumUsage(
    /** 能跳详情页的本子号；null = 无法归属的"其他"行（只展示，不跳转）。 */
    val albumId: Long?,
    val title: String,
    val sizeBytes: Long,
    /** 附加说明行（zip / pdf 行放文件名）。 */
    val detail: String? = null,
    /**
     * 删除键：图片库行 = 本子目录名；阅读缓存行 = `reader-cache/` 下的目录名。
     * zip / pdf 行不需要它（[detail] 的文件名就是删除键）。
     */
    val folderName: String? = null,
)

data class SegmentStats(
    val kind: StorageKind,
    val totalBytes: Long,
    /** 按大小降序；null = 该类不提供按本钻取（封面缓存）。 */
    val items: List<AlbumUsage>?,
)

data class StorageStats(val segments: List<SegmentStats>) {
    val totalBytes: Long get() = segments.sumOf { it.totalBytes }

    fun segment(kind: StorageKind): SegmentStats? = segments.firstOrNull { it.kind == kind }
}

/**
 * 「关于」页的本地存储统计：一次扫描同时产出每类的总量与按本明细，
 * 之后在页面上切换分段**不再有 IO**。
 *
 * ## 归属规则（两级匹配）
 *
 * 每类内容都能落到"某一本"上，靠的是统一命名约定：
 * - 漫画图片目录名 = `<清洗后的标题>_JM<aid>`（[FileNaming.albumFolderName]）；
 * - reader-cache 目录名 = `<aid>`；
 * - 导出成品文件名 = `<本子目录名>.zip|pdf`（系统重名时可能被追加 ` (1)`，
 *   匹配前先用 [FileNaming.normalizeExportName] 还原）。
 *
 * 先按**目录名 / 文件基名**精确配 `t_jm_album.album_folder_name`；
 * 配不上再从名字尾缀 `_JM<aid>` 解析 aid 配 `t_jm_album.id`（覆盖标题清洗规则
 * 变更导致目录名与库记录不一致的旧数据）。仍未配上的进"其他"行，只展示不跳转。
 *
 * ## 封面缓存只有总量
 *
 * `covers/` 是 Coil 的磁盘缓存，文件按 URL 哈希命名、与头像混存，
 * 没有可靠的按本归属（用户已拍板：封面段不做钻取，`items = null`）。
 *
 * ## 与系统状态的并发
 *
 * 只读统计：下载进行中数字会略有出入（读到半成品），`sizeOf` 对目录为空 /
 * 文件消失都返回 0，不抛、不崩，下次重扫自然校正。
 */
class StorageStatsCollector(
    private val repo: Repository,
    private val dirs: LibraryDirs,
    private val exports: ExportPublisher,
    private val officialZips: OfficialZipPublisher,
) {

    /** @return 统计结果；扫描失败返回 null（界面显示"读取失败 + 重试"）。 */
    suspend fun collect(): StorageStats? = withContext(Dispatchers.IO) {
        runCatching { collectInternal() }
            .onFailure { Timber.tag("about").w(it, "存储统计失败") }
            .getOrNull()
    }

    private suspend fun collectInternal(): StorageStats {
        // t_jm_album 是永久缓存（移出书库的行也在），归属覆盖面最大
        val albums = repo.albumBasicsAll()
        val byId = HashMap<Long, AlbumBaseEntity>(albums.size)
        val byFolder = HashMap<String, AlbumBaseEntity>()
        albums.forEach { album ->
            byId[album.id] = album
            album.albumFolderName?.takeIf { it.isNotBlank() }?.let { byFolder[it] = album }
        }

        val library = scanLibrary(byFolder, byId)
        val readerCache = scanReaderCache(byId)
        val (zips, pdfs) = scanExports(byFolder, byId)
        val officialZip = scanOfficialZips(byFolder, byId)

        return StorageStats(
            segments = listOf(
                SegmentStats(StorageKind.LIBRARY, library.sumOf { it.sizeBytes }, library),
                SegmentStats(StorageKind.COVERS, dirs.sizeOf(dirs.covers), null),
                SegmentStats(StorageKind.EXPORT_ZIP, zips.sumOf { it.sizeBytes }, zips),
                SegmentStats(StorageKind.EXPORT_PDF, pdfs.sumOf { it.sizeBytes }, pdfs),
                SegmentStats(
                    StorageKind.OFFICIAL_ZIP,
                    officialZip.sumOf { it.sizeBytes },
                    officialZip,
                ),
                SegmentStats(StorageKind.READER_CACHE, readerCache.sumOf { it.sizeBytes }, readerCache),
            ),
        )
    }

    /** 漫画图片：`library/<本子目录>`，每个目录一本。 */
    private fun scanLibrary(
        byFolder: Map<String, AlbumBaseEntity>,
        byId: Map<Long, AlbumBaseEntity>,
    ): List<AlbumUsage> =
        dirs.library.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val size = dirs.sizeOf(dir)
                if (size <= 0) return@mapNotNull null
                val aid = aidFromFolderName(dir.name)
                val album = byFolder[dir.name] ?: aid?.let { byId[it] }
                // 配不上库的目录：目录名本身可读（含标题），能解析出 aid 也允许跳详情
                AlbumUsage(
                    albumId = album?.id ?: aid,
                    title = album?.let(::titleOf) ?: dir.name,
                    sizeBytes = size,
                    folderName = dir.name,
                )
            }
            .sortedByDescending { it.sizeBytes }

    /** **旧版**阅读缓存：`reader-cache/<aid>`，目录名就是本子号；新版本不再写入。 */
    private fun scanReaderCache(byId: Map<Long, AlbumBaseEntity>): List<AlbumUsage> =
        dirs.readerCache.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val size = dirs.sizeOf(dir)
                if (size <= 0) return@mapNotNull null
                val aid = dir.name.toLongOrNull()
                val album = aid?.let { byId[it] }
                AlbumUsage(
                    albumId = album?.id ?: aid,
                    title = album?.let(::titleOf) ?: "JM${dir.name}",
                    sizeBytes = size,
                    folderName = dir.name,
                )
            }
            .sortedByDescending { it.sizeBytes }

    /** 导出成品：一次 MediaStore 查询拿全（`listAll` 内部已兜底为空表）。 */
    private fun scanExports(
        byFolder: Map<String, AlbumBaseEntity>,
        byId: Map<Long, AlbumBaseEntity>,
    ): Pair<List<AlbumUsage>, List<AlbumUsage>> {
        val zips = mutableListOf<AlbumUsage>()
        val pdfs = mutableListOf<AlbumUsage>()
        for (file in exports.listAll()) {
            val size = file.sizeBytes.coerceAtLeast(0L)
            if (size <= 0L) continue
            // 先去掉系统重名追加的序号再解析：旧安装留下的同名文件会把本安装的
            // 成品挤成 `xxx (1).zip`（Android 10 是 `xxx.zip (1)`），直接按原名
            // 解析既认不出扩展名，也解析不出 `_JM<aid>`，整行会掉进"无法归属"。
            val normalized = FileNaming.normalizeExportName(file.displayName)
            val extension = FileNaming.extension(normalized) ?: continue
            if (extension != "zip" && extension != "pdf") continue
            val base = FileNaming.baseName(normalized)
            val aid = aidFromFolderName(base)
            val album = byFolder[base] ?: aid?.let { byId[it] }
            val usage = AlbumUsage(
                albumId = album?.id ?: aid,
                title = album?.let(::titleOf) ?: base,
                sizeBytes = size,
                detail = file.displayName,
            )
            if (extension == "zip") zips += usage else pdfs += usage
        }
        return zips.sortedByDescending { it.sizeBytes } to pdfs.sortedByDescending { it.sizeBytes }
    }

    /**
     * 官方 ZIP：只统计**直接放在** `官方zip/<本子>/` 下的 `.zip`，按本子聚合。
     *
     * 解压出来的文件夹（`官方zip/<本子>/<解压目录>/`）不会被 [OfficialZipPublisher.listAll]
     * 返回，所以既不进总量，也不会被删除。
     */
    private fun scanOfficialZips(
        byFolder: Map<String, AlbumBaseEntity>,
        byId: Map<Long, AlbumBaseEntity>,
    ): List<AlbumUsage> =
        officialZips.listAll()
            .filter { it.sizeBytes > 0L }
            .groupBy { it.albumFolder }
            .map { (folder, files) ->
                val aid = aidFromFolderName(folder)
                val album = byFolder[folder] ?: aid?.let { byId[it] }
                AlbumUsage(
                    albumId = album?.id ?: aid,
                    title = album?.let(::titleOf) ?: folder,
                    sizeBytes = files.sumOf { it.sizeBytes },
                    detail = "${files.size} 个 zip",
                    folderName = folder,
                )
            }
            .sortedByDescending { it.sizeBytes }

    companion object {
        /** 本子目录名的尾缀约定：`<清洗后的标题>_JM<aid>`（后缀不参与截断）。 */
        private val FOLDER_AID_SUFFIX = Regex("""_JM(\d+)$""")

        /** 从本子目录名 / 成品文件基名解析 aid；不符合约定返回 null。 */
        internal fun aidFromFolderName(name: String): Long? =
            FOLDER_AID_SUFFIX.find(name)?.groupValues?.get(1)?.toLongOrNull()

        /** 列表展示名：标题优先，空白回落 JM 号。 */
        internal fun titleOf(album: AlbumBaseEntity): String =
            album.name?.takeIf { it.isNotBlank() } ?: "JM${album.id}"
    }
}
