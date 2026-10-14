package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 收藏页排序的纯逻辑验收（用户要求第 1 点）：
 *
 * - 三种排序方式（创建时间 / 漫画数量 / 最近加入）**都是降序**；
 * - **默认收藏夹永远置顶**，换排序方式也不动它；
 * - 同值时按 id 兜底，顺序稳定（不会因为重组抖动）。
 *
 * 抽成纯函数是因为它只跟"列表顺序"有关，跟 Compose 无关；
 * 界面只需要把它算出来的顺序画出来。
 */
class FavoriteSortTest {

    @Test
    fun `the default favorite always stays on top`() {
        val rows = listOf(
            row(id = 1, name = "夹一", albums = 10, created = "2026-05-01 10:00:00"),
            row(id = 2, name = "默认收藏夹", albums = 0, created = "2020-01-01 10:00:00", isDefault = true),
            row(id = 3, name = "夹二", albums = 5, created = "2026-06-01 10:00:00"),
        )

        // 三种排序方式下，默认夹都在第一个
        FavoriteSort.entries.forEach { sort ->
            val sorted = sortFavorites(rows, sort)
            assertThat(sorted.first().name).isEqualTo("默认收藏夹")
        }
    }

    @Test
    fun `created time sorts descending and is the default`() {
        val rows = listOf(
            row(id = 1, name = "旧", created = "2026-01-01 10:00:00"),
            row(id = 2, name = "新", created = "2026-06-01 10:00:00"),
            row(id = 3, name = "中", created = "2026-03-01 10:00:00"),
        )

        assertThat(FavoriteSort.DEFAULT).isEqualTo(FavoriteSort.CREATED_DESC)
        assertThat(sortFavorites(rows, FavoriteSort.CREATED_DESC).map { it.name })
            .containsExactly("新", "中", "旧").inOrder()
    }

    @Test
    fun `album count sorts descending`() {
        val rows = listOf(
            row(id = 1, name = "少", albums = 1),
            row(id = 2, name = "多", albums = 9),
            row(id = 3, name = "中", albums = 4),
        )

        assertThat(sortFavorites(rows, FavoriteSort.ALBUM_COUNT_DESC).map { it.name })
            .containsExactly("多", "中", "少").inOrder()
    }

    @Test
    fun `last added time sorts descending and empty folders go last`() {
        val rows = listOf(
            row(id = 1, name = "很久没加", albums = 3, lastAdded = "2026-01-01 10:00:00"),
            row(id = 2, name = "刚加过", albums = 1, lastAdded = "2026-09-01 10:00:00"),
            // 空夹：lastAddedTime 为 null
            row(id = 3, name = "空夹", albums = 0, lastAdded = null),
        )

        assertThat(sortFavorites(rows, FavoriteSort.LAST_ADDED_DESC).map { it.name })
            .containsExactly("刚加过", "很久没加", "空夹").inOrder()
    }

    @Test
    fun `ties fall back to the id so the order never jitters`() {
        val rows = listOf(
            row(id = 7, name = "B", albums = 2, created = "2026-01-01 10:00:00"),
            row(id = 3, name = "A", albums = 2, created = "2026-01-01 10:00:00"),
        )

        assertThat(sortFavorites(rows, FavoriteSort.ALBUM_COUNT_DESC).map { it.id })
            .containsExactly(3L, 7L).inOrder()
        assertThat(sortFavorites(rows, FavoriteSort.CREATED_DESC).map { it.id })
            .containsExactly(3L, 7L).inOrder()
    }

    @Test
    fun `an empty list sorts to an empty list`() {
        assertThat(sortFavorites(emptyList(), FavoriteSort.DEFAULT)).isEmpty()
    }

    /** 界面上只展示这三个词，不能出现内部枚举名。 */
    @Test
    fun `sort options are the user facing wording`() {
        assertThat(FavoriteSort.entries.map { it.label })
            .containsExactly("创建时间", "漫画数量", "最近加入")
    }

    // ---------------- 工具 ----------------

    private fun row(
        id: Long,
        name: String,
        albums: Int = 0,
        created: String? = "2026-01-01 00:00:00",
        lastAdded: String? = null,
        isDefault: Boolean = false,
    ) = FavoritesUiState.FavoriteRow(
        id = id,
        name = name,
        albumCount = albums,
        isDefault = isDefault,
        createTime = created,
        lastAddedTime = lastAdded,
        latestAlbumId = if (albums > 0) id * 1000 else null,
    )
}
