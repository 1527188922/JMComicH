package com.haruhi.jmcomic.core.account

import kotlinx.serialization.Serializable

/**
 * 会员资料快照（`POST /login` 的 `data`）。
 *
 * 这是**本机缓存**，只用于「我的」页首屏（离线也能显示上次的昵称/头像），
 * 不是账号数据的真相；真相永远在服务器。
 *
 * 字段来源见 `docs/official-api-manga.md` §7.1（实测）：
 * `uid`/`exp`/`coin`/`charge` 是字符串，`level`/`album_favorites` 是数字，`ad_free` 是布尔。
 */
@Serializable
data class MemberProfile(
    val uid: String,
    val username: String,
    val email: String? = null,
    /** 头像文件名（拼 `{img_host}/media/users/{photo}`），例如 `nopic-.gif?v=0`。 */
    val photo: String? = null,
    val level: Int = 0,
    /** 称号，例如 `地上的月影`。 */
    val levelName: String = "",
    val exp: Long = 0L,
    val nextLevelExp: Long = 0L,
    val coin: String = "0",
    val albumFavorites: Int = 0,
    val albumFavoritesMax: Int = 0,
    /** `ad_free`：是否开通了"去广告"会员。 */
    val adFree: Boolean = false,
    /** `ad_free_before`：会员到期时间；未开通时是 `0000-00-00 00:00:00`。 */
    val adFreeBefore: String? = null,
) {
    /**
     * 会员到期日（`2026-11-01`）。
     *
     * 只有真的是会员才有值——服务端未开通时给的是全 0 时间戳，
     * 直接显示成"至 0000-00-00"会让人以为坏了，所以这里就折成 null。
     */
    val memberUntil: String?
        get() = adFreeBefore
            ?.takeIf { adFree && it.isNotBlank() && !it.startsWith("0000") }
            ?.substringBefore(' ')
}
