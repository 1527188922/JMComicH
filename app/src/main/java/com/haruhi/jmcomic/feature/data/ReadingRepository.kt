package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.ChapterImageDao
import com.haruhi.jmcomic.core.database.PlaybackPositionDao
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.PlaybackKind
import com.haruhi.jmcomic.core.database.entity.PlaybackPositionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 阅读位置与章节清单的查询（计划 §9 第 8 条）。
 *
 * ## 位置用什么定位
 *
 * `本子 + 章节 + 图片文件名 + 页内比例`。**用文件名而不是页码**：
 * 章节重新拉取清单时页数可能变（JM 会补页），页码会错位，文件名不会。
 *
 * ## 为什么单独一个仓库
 *
 * [Repository] 已经承担了在线接口、入库、收藏、书库筛选；
 * 阅读位置属于"阅读器"这条线，混进去只会让它更难读。
 * 两者的边界很清楚：**这个类不联网、不碰文件**——
 * 图片在不在磁盘上、要不要联网取，全部由 [ImageStore] 负责。
 */
class ReadingRepository(
    private val positionDao: PlaybackPositionDao,
    private val chapterDao: ChapterImageDao,
) {

    // ------------------------------------------------------------------
    // 位置读写
    // ------------------------------------------------------------------

    fun observePosition(albumId: Long): Flow<ReadingPosition?> =
        positionDao.observe(PlaybackKind.JM, albumId.toString()).map { it?.toReadingPosition() }

    suspend fun positionOf(albumId: Long): ReadingPosition? =
        positionDao.find(PlaybackKind.JM, albumId.toString())?.toReadingPosition()

    /**
     * 保存阅读位置。
     *
     * `offsetRatio` 是**页内**滚动比例（长图一屏放不下时用），不是整章比例——
     * 整章比例会随着"读到第几张"一起变，两者混在一起恢复时必然错位。
     */
    suspend fun savePosition(
        albumId: Long,
        chapterId: Long,
        imageFile: String?,
        imageIndex: Int,
        offsetRatio: Float,
    ) {
        val ratio = offsetRatio.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        fun entity(kind: String) = PlaybackPositionEntity(
            kind = kind,
            bizKey = albumId.toString(),
            chapterId = chapterId,
            imageFile = imageFile,
            // JM 用 position 存图片序号（0 起），恢复时不必再按文件名反查
            position = imageIndex.toLong(),
            offsetRatio = ratio,
            updateTime = now,
        )
        // 逐本记录（每本一条，阅读器恢复用）
        positionDao.upsert(entity(PlaybackKind.JM))
        // 顺带覆盖「继续阅读」指针（全表此类只有一行）——它是书库卡片的**唯一数据源**。
        // 两个 kind 各写各的，所以"移出书库删逐本记录"不会波及指针。
        positionDao.replaceSingle(entity(CONTINUE_KIND))
    }

    suspend fun clearPosition(albumId: Long) = positionDao.delete(PlaybackKind.JM, albumId.toString())

    // ------------------------------------------------------------------
    // 目录与文件
    // ------------------------------------------------------------------

    /**
     * 某一话的**展示用**章节名（"上次读到 第2话"这类文案）。
     *
     * **不是磁盘目录名**：目录名由 `Repository.chaptersOf` 算好的
     * `ChapterSummary.folderName` 决定（标题重名时会带 `-<chapterId>`），
     * 两者刻意分开——之前这里叫 `chapterFolderName` 并被当成目录名用，
     * 正是"两话共用一个目录"那类 bug 的温床。
     *
     * 章节清单为空时返回 null（还没拉过清单）。
     */
    suspend fun chapterTitle(albumId: Long, chapterId: Long): String? {
        // 单话本子：章节 id 就是本子 id，展示名固定「第1话」。
        if (chapterId == albumId) return Repository.SINGLE_CHAPTER_TITLE
        val first = chapterDao.images(albumId, chapterId).firstOrNull() ?: return null
        return first.chapterTitle?.takeIf { it.isNotBlank() }
            ?: first.chapterName?.takeIf { it.isNotBlank() }
            ?: chapterId.toString()
    }

    /** 某一话已入库的图片清单（不管有没有下载）。 */
    suspend fun imagesOf(albumId: Long, chapterId: Long): List<ChapterImageEntity> =
        chapterDao.images(albumId, chapterId)

    fun observeImages(albumId: Long, chapterId: Long): Flow<List<ChapterImageEntity>> =
        chapterDao.observeImages(albumId, chapterId)

    /**
     * 书库页「继续阅读」卡片的数据源：**独立存在的唯一一条指针**。
     *
     * 与逐本阅读记录（[observePosition]/[positionOf] 用的 [PlaybackKind.JM]）分开存，
     * 所以"移出书库删掉这本的逐本记录"不会让卡片静默掉回一本更早读过的旧书
     * （本次修复的核心）。
     *
     * 返回 `null` 表示没有可继续的——从没读过，或指针被 ✕ / 指向的书被移出书库时
     * 清掉，书库页就不显示卡片（§9 第 4 条）。
     */
    fun observeContinueReading(): Flow<ReadingPosition?> =
        positionDao.observeLastForKind(CONTINUE_KIND).map { it?.toReadingPosition() }

    /**
     * 卡片右上角 ✕：只清这条指针，**不动**逐本阅读记录。
     *
     * 逐本记录留着，之后读同一本时还能从原位置续上；只是书库页不再显示卡片。
     */
    suspend fun clearContinueReading() = positionDao.clearKind(CONTINUE_KIND)

    private fun PlaybackPositionEntity.toReadingPosition() = ReadingPosition(
        albumId = bizKey.toLongOrNull() ?: 0L,
        chapterId = chapterId,
        imageFile = imageFile,
        imageIndex = position.toInt(),
        offsetRatio = offsetRatio,
        updatedAt = updateTime,
    )

    companion object {
        /**
         * `t_playback_position.kind`：**「继续阅读」指针**（此类只存一行）。
         * 取值见 [PlaybackKind.CONTINUE]（与"逐本阅读记录"共用一张表，所以常量统一放那边）。
         */
        const val CONTINUE_KIND = PlaybackKind.CONTINUE
    }
}

/**
 * 一次阅读位置快照。
 *
 * [imageFile] 为 null 表示"只记到章节"（例如刚进章节就退出），
 * 阅读器会从这一话的第一张开始。
 */
data class ReadingPosition(
    val albumId: Long,
    val chapterId: Long?,
    val imageFile: String?,
    /** 图片序号（0 起）。与 [imageFile] 冗余，方便快速恢复。 */
    val imageIndex: Int,
    /** 页内滚动比例（长图一屏放不下时用）。 */
    val offsetRatio: Float,
    val updatedAt: Long,
) {
    /** 位置是否可用（没有章节号的位置只能当"读过这本"用）。 */
    val isUsable: Boolean get() = chapterId != null
}
