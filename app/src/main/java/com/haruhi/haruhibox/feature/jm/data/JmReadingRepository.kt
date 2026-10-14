package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.database.JmChapterImageDao
import com.haruhi.haruhibox.core.database.PlaybackPositionDao
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.PlaybackPositionEntity
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
 * [JmRepository] 已经承担了在线接口、入库、收藏、书库筛选；
 * 阅读位置属于"阅读器"这条线，混进去只会让它更难读。
 * 两者的边界很清楚：**这个类不联网、不碰文件**——
 * 图片在不在磁盘上、要不要联网取，全部由 [JmImageStore] 负责。
 */
class JmReadingRepository(
    private val positionDao: PlaybackPositionDao,
    private val chapterDao: JmChapterImageDao,
) {

    // ------------------------------------------------------------------
    // 位置读写
    // ------------------------------------------------------------------

    fun observePosition(albumId: Long): Flow<ReadingPosition?> =
        positionDao.observe(KIND, albumId.toString()).map { it?.toReadingPosition() }

    suspend fun positionOf(albumId: Long): ReadingPosition? =
        positionDao.find(KIND, albumId.toString())?.toReadingPosition()

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
        positionDao.upsert(entity(KIND))
        // 顺带覆盖「继续阅读」指针（全表此类只有一行）——它是书库卡片的**唯一数据源**。
        // 两个 kind 各写各的，所以"移出书库删逐本记录"不会波及指针。
        positionDao.replaceSingle(entity(CONTINUE_KIND))
    }

    suspend fun clearPosition(albumId: Long) = positionDao.delete(KIND, albumId.toString())

    // ------------------------------------------------------------------
    // 目录与文件
    // ------------------------------------------------------------------

    /** 某一话的章节文件夹名。章节清单为空时返回 null（还没拉过清单）。 */
    suspend fun chapterFolderName(albumId: Long, chapterId: Long): String? {
        // 单话本子：章节 id 就是本子 id，目录名固定「第1话」。
        // 这条规则与 JmRepository.chaptersOf 一致——否则"下载写到哪个目录"和
        // "阅读器去哪个目录找图"会对不上。
        if (chapterId == albumId) return JmRepository.SINGLE_CHAPTER_TITLE
        val first = chapterDao.images(albumId, chapterId).firstOrNull() ?: return null
        return first.chapterTitle?.takeIf { it.isNotBlank() }
            ?: first.chapterName?.takeIf { it.isNotBlank() }
            ?: chapterId.toString()
    }

    /** 某一话已入库的图片清单（不管有没有下载）。 */
    suspend fun imagesOf(albumId: Long, chapterId: Long): List<JmChapterImageEntity> =
        chapterDao.images(albumId, chapterId)

    fun observeImages(albumId: Long, chapterId: Long): Flow<List<JmChapterImageEntity>> =
        chapterDao.observeImages(albumId, chapterId)

    /**
     * 书库页「继续阅读」卡片的数据源：**独立存在的唯一一条指针**。
     *
     * 与逐本阅读记录（[observePosition]/[positionOf] 用的 [KIND]）分开存，
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
        /** `t_playback_position.kind`：JM 漫画。 */
        const val KIND = "JM"

        /**
         * `t_playback_position.kind`：**「继续阅读」指针**（此类只存一行）。
         *
         * 单独开一个 kind，是把"最近在读哪本"做成一条**显式、独立**的记录，
         * 而不是从逐本记录里 `ORDER BY updateTime LIMIT 1` 临时算出来——
         * 后者一旦最新那条被删（正是移出书库时），卡片就会静默回退到旧记录。
         */
        const val CONTINUE_KIND = "JM_CONTINUE"
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
