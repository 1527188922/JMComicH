package com.haruhi.jmcomic.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.DownloadTaskChapterEntity
import com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteAlbumEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteEntity
import com.haruhi.jmcomic.core.database.entity.LibraryEntity
import com.haruhi.jmcomic.core.database.entity.RecentAlbumEntity
import com.haruhi.jmcomic.core.database.entity.SearchHistoryEntity
import com.haruhi.jmcomic.core.database.entity.PlaybackPositionEntity
import kotlinx.coroutines.flow.Flow

// ======================================================================
// JM 本子
// ======================================================================

/**
 * `t_jm_album`（基础数据表）+ `t_jm_library`（书库成员表）。
 *
 * 成员表与基础表分开之后：
 * - **读取书库**（列表 / 详情 / "是否已入库"）都要拼成员表；
 * - **写入基础数据**（在线详情缓存）不碰成员表；
 * - "移出书库"只删成员行，基础数据保留（用户要求：缓存表不因任何操作删除）。
 */
@Dao
interface AlbumDao {

    // ---------------- 基础数据表（永久缓存） ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(album: AlbumBaseEntity): Long

    /** 整行替换（`INSERT OR REPLACE` = 先删后插）；不要在事务外直接调，优先用 [replaceAlbum]。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(album: AlbumBaseEntity)

    /**
     * 刷新基础数据：**事务内先删后插**（用户要求）。
     *
     * 每次 `/album` 请求成功都会走到这里，整段替换是原子的：
     * Android 的 SQLite 支持事务（Room 的 `@Transaction` 会把整个方法包进
     * BEGIN/END，并在同一连接上串行执行），所以"读旧 `create_time` → 删旧行 → 插新行"
     * 不会与其它并发写入交错，也不会出现"删了还没插"的中间态。
     *
     * 保留 `create_time`（首次缓存时间，刷新不改它）；本地收藏标记 `collected`
     * 不在本表，由 `t_jm_library` 单独维护，天然不受刷新影响。
     */
    @Transaction
    suspend fun replaceAlbum(album: AlbumBaseEntity) {
        val createTime = findById(album.id)?.createTime
        insertReplace(album.copy(createTime = createTime ?: album.createTime))
    }

    /** 基础数据（不看是否入库）。 */
    @Query("SELECT * FROM t_jm_album WHERE id = :id")
    suspend fun findById(id: Long): AlbumBaseEntity?

    @Query("SELECT * FROM t_jm_album")
    suspend fun all(): List<AlbumBaseEntity>

    // ---------------- 书库成员表 ----------------

    /** 加入书库成员；已存在时保留原入库时间与收藏标记（IGNORE）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibraryRow(row: LibraryEntity): Long

    /** 移出书库：只删成员行（含收藏标记），基础数据不动。 */
    @Query("DELETE FROM t_jm_library WHERE album_id IN (:ids)")
    suspend fun deleteLibraryRows(ids: List<Long>)

