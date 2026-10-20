package com.haruhi.jmcomic.feature.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * 章节详情（图片清单）——**两代接口归一化后的同一个模型**。
 *
 * - 旧机制：[ApiClient] 直接反序列化 `/chapter` 的响应；
 * - 官方 2.1.11：由 [ComicReadDto] 转换而来（完整 CDN URL 只取文件名）。
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
    /**
     * 本话的 `scramble_id`（解扰块数的输入）。
     *
     * 旧机制 `/chapter` 里没有这个字段（那时另外走 `/chapter_view_template`），
     * 所以为 null；官方 2.1.11 的 `/comic_read` 直接给（接口给的是**字符串**，
     * 用 [LooseStringSerializer] 读，兼容将来改成数字）。
     */
    @SerialName("scramble_id")
    @Serializable(with = LooseStringSerializer::class)
    val scrambleId: String? = null,
)

/**
 * `GET /comic_read` 的 `data` 解密后的 JSON（官方逆向文档 §7.5）。
 *
 * 实测形如：
 * ```json
 * { "id":114514, "scramble_id":"220980", "name":"…", "total_page":27,
 *   "images":[{"page":1,"image":"https://{cdn}/media/photos/114514/00001.webp?t=…"}] }
 * ```
 *
 * 两处与文档不一致、因此按宽松方式读：
 * - `id` 文档是字符串、实测是数字 → 用 [LooseStringSerializer]；
 * - `images[].image` 是**完整 URL**（指向接口当时给的 CDN），
 *   本工程只从中取文件名，图片仍按用户选中的图片线路拼接（见 [ApiClient.comicRead]）。
 */
@Serializable
data class ComicReadDto(
    @SerialName("id")
    @Serializable(with = LooseStringSerializer::class)
    val id: String? = null,
    @SerialName("scramble_id")
    @Serializable(with = LooseStringSerializer::class)
    val scrambleId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("total_page")
    @Serializable(with = LooseStringSerializer::class)
    val totalPage: String? = null,
    @SerialName("images") val images: List<ComicReadImageDto>? = null,
    /** 实测的 key 是 `addtime`（不是文档其它接口那种 `add_time`）。 */
    @SerialName("addtime") val addTime: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean? = null,
    @SerialName("liked") val liked: Boolean? = null,
)

/** `/comic_read` 的一页：`page` 用不上（入库前统一按文件名排序），只取完整 URL 里的文件名。 */
@Serializable
data class ComicReadImageDto(
    @SerialName("image") val image: String? = null,
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
    /**
     * 上架日期 `YYYY-MM-DD`。
     *
     * 分类列表的每一条都带它，而 `update_at` 偶尔缺失/为 0——
     * 所以"更新时间"的展示规则是：有 [updateAt] 用它，没有就用 [addDate]（用户要求）。
     * `/search` 也会返回这个字段，但那边界面不展示。
     */
    @SerialName("adddate") val addDate: String? = null,
)

@Serializable
data class CategoryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
)

// ----------------------------------------------------------------------
// 分类（`/categories`、`/categories/filter`）
// ----------------------------------------------------------------------

/**
 * 「字符串优先」的宽松标量反序列化器。
 *
 * JM 的 `total` 在不同接口 / 不同时期有**数字**与**字符串**两种形态：
 * 逆向文档 §4.3 写的是 `"total": 10000`，而 §6.3 的 `/forum` 实测是 `"total": "851"`。
 * 声明成 `String` 遇到数字会整条响应解析失败（Java 实现里它是 `Object` 再 `toString`），
 * 声明成 `Long` 又会在字符串上翻车。所以这里两边都当文本读。
 *
 * 注意 `JsonNull` 也是 [JsonPrimitive]（[JsonPrimitive.contentOrNull] 对它返回 null），
 * 而可空属性本身由 kotlinx 的 nullable 包装处理，所以这里只处理"非空标量"。
 */
internal object LooseStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "com.haruhi.jmcomic.LooseString",
        PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull ?: ""
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/**
 * `GET /categories` 的 `data` 解密后的 JSON（官方逆向文档 §4.2）。
 *
 * 响应里还有 `blocks`（主题标签组，点击走 `/search?filter=`）与每个分类的 `id`，
 * 本工程不用：分类的下拉抽屉只按 `slug` 区分（`id` 是数字与字符串混排，
 * 声明它反而要额外兼容）。`ignoreUnknownKeys = true` 会跳过它们。
 */
@Serializable
data class CategoriesRespDto(
    @SerialName("categories") val categories: List<CategoryGroupDto>? = null,
)

/** 一个主分类；`sub_categories` 可能缺省（最新A漫 / Cosplay / 3D / 禁漫漢化組就没有）。 */
@Serializable
data class CategoryGroupDto(
    @SerialName("name") val name: String? = null,
    @SerialName("slug") val slug: String? = null,
    /**
     * 该主分类下的漫画总数。
     *
     * 实测形态不统一：最新A漫 / Cosplay / 3D / 禁漫漢化組 是数字 `0`，
     * 有内容的分类是字符串（`"354792"`）——所以同样用宽松标量读。
     */
    @SerialName("total_albums")
    @Serializable(with = LooseStringSerializer::class)
    val totalAlbums: String? = null,
    @SerialName("sub_categories") val subCategories: List<CategorySubDto>? = null,
)

/** 一个二级分类，例如 `漢化`（`slug = "chinese"`）。 */
@Serializable
data class CategorySubDto(
    @SerialName("name") val name: String? = null,
    @SerialName("slug") val slug: String? = null,
)

/**
 * `GET /categories/filter` 的 `data` 解密后的 JSON（官方逆向文档 §4.3）。
 *
 * `content` 的条目与 `/search` **完全同构**（唯一差别是分类列表没有 `description`），
 * 所以直接复用 [SearchItemDto]；`tags` 是"本页出现的热门标签"，本工程不用。
 * `total` 被服务端截断到 **10000**，前端据此算页数（最多 125 页）。
 */
@Serializable
data class CategoryFilterRespDto(
    @SerialName("total")
    @Serializable(with = LooseStringSerializer::class)
    val total: String? = null,
    @SerialName("content") val content: List<SearchItemDto>? = null,
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
