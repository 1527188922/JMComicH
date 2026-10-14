package com.haruhi.jmcomic.feature.ui

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.feature.data.ExportFormat
import org.junit.Test

/**
 * 「继续阅读」那行小字与"导出中"状态文案（用户第十一轮要求第 1、3 点）。
 *
 * 都是纯展示逻辑，但很容易写出"第 0 页""处理中"这种让人看不懂的字眼，
 * 所以用用例把边界钉住。
 */
class ReadingProgressLabelTest {

    @Test
    fun `a saved page shows the chapter and the page number`() {
        val text = readingProgressLabel(chapterTitle = "第2话", hasPage = true, pageNumber = 15)

        assertThat(text).isEqualTo("上次读到 第2话 · 第 15 页")
    }

    @Test
    fun `a position that only knows the chapter does not pretend to know the page`() {
        // 点章节进去但一张都没翻就退出：位置只记到章节
        val text = readingProgressLabel(chapterTitle = "第3话", hasPage = false, pageNumber = 1)

        assertThat(text).isEqualTo("上次读到 第3话")
    }

    @Test
    fun `a missing chapter title still yields something readable`() {
        val text = readingProgressLabel(chapterTitle = "  ", hasPage = true, pageNumber = 2)

        assertThat(text).isEqualTo("上次读到 上次读到的地方 · 第 2 页")
    }

    @Test
    fun `an active export shows queue running with percent and waiting states`() {
        assertThat(ActiveExport(ExportFormat.ZIP, DownloadStatus.QUEUED, 0f).statusText)
            .isEqualTo("排队中")
        assertThat(ActiveExport(ExportFormat.ZIP, DownloadStatus.RUNNING, 0.456f).statusText)
            .isEqualTo("导出中 45%")
        // 还没开始算进度时不显示 0%，否则看着像卡住了
        assertThat(ActiveExport(ExportFormat.PDF, DownloadStatus.RUNNING, 0f).statusText)
            .isEqualTo("导出中")
        assertThat(ActiveExport(ExportFormat.PDF, DownloadStatus.WAITING_NETWORK, 0f).statusText)
            .isEqualTo("等待网络")
    }
}
