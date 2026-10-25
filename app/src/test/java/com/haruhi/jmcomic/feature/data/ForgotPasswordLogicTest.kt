package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.feature.api.ApiJson
import com.haruhi.jmcomic.feature.api.EditResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「忘记密码」的**纯逻辑**测试（不联网、不碰 Android）。
 *
 * 锁住三件事（`docs/official-api-manga.md` §7.5 的实测结论）：
 * 1. 本地只拦"没填 / 不像邮箱"，邮箱是否存在交给服务端；
 * 2. `POST /forgot` 的失败也是 `HTTP 200 + data.status=fail`，文案在 `msg` / `errors` 里；
 * 3. 成功时 `errors` 是空字符串（与 `/register` 同一种形态），必须能宽容解析。
 */
class ForgotPasswordLogicTest {

    // ---------------- 本地校验 ----------------

    @Test
    fun `合法邮箱没有本地错误`() {
        assertNull(validateForgotPassword(ForgotPasswordForm("1527188922@qq.com")))
        assertNull(validateForgotPassword(ForgotPasswordForm("a.b+1@sub.domain.co")))
        // 前后空格不该影响判断（提交时会 trim）
        assertNull(validateForgotPassword(ForgotPasswordForm("  probe@qq.com  ")))
    }

    @Test
    fun `空邮箱拦下来且不发请求`() {
        assertEquals("请输入注册邮箱", validateForgotPassword(ForgotPasswordForm("")))
        assertEquals("请输入注册邮箱", validateForgotPassword(ForgotPasswordForm("   ")))
    }

    @Test
    fun `不像邮箱的输入本地就拦`() {
        // 传用户名（实测服务端回「請輸入有效的電子EMAIL地址！」）在本地就能拦住
        assertEquals("邮箱格式不正确", validateForgotPassword(ForgotPasswordForm("1527188922")))
        assertEquals("邮箱格式不正确", validateForgotPassword(ForgotPasswordForm("a@b")))
        assertEquals("邮箱格式不正确", validateForgotPassword(ForgotPasswordForm("not-an-email")))
    }

    @Test
    fun `邮箱按码位截到 50 个字符`() {
        val emoji = "😀"
        assertEquals(FORGOT_EMAIL_MAX, ForgotPasswordForm("a".repeat(60)).limited().email.length)
        assertEquals(emoji.repeat(50), ForgotPasswordForm(emoji.repeat(60)).limited().email)
    }

    // ---------------- 服务端返回的判读 ----------------

    @Test
    fun `发送成功的真实形态能解析并原样回文案`() {
        // 2026-10-25 实测（真实账号）：注意 errors 是空字符串，不是数组
        val json = """
            {"type":"","msg":"用戶名和新密碼已通過電子EMAIL發送給您！","status":"ok","errors":""}
        """.trimIndent()
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), json)
        assertTrue(dto.isOk)
        assertTrue(dto.errors.isEmpty())
        assertEquals(
            "用戶名和新密碼已通過電子EMAIL發送給您！",
            dto.requireSuccess(fallback = "发送失败", successFallback = "新密码已发送"),
        )
    }

    @Test
    fun `空邮箱的服务端失败文案原样展示`() {
        val json = """
            {"type":"","msg":"請輸入您的電子EMAIL，您第一次註冊。","status":"fail",
             "errors":["請輸入您的電子EMAIL，您第一次註冊。"]}
        """.trimIndent()
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), json)
        assertFalse(dto.isOk)
        assertEquals("請輸入您的電子EMAIL，您第一次註冊。", dto.displayMessage())
        val error = assertThrows(NetworkError.Business::class.java) {
            dto.requireSuccess(fallback = "发送失败", successFallback = "新密码已发送")
        }
        assertEquals("請輸入您的電子EMAIL，您第一次註冊。", error.message)
    }

    @Test
    fun `不是邮箱与没有该邮箱的失败文案都原样展示`() {
        val notEmail = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"type":"","msg":"請輸入有效的電子EMAIL地址！","status":"fail",
                "errors":["請輸入有效的電子EMAIL地址！"]}""",
        )
        assertEquals("請輸入有效的電子EMAIL地址！", notEmail.displayMessage())

        val noUser = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"type":"","msg":"沒有任何用戶使用此電子EMAIL！","status":"fail",
                "errors":["沒有任何用戶使用此電子EMAIL！"]}""",
        )
        assertEquals("沒有任何用戶使用此電子EMAIL！", noUser.displayMessage())
        assertThrows(NetworkError.Business::class.java) {
            noUser.requireSuccess(fallback = "发送失败", successFallback = "新密码已发送")
        }
    }

    @Test
    fun `成功但没有 msg 时用兜底文案`() {
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), """{"status":"ok"}""")
        assertEquals(
            "新密码已发送到注册邮箱，请查收",
            dto.requireSuccess(fallback = "发送失败", successFallback = "新密码已发送到注册邮箱，请查收"),
        )
    }
}
