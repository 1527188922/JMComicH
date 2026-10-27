package com.haruhi.jmcomic.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteAlbumEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteEntity
import com.haruhi.jmcomic.core.database.entity.LibraryEntity
import com.haruhi.jmcomic.core.database.entity.RecentAlbumEntity
import com.haruhi.jmcomic.core.database.entity.SearchHistoryEntity
import com.haruhi.jmcomic.core.database.entity.PlaybackPositionEntity

/**
 * 应用数据库。
 *
 * 表清单（全部 10 张）：
 * - `t_jm_album`（漫画基础数据表，永久缓存）/ `t_jm_chapter_image` /
 *   `t_jm_favorite` / `t_jm_favorite_album` / `t_jm_library`（书库成员）
 * - `t_download_task` / `t_download_task_chapter`（持久化任务，§4.5）
 * - `t_playback_position`（阅读位置，§4.5 末条）
 * - `t_jm_search_history`（在线搜索历史，§2.4 第 1 条）
 * - `t_jm_recent_album`（最近预览：进过详情页/阅读页的本子，按浏览时间倒序）
 *
 * ## 版本与迁移
 *
 * **当前基线是 v4**：这次改造同时改了数据库文件名（`haruhibox.db` → `jmcomic.db`）、
 * 迁移类名与表结构（删掉 B站视频表、`BILI_VIDEO` 任务类型），旧安装的库文件本来就不会
 * 再被打开，所以 v1～v3 的迁移链与历史 schema 已经整体删除，v4 是全新的干净基线。
 * 详见 `docs/PROGRESS.md` 的「改造：纯漫画版」一节。
 *
 * 从 v4 起恢复常规约定：**加表/加列必须同时把 `version` 加一并补一条 `Migration`**，
 * 否则应用启动即抛 `IllegalStateException: Room cannot verify the data integrity`
 * （Room 会比对编译期生成的 identity hash 与库里的 `room_master_table`）——
 * 这种失败只在覆盖安装时暴露，全新安装不会复现。
 * **刻意不使用 `fallbackToDestructiveMigration()`**：用户的下载记录与漫画库是真实资产。
 *
 * **v5**：`t_jm_chapter_image` 加一列 `scramble_id`（可空），
 * 用于把 `/comic_read` 一起返回的解扰参数随图片清单存下来（见 [MIGRATION_4_5]）。
 *
 * **v6**：`t_jm_chapter_image` 加一列 `storage_encoding`（非空、默认 0），
 * 记录 `library/` 里这张图是**旧版解扰成品**还是**站点原始字节**（见 [MIGRATION_5_6]）。
 * 升级用户没有这一列时一律按旧成品处理：那些文件确实已经解扰过，不能二次解扰。
 */
@Database(
    entities = [
        AlbumBaseEntity::class,
        ChapterImageEntity::class,
        FavoriteEntity::class,
        FavoriteAlbumEntity::class,
        LibraryEntity::class,
        RecentAlbumEntity::class,
        DownloadTaskEntity::class,
        DownloadTaskChapterEntity::class,
        PlaybackPositionEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class JmComicDatabase : RoomDatabase() {

    abstract fun albumDao(): AlbumDao
    abstract fun chapterDao(): ChapterImageDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentAlbumDao(): RecentAlbumDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        /**
         * 数据库文件名。
         *
         * 注意：改库名（或改实体/表名）等于**放弃旧安装的本地数据**——Room 会在新文件名下
         * 建一个全新的库。这次改造就是这么做的（见类注释的「版本与迁移」），
         * 以后不要再无故改它。
         */
        const val NAME = "jmcomic.db"

        /**
         * v4 → v5：`t_jm_chapter_image` 加一列 `scramble_id`（可空）。
         *
         * 用 `ALTER TABLE ... ADD COLUMN`：旧行该列为 NULL（旧机制的章节行本来就没有这个值），
         * 仓库层读到 NULL 会回落到接口，行为与升级前完全一致。
         * 全新安装不跑迁移，直接按 v5 建表。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE t_jm_chapter_image ADD COLUMN scramble_id INTEGER")
            }
        }

        /**
         * v5 → v6：`t_jm_chapter_image` 加一列 `storage_encoding`（非空、默认 0）。
         *
         * 旧版本 `library/` 里全是解扰后的成品，所以默认 0（DESCRAMBLED）就是对的；
         * 新版本下载时写 1（RAW），阅读 / ZIP / PDF 才知道要现场解扰。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE t_jm_chapter_image " +
                        "ADD COLUMN storage_encoding INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun create(context: Context): JmComicDatabase =
            Room.databaseBuilder(context.applicationContext, JmComicDatabase::class.java, NAME)
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
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