    /** 已入库的本子 id（搜索结果"已入库"标识用）。 */
    @Query("SELECT album_id FROM t_jm_library WHERE album_id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    /** 书库视角读一本（基础数据 + 收藏标记）。未入库返回 null。 */
    @Query(
        """
        SELECT b.*, l.collected AS collected FROM t_jm_album b
        INNER JOIN t_jm_library l ON l.album_id = b.id
        WHERE b.id = :id
        """,
    )
    suspend fun findLibraryAlbum(id: Long): AlbumEntity?

    @Query(
        """
        SELECT b.*, l.collected AS collected FROM t_jm_album b
        INNER JOIN t_jm_library l ON l.album_id = b.id
        WHERE b.id = :id
        """,
    )
    fun observeLibraryAlbum(id: Long): Flow<AlbumEntity?>

    /**
     * 书库列表（含筛选与排序）。
     *
     * **排序：入库时间倒序**（用户要求第 2 点）——`t_jm_library.create_time` 是本子
     * 第一次进书库的时间，之后刷新详情只会更新基础数据，所以用它才是"入库顺序"。
     * 时间戳是 `yyyy-MM-dd HH:mm:ss` 文本，字典序即时间序；同一秒内用 id 兜底。
     *
     * **指定收藏夹时改按"加入这个夹的时间"倒序**（用户要求第 4 点）：
     * 夹内的顺序应该是"最近加进来的在最前"，和书库的入库顺序是两回事，
     * 所以第一个排序键取关联表 `t_jm_favorite_album.create_time`（用相关子查询取当前夹的关联时间）。
     *
     * 筛选条件全部用 `(:param IS NULL OR ...)` 形式，避免为每个组合写一条 SQL。
     *
     * **标签多选是 OR 语义**，与参考实现 `applyTagFilter` 一致：
     * `tags` 列存的是 JSON 数组字符串，所以用 `INSTR(tags, '"标签"') > 0` 做子串匹配。
     * 要求标签本身不含 `"` 与 `,`，否则会破坏匹配（JM 的标签不含这两种字符）。
     */
    @Query(
        """
        SELECT b.*, l.collected AS collected FROM t_jm_album b
        INNER JOIN t_jm_library l ON l.album_id = b.id
        WHERE (:keyword IS NULL OR b.name LIKE '%' || :keyword || '%'
               OR b.author LIKE '%' || :keyword || '%'
               OR b.tags LIKE '%' || :keyword || '%'
               OR CAST(b.id AS TEXT) = :keyword)
          AND (:onlyCollected IS NULL OR l.collected = :onlyCollected)
          AND (:author IS NULL OR b.author LIKE '%' || :author || '%')
          AND (:tagsCsv = '' OR EXISTS (
                SELECT 1 FROM (
                    SELECT (INSTR(b.tags, '"' || value || '"') > 0) AS hit
                    FROM (SELECT TRIM(value) AS value FROM (
                        WITH RECURSIVE split(value, rest) AS (
                            SELECT '', :tagsCsv || ','
                            UNION ALL
                            SELECT SUBSTR(rest, 0, INSTR(rest, ',')),
                                   SUBSTR(rest, INSTR(rest, ',') + 1)
                            FROM split WHERE rest <> ''
                        ) SELECT value FROM split WHERE value <> ''
                    ))
                ) WHERE hit = 1))
          AND (:favoriteId IS NULL OR b.id IN (
                SELECT album_id FROM t_jm_favorite_album WHERE favorite_id = :favoriteId))
        ORDER BY
            CASE WHEN :favoriteId IS NULL THEN NULL ELSE (
                SELECT fa.create_time FROM t_jm_favorite_album fa
                WHERE fa.favorite_id = :favoriteId AND fa.album_id = b.id
            ) END DESC,
            COALESCE(l.create_time, '') DESC,
            b.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeLibrary(
        keyword: String?,
        onlyCollected: Boolean?,
        author: String?,
        tagsCsv: String,
        favoriteId: Long?,
        limit: Int,
        offset: Int,
    ): Flow<List<AlbumEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM t_jm_album b
        INNER JOIN t_jm_library l ON l.album_id = b.id
        WHERE (:keyword IS NULL OR b.name LIKE '%' || :keyword || '%'
               OR b.author LIKE '%' || :keyword || '%'
               OR b.tags LIKE '%' || :keyword || '%'
               OR CAST(b.id AS TEXT) = :keyword)
          AND (:onlyCollected IS NULL OR l.collected = :onlyCollected)
          AND (:author IS NULL OR b.author LIKE '%' || :author || '%')
          AND (:tagsCsv = '' OR EXISTS (
                SELECT 1 FROM (
                    SELECT (INSTR(b.tags, '"' || value || '"') > 0) AS hit
                    FROM (SELECT TRIM(value) AS value FROM (
                        WITH RECURSIVE split(value, rest) AS (
                            SELECT '', :tagsCsv || ','
                            UNION ALL
                            SELECT SUBSTR(rest, 0, INSTR(rest, ',')),
                                   SUBSTR(rest, INSTR(rest, ',') + 1)
                            FROM split WHERE rest <> ''
                        ) SELECT value FROM split WHERE value <> ''
                    ))
                ) WHERE hit = 1))
          AND (:favoriteId IS NULL OR b.id IN (
                SELECT album_id FROM t_jm_favorite_album WHERE favorite_id = :favoriteId))
        """,
    )
    fun observeLibraryCount(
        keyword: String?,
        onlyCollected: Boolean?,
        author: String?,
        tagsCsv: String,
        favoriteId: Long?,
    ): Flow<Int>

    /** 详情页的收藏开关。**只改成员表里的这一个字段**，不动基础数据。 */
    @Query("UPDATE t_jm_library SET collected = :collected WHERE album_id = :id")
    suspend fun setCollected(id: Long, collected: Boolean)

    /**
     * 把**账号收藏**状态（官方 `/album` 的 `is_favorite`）写回本机快照。
     *
     * 为什么需要：以前本机从不更新这个字段，点了收藏之后再进详情页，
     * 按钮会先按旧快照画成「收藏」、等 `/album` 回来才翻成「已收藏」（用户反馈的闪变）。
     * 写一次之后，下次进页面第一帧就是对的。
     */
    @Query("UPDATE t_jm_album SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavoriteFlag(id: Long, favorite: Boolean)
}

// ======================================================================
// JM 章节图片
// ======================================================================

@Dao
interface ChapterImageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ChapterImageEntity>)

    /**
     * 章节列表。**照抄参考实现 `ChapterImageSqliteMapper.xml` 的 `selectChapterList`**：
     * 按 `chapter_id` 分组，取 `MIN(id)` / `MIN(image_file)` / `MIN(image_sort)` 作为代表行，
     * 排序是 `CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)`。
     *
     * 之所以照抄：详情页要展示"本地已缓存的章节"（§9 第 7 条），
     * 而这张表里一章有几十行图片，必须分组后才能当章节列表用。
     */
    @Query(
        """
        SELECT MIN(id) AS id,
               album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite, scramble_id,
               MIN(storage_encoding) AS storage_encoding,
               MIN(image_file) AS image_file,
               MIN(image_sort) AS image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId
        GROUP BY album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
                 chapter_add_time, series_id, liked, is_favorite, scramble_id
        ORDER BY CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)
        """,
    )
    fun observeChapterList(albumId: Long): Flow<List<ChapterImageEntity>>

