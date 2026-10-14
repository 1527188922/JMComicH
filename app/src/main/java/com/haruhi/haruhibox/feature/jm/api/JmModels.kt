package com.haruhi.haruhibox.feature.jm.api

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
internal val JmJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

// 响应外壳：{ "data": "<密文>" }
@Serializable
data class JmEnvelope(
    @SerialName("data") val data: String? = null,
)

/** 把库里存的 JSON 字符串数组还原成列表。解析失败会抛异常，调用方决定怎么兜底。 */
fun decodeStringList(json: String): List<String> =
    JmJson.decodeFromString(ListSerializer(String.serializer()), json)

/**
 * 本子详情（`/album` 的 `data` 解密后的 JSON）。
 *
 * 对应参考实现 `Album.java`。注意 `series` 是**章节列表的权威来源**：
 * 详情页优先展示它，避免只从已下载图片反推章节（计划 §D.1）。
 */
@Serializable
data class JmAlbumDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String? = null,
    @SerialName("images") val images: List<String>? = null,
    @SerialName("add_time") val addTime: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("total_views") val totalViews: String? = null,
    @SerialName("likes") val likes: String? = null,
    @SerialName("series") val series: List<JmSeriesDto>? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("comment_total") val commentTotal: String? = null,
    @SerialName("author") val author: List<String>? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("works") val works: List<String>? = null,
    @SerialName("actors") val actors: List<String>? = null,
    @SerialName("related_list") val relatedList: List<JmRelatedDto>? = null,
    @SerialName("liked") val liked: Boolean? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    @SerialName("is_aids") val isAids: Boolean? = null,
    @SerialName("price") val price: String? = null,
    @SerialName("purchased") val purchased: String? = null,
)

/** 章节条目。`id` 是 chapterId；`title` 是接口给的展示名，可能为空。 */
@Serializable
data class JmSeriesDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("sort") val sort: String? = null,
    @SerialName("title") val title: String? = null,
)

@Serializable
data class JmRelatedDto(
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
data class JmChapterDto(
    @SerialName("id") val id: Long,
    @SerialName("series") val series: List<JmSeriesDto>? = null,
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
data class JmSearchRespDto(
    @SerialName("search_query") val searchQuery: String? = null,
    @SerialName("total") val total: String? = null,
    @SerialName("content") val content: List<JmSearchItemDto>? = null,
)

@Serializable
data class JmSearchItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("category") val category: JmCategoryDto? = null,
    @SerialName("category_sub") val categorySub: JmCategoryDto? = null,
    @SerialName("liked") val liked: Boolean? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    /** 10 位时间戳。 */
    @SerialName("update_at") val updateAt: Long? = null,
)

@Serializable
data class JmCategoryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
)
