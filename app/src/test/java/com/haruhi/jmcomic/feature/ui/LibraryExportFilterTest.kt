package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.feature.data.ExportFormat
import org.junit.Test

/**
 * 「有 ZIP / 有 PDF」这两条筛选的判定（用户第九轮要求第 1 点）。
 *
 * 规则本身很小，但两个地方容易写错：
 * 1. 两个条件都打开时应该是"**两种都有**"，不是"有任意一种"；
 * 2. **快照还没回来时不能误杀**——`formats == null` 表示"这本还没查过"，
 *    当成"没有成品"会让用户一进页面看到空列表。
 */
class LibraryExportFilterTest {

    private val zip = setOf(ExportFormat.ZIP)
    private val pdf = setOf(ExportFormat.PDF)
    private val both = setOf(ExportFormat.ZIP, ExportFormat.PDF)

    @Test
    fun `without any export filter everything passes`() {
        assertThat(matchesExportFilter(null, hasZipOnly = false, hasPdfOnly = false)).isTrue()
        assertThat(matchesExportFilter(emptySet(), hasZipOnly = false, hasPdfOnly = false)).isTrue()
    }

    @Test
    fun `zip filter keeps only albums with a zip`() {
        assertThat(matchesExportFilter(zip, hasZipOnly = true, hasPdfOnly = false)).isTrue()
        assertThat(matchesExportFilter(both, hasZipOnly = true, hasPdfOnly = false)).isTrue()
        assertThat(matchesExportFilter(pdf, hasZipOnly = true, hasPdfOnly = false)).isFalse()
        assertThat(matchesExportFilter(emptySet(), hasZipOnly = true, hasPdfOnly = false)).isFalse()
    }

    @Test
    fun `pdf filter keeps only albums with a pdf`() {
        assertThat(matchesExportFilter(pdf, hasZipOnly = false, hasPdfOnly = true)).isTrue()
        assertThat(matchesExportFilter(zip, hasZipOnly = false, hasPdfOnly = true)).isFalse()
    }

    @Test
    fun `both filters together require both formats`() {
        assertThat(matchesExportFilter(both, hasZipOnly = true, hasPdfOnly = true)).isTrue()
        assertThat(matchesExportFilter(zip, hasZipOnly = true, hasPdfOnly = true)).isFalse()
        assertThat(matchesExportFilter(pdf, hasZipOnly = true, hasPdfOnly = true)).isFalse()
    }

    @Test
    fun `an album whose exports were not loaded yet is not filtered out`() {
        // null = 磁盘快照里还没有这本（刚进页面），此时放行，等快照回来再筛
        assertThat(matchesExportFilter(null, hasZipOnly = true, hasPdfOnly = false)).isTrue()
        assertThat(matchesExportFilter(null, hasZipOnly = true, hasPdfOnly = true)).isTrue()
    }

    @Test
    fun `the clear button only counts filters the user added`() {
        val inFolder = LibraryFilter(favoriteId = 7L)
        // 收藏夹本身不是筛选项，界面上不该出现"清除 1 项"
        assertThat(inFolder.hasUserFilter).isFalse()
        assertThat(inFolder.userActiveCount).isEqualTo(0)
        assertThat(inFolder.activeCount).isEqualTo(1)

        val withChips = inFolder.copy(hasZipOnly = true, downloadedOnly = true)
        assertThat(withChips.hasUserFilter).isTrue()
        assertThat(withChips.userActiveCount).isEqualTo(2)
        assertThat(withChips.activeCount).isEqualTo(3)
    }
}
