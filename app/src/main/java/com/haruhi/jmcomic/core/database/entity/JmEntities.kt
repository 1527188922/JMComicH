package com.haruhi.jmcomic.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `t_jm_album`：**漫画基础数据表**（永久缓存）。
 *
 * 在线 `/album` 拿到的详情都落在这里，**原有内容字段全部保留**（对齐参考实现
 * `JmAlbumSqlite` 的字段集）。它**不表达**"在不在书库"，也**不表达**"看没看过"：
 * - 书库成员关系、本地收藏标记 `collected`、首次入库时间在 [LibraryEntity]（`t_jm_library`）；
 * - 最近预览在 [RecentAlbumEntity]（`t_jm_recent_album`），只存 `id` 与浏览时间。
 *
 * 两张关联表都通过 `id` 引用本表，列表/详情渲染时与本表拼接。所以
 * **清空最近浏览、移出书库都不会删除这里的任何数据**——这样"请求漫画详情接口"
 * 与"加入最近预览"才能真正解耦，同时保留下载页、阅读器、精确匹配搜索
 * 依赖的本地详情缓存。
 */
@Entity(
    tableName = "t_jm_album",
    indices = [
        Index(value = ["name"]),
    ],
)
data class AlbumBaseEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "album_folder_name") val albumFolderName: String? = null,
    @ColumnInfo(name = "images") val images: String? = null,
    @ColumnInfo(name = "add_time") val addTime: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "total_views") val totalViews: String? = null,
    @ColumnInfo(name = "likes") val likes: String? = null,
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "comment_total") val commentTotal: String? = null,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    @ColumnInfo(name = "works") val works: String? = null,
    @ColumnInfo(name = "actors") val actors: String? = null,
    @ColumnInfo(name = "related_list") val relatedList: String? = null,
    @ColumnInfo(name = "liked") val liked: Boolean? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean? = null,
    @ColumnInfo(name = "is_aids") val isAids: Boolean? = null,
    @ColumnInfo(name = "price") val price: String? = null,
    @ColumnInfo(name = "purchased") val purchased: String? = null,
    @ColumnInfo(name = "raw") val raw: String? = null,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
    @ColumnInfo(name = "modify_time") val modifyTime: String? = null,
)

/**
 * 漫画读取模型（**不是数据库实体**）：基础数据 + 书库语义的 `collected`。
 *
 * 书库列表 / 最近列表 / 详情页读取时由查询拼出来：内容列来自 `t_jm_album`，
 * `collected` 来自 `t_jm_library`（未入库时为空）。保留"字段最全"的一个模型，
 * 是为了详情页、下载、导出继续共用同一套渲染与目录逻辑。
 *
 * `@ColumnInfo` 仍然有效：Room 用它把查询结果里的 snake_case 列映射到字段。
 */
data class AlbumEntity(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "album_folder_name") val albumFolderName: String? = null,
    @ColumnInfo(name = "images") val images: String? = null,
    @ColumnInfo(name = "add_time") val addTime: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "total_views") val totalViews: String? = null,
    @ColumnInfo(name = "likes") val likes: String? = null,
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "comment_total") val commentTotal: String? = null,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    @ColumnInfo(name = "works") val works: String? = null,
    @ColumnInfo(name = "actors") val actors: String? = null,
    @ColumnInfo(name = "related_list") val relatedList: String? = null,
    @ColumnInfo(name = "liked") val liked: Boolean? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean? = null,
    /** 本地收藏标记：只存在 `t_jm_library.collected`，未入库/未收藏时为空。 */
    @ColumnInfo(name = "collected") val collected: Boolean? = null,
    @ColumnInfo(name = "is_aids") val isAids: Boolean? = null,
    @ColumnInfo(name = "price") val price: String? = null,
    @ColumnInfo(name = "purchased") val purchased: String? = null,
    @ColumnInfo(name = "raw") val raw: String? = null,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
    @ColumnInfo(name = "modify_time") val modifyTime: String? = null,
)

