package com.haruhi.haruhibox.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移。
 *
 * **规则：每次改动 `@Database(entities = ...)` 就必须同时**
 * 1. 把 `@Database(version = ...)` 加一；
 * 2. 在 [ALL] 里补一条迁移。
 *
 * 不这么做的后果不是"少个字段"，而是**应用一启动就抛**
 * `IllegalStateException: Room cannot verify the data integrity`——
 * 因为 Room 会比对编译期生成的 identity hash 与运行期库里的 `room_master_table`，
 * 对不上就直接崩。P2 加 `t_jm_recent_album` 时漏了这一步，覆盖安装才暴露
 * （全新安装不会有这个问题，所以只跑新装很容易漏掉）。
 *
 * 刻意**不使用** `fallbackToDestructiveMigration()`：用户的下载记录、
 * 章节计数与书库是真实资产，一次 destructive 迁移就是数据全丢。
 */
object Migrations {

    /**
     * v1 → v2：新增最近浏览表 `t_jm_recent_album`。
     *
     * 字段与 `t_jm_album` 一致再加 `viewed_time`；表名与列名必须与
     * `JmRecentAlbumEntity` 的 `@Entity` / `@ColumnInfo` 完全对应，
     * 否则 Room 的迁移后校验一样会失败。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `t_jm_recent_album` (
                    `id` INTEGER NOT NULL,
                    `name` TEXT,
                    `album_folder_name` TEXT,
                    `images` TEXT,
                    `add_time` TEXT,
                    `description` TEXT,
                    `total_views` TEXT,
                    `likes` TEXT,
                    `series` TEXT,
                    `series_id` TEXT,
                    `comment_total` TEXT,
                    `author` TEXT,
                    `tags` TEXT,
                    `works` TEXT,
                    `actors` TEXT,
                    `related_list` TEXT,
                    `liked` INTEGER,
                    `is_favorite` INTEGER,
                    `is_aids` INTEGER,
                    `price` TEXT,
                    `purchased` TEXT,
                    `raw` TEXT,
                    `create_time` TEXT,
                    `modify_time` TEXT,
                    `viewed_time` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_t_jm_recent_album_viewed_time` " +
                    "ON `t_jm_recent_album` (`viewed_time`)",
            )
        }
    }

    /**
     * v2 → v3：拆分"基础数据 / 书库成员 / 最近预览"三张表。
     *
     * 背景（用户要求）：拉取 `/album` 不等于加入最近预览，所以详情缓存要有一个
     * **任何操作都不会删除**的归宿。改造后：
     * - `t_jm_album` 变成**漫画基础数据表**（永久缓存，原内容字段全部保留，只把
     *   书库语义的 `collected` 移走）；
     * - 新增 `t_jm_library`：书库成员表，存 `album_id` + 本地收藏标记 `collected`
     *   + 首次入库时间（书库默认排序用它；"移出书库"只删这里的行）；
     * - `t_jm_recent_album` 瘦身成 `id + viewed_time`，渲染时与基础表拼接。
     *
     * 迁移步骤：
     * 1. 建成员表，把现有书库行（原 `t_jm_album` 全是已入库本子）的
     *    `collected` 与入库时间灌进去；
     * 2. 重建基础表（去掉 `collected` 列，其余字段原样保留）；
     * 3. 把**只存在于最近表里的在线数据**（看过但没入库的本子）并入基础表——
     *    只有真正拿到过在线数据的行（`name` 非空）才并，骨架行不并；
     * 4. 重建最近表为瘦身版，浏览时间原样保留。
     *
     * 注意：迁移不会去区分"最近表里哪些行是搜索/入库时被顺带写进来的"——
     * 旧数据已经无法区分真实浏览与顺带写入，统一按浏览记录保留，用户可自行清理。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1) 书库成员表（含收藏标记与入库时间）+ 从现有书库行灌入
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `t_jm_library` (
                    `album_id` INTEGER NOT NULL,
                    `collected` INTEGER,
                    `create_time` TEXT,
                    PRIMARY KEY(`album_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `t_jm_library` (`album_id`, `collected`, `create_time`)
                SELECT `id`, `collected`, `create_time` FROM `t_jm_album`
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_t_jm_library_collected` ON `t_jm_library` (`collected`)",
            )

            // 2) 重建基础表：去掉 collected，其余内容字段全部保留
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `t_jm_album_v3` (
                    `id` INTEGER NOT NULL,
                    `name` TEXT,
                    `album_folder_name` TEXT,
                    `images` TEXT,
                    `add_time` TEXT,
                    `description` TEXT,
                    `total_views` TEXT,
                    `likes` TEXT,
                    `series` TEXT,
                    `series_id` TEXT,
                    `comment_total` TEXT,
                    `author` TEXT,
                    `tags` TEXT,
                    `works` TEXT,
                    `actors` TEXT,
                    `related_list` TEXT,
                    `liked` INTEGER,
                    `is_favorite` INTEGER,
                    `is_aids` INTEGER,
                    `price` TEXT,
                    `purchased` TEXT,
                    `raw` TEXT,
                    `create_time` TEXT,
                    `modify_time` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `t_jm_album_v3`
                    (`id`, `name`, `album_folder_name`, `images`, `add_time`, `description`,
                     `total_views`, `likes`, `series`, `series_id`, `comment_total`, `author`,
                     `tags`, `works`, `actors`, `related_list`, `liked`, `is_favorite`,
                     `is_aids`, `price`, `purchased`, `raw`, `create_time`, `modify_time`)
                SELECT `id`, `name`, `album_folder_name`, `images`, `add_time`, `description`,
                       `total_views`, `likes`, `series`, `series_id`, `comment_total`, `author`,
                       `tags`, `works`, `actors`, `related_list`, `liked`, `is_favorite`,
                       `is_aids`, `price`, `purchased`, `raw`, `create_time`, `modify_time`
                FROM `t_jm_album`
                """.trimIndent(),
            )
            // 3) 最近表里"看过但没入库"的在线数据并入基础表（书库已有的行优先，IGNORE）
            db.execSQL(
                """
                INSERT OR IGNORE INTO `t_jm_album_v3`
                    (`id`, `name`, `album_folder_name`, `images`, `add_time`, `description`,
                     `total_views`, `likes`, `series`, `series_id`, `comment_total`, `author`,
                     `tags`, `works`, `actors`, `related_list`, `liked`, `is_favorite`,
                     `is_aids`, `price`, `purchased`, `raw`, `create_time`, `modify_time`)
                SELECT `id`, `name`, `album_folder_name`, `images`, `add_time`, `description`,
                       `total_views`, `likes`, `series`, `series_id`, `comment_total`, `author`,
                       `tags`, `works`, `actors`, `related_list`, `liked`, `is_favorite`,
                       `is_aids`, `price`, `purchased`, `raw`, `create_time`, `modify_time`
                FROM `t_jm_recent_album`
                WHERE `name` IS NOT NULL AND `name` <> ''
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `t_jm_album`")
            db.execSQL("ALTER TABLE `t_jm_album_v3` RENAME TO `t_jm_album`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_t_jm_album_name` ON `t_jm_album` (`name`)")

            // 4) 最近表瘦身：只留 id + viewed_time
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `t_jm_recent_album_v3` (
                    `id` INTEGER NOT NULL,
                    `viewed_time` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `t_jm_recent_album_v3` (`id`, `viewed_time`)
                SELECT `id`, `viewed_time` FROM `t_jm_recent_album`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `t_jm_recent_album`")
            db.execSQL("ALTER TABLE `t_jm_recent_album_v3` RENAME TO `t_jm_recent_album`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_t_jm_recent_album_viewed_time` " +
                    "ON `t_jm_recent_album` (`viewed_time`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
