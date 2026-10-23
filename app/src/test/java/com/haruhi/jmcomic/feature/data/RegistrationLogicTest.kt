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
 * 注册 / 验证码的**纯逻辑**测试（不联网、不碰 Android）。
 *
 * 三条产品规则被锁在这里：
 * 1. 本地校验不过**不发请求**（用户名 15 字上限是服务端实测出来的，不是官方前端的 50）；
 * 2. 服务端业务失败是 `HTTP 200 + data.status=fail`，必须从 `errors` 里取**全部**问题；
 * 3. 验证码重发/验证的入参互斥（一个带 `numcode`，一个带 `password`），由 ViewModel 保证。
 */
class RegistrationLogicTest {

    private fun validForm() = SignUpForm(
        username = "probe_user",
        password = "P@ssw0rd",
        passwordConfirm = "P@ssw0rd",
        email = "probe@qq.com",
        gender = "Male",
        adultConfirmed = true,
        termsAccepted = true,
    )

    // ---------------- 本地校验 ----------------

    @Test
    fun `完整表单没有本地错误`() {
        val validation = validateSignUp(validForm())
        assertFalse(validation.hasError)
        assertNull(validation.username)
        assertNull(validation.password)
        assertNull(validation.passwordConfirm)
        assertNull(validation.email)
        assertNull(validation.gender)
        assertNull(validation.confirmation)
    }

    @Test
    fun `空表单每一条都拦下来`() {
        val validation = validateSignUp(SignUpForm())
        assertTrue(validation.hasError)
        assertEquals("请输入用户名", validation.username)
        assertEquals("请输入密码", validation.password)
        assertEquals("请再次输入密码", validation.passwordConfirm)
        assertEquals("请输入邮箱", validation.email)
        assertEquals("请选择性别", validation.gender)
        assertEquals("请确认已年满 18 岁", validation.confirmation)
    }

    @Test
    fun `用户名上限是服务端实测的 15 个字符`() {
        assertNull(validateSignUp(validForm().copy(username = "a".repeat(15))).username)
        assertEquals(
            "用户名最多 15 个字符",
            validateSignUp(validForm().copy(username = "a".repeat(16))).username,
        )
        // 官方前端 maxLength=50，服务端其实只认 15 —— 本地就该按 15 截
        assertEquals(15, SignUpForm(username = "a".repeat(30)).limited().username.length)
    }

    @Test
    fun `邮箱按官方正则校验`() {
        assertNull(validateSignUp(validForm().copy(email = "a.b+1@sub.domain.co")).email)
        assertEquals(
            "邮箱格式不正确",
            validateSignUp(validForm().copy(email = "not-an-email")).email,
        )
        assertEquals(
            "邮箱格式不正确",
            validateSignUp(validForm().copy(email = "a@b")).email,
        )
    }

    @Test
    fun `两次密码必须一致`() {
        assertEquals(
            "两次输入的密码不一致",
            validateSignUp(validForm().copy(passwordConfirm = "different")).passwordConfirm,
        )
    }

    @Test
    fun `性别与两个勾选都是必填`() {
        assertEquals("请选择性别", validateSignUp(validForm().copy(gender = "")).gender)
        assertEquals(
            "请确认已年满 18 岁",
            validateSignUp(validForm().copy(adultConfirmed = false)).confirmation,
        )
        assertEquals(
            "请阅读并同意用户协议",
            validateSignUp(validForm().copy(termsAccepted = false)).confirmation,
        )
    }

    @Test
    fun `验证码只拦空与超长`() {
        assertNull(validateSignUpCode("123456"))
        assertEquals("请输入邮箱验证码", validateSignUpCode("   "))
        assertEquals("验证码最多 10 位", validateSignUpCode("1".repeat(11)))
    }

    @Test
    fun `密码按码位截断不会劈开 emoji`() {
        val emoji = "😀"
        val limited = SignUpForm(password = emoji.repeat(60)).limited()
        assertEquals(emoji.repeat(50), limited.password)
    }

    // ---------------- 服务端返回的判读 ----------------

    @Test
    fun `注册失败时展示 errors 里的全部问题`() {
        // 2026-10-23 实测（未创建账号）的真实形态
        val json = """
            {"type":"","msg":"电子邮件是不是有效的电子邮件地址!1",
             "status":"fail",
             "errors":["电子邮件是不是有效的电子邮件地址!1","用户名已经存在!"]}
        """.trimIndent()
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), json)
        assertFalse(dto.isOk)
        assertEquals(
            "电子邮件是不是有效的电子邮件地址!1；用户名已经存在!",
            dto.displayMessage(),
        )
        val error = assertThrows(NetworkError.Business::class.java) {
            dto.requireSuccess(fallback = "注册失败", successFallback = "注册成功")
        }
        assertEquals("电子邮件是不是有效的电子邮件地址!1；用户名已经存在!", error.message)
    }

    @Test
    fun `注册成功的文案原样清洗后返回`() {
        val json = """{"status":"ok","msg":"註冊成功！<br>請到信箱收驗證碼"}"""
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), json)
        assertTrue(dto.isOk)
        assertEquals(
            "註冊成功！ 請到信箱收驗證碼",
            dto.requireSuccess(fallback = "注册失败", successFallback = "注册成功"),
        )
    }

    @Test
    fun `注册成功时 errors 是空字符串也能解析（真机实测的形态）`() {
        // 2026-10-23 真机：账号已创建、邮件已发，但客户端因为把 errors 当数组而解析失败。
        val json = """
            {"type":"","msg":"您已注册。检查您的电子邮件中的确认链接!","status":"ok","errors":""}
        """.trimIndent()
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), json)
        assertTrue(dto.isOk)
        assertTrue(dto.errors.isEmpty())
        assertEquals(
            "您已注册。检查您的电子邮件中的确认链接!",
            dto.requireSuccess(fallback = "注册失败", successFallback = "注册成功"),
        )
    }

    @Test
    fun `errors 是单个字符串或 null 时也能解析`() {
        val single = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"status":"fail","errors":"用户名已经存在!"}""",
        )
        assertEquals(listOf("用户名已经存在!"), single.errors)
        assertEquals("用户名已经存在!", single.displayMessage())

        val missing = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"status":"fail","msg":"失败"}""",
        )
        assertTrue(missing.errors.isEmpty())
        assertEquals("失败", missing.displayMessage())

        val nullErrors = ApiJson.decodeFromString(
            EditResultDto.serializer(),
            """{"status":"fail","errors":null}""",
        )
        assertTrue(nullErrors.errors.isEmpty())
    }

    @Test
    fun `成功但没有 msg 时用兜底文案`() {
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), """{"status":"ok"}""")
        assertEquals(
            "验证码已发送",
            dto.requireSuccess(fallback = "失败", successFallback = "验证码已发送"),
        )
    }

    @Test
    fun `errors 里的 HTML 被清洗掉`() {
        val json = """{"status":"fail","errors":["<div>用户名<b>已存在</b></div>"]}"""
        val dto = ApiJson.decodeFromString(EditResultDto.serializer(), json)
        assertEquals("用户名已存在", dto.displayMessage())
    }

    @Test
    fun `msg 里的连续空白压成一个空格`() {
        assertEquals("两段话", cleanServerMessage("两段话\n\n"))
        assertEquals("a b", cleanServerMessage("a<br>b"))
        assertNull(cleanServerMessage("   "))
        assertNull(cleanServerMessage(null))
    }
}
