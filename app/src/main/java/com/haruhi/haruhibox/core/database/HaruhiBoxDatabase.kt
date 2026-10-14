package com.haruhi.haruhibox.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.haruhi.haruhibox.core.database.entity.BilibiliVideoEntity
import com.haruhi.haruhibox.core.database.entity.DownloadStatus
import com.haruhi.haruhibox.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.haruhibox.core.database.entity.DownloadTaskEntity
import com.haruhi.haruhibox.core.database.entity.DownloadType
import com.haruhi.haruhibox.core.database.entity.JmAlbumBaseEntity
import com.haruhi.haruhibox.core.database.entity.JmChapterImageEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmFavoriteEntity
import com.haruhi.haruhibox.core.database.entity.JmLibraryEntity
import com.haruhi.haruhibox.core.database.entity.JmRecentAlbumEntity
import com.haruhi.haruhibox.core.database.entity.JmSearchHistoryEntity
import com.haruhi.haruhibox.core.database.entity.PlaybackPositionEntity

/**
 * 应用数据库。
 *
 * 表清单（业务表 6 张 + Android 侧新增 5 张）：
 * - `t_jm_album`（漫画基础数据表，永久缓存）/ `t_jm_chapter_image` /
 *   `t_jm_favorite` / `t_jm_favorite_album` / `t_jm_library`（书库成员）
 * - `t_bilibili_video`
 * - `t_download_task` / `t_download_task_chapter`（持久化任务，§4.5）
 * - `t_playback_position`（阅读/播放位置，§4.5 末条）
 * - `t_jm_search_history`（在线搜索历史，§2.4 第 1 条）
 * - `t_jm_recent_album`（最近预览：进过详情页/阅读页的本子，按浏览时间倒序）
 *
 * 迁移策略：**唯一权威是 `@Database(version)` + 手写 `Migration`**（见 [Migrations]），
 * 不使用 `fallbackToDestructiveMigration`——用户的下载记录与漫画库是真实资产，
 * 一次 destructive 迁移就是数据全丢。
 *
 * ⚠ **加表/加列时必须同时升 version 并补迁移**，否则应用启动即崩
 * （Room 会比对 identity hash）。详见 [Migrations] 的注释。
 */
@Database(
    entities = [
        JmAlbumBaseEntity::class,
        JmChapterImageEntity::class,
        JmFavoriteEntity::class,
        JmFavoriteAlbumEntity::class,
        JmLibraryEntity::class,
        JmRecentAlbumEntity::class,
        BilibiliVideoEntity::class,
        DownloadTaskEntity::class,
        DownloadTaskChapterEntity::class,
        PlaybackPositionEntity::class,
        JmSearchHistoryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class HaruhiBoxDatabase : RoomDatabase() {

    abstract fun jmAlbumDao(): JmAlbumDao
    abstract fun jmChapterDao(): JmChapterImageDao
    abstract fun jmFavoriteDao(): JmFavoriteDao
    abstract fun jmRecentAlbumDao(): JmRecentAlbumDao
    abstract fun biliVideoDao(): BilibiliVideoDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun jmSearchHistoryDao(): JmSearchHistoryDao

    companion object {
        const val NAME = "haruhibox.db"

        fun create(context: Context): HaruhiBoxDatabase =
            Room.databaseBuilder(context.applicationContext, HaruhiBoxDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/** Room 的类型转换器。枚举以 `name` 存储，便于以后重排枚举顺序而不破坏旧数据。 */
class DatabaseConverters {

    @TypeConverter
    fun downloadStatusToString(value: DownloadStatus): String = value.name

    @TypeConverter
    fun stringToDownloadStatus(value: String): DownloadStatus =
        DownloadStatus.entries.firstOrNull { it.name == value } ?: DownloadStatus.FAIL

    @TypeConverter
    fun downloadTypeToString(value: DownloadType): String = value.name

    @TypeConverter
    fun stringToDownloadType(value: String): DownloadType =
        DownloadType.entries.firstOrNull { it.name == value }
            ?: error("未知的下载任务类型：$value")

    /** 列表统一用换行分隔（与 JM 的 `images` 字段、参考实现的 `saveOrUpdateChapterImages` 一致）。 */
    @TypeConverter
    fun listToString(value: List<String>?): String? = value?.joinToString("\n")

    @TypeConverter
    fun stringToList(value: String?): List<String>? =
        value?.takeIf { it.isNotEmpty() }?.split("\n")
}
