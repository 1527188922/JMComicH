package com.haruhi.jmcomic.core.account

import com.haruhi.jmcomic.feature.api.ApiJson
import com.haruhi.jmcomic.feature.api.EditResultDto
import com.haruhi.jmcomic.feature.api.FavoritePageDto
import com.haruhi.jmcomic.feature.api.LoginDto
import com.haruhi.jmcomic.feature.ui.MyUiState
import com.haruhi.jmcomic.feature.ui.AccountFavoritesUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号相关的**纯逻辑**测试（不联网、不碰 Android 框架）。
 *
 * 覆盖三件事（都是 `docs/account-design.md` 里写死的产品规则）：
 * 1. 会员行只对会员显示（[MemberProfile.memberUntil] / [MyUiState.memberLine]）；
 * 2. 三种会话状态各自能取到/取不到资料快照；
 * 3. **真实响应样本**能被 DTO 正确解析（字段名/permissive 类型）。
 */
class AccountModelTest {

    // ---------------- 会员信息只对会员显示 ----------------

    @Test
    fun `非会员没有会员到期日`() {
        val profile = profile(adFree = false, adFreeBefore = "0000-00-00 00:00:00")
        assertNull(profile.memberUntil)
        assertNull(MyUiState(account = AccountState.SignedIn(profile)).memberLine)
    }

    @Test
    fun `会员显示到期日（只取日期部分）`() {
        val profile = profile(adFree = true, adFreeBefore = "2026-11-01 12:00:00")
        assertEquals("2026-11-01", profile.memberUntil)
        assertEquals(
            "超级会员 · 至 2026-11-01",
            MyUiState(account = AccountState.SignedIn(profile)).memberLine,
        )
    }

    @Test
    fun `会员但到期时间是全零时只显示超级会员`() {
        val profile = profile(adFree = true, adFreeBefore = "0000-00-00 00:00:00")
        assertNull(profile.memberUntil)
        assertEquals("超级会员", MyUiState(account = AccountState.SignedIn(profile)).memberLine)
    }

    @Test
    fun `未登录没有账户摘要`() {
        val state = MyUiState(account = AccountState.Anonymous)
        assertNull(state.profile)
        assertNull(state.levelLine)
        assertNull(state.memberLine)
        assertNull(state.favoritesSummary)
    }

    @Test
    fun `等级行是 Lv 加称号`() {
        val profile = profile(level = 4, levelName = "地上的月影")
        assertEquals("Lv.4 · 地上的月影", MyUiState(account = AccountState.SignedIn(profile)).levelLine)
    }

    // ---------------- 显示名：昵称优先 ----------------

    @Test
    fun `没有昵称时显示登录名`() {
        val profile = profile().copy(username = "1527188922", nickName = null)
        assertEquals("1527188922", profile.displayName)
        assertEquals("1527188922", profile.copy(nickName = "").displayName)
        assertEquals("1527188922", profile.copy(nickName = "   ").displayName)
    }

    @Test
    fun `有昵称时显示昵称`() {
        val profile = profile().copy(username = "1527188922", nickName = "小早川")
        assertEquals("小早川", profile.displayName)
        assertEquals("小早川", profile.copy(nickName = "  小早川  ").displayName)
    }

    @Test
    fun `升级前存的资料快照没有昵称字段也能读`() {
        // 昵称是后加的字段：老快照 JSON 里没有它，必须按默认值 null 读出来，
        // 否则升级后「我的」页会直接解析失败、退回"未登录"。
        val oldJson = """{"uid":"6404735","username":"1527188922","level":4,"levelName":"地上的月影"}"""
        val profile = ApiJson.decodeFromString(MemberProfile.serializer(), oldJson)
        assertNull(profile.nickName)
        assertEquals("1527188922", profile.displayName)
    }

    // ---------------- 会话状态 ----------------

