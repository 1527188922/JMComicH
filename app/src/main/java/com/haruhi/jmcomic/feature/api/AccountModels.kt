package com.haruhi.jmcomic.feature.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JM 账号（会员）相关接口的响应模型。
 *
 * 全部按 `docs/official-api-manga.md` §7 / §8 的**实测**响应写；字段类型不规整的地方
 * （`total` 有时字符串、`FID` 是字符串数字、`level` 是数字而 `exp` 是字符串）都用
 * [LooseStringSerializer] 或对应类型显式声明，避免换一种形态就整条解析失败。
 */

/** 带 `errorMsg` 的信封：登录失败是 `HTTP 401 + errorMsg`（实测），不走加密的 `data`。 */
@Serializable
internal data class AccountEnvelopeDto(
    @SerialName("code") val code: Int? = null,
    @SerialName("data") val data: String? = null,
    @SerialName("errorMsg") val errorMsg: String? = null,
    @SerialName("msg") val msg: String? = null,
)

/**
 * `POST /login` 成功时的 `data`（= 会员资料）。
 *
 * 只声明本工程用得到的字段；`ignoreUnknownKeys` 会跳过 `badges` / `invitation_*` 等。
 * `nextLevelExp` 实测是数字、`exp` 是字符串，所以这里分别用 `Long` 与宽松字符串。
 */
@Serializable
data class LoginDto(
    @SerialName("uid") val uid: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("photo") val photo: String? = null,
    @SerialName("level") val level: Int? = null,
    @SerialName("level_name") val levelName: String? = null,
    @SerialName("exp")
    @Serializable(with = LooseStringSerializer::class)
    val exp: String? = null,
    @SerialName("nextLevelExp") val nextLevelExp: Long? = null,
    @SerialName("coin")
    @Serializable(with = LooseStringSerializer::class)
    val coin: String? = null,
    @SerialName("album_favorites") val albumFavorites: Int? = null,
    @SerialName("album_favorites_max") val albumFavoritesMax: Int? = null,
    @SerialName("ad_free") val adFree: Boolean? = null,
    @SerialName("ad_free_before") val adFreeBefore: String? = null,
    @SerialName("jwttoken") val jwtToken: String? = null,
    @SerialName("s") val avs: String? = null,
)

/** `GET /favorite` 的收藏夹条目；`FID` 是字符串数字。 */
@Serializable
data class FavoriteFolderDto(
    @SerialName("FID") val fid: String? = null,
    @SerialName("UID") val uid: String? = null,
    @SerialName("name") val name: String? = null,
)

/** `GET /favorite` 的列表项（与分类/搜索列表项基本同构，但**没有** `update_at`）。 */
@Serializable
data class FavoriteItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("category") val category: CategoryDto? = null,
    @SerialName("category_sub") val categorySub: CategoryDto? = null,
    @SerialName("latest_ep") val latestEp: String? = null,
    @SerialName("latest_ep_aid") val latestEpAid: String? = null,
)

/** `GET /favorite`（`docs/official-api-manga.md` §8.1）。`total` 是字符串、`count` 是每页条数。 */
@Serializable
data class FavoritePageDto(
    @SerialName("list") val list: List<FavoriteItemDto>? = null,
    @SerialName("folder_list") val folderList: List<FavoriteFolderDto>? = null,
    @SerialName("total")
    @Serializable(with = LooseStringSerializer::class)
    val total: String? = null,
    @SerialName("count") val count: Int? = null,
)

/**
 * 收藏 / 收藏夹操作的统一返回（`docs/official-api-manga.md` §8.2 / §8.3）。
 *
 * 成功：`{status:"ok", msg, type?}`；业务失败仍是 **HTTP 200 + 信封 200**，
 * 但 `data` 里带 `{status:"fail", msg, code:400}` —— 所以 [code] 必须一起读。
 */
@Serializable
data class EditResultDto(
    @SerialName("status") val status: String? = null,
    @SerialName("msg") val msg: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("code") val code: Int? = null,
) {
    val isOk: Boolean get() = status == "ok"
}

/** `GET /useredit/{uid}`（`docs/official-api-manga.md` §7.6）。字段即官方表单的 24 项。 */
@Serializable
data class UserEditDto(
    @SerialName("nickName") val nickName: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("password_confirm") val passwordConfirm: String? = null,
    @SerialName("firstName") val firstName: String? = null,
    @SerialName("lastName") val lastName: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("birthday") val birthday: String? = null,
    @SerialName("relations") val relations: String? = null,
    @SerialName("sexuality") val sexuality: String? = null,
    @SerialName("website") val website: String? = null,
    @SerialName("birthPlace") val birthPlace: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("occupation") val occupation: String? = null,
    @SerialName("company") val company: String? = null,
    @SerialName("school") val school: String? = null,
    @SerialName("aboutMe") val aboutMe: String? = null,
    @SerialName("infoHere") val infoHere: String? = null,
    @SerialName("collections") val collections: String? = null,
    @SerialName("ideal") val ideal: String? = null,
    @SerialName("erogenic") val erogenic: String? = null,
    @SerialName("favorite") val favorite: String? = null,
    @SerialName("hate") val hate: String? = null,
    @SerialName("status") val status: String? = null,
)

/** 账号请求需要的两个 header（`Authorization: Bearer …` + `Cookie: AVS=…`）。 */
data class AuthHeaders(val jwt: String, val avs: String)