/**
 * `t_jm_library`：**书库成员表**。存"这本在书库里"、本地收藏标记与首次入库时间。
 *
 * 为什么不放在基础表上：基础表是永久缓存，而"移出书库"要能把这本
 * 从书库列表里去掉——成员关系（连同收藏标记）必须可删、且删掉后基础数据仍在。
 * 书库列表渲染时与 [AlbumBaseEntity] 按 `album_id` 拼接。
 *
 * `create_time` 是**第一次入库时间**，书库默认排序用它（刷新详情不改变它）。
 */
@Entity(
    tableName = "t_jm_library",
    indices = [Index(value = ["collected"])],
)
data class LibraryEntity(
    @PrimaryKey @ColumnInfo(name = "album_id") val albumId: Long,
    /**
     * 本地收藏标记（原 `t_jm_album.collected`）。
     *
     * 收集/取消收藏只改这里（`/album` 响应里没有这个字段，刷新详情不受影响）；
     * 移出书库时整行删除，标记自然消失（与旧行为一致）。
     */
    @ColumnInfo(name = "collected") val collected: Boolean? = null,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
)

/**
 * `t_jm_chapter_image`：本子章节的图片清单（一章一图一行）。
 *
 * 这张表是"章节清单"的**权威来源**：详情页的章节列表、图片清单都来自它，
 * 而不是从"已下载的图片文件"反推（计划 §D.1 的高优先级项：
 * 「必须有稳定的章节图片清单，才能准确判断漏图」）。
 *
 * 参考实现对该表有专门的自定义查询（`ChapterImageSqliteMapper.xml` 的
 * `selectChapterList` / `selectImages`），移植时照抄 SQL 语义——
 * 前者是 `GROUP BY chapter_id … MIN(id) … ORDER BY CAST(chapter_sort AS INTEGER), CAST(chapter_id AS INTEGER)`。
 */
@Entity(
    tableName = "t_jm_chapter_image",
    indices = [
        Index(value = ["album_id", "chapter_id"]),
        Index(value = ["album_id", "chapter_id", "image_sort"]),
    ],
)
data class ChapterImageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "chapter_sort") val chapterSort: String? = null,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String? = null,
    @ColumnInfo(name = "chapter_name") val chapterName: String? = null,
    @ColumnInfo(name = "chapter_add_time") val chapterAddTime: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "liked") val liked: Boolean? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean? = null,
    /** 接口返回的原始文件名，同时就是磁盘上的文件名。 */
    @ColumnInfo(name = "image_file") val imageFile: String,
    @ColumnInfo(name = "image_sort") val imageSort: Int? = null,
)

/**
 * `t_jm_favorite`：收藏夹（文件夹）。`name` 唯一。
 *
 * 默认收藏夹名 `默认收藏夹` 由初始化流程写入，且不允许删除/重命名。
 */
@Entity(
    tableName = "t_jm_favorite",
    indices = [Index(value = ["name"], unique = true)],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** 展示排序，值越小越靠前 */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
    @ColumnInfo(name = "modify_time") val modifyTime: String? = null,
)

/**
 * `t_jm_favorite_album`：收藏夹 ↔ 本子的多对多关联。
 * 唯一索引 `(favorite_id, album_id)` 保证同一收藏夹内不重复。
 */
@Entity(
    tableName = "t_jm_favorite_album",
    indices = [Index(value = ["favorite_id", "album_id"], unique = true)],
)
data class FavoriteAlbumEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "favorite_id") val favoriteId: Long,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
)

/**
 * `t_jm_recent_album`：**最近预览**。只记录"进过详情页/阅读页"这一事实与浏览时间。
 *
 * 内容字段全部去掉：渲染时与 [AlbumEntity]（基础数据表）按 `id` 拼接，
 * 所以条目不会因为"有没有联网数据"而分叉，接口数据也只存一份。
 *
 * 写入**只发生在进入详情页或阅读页时**（`Repository.touchRecent`）：
 * 拉取 `/album` 接口本身不写这张表（用户要求：请求接口 ≠ 加入最近预览）。
 */
@Entity(
    tableName = "t_jm_recent_album",
    indices = [Index(value = ["viewed_time"])],
)
data class RecentAlbumEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    /** 浏览时间（毫秒）。**只在点进详情页/阅读页时更新**；刷新详情不改它。 */
    @ColumnInfo(name = "viewed_time") val viewedTime: Long = System.currentTimeMillis(),
)