    @Query(
        """
        SELECT MIN(id) AS id,
               album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite, scramble_id,
               MIN(storage_encoding) AS storage_encoding,
               MIN(image_file) AS image_file,
               MIN(image_sort) AS image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId
        GROUP BY album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
                 chapter_add_time, series_id, liked, is_favorite, scramble_id
        ORDER BY CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)
        """,
    )
    suspend fun chapterList(albumId: Long): List<ChapterImageEntity>

    /** 某章的图片清单，按 `image_sort, id` 升序（照抄 `selectImages`）。 */
    @Query(
        """
        SELECT id, album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite, scramble_id,
               storage_encoding, image_file, image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId AND chapter_id = :chapterId
        ORDER BY image_sort, id
        """,
    )
    fun observeImages(albumId: Long, chapterId: Long): Flow<List<ChapterImageEntity>>

    @Query(
        """
        SELECT id, album_id, chapter_id, chapter_sort, chapter_title, chapter_name,
               chapter_add_time, series_id, liked, is_favorite, scramble_id,
               storage_encoding, image_file, image_sort
        FROM t_jm_chapter_image
        WHERE album_id = :albumId AND chapter_id = :chapterId
        ORDER BY image_sort, id
        """,
    )
    suspend fun images(albumId: Long, chapterId: Long): List<ChapterImageEntity>

    @Query("SELECT COUNT(*) FROM t_jm_chapter_image WHERE album_id = :albumId AND chapter_id = :chapterId")
    suspend fun imageCount(albumId: Long, chapterId: Long): Int

    @Query("SELECT chapter_id FROM t_jm_chapter_image WHERE album_id = :albumId GROUP BY chapter_id")
    suspend fun chapterIds(albumId: Long): List<Long>

    /**
     * 某章的 `scramble_id`（解扰块数的输入）。**只按 chapter id 查**：
     * chapter id 在 JM 里全局唯一，而阅读器 / 下载任务手里只有它（不必再多带一个 albumId）。
     *
     * `scramble_id` 是按"话"给的，同一话所有图片行同值；只取一行即可。
     * 没有值（旧机制的章节行）返回 null，由仓库层回落到接口。
     */
    @Query(
        """
        SELECT scramble_id FROM t_jm_chapter_image
        WHERE chapter_id = :chapterId AND scramble_id IS NOT NULL
        LIMIT 1
        """,
    )
    suspend fun scrambleIdOfChapter(chapterId: Long): Long?

    /** 这一话里哪些文件是站点原始字节（`storage_encoding = 1`）。整章替换时用它保留标记。 */
    @Query(
        """
        SELECT image_file FROM t_jm_chapter_image
        WHERE album_id = :albumId AND chapter_id = :chapterId AND storage_encoding = 1
        """,
    )
    suspend fun rawImageFiles(albumId: Long, chapterId: Long): List<String>

    /** 单张图的存储形态；没有这一行（清单未入库）时返回 null。 */
    @Query(
        """
        SELECT storage_encoding FROM t_jm_chapter_image
        WHERE album_id = :albumId AND chapter_id = :chapterId AND image_file = :imageFile
        LIMIT 1
        """,
    )
    suspend fun storageEncodingOf(albumId: Long, chapterId: Long, imageFile: String): Int?

    /** 某张图被新版本以"站点原始字节"下载完成；重复调用幂等。 */
    @Query(
        """
        UPDATE t_jm_chapter_image SET storage_encoding = 1
        WHERE album_id = :albumId AND chapter_id = :chapterId AND image_file = :imageFile
        """,
    )
    suspend fun markRaw(albumId: Long, chapterId: Long, imageFile: String)

    @Query("DELETE FROM t_jm_chapter_image WHERE album_id = :albumId AND chapter_id = :chapterId")
    suspend fun deleteChapter(albumId: Long, chapterId: Long)

    @Query("DELETE FROM t_jm_chapter_image WHERE album_id = :albumId")
    suspend fun deleteByAlbum(albumId: Long)

    /**
     * 章节图片入库：先删掉该章旧行再插新行。
     *
     * 为什么整章替换而不是 upsert：接口返回的图片清单是**权威快照**，
     * 逐行 upsert 会让"服务端已删除的图片"永远留在库里，导致详情页显示的图片数比实际多。
     * 整章替换保证本地清单与接口一致（§D.1：「必须有稳定的章节图片清单，才能准确判断漏图」）。
     */
    @Transaction
    suspend fun replaceChapter(albumId: Long, chapterId: Long, images: List<ChapterImageEntity>) {
        deleteChapter(albumId, chapterId)
        insertAll(images)
    }
}

