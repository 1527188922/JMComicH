package com.haruhi.haruhibox.feature.jm.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 路由参数编解码的回归测试（用户报的 `+` bug）。
 *
 * 用户的现象：在搜索记录页粘贴一段带空格、带 `%`、带 `[]` 的多行文本，点搜索进结果页，
 * 结果页输入框里**所有空格都变成了 `+`**。
 *
 * 根因见 [encodeRouteArg] 的注释：`URLEncoder` 把空格编成 `+`（Navigation 只做百分号解码，
 * 不会还原 `+`），而页面里那次多余的 `URLDecoder.decode` 又因为关键字里的 `%` 抛异常、
 * 退回未解码的串——两层叠起来就是用户看到的样子。
 *
 * 这里锁住的是**值本身的性质**：编码结果只由 `A-Za-z0-9-_` 和前缀里的 `.` 组成，
 * 所以任何 URI 层（百分号解码、`+` 还原、解两次）对它都是空操作。
 */
class JmRouteArgsTest {

    /** 用户报 bug 时用的那段关键字（换行已被输入框的净化规则去掉，这里保留原样更严格）。 */
    private val reportedKeyword = "[Hoshikawa Ranga] 120cm no Gaki o 100% Kushizashi Rinkan suru " +
        "Houhou ~ Shigure Shigure.mp4 ~ [Chinese] [Digital]\n" +
        "[星川ランガ] 120cmのガキを100%串刺輪姦する方法～時雨紫雨.mp4～ [中国翻訳] [DL版]"

    @Test
    fun `keyword with spaces percent and brackets survives the route round trip`() {
        val encoded = encodeRouteArg(reportedKeyword)

        assertThat(decodeRouteArg(encoded)).isEqualTo(reportedKeyword)
    }

    @Test
    fun `encoded value cannot be touched by any uri layer`() {
        val encoded = encodeRouteArg(reportedKeyword)

        // 任何一次百分号解码 / `+` 还原 / 再解一次，都改不动这些字符
        assertThat(encoded).doesNotContain("%")
        assertThat(encoded).doesNotContain("+")
        assertThat(encoded).doesNotContain("&")
        assertThat(encoded).doesNotContain("=")
        assertThat(encoded).doesNotContain("?")
        assertThat(encoded).doesNotContain("#")
        assertThat(encoded).doesNotContain("/")
        assertThat(encoded).doesNotContain(" ")
        assertThat(encoded).startsWith(ROUTE_ARG_PREFIX)
    }

    @Test
    fun `a plus in the keyword is preserved instead of being eaten as a space`() {
        val keyword = "C++ 入门"

        assertThat(decodeRouteArg(encodeRouteArg(keyword))).isEqualTo(keyword)
    }

    @Test
    fun `a keyword that looks like base64 is not mangled`() {
        // 没有前缀判别的话，`abcd` / `YQ` 会被 Base64 解成乱码
        listOf("abcd", "YQ", "YQ==", "b64", "b64.", "test").forEach { keyword ->
            assertThat(decodeRouteArg(encodeRouteArg(keyword))).isEqualTo(keyword)
        }
    }

    @Test
    fun `plain values without the prefix are passed through`() {
        // 兼容旧路由 / 手写的深链
        assertThat(decodeRouteArg("魔法少女")).isEqualTo("魔法少女")
        assertThat(decodeRouteArg("")).isEmpty()
        assertThat(decodeRouteArg("abcd")).isEqualTo("abcd")
    }

    @Test
    fun `a broken payload falls back to the raw value instead of throwing`() {
        assertThat(decodeRouteArg(ROUTE_ARG_PREFIX + "!!!not base64!!!")).isEqualTo(ROUTE_ARG_PREFIX + "!!!not base64!!!")
    }

    @Test
    fun `empty and blank keywords round trip`() {
        assertThat(decodeRouteArg(encodeRouteArg(""))).isEmpty()
        assertThat(decodeRouteArg(encodeRouteArg("   "))).isEqualTo("   ")
    }

    @Test
    fun `round trip is stable for long multi line text`() {
        val keyword = (1..50).joinToString("\n") { "第 $it 行 100% [测试] + plus" }

        assertThat(decodeRouteArg(encodeRouteArg(keyword))).isEqualTo(keyword)
    }
}
