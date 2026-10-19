package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.feature.data.CATEGORY_SUB_ALL
import com.haruhi.jmcomic.feature.data.CategoryGroup
import com.haruhi.jmcomic.feature.data.CategorySub
import org.junit.Test

/**
 * 分类页状态的派生字段（抽屉按钮文案、排序项、翻页按钮出现与否）。
 *
 * 这些属性直接决定界面长什么样，抽在 data class 里就是为了能这样测——
 * Compose 里的排版在这台设备上没法自动验证。
 */
class CategoryStateTest {

    private fun group(name: String, slug: String, vararg subs: CategorySub) =
        CategoryGroup(name = name, slug = slug, subs = subs.toList())

    private val catalog = listOf(
        group("最新A漫", "", CATEGORY_SUB_ALL),
        group("同人", "doujin", CATEGORY_SUB_ALL, CategorySub("漢化", "chinese")),
    )

    @Test
    fun `state without catalog still shows the default selection`() {
        val state = CategoryUiState()

        // 分类树还没回来时标题不能空着，否则用户会看到一行只有图标和排序
        assertThat(state.selectorLabel).isEqualTo("最新A漫 · 全部")
        assertThat(state.main).isNull()
        assertThat(state.sorts).hasSize(5)
    }

    @Test
    fun `selector label follows the selected main and sub`() {
        val state = CategoryUiState(
            catalog = catalog,
            mainSlug = "doujin",
            subSlug = "chinese",
        )

        assertThat(state.selectorLabel).isEqualTo("同人 · 漢化")
    }

    @Test
    fun `sort options drop the weekly rank for non-latest groups`() {
        assertThat(CategoryUiState().sorts).contains(CategorySort.RANK_WEEK)
        assertThat(CategoryUiState(mainSlug = "doujin").sorts)
            .doesNotContain(CategorySort.RANK_WEEK)
    }

    @Test
    fun `load more only appears when a page is loaded and more pages remain`() {
        assertThat(CategoryUiState().hasMore).isFalse()
        assertThat(CategoryUiState(page = 1, totalPages = 1).hasMore).isFalse()
        assertThat(CategoryUiState(page = 1, totalPages = 3).hasMore).isTrue()
        assertThat(CategoryUiState(page = 3, totalPages = 3).hasMore).isFalse()
    }
}
