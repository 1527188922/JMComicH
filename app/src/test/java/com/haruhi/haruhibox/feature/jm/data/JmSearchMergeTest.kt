package com.haruhi.haruhibox.feature.jm.data

import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.feature.jm.api.JmSearchItemDto
import org.junit.Test

/**
 * "关键词是纯数字时同时查 /search 与 /album"的合并规则（用户要求第 3 点）。
 *
 * 为什么单独测这个纯函数：JM 的搜索响应是**加密**的，MockWebServer 造不出真实结果，
 * 而这条规则决定了用户第一眼看到什么（置顶 + 不重复 + 条数对得上）。
 */
class JmSearchMergeTest {

    private fun item(id: Long, exact: Boolean = false) = OnlineSearchItem(
        id = id,
        name = "本子 $id",
        author = "[]",
        description = null,
        coverUrl = "",
        tags = emptyList(),
        updateAt = null,
        existsLocal = false,
        isExactIdMatch = exact,
        raw = JmSearchItemDto(id = id.toString(), name = "本子 $id"),
    )

    private fun result(vararg ids: Long, total: Long = ids.size.toLong()) = OnlineSearchResult(
        query = ids.firstOrNull()?.toString().orEmpty(),
        sort = JmSearchSort.DEFAULT,
        page = 1,
        total = total,
        pageSize = 80,
        totalPages = 1,
        items = ids.map { item(it) },
    )

    @Test
    fun `exact match is pinned to the front`() {
        val merged = mergeExactIdMatch(result(11L, 22L), item(99L, exact = true))

        assertThat(merged.items.map { it.id }).containsExactly(99L, 11L, 22L).inOrder()
        assertThat(merged.items.first().isExactIdMatch).isTrue()
    }

    @Test
    fun `an album already in the results is not listed twice`() {
        val merged = mergeExactIdMatch(result(11L, 99L, 22L), item(99L, exact = true))

        assertThat(merged.items.map { it.id }).containsExactly(99L, 11L, 22L).inOrder()
        // 已经算在搜索结果里了，总数不能再 +1
        assertThat(merged.total).isEqualTo(3L)
    }

    @Test
    fun `the pinned album adds one to the total when the search missed it`() {
        val merged = mergeExactIdMatch(result(11L, 22L), item(99L, exact = true))

        assertThat(merged.total).isEqualTo(3L)
    }

    @Test
    fun `a search with no hits still shows the exact match`() {
        val merged = mergeExactIdMatch(result(total = 0), item(99L, exact = true))

        assertThat(merged.items.map { it.id }).containsExactly(99L)
        assertThat(merged.total).isEqualTo(1L)
        // 分页信息保持搜索接口给的：精确匹配不改变"还有没有下一页"
        assertThat(merged.page).isEqualTo(1)
        assertThat(merged.sort).isEqualTo(JmSearchSort.DEFAULT)
    }

    /**
     * 纯数字查询时 JM 会返回 `total = 1` 但 `content` 为空（一条搜索结果都没有），
     * 直接"总数 +1"就会显示成"共 2 条"（用户要求第 6 点③）。
     * 搜索一条都没返回时，接口给的 total 不可信，以实际条目为准。
     */
    @Test
    fun `a phantom search total is ignored when the search returned nothing`() {
        val merged = mergeExactIdMatch(result(total = 1), item(99L, exact = true))

        assertThat(merged.items.map { it.id }).containsExactly(99L)
        assertThat(merged.total).isEqualTo(1L)
    }
}
