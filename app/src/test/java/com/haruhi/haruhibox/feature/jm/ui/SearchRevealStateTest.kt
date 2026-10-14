package com.haruhi.haruhibox.feature.jm.ui

import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.core.jm.JmSearchSort
import com.haruhi.haruhibox.feature.jm.api.JmSearchItemDto
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchItem
import com.haruhi.haruhibox.feature.jm.data.OnlineSearchResult
import org.junit.Test

/**
 * 搜索结果的"分屏追加"状态机测试（用户要求第 5 点）。
 *
 * 为什么单独测这个：滚动触发的判定在真机/模拟器上很难自动验证，
 * 但**判定条件本身**是纯逻辑，可以完整覆盖。判定抽在
 * [OnlineSearchUiState.shouldRevealMore]，Compose 侧只负责在滚动时调用它。
 *
 * 背景：接口一页返回 80 条，一次性渲染会同时发起 80 个封面请求。
 */
class SearchRevealStateTest {

    @Test
    fun `first screen renders only ten items`() {
        val state = stateWith(itemCount = 80)

        assertThat(state.visibleItemsCount).isEqualTo(10)
        assertThat(state.remainingCount).isEqualTo(70)
        assertThat(state.allVisible).isFalse()
    }

    @Test
    fun `reveal triggers when the last visible item approaches the end of the batch`() {
        val state = stateWith(itemCount = 80)

        // 列表未布局时不应触发
        assertThat(state.shouldRevealMore(lastVisibleIndex = -1)).isFalse()
        // 还在前几条时不应触发
        assertThat(state.shouldRevealMore(lastVisibleIndex = 3)).isFalse()
        // 距底部还有 REVEAL_AHEAD 条时开始触发（滚动手感更连续）
        val threshold = state.visibleItemsCount - OnlineSearchUiState.REVEAL_AHEAD
        assertThat(state.shouldRevealMore(lastVisibleIndex = threshold - 1)).isFalse()
        assertThat(state.shouldRevealMore(lastVisibleIndex = threshold)).isTrue()
        assertThat(state.shouldRevealMore(lastVisibleIndex = state.visibleItemsCount)).isTrue()
    }

    @Test
    fun `each reveal adds one batch until everything is shown`() {
        var state = stateWith(itemCount = 35)

        // 10 -> 20 -> 30 -> 35
        val expected = listOf(20, 30, 35)
        expected.forEach { next ->
            val threshold = state.visibleItemsCount - OnlineSearchUiState.REVEAL_AHEAD
            assertThat(state.shouldRevealMore(threshold)).isTrue()
            assertThat(state.shouldRevealMore(state.visibleItemsCount + 5)).isTrue()
            state = state.copy(visibleCount = next)
            assertThat(state.visibleItemsCount).isEqualTo(next)
        }

        // 全部展示完之后不再触发追加（此时才轮到"加载下一页"）
        assertThat(state.remainingCount).isEqualTo(0)
        assertThat(state.allVisible).isTrue()
        assertThat(state.shouldRevealMore(lastVisibleIndex = 999)).isFalse()
    }

    @Test
    fun `visible count never exceeds the actual item count`() {
        // 最后一页只有 4 条，但 visibleCount 已经被加到 50
        val state = stateWith(itemCount = 4).copy(visibleCount = 50)

        assertThat(state.visibleItemsCount).isEqualTo(4)
        assertThat(state.remainingCount).isEqualTo(0)
        assertThat(state.allVisible).isTrue()
    }

    @Test
    fun `no result means nothing to reveal`() {
        val state = OnlineSearchUiState()

        assertThat(state.visibleItemsCount).isEqualTo(0)
        assertThat(state.remainingCount).isEqualTo(0)
        assertThat(state.allVisible).isTrue()
        assertThat(state.shouldRevealMore(lastVisibleIndex = 99)).isFalse()
    }

    @Test
    fun `fewer items than one batch are shown immediately`() {
        val state = stateWith(itemCount = 6)

        assertThat(state.visibleItemsCount).isEqualTo(6)
        assertThat(state.remainingCount).isEqualTo(0)
        assertThat(state.allVisible).isTrue()
    }

    // ------------------------------------------------------------------

    /** 构造一个"第一页、尚未追加"的搜索状态。 */
    private fun stateWith(itemCount: Int, totalPages: Int = 2) = OnlineSearchUiState(
        keyword = "关键字",
        result = OnlineSearchResult(
            query = "关键字",
            sort = JmSearchSort.LATEST,
            page = 1,
            total = itemCount.toLong(),
            pageSize = 80,
            totalPages = totalPages,
            items = (1..itemCount).map { index ->
                OnlineSearchItem(
                    id = index.toLong(),
                    name = "结果 $index",
                    author = null,
                    description = null,
                    coverUrl = "https://example.invalid/$index.jpg",
                    tags = emptyList(),
                    updateAt = null,
                    existsLocal = false,
                    raw = JmSearchItemDto(id = index.toString()),
                )
            },
        ),
        visibleCount = OnlineSearchUiState.INITIAL_VISIBLE_COUNT,
    )
}