    @Test
    fun `三种状态的登录判定与快照`() {
        val profile = profile()
        assertFalse(AccountState.Anonymous.isSignedIn)
        assertNull(AccountState.Anonymous.profileOrNull)

        assertTrue(AccountState.SignedIn(profile).isSignedIn)
        assertEquals(profile, AccountState.SignedIn(profile).profileOrNull)

        // 失效：不算登录，但**快照还在**（页面不清空昵称/收藏数）
        assertFalse(AccountState.Expired(profile).isSignedIn)
        assertEquals(profile, AccountState.Expired(profile).profileOrNull)
    }

    // ---------------- 真实响应能被解析 ----------------

    @Test
    fun `登录响应解析（数字与字符串混排）`() {
        val json = """
            {
              "uid": "6404735", "username": "1527188922", "email": "a@b.c",
              "photo": "nopic-.gif?v=0", "level": 4, "level_name": "地上的月影",
              "exp": "1490", "nextLevelExp": 2100, "coin": "865",
              "album_favorites": 178, "album_favorites_max": 600,
              "ad_free": false, "ad_free_before": "0000-00-00 00:00:00",
              "jwttoken": "jwt-xxx", "s": "avs-xxx"
            }
        """.trimIndent()
        val dto = ApiJson.decodeFromString(LoginDto.serializer(), json)
        assertEquals("6404735", dto.uid)
        assertEquals(4, dto.level)
        assertEquals("地上的月影", dto.levelName)
        assertEquals(178, dto.albumFavorites)
        assertEquals(600, dto.albumFavoritesMax)
        assertEquals(false, dto.adFree)
        assertEquals("jwt-xxx", dto.jwtToken)
        assertEquals("avs-xxx", dto.avs)
    }

    @Test
    fun `收藏列表解析（total 是字符串、FID 是字符串数字、列表项没有 update_at）`() {
        val json = """
            {
              "list": [
                { "id": "1225147", "author": "CunnyFunky", "name": "Shigure Ui",
                  "image": "", "latest_ep": null, "latest_ep_aid": null,
                  "category": { "id": "1", "title": "同人" },
                  "category_sub": { "id": "3", "title": "短篇" } }
              ],
              "folder_list": [
                { "FID": "1780873", "UID": "6404735", "name": "本子" },
                { "FID": "4090037", "UID": "6404735", "name": "AI" }
              ],
              "total": "178",
              "count": 20
            }
        """.trimIndent()
        val dto = ApiJson.decodeFromString(FavoritePageDto.serializer(), json)
        assertEquals("178", dto.total)
        assertEquals(20, dto.count)
        assertEquals(2, dto.folderList?.size)
        assertEquals("1780873", dto.folderList?.first()?.fid)
        assertEquals("1225147", dto.list?.first()?.id)
    }

    @Test
    fun `收藏操作的两种返回：成功与业务失败`() {
        val ok = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"status":"ok","msg":"漫画添加到您最喜爱的清单!","type":"add"}""",
        )
        assertTrue(ok.isOk)
        assertEquals("add", ok.type)

        // 业务失败也是 HTTP 200，只能看 data 里的 code/status
        val fail = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"status":"fail","msg":"该收藏夹裡还有漫画，请先删除漫画","code":400}""",
        )
        assertFalse(fail.isOk)
        assertEquals(400, fail.code)
        assertEquals("该收藏夹裡还有漫画，请先删除漫画", fail.msg)
    }

    @Test
    fun `未登录时收藏页状态直接显示登录引导`() {
        val state = AccountFavoritesUiState(signedIn = false, loading = false)
        assertFalse(state.signedIn)
        assertEquals(0, state.total)
        assertTrue(state.folders.isEmpty())
    }

    private fun profile(
        level: Int = 4,
        levelName: String = "地上的月影",
        adFree: Boolean = false,
        adFreeBefore: String? = "0000-00-00 00:00:00",
    ) = MemberProfile(
        uid = "6404735",
        username = "1527188922",
        level = level,
        levelName = levelName,
        albumFavorites = 178,
        albumFavoritesMax = 600,
        adFree = adFree,
        adFreeBefore = adFreeBefore,
    )
}
