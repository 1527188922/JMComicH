package com.haruhi.jmcomic.feature.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JM 接口的响应模型。
 *
 * 字段与 JSON key 逐条对应参考实现 `features/jmcomic/client/model` 下的 Java 类。
 * 用 `@SerialName` 显式写出每个 key 而不是配全局命名策略：JM 的 key 不是规整的
 * snake_case（有 `is_favorite`、`category_sub`、`update_at` 这种混排），
 * 显式映射能避免"改个策略就连带改坏"的风险。
 *
 * 注意：本文件不要写含 `斜杠星号` 的注释片段，Kotlin 的块注释支持嵌套，
 * 会直接把后面的代码吞掉（P2 踩过一次）。
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

// 响应外壳：{ "data": "<密文>" }
@Serializable
data class ApiEnvelope(
    @SerialName("data") val data: String? = null,
)

/** 把库里存的 JSON 字符串数组还原成列表。解析失败会抛异常，调用方决定怎么兜底。 */
fun decodeStringList(json: String): List<String> =
    ApiJson.decodeFromString(ListSerializer(String.serializer()), json)

/**
 * 本子详情（`/album` 的 `data` 解密后的 JSON）。
 *
 * 对应参考实现 `Album.java`。注意 `series` 是**章节列表的权威来源**：
 * 详情页优先展示它，避免只从已下载图片反推章节（计划 §D.1）。
 */
@Serializable
data class AlbumDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String? = null,
    @SerialName("images") val images: List<String>? = null,
    @SerialName("add_time") val addTime: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("total_views") val totalViews: String? = null,
    @SerialName("likes") val likes: String? = null,
    @SerialName("series") val series: List<SeriesDto>? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("comment_total") val commentTotal: String? = null,
    @SerialName("author") val author: List<String>? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("works") val works: List<String>? = null,
    @SerialName("actors") val actors: List<String>? = null,
    @SerialName("related_list") val relatedList: List<RelatedDto>? = null,
    @SerialName("liked") val liked: Boolean? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    @SerialName("is_aids") val isAids: Boolean? = null,
    @SerialName("price") val price: String? = null,
    @SerialName("purchased") val purchased: String? = null,
)

/** 章节条目。`id` 是 chapterId；`title` 是接口给的展示名，可能为空。 */
@Serializable
data class SeriesDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("sort") val sort: String? = null,
    @SerialName("title") val title: String? = null,
)

@Serializable
data class RelatedDto(
    @SerialName("id") val id: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("image") val image: String? = null,
)

/**
 * 章节详情（`/chapter` 的 `data` 解密后的 JSON）：图片清单。
 *
 * 对应参考实现 `Chapter.java`。`images` 是接口原始文件名列表，
 * 入库前必须按 `FileNaming.sortImageFiles` 排序（参考实现也这么做）。
 */
@Serializable
data class ChapterDto(
    @SerialName("id") val id: Long,
    @SerialName("series") val series: List<SeriesDto>? = null,
    @SerialName("tags") val tags: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("images") val images: List<String>? = null,
    @SerialName("add_time") val addTime: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    @SerialName("liked") val liked: Boolean? = null,
)

/** 搜索结果（`/search` 的 `data` 解密后的 JSON）。 */
@Serializable
data class SearchRespDto(
    @SerialName("search_query") val searchQuery: String? = null,
    @SerialName("total") val total: String? = null,
    @SerialName("content") val content: List<SearchItemDto>? = null,
)

@Serializable
data class SearchItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("category") val category: CategoryDto? = null,
    @SerialName("category_sub") val categorySub: CategoryDto? = null,
    @SerialName("liked") val liked: Boolean? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    /** 10 位时间戳。 */
    @SerialName("update_at") val updateAt: Long? = null,
)

@Serializable
data class CategoryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
)

// ----------------------------------------------------------------------
// 评论（`/forum`）
// ----------------------------------------------------------------------

/**
 * 评论列表（`/forum?mode=all&page=N&aid=JM号` 的 `data` 解密后的 JSON）。
 *
 * 实测（2026-10，aid=114514）：`total` 被服务端截断到 5000，每页固定 10 条，
 * 匿名可读（不需要登录态）。字段类型也是**实测**的，与官方逆向文档 §6.3 有两处不同：
 *
 * | 文档 | 实际响应 |
 * |---|---|
 * | `likes: 0`（数字） | `"likes": "0"`（字符串） |
 * | `spoiler: false`（布尔） | `"spoiler": "1"`（字符串，且同一本**每条都是 "1"**） |
 *
 * 所以 `likes` 这里声明成 `String?`；`spoiler` **故意不声明**（`ignoreUnknownKeys`
 * 会跳过它）：实测拿它无法区分剧透与否，声明成 `String` 反而会因将来类型变化整页解析失败。
 */
@Serializable
data class ForumRespDto(
    @SerialName("total") val total: String? = null,
    @SerialName("list") val list: List<ForumItemDto>? = null,
)

/**
 * 一条评论。
 *
 * `replys` 是**内嵌的回复列表**（文档里没写，实测存在）：每页 10 条主楼里带着各自的回复，
 * 不用再按 `parent_CID` 单独请求。回复项的结构与主楼相同（只是没有再嵌一层）。
 */
@Serializable
data class ForumItemDto(
    @SerialName("CID") val cid: String? = null,
    @SerialName("UID") val uid: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("nickname") val nickname: String? = null,
    /** 正文是 HTML 片段（`<div>` 包裹，可能含 `<img>` 表情），展示前必须清洗。 */
    @SerialName("content") val content: String? = null,
    /** 头像文件名，拼 `{img_host}/media/users/{photo}`；没有头像时是 `nopic-*.gif`。 */
    @SerialName("photo") val photo: String? = null,
    @SerialName("likes") val likes: String? = null,
    @SerialName("gender") val gender: String? = null,
    /** 展示用时间串，实测形如 `Oct 11, 2026`。 */
    @SerialName("addtime") val addTime: String? = null,
    /** 父评论 id；主楼为空或 `"0"`。 */
    @SerialName("parent_CID") val parentCid: String? = null,
    @SerialName("expinfo") val expInfo: ForumExpDto? = null,
    @SerialName("replys") val replies: List<ForumItemDto>? = null,
)

/** 评论者的等级 / 徽章信息（只取展示用得上的两项）。 */
@Serializable
data class ForumExpDto(
    @SerialName("level") val level: Int? = null,
    @SerialName("level_name") val levelName: String? = null,
)
