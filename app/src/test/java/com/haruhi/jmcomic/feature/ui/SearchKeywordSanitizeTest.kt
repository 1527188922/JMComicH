package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 搜索输入框的净化规则测试（用户要求）。
 *
 * 两个页面（搜索记录页 / 搜索结果页）共用 [sanitizeSearchKeyword]，
 * 所以这一份用例同时覆盖两边：
 * - 首尾空格自动去掉；
 * - 字符串**当中**的换行自动去掉（粘贴多行文本、输入法回车都可能带进来）；
 * - 中间的普通空格保留（"魔法 少女"是合法关键字）。
 */
class SearchKeywordSanitizeTest {

    @Test
    fun `leading and trailing spaces are dropped`() {
        assertThat(sanitizeSearchKeyword("  魔法少女  ")).isEqualTo("魔法少女")
        assertThat(sanitizeSearchKeyword(" abc ")).isEqualTo("abc")
    }

    @Test
    fun `newlines anywhere are removed`() {
        assertThat(sanitizeSearchKeyword("魔法\n少女")).isEqualTo("魔法少女")
        assertThat(sanitizeSearchKeyword("魔法\r\n少女")).isEqualTo("魔法少女")
        assertThat(sanitizeSearchKeyword("\n魔法少女\n")).isEqualTo("魔法少女")
        // 多行粘贴：换行被吃掉，但行首行尾的空格不会被并进关键字里
        assertThat(sanitizeSearchKeyword("  魔法\n  少女\n")).isEqualTo("魔法  少女")
    }

    @Test
    fun `inner spaces are kept`() {
        assertThat(sanitizeSearchKeyword("魔法 少女")).isEqualTo("魔法 少女")
    }

    @Test
    fun `sanitizing is idempotent`() {
        val once = sanitizeSearchKeyword("  魔法\n少女  ")
        assertThat(sanitizeSearchKeyword(once)).isEqualTo(once)
    }

    @Test
    fun `blank input stays blank`() {
        assertThat(sanitizeSearchKeyword("")).isEmpty()
        assertThat(sanitizeSearchKeyword("   ")).isEmpty()
        assertThat(sanitizeSearchKeyword("\n\n")).isEmpty()
    }
}