// ======================================================================
// JM 收藏夹
// ======================================================================

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("SELECT * FROM t_jm_favorite ORDER BY sort_order, id")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM t_jm_favorite ORDER BY sort_order, id")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT * FROM t_jm_favorite WHERE name = :name")
    suspend fun findByName(name: String): FavoriteEntity?

    @Query("SELECT COUNT(*) FROM t_jm_favorite")
    suspend fun count(): Int

    @Query("UPDATE t_jm_favorite SET name = :name, modify_time = :modifyTime WHERE id = :id")
    suspend fun rename(id: Long, name: String, modifyTime: String)

    @Query("UPDATE t_jm_favorite SET sort_order = :sortOrder, modify_time = :modifyTime WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, modifyTime: String)

    /** 删除收藏夹：只删夹与关联，**不动**书库记录和本地文件（§9 第 6 条）。 */
    @Transaction
    suspend fun deleteWithLinks(id: Long) {
        deleteLinksOf(id)
        deleteById(id)
    }

    @Query("DELETE FROM t_jm_favorite WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: FavoriteAlbumEntity): Long

    @Query("DELETE FROM t_jm_favorite_album WHERE favorite_id = :favoriteId AND album_id = :albumId")
    suspend fun unlink(favoriteId: Long, albumId: Long)

    @Query("DELETE FROM t_jm_favorite_album WHERE favorite_id = :favoriteId")
    suspend fun deleteLinksOf(favoriteId: Long)

    @Query("DELETE FROM t_jm_favorite_album WHERE album_id = :albumId")
    suspend fun deleteLinksOfAlbum(albumId: Long)

    @Query("SELECT favorite_id FROM t_jm_favorite_album WHERE album_id = :albumId")
    suspend fun favoriteIdsOf(albumId: Long): List<Long>

    /**
     * 观察一个本子所属的收藏夹（详情页的收藏夹面板与"收藏"按钮靠它保持同步）。
     *
     * 之前只在页面加载时查一次，于是"在阅读器里收藏 → 返回详情页"看到的还是旧状态；
     * 改成 Flow 之后，无论谁改了关联表，界面都会跟着变。
     */
    @Query("SELECT favorite_id FROM t_jm_favorite_album WHERE album_id = :albumId")
    fun observeFavoriteIdsOf(albumId: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM t_jm_favorite_album WHERE favorite_id = :favoriteId")
    suspend fun albumCount(favoriteId: Long): Int

    @Query("SELECT COUNT(*) FROM t_jm_favorite_album WHERE favorite_id = :favoriteId")
    fun observeAlbumCount(favoriteId: Long): Flow<Int>

    /**
     * 收藏夹列表 + 每个夹的本子数量，**一次查询**拿到。
     *
     * 之前收藏页给每行分别 `observeAlbumCount` 再用 `first()` 取一次值，
     * 结果收藏之后数量不刷新（用户反馈的第 1 点）。
     * 用一条 LEFT JOIN 的 Flow 查询取代 N 次单发查询：
     * 关联表任何变动都会重新发射，UI 自然跟着更新。
     */
    @Query(
        """
        SELECT f.id AS id, f.name AS name, f.sort_order AS sortOrder,
               (SELECT COUNT(*) FROM t_jm_favorite_album fa WHERE fa.favorite_id = f.id) AS albumCount,
               f.create_time AS createTime, f.modify_time AS modifyTime,
               (SELECT MAX(fa.create_time) FROM t_jm_favorite_album fa
                WHERE fa.favorite_id = f.id) AS lastAddedTime,
               (SELECT fa.album_id FROM t_jm_favorite_album fa
                WHERE fa.favorite_id = f.id
                ORDER BY COALESCE(fa.create_time, '') DESC, fa.id DESC LIMIT 1) AS latestAlbumId
        FROM t_jm_favorite f
        ORDER BY f.sort_order, f.id
        """,
    )
    fun observeFavoritesWithCount(): Flow<List<FavoriteWithCount>>
}

/**
 * 收藏夹 + 本子数量 + 封面来源。
 *
 * `sortOrder` / `createTime` 等是 SQL 里 `AS` 出来的别名
 * （`@ColumnInfo` 不生效于查询投影，所以这里用显式的 `AS`）。
 *
 * [latestAlbumId] 是**最近加入这个收藏夹的那本漫画**，列表用它当封面
 * （用户要求：收藏夹要有封面，空夹给占位）。
 */
data class FavoriteWithCount(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val albumCount: Int,
    val createTime: String?,
    val modifyTime: String?,
    /** 夹内最近一次"加入"的时间；空夹为 null。 */
    val lastAddedTime: String?,
    /** 夹内最近加入的本子 id；空夹为 null。 */
    val latestAlbumId: Long?,
)

// ======================================================================
// JM 最近预览
// ======================================================================

/**
 * 最近预览：只存"进过详情页/阅读页"与时间。
 * 列表内容与 `t_jm_album`（基础数据表）拼接，见 [observeRecent]。
 */
@Dao
interface RecentAlbumDao {

    /**
     * 写入/更新一条最近预览。
     *
     * `REPLACE` 语义在这里是安全的：这张表只有 `id` 与 `viewed_time`，
     * 没有需要保留的本地字段，整行覆盖正是想要的行为；
     * 单条语句也天然原子，不再需要"先读旧值再保留"的串行化处理。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: RecentAlbumEntity)

    @Query("SELECT * FROM t_jm_recent_album WHERE id = :id")
    suspend fun findById(id: Long): RecentAlbumEntity?

    /**
     * 按浏览时间倒序（用户要求：最近降序），内容与基础数据表**左连接**拼出来；
     * `collected` 再从成员表取（未入库时为空）。
     *
     * 用 LEFT JOIN：只进过页面、还没缓存到基础数据（例如首次进入时离线）的条目
     * 仍然显示（此时除 id 外字段为空，界面回落成 "JM<id>"），与旧行为一致。
     */
    @Query(
        """
        SELECT r.id AS id,
               b.name AS name,
               b.album_folder_name AS album_folder_name,
               b.images AS images,
               b.add_time AS add_time,
               b.description AS description,
               b.total_views AS total_views,
               b.likes AS likes,
               b.series AS series,
               b.series_id AS series_id,
               b.comment_total AS comment_total,
               b.author AS author,
               b.tags AS tags,
               b.works AS works,
               b.actors AS actors,
               b.related_list AS related_list,
               b.liked AS liked,
               b.is_favorite AS is_favorite,
               l.collected AS collected,
               b.is_aids AS is_aids,
               b.price AS price,
               b.purchased AS purchased,
               b.raw AS raw,
               b.create_time AS create_time,
               b.modify_time AS modify_time
        FROM t_jm_recent_album r
        LEFT JOIN t_jm_album b ON b.id = r.id
        LEFT JOIN t_jm_library l ON l.album_id = r.id
        ORDER BY r.viewed_time DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeRecent(limit: Int, offset: Int = 0): Flow<List<AlbumEntity>>

    @Query("SELECT COUNT(*) FROM t_jm_recent_album")
    fun observeCount(): Flow<Int>

    /** 全部 id；"清空最近预览"前先取一份，用来判断哪些封面还能删。 */
    @Query("SELECT id FROM t_jm_recent_album")
    suspend fun allIds(): List<Long>

    /** 只更新浏览时间（进入详情页/阅读页时调用）。 */
    @Query("UPDATE t_jm_recent_album SET viewed_time = :viewedTime WHERE id = :id")
    suspend fun touch(id: Long, viewedTime: Long)

    /** 删除一条。 */
    @Query("DELETE FROM t_jm_recent_album WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM t_jm_recent_album")
    suspend fun clear(): Int
}
// ======================================================================
// 持久化下载任务
// ======================================================================

@Dao
interface DownloadTaskDao {

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM t_download_task WHERE id = :id")
    suspend fun findById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM t_download_task WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadTaskEntity?>

    /** **去重规则**：同一 `(type, bizKey)` 只允许一个活跃任务。 */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE type = :type AND bizKey = :bizKey
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id DESC LIMIT 1
        """,
    )
    suspend fun activeTask(type: DownloadType, bizKey: String): DownloadTaskEntity?

    /**
     * 观察某个业务对象的**活跃任务**（详情页靠它判断"哪些章节已经在队列里"）。
     *
     * 去重规则保证同一 `(type, bizKey)` 最多一个活跃任务，所以取一行就够。
     */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE type = :type AND bizKey = :bizKey
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id DESC LIMIT 1
        """,
    )
    fun observeActiveTask(type: DownloadType, bizKey: String): Flow<DownloadTaskEntity?>

    @Query(
        """
        SELECT * FROM t_download_task
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id
        """,
    )
    suspend fun activeTasks(): List<DownloadTaskEntity>

    /** 任务页"进行中"分组：不包含失败（§9 第 9 条）。 */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'WAITING_NETWORK')
        ORDER BY id DESC
        """,
    )
    fun observeActive(): Flow<List<DownloadTaskEntity>>

    /** 任务页"已完成"分组：成功 + 失败 + 取消，最近在前。 */
    @Query(
        """
        SELECT * FROM t_download_task
        WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')
        ORDER BY COALESCE(endTime, updateTime) DESC
        LIMIT :limit
        """,
    )
    fun observeFinished(limit: Int): Flow<List<DownloadTaskEntity>>

    /**
     * 底部角标：**排队 + 执行中 + 等待网络**。
     *
     * 计划 §9 第 9 条说"角标仅统计排队/执行任务，失败另用提示点"——这里的取舍是：
     * 「等待网络」也算进角标。它不是失败（不计入 [observeFailedCount]），
     * 而是一个**已经提交、正卡在网络上**的任务：不亮角标的话，用户点完下载、
     * 回到浏览页以后就再也没有任何线索（模拟器实测就是这个现象）。
     * 失败与取消仍然只走 [observeFailedCount]。
     */
    @Query("SELECT COUNT(*) FROM t_download_task WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_NETWORK')")
    fun observeBadgeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM t_download_task WHERE status = 'FAIL'")
    fun observeFailedCount(): Flow<Int>

    @Query("UPDATE t_download_task SET status = :status, updateTime = :updateTime WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus, updateTime: Long)

    @Query(
        """
        UPDATE t_download_task
        SET status = :status, message = :message, endTime = :endTime, updateTime = :endTime
        WHERE id = :id
        """,
    )
    suspend fun finish(id: Long, status: DownloadStatus, message: String?, endTime: Long)

    /**
     * 进入运行中。`startTime` 只在第一次写入（已有时保留）。
     *
     * **列级 UPDATE 而不是"读整行 → copy → 写回"**：下载期间任务行会被进度心跳
     * 高频改写，整行写回等于用旧快照覆盖别人刚写的值（最典型的是把 pause 写好的
     * `PAUSED` 覆盖回 `RUNNING`）。所有"下载中会发生的写入"都必须只动自己那几列。
     */
    @Query(
        """
        UPDATE t_download_task
        SET status = 'RUNNING', startTime = COALESCE(startTime, :now), updateTime = :now
        WHERE id = :id
        """,
    )
    suspend fun markRunning(id: Long, now: Long)

    /** 等待网络：不算失败也不算"正在运行"，只动状态/原因/阶段三列。 */
    @Query(
        """
        UPDATE t_download_task
        SET status = 'WAITING_NETWORK', message = :message, stageText = :stageText, updateTime = :now
        WHERE id = :id
        """,
    )
    suspend fun markWaitingNetwork(id: Long, message: String?, stageText: String?, now: Long)

    /**
     * 更新任务进度。传 null 表示"这一项不动"（`COALESCE(:x, x)` 与原
     * `x ?: task.x` 语义一致），所以**没有**"把某个计数改成 0"的用法——
     * 需要归零请走 [recomputeTotalsFromChapters] 这类重算入口。
     */
    @Query(
        """
        UPDATE t_download_task SET
            stageText = COALESCE(:stageText, stageText),
            doneChapters = COALESCE(:doneChapters, doneChapters),
            totalChapters = COALESCE(:totalChapters, totalChapters),
            doneImages = COALESCE(:doneImages, doneImages),
            totalImages = COALESCE(:totalImages, totalImages),
            downloadedBytes = COALESCE(:downloadedBytes, downloadedBytes),
            totalBytes = COALESCE(:totalBytes, totalBytes),
            updateTime = :now
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(
        id: Long,
        stageText: String?,
        doneChapters: Int?,
        totalChapters: Int?,
        doneImages: Int?,
        totalImages: Int?,
        downloadedBytes: Long?,
        totalBytes: Long?,
        now: Long,
    )

    /**
     * 把任务级进度按章节明细**一条 SQL**重算（图片数 / 已完成图片 / 已完成章节）。
     *
     * 下载期间进度心跳每 600ms 调一次，所以必须原子：子查询在语句内部读章节表，
     * 不存在"读到旧行再写回"的窗口，与 pause / cancel / 单张图进度写入都不会互相覆盖。
     * 只服务 JM 漫画任务（导出任务的进度由 [updateProgress] 直接上报）。
     */
    @Query(
        """
        UPDATE t_download_task SET
            totalChapters = (SELECT COUNT(*) FROM t_download_task_chapter WHERE taskId = :taskId),
            doneChapters = (SELECT COUNT(*) FROM t_download_task_chapter
                            WHERE taskId = :taskId AND status = 'SUCCESS'),
            totalImages = (SELECT COALESCE(SUM(totalImages), 0) FROM t_download_task_chapter
                           WHERE taskId = :taskId),
            doneImages = (SELECT COALESCE(SUM(doneImages), 0) FROM t_download_task_chapter
                          WHERE taskId = :taskId),
            updateTime = :now
        WHERE id = :taskId
        """,
    )
    suspend fun recomputeTotalsFromChapters(taskId: Long, now: Long)

    /**
     * 应用被杀死后重启时的恢复兜底：把**还没被恢复流程处理掉**的
     * `RUNNING` / `WAITING_NETWORK` 任务统一置为 `PAUSED`。
     *
     * 为什么是暂停而不是打回队列：用户拍板"退出应用 = 下载暂停、导出删除"，
     * 杀进程没跑到退出钩子时也要落到同一语义——任务显示"已暂停"，等用户点"继续"，
     * 不会在下次启动时自己跑起来。
     *
     * **不动 `PAUSED`**（等用户点恢复）与 `CANCELLED`（不复活，§D.3）。
     * 进度字段先按磁盘现状由仓库层重算后再调用本方法。
     */
    @Query(
        """
        UPDATE t_download_task
        SET status = 'PAUSED', message = NULL, updateTime = :now
        WHERE status IN ('RUNNING', 'WAITING_NETWORK')
        """,
    )
    suspend fun pauseInterrupted(now: Long): Int

    /**
     * 退出应用时把指定类型的活跃任务**批量**置为 `PAUSED`（漫画图片下载用）。
     *
     * 只覆盖"会自己跑"的状态（排队 / 运行中 / 等待网络）：本来就是 `PAUSED` 的不动。
     * `message` 一并清掉——它多半是"等待网络"的原因，任务已暂停，留着会误导。
     *
     * 配合 [requeueRunningChapters] 使用：任务行暂停的同时，正在跑的那一章
     * 也要从 `RUNNING` 打回队列（与单任务 [requeueUnfinishedChapters]
     * 同一个理由：界面上不留"假下载中"）。
     */
    @Query(
        """
        UPDATE t_download_task
        SET status = 'PAUSED', message = NULL, updateTime = :now
        WHERE type IN (:types) AND status IN ('QUEUED', 'RUNNING', 'WAITING_NETWORK')
        """,
    )
    suspend fun pauseActive(types: List<DownloadType>, now: Long): Int

    @Query("DELETE FROM t_download_task WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')")
    suspend fun clearFinished(): Int

    @Query("DELETE FROM t_download_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 某个业务对象的**全部任务行**（不分类型 / 状态），移出书库时清理用。 */
    @Query("SELECT * FROM t_download_task WHERE bizKey = :bizKey")
    suspend fun tasksByBizKey(bizKey: String): List<DownloadTaskEntity>

    /** 同 `(type, bizKey)` 的**全部任务行**（不分状态）。 */
    @Query("SELECT * FROM t_download_task WHERE type = :type AND bizKey = :bizKey")
    suspend fun tasksByTypeAndKey(type: DownloadType, bizKey: String): List<DownloadTaskEntity>

    @Query("DELETE FROM t_download_task WHERE type = :type AND bizKey = :bizKey")
    suspend fun deleteByTypeAndKey(type: DownloadType, bizKey: String): Int

    /**
     * **原子替换**：删掉同 `(type, bizKey)` 的所有旧任务行（连同章节明细），再插入新行。
     *
     * 只在提交新任务时调用——调用方已确认当前没有活跃任务，所以这里的旧行都是已结束的
     * （成功 / 失败 / 取消）。同业务对象同类型只保留最新一条，避免旧行一直堆在任务页历史上。
     */
    @Transaction
    suspend fun replaceByTypeAndKey(type: DownloadType, bizKey: String, task: DownloadTaskEntity): Long {
        tasksByTypeAndKey(type, bizKey).forEach { deleteChapters(it.id) }
        deleteByTypeAndKey(type, bizKey)
        return insert(task)
    }

    /** 完成历史保留最近 N 条（当前取 20，§3.3）。 */
    @Query(
        """
        DELETE FROM t_download_task
        WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')
          AND id NOT IN (
            SELECT id FROM t_download_task
            WHERE status IN ('SUCCESS', 'FAIL', 'CANCELLED')
            ORDER BY COALESCE(endTime, updateTime) DESC
            LIMIT :keep
          )
        """,
    )
    suspend fun trimFinished(keep: Int): Int

    // ---------- 章节明细 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<DownloadTaskChapterEntity>)

    @Query("SELECT * FROM t_download_task_chapter WHERE taskId = :taskId ORDER BY sort, id")
    fun observeChapters(taskId: Long): Flow<List<DownloadTaskChapterEntity>>

    @Query("SELECT * FROM t_download_task_chapter WHERE taskId = :taskId ORDER BY sort, id")
    suspend fun chapters(taskId: Long): List<DownloadTaskChapterEntity>

    @Query(
        """
        UPDATE t_download_task_chapter
        SET doneImages = :doneImages, status = :status, message = :message, updateTime = :now
        WHERE taskId = :taskId AND chapterId = :chapterId
        """,
    )
    suspend fun updateChapterProgress(
        taskId: Long,
        chapterId: Long,
        doneImages: Int,
        status: DownloadStatus,
        message: String?,
        now: Long,
    )

    /**
     * 写入某章应有的图片数。
     *
     * 提交任务时往往还不知道章节里有多少张图（图片清单要拉 `/chapter` 才知道），
     * 所以 `totalImages` 先写 0，拿到清单后再补。**单独一条 UPDATE**
     * 而不是重跑 `upsertChapters`：后者是 REPLACE，会把已完成的进度清掉。
     */
    @Query(
        """
        UPDATE t_download_task_chapter
        SET totalImages = :totalImages, updateTime = :now
        WHERE taskId = :taskId AND chapterId = :chapterId
        """,
    )
    suspend fun setChapterTotal(taskId: Long, chapterId: Long, totalImages: Int, now: Long)

    /**
     * 重试 / 恢复前把章节打回队列。
     *
     * 覆盖 `FAIL`、`CANCELLED`，以及被取消时**卡在 `RUNNING` 的章节**
     * （任务被取消时正在跑的那一章就是这种状态，不重置的话它会永远显示"下载中"）。
     * **不动 `doneImages`**：磁盘上已有的图片会被跳过，重算由仓库层做。
     */
    @Query(
        """
        UPDATE t_download_task_chapter
        SET status = 'QUEUED', message = NULL, updateTime = :now
        WHERE taskId = :taskId AND status IN ('FAIL', 'CANCELLED', 'RUNNING')
        """,
    )
    suspend fun requeueUnfinishedChapters(taskId: Long, now: Long): Int

    /**
     * 把指定类型任务里**卡在 `RUNNING`** 的章节打回队列（退出应用暂停下载时用）。
     *
     * 与 [requeueUnfinishedChapters] 的区别是**批量口径**：那边是"重试/恢复某一个任务"
     * 时连 `FAIL`/`CANCELLED` 一起重置，这里只处理"任务被暂停时正在跑的那一章"——
     * 失败/取消的章节状态不该被退出动作动到，等用户在任务页点重试/继续时再统一处理。
     */
    @Query(
        """
        UPDATE t_download_task_chapter
        SET status = 'QUEUED', message = NULL, updateTime = :now
        WHERE status = 'RUNNING'
          AND taskId IN (SELECT id FROM t_download_task WHERE type IN (:types))
        """,
    )
    suspend fun requeueRunningChapters(types: List<DownloadType>, now: Long): Int

    @Query("DELETE FROM t_download_task_chapter WHERE taskId = :taskId")
    suspend fun deleteChapters(taskId: Long)
}

// ======================================================================
// 阅读 / 播放位置
// ======================================================================

@Dao
interface PlaybackPositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PlaybackPositionEntity)

    /**
     * 「继续阅读」指针的覆盖写：**先清同 kind 的旧行，再写新行**（事务内原子）。
     *
     * 指针只应存在一行。换一本读时 `bizKey`（本子 id）会变，直接 upsert 会留下
     * 两条旧行，所以先清零再写。清 + 写包在同一个事务里，观察者只会看到
     * "改前 / 改后"，不会闪过"短暂为空"的中间态。
     */
    @Transaction
    suspend fun replaceSingle(position: PlaybackPositionEntity) {
        clearKind(position.kind)
        upsert(position)
    }

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind AND bizKey = :bizKey")
    suspend fun find(kind: String, bizKey: String): PlaybackPositionEntity?

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind AND bizKey = :bizKey")
    fun observe(kind: String, bizKey: String): Flow<PlaybackPositionEntity?>

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind ORDER BY updateTime DESC LIMIT 1")
    fun observeLastForKind(kind: String): Flow<PlaybackPositionEntity?>

    @Query("SELECT * FROM t_playback_position WHERE kind = :kind ORDER BY updateTime DESC LIMIT :limit")
    fun observeRecent(kind: String, limit: Int): Flow<List<PlaybackPositionEntity>>

    /** 清空播放记录：只清位置，**不删视频文件**（§9.4）。 */
    @Query("DELETE FROM t_playback_position WHERE kind = :kind")
    suspend fun clearKind(kind: String): Int

    @Query("DELETE FROM t_playback_position WHERE kind = :kind AND bizKey = :bizKey")
    suspend fun delete(kind: String, bizKey: String)

    /**
     * 清掉"指向这些本子"的某类位置记录（单条 SQL 原子）。
     *
     * 「继续阅读」指针用它：移出书库时若指针正指向被移出的本，一并清掉指针，
     * 宁可卡片消失也不回退到更早读过的旧书（用户方案 B）。
     */
    @Query("DELETE FROM t_playback_position WHERE kind = :kind AND bizKey IN (:bizKeys)")
    suspend fun deleteForKeys(kind: String, bizKeys: List<String>): Int
}

// ======================================================================
// JM 在线搜索历史
// ======================================================================

@Dao
interface SearchHistoryDao {

    @Insert
    suspend fun insert(history: SearchHistoryEntity): Long

    @Query("SELECT * FROM t_jm_search_history ORDER BY updateTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM t_jm_search_history ORDER BY updateTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Query("SELECT * FROM t_jm_search_history WHERE id = :id")
    suspend fun findById(id: Long): SearchHistoryEntity?

    /** 相同条件（关键字 + 排序 + 页码）命中时刷新时间而不是新增，避免历史被同一条件刷屏。 */
    @Query(
        """
        SELECT * FROM t_jm_search_history
        WHERE keyword = :keyword AND sort = :sort AND page = :page
        ORDER BY updateTime DESC LIMIT 1
        """,
    )
    suspend fun findSameCondition(keyword: String, sort: String, page: Int): SearchHistoryEntity?

    @Query("UPDATE t_jm_search_history SET updateTime = :now, total = :total, resultCount = :resultCount, items = :items WHERE id = :id")
    suspend fun refresh(id: Long, now: Long, total: Long, resultCount: Int, items: String?)

    @Query("DELETE FROM t_jm_search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM t_jm_search_history")
    suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM t_jm_search_history")
    suspend fun count(): Int

    /** 保留最近 N 条，删除超额的最旧记录（§9.3「减少数量保存时清除超额最旧记录」）。 */
    @Query(
        """
        DELETE FROM t_jm_search_history
        WHERE id NOT IN (
            SELECT id FROM t_jm_search_history ORDER BY updateTime DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int): Int
}
