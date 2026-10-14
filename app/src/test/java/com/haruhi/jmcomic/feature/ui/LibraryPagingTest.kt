package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import org.junit.Test

/**
 * 书库分页的拼页规则（用户第十轮要求第 4 点）。
 *
 * 最容易出事的是**去重**：列表用 `key = { it.album.id }` 渲染，
 * 而第一页是数据库的实时 Flow、后续页是快照——用户翻到第 3 页时如果有新本子入库，
 * 后面的条目会整体后移一位，于是第 2 页的第一条会和第一页的最后一条重复，
 * 重复 key 会让 `LazyVerticalGrid` 直接抛 `IllegalArgumentException`（整页白屏）。
 */
class LibraryPagingTest {

    private fun album(id: Long) = AlbumEntity(id = id, name = "本子 $id", albumFolderName = "本子_$id")

    @Test
    fun `extra pages are appended after the first page`() {
        val merged = mergeLibraryPages(listOf(album(1), album(2)), listOf(album(3), album(4)))

        assertThat(merged.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `an album that appears on both pages is only listed once`() {
        // 第一页最后一条 = 第二页第一条（入库导致整体后移一位）
        val merged = mergeLibraryPages(
            listOf(album(1), album(2), album(3)),
            listOf(album(3), album(4)),
        )

        assertThat(merged.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `without extra pages the first page is returned as is`() {
        val first = listOf(album(1), album(2))

        // 快路径：一条 SQL 的一页里不可能出现同一行两次，所以不额外做一次去重分配
        assertThat(mergeLibraryPages(first, emptyList())).isSameInstanceAs(first)
    }
}
