package com.haruhi.jmcomic.feature.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页版 `/confirm` 响应的解析测试（纯逻辑，不联网）。
 *
 * 这里的每一条都来自 2026-10-23 的真实响应：
 * - 成功时网页版用的是 `toastr['error']`（红色提示），**不能按 level 判成功**；
 * - 同一个页面里还会夹带 `請勿重複簽到` 这类无关提示，不能当成本次重发结果；
 * - 官方 App 的 resend 分支会返回 `無效的核查身份證`，解析后应当是失败。
 */
class WebConfirmParserTest {

    @Test
    fun `成功提示即使标成 error 也算成功`() {
        val html = """<script>toastr['error']("信件已发出，请十分钟后再试 !")</script>"""
        assertEquals(
            WebConfirmResult.Success("信件已发出，请十分钟后再试 !"),
            parseWebConfirmResponse(html),
        )
    }

    @Test
    fun `繁体成功提示也算成功`() {
        val html = """toastr["error"]("信件已發出，請十分鐘後再試 !")"""
        assertEquals(
            WebConfirmResult.Success("信件已發出，請十分鐘後再試 !"),
            parseWebConfirmResponse(html),
        )
    }

    @Test
    fun `同一个页面里的无关提示不会覆盖重发结果`() {
        val html = """
            toastr['error']("信件已发出，请十分钟后再试 !");
            toastr['error']("請勿重複簽到");
        """.trimIndent()
        assertEquals(
            WebConfirmResult.Success("信件已发出，请十分钟后再试 !"),
            parseWebConfirmResponse(html),
        )
    }

    @Test
    fun `密码错误是失败`() {
        val html = """toastr['error']("无效的用户名和/或密码!")"""
        assertEquals(
            WebConfirmResult.Failure("无效的用户名和/或密码!"),
            parseWebConfirmResponse(html),
        )
    }

    @Test
    fun `无效核查身份证是失败`() {
        val html = """toastr['error']("無效的核查身份證！您確定此ID是正確的？ ！2")"""
        assertTrue(parseWebConfirmResponse(html) is WebConfirmResult.Failure)
    }

    @Test
    fun `用户名不存在也是重发相关失败`() {
        val html = """toastr['error']("用戶名不存在")"""
        assertEquals(
            WebConfirmResult.Failure("用戶名不存在"),
            parseWebConfirmResponse(html),
        )
    }

    @Test
    fun `没有 toastr 的页面返回未知`() {
        assertEquals(WebConfirmResult.Unknown, parseWebConfirmResponse("<html></html>"))
        assertEquals(WebConfirmResult.Unknown, parseWebConfirmResponse(""))
    }

    @Test
    fun `只有无关提示时返回未知`() {
        val html = """toastr['error']("請勿重複簽到")"""
        assertEquals(WebConfirmResult.Unknown, parseWebConfirmResponse(html))
    }

    @Test
    fun `HTML 实体与 JS 转义会被清洗`() {
        assertEquals(
            "信件已发出 请十分钟后再试",
            cleanWebMessage("信件已发出&nbsp;请十分钟后再试"),
        )
        assertEquals("a \"b\" c", cleanWebMessage("a \\\"b\\\" c"))
        assertEquals("A", cleanWebMessage("&#65;"))
        assertNull(cleanWebMessage("   "))
    }

    @Test
    fun `重发表单必须带上 submit_action`() {
        val form = buildResendForm(" user ", "p@ss")
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("resend", fields["submit_action"])
        assertEquals("user", fields["username"])
        assertEquals("p@ss", fields["password"])
        assertEquals("发送邮件", fields["submit_confirm"])
    }
}
