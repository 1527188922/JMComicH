package com.haruhi.jmcomic.feature.ui

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.feature.data.albumAddTimeLabel
import org.junit.Test

/**
 * 书库/详情页**展示逻辑**的纯单元测试。
 *
 * 这些文案与状态判定直接对应计划 §9 的界面要求：
 * - §9 第 4 条：「状态优先展示下载章节数，避免堆叠徽标」；
 * - §9 第 7 条：章节行显示"已下载 / 部分下载 x/y / 未下载"；
 * - §9.1：占用体积要给用户看得懂的单位。
 *
 * **为什么不做 Compose UI 测试**：完整交互测试需要设备处于**亮屏且解锁**状态
 * （Compose 的测试框架会一直等窗口获得焦点，熄屏时会永久阻塞）。
 * 在无法保证该前提的机器上跑这类测试只会得到"永远挂住"，没有验证价值。
 * 界面本身的验证方式见 `docs/BUILD.md` 的"设备测试"一节。
 */
class DisplayLogicTest {

    // ---------------- 书库卡片状态文案 ----------------

    @Test
    fun `album card summarizes download progress instead of stacking badges`() {
        assertThat(downloadSummary(card(total = 0, downloaded = 0))).isEqualTo("未获取章节")
        assertThat(downloadSummary(card(total = 10, downloaded = 0))).isEqualTo("未下载 · 共 10 话")
        assertThat(downloadSummary(card(total = 10, downloaded = 10))).isEqualTo("已下载全 10 话")
        assertThat(downloadSummary(card(total = 10, downloaded = 3))).isEqualTo("已下载 3/10 话")
    }

    @Test
    fun `album card falls back to the jm id when the name is blank`() {
        val album = AlbumEntity(id = 480854, name = "   ")
        assertThat(AlbumCard(album, 0, 0, 0).displayName).isEqualTo("JM480854")

        val named = AlbumEntity(id = 480854, name = "有名字")
        assertThat(AlbumCard(named, 0, 0, 0).displayName).isEqualTo("有名字")
    }

    // ---------------- 章节状态 ----------------

    @Test
    fun `chapter row reports downloaded partial and missing states`() {
        assertThat(chapter(total = 20, done = 20).statusText).isEqualTo("已下载")
        assertThat(chapter(total = 45, done = 12).statusText).isEqualTo("部分下载 12/45")
        assertThat(chapter(total = 30, done = 0).statusText).isEqualTo("未下载")
    }

    @Test
    fun `chapter row boolean flags match the status text`() {
        val full = chapter(total = 20, done = 20)
        assertThat(full.isDownloaded).isTrue()
        assertThat(full.isPartial).isFalse()
        assertThat(full.isMissing).isFalse()

        val partial = chapter(total = 45, done = 12)
        assertThat(partial.isDownloaded).isFalse()
        assertThat(partial.isPartial).isTrue()
        assertThat(partial.isMissing).isFalse()

        val missing = chapter(total = 30, done = 0)
        assertThat(missing.isDownloaded).isFalse()
        assertThat(missing.isPartial).isFalse()
        assertThat(missing.isMissing).isTrue()
    }

    @Test
    fun `chapter without a known image list is treated as missing not downloaded`() {
        // 还没拉过图片清单（totalImages == 0）时不能显示"已下载"
        val unknown = chapter(total = 0, done = 0)
        assertThat(unknown.isDownloaded).isFalse()
        assertThat(unknown.statusText).isEqualTo("未下载")
    }

    // ---------------- 体积文案 ----------------

    @Test
    fun `formatBytes uses readable units`() {
        assertThat(formatBytes(0)).isEqualTo("0 B")
        assertThat(formatBytes(-1)).isEqualTo("0 B")
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(2048)).isEqualTo("2.0 KB")
        assertThat(formatBytes(5L * 1024 * 1024)).isEqualTo("5.0 MB")
        assertThat(formatBytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.00 GB")
    }

    // ---------------- 详情页头部统计 / 章节区标题 ----------------

    @Test
    fun `album stats drop blank counts and use the same compact format as categories`() {
        // 还没联网刷新过、或接口没给这一项时整项不显示（不留"♥ 0"这种假数字）
        assertThat(formatStatCount(null)).isNull()
        assertThat(formatStatCount("")).isNull()
        assertThat(formatStatCount("   ")).isNull()
        // 用户 2026-10-20 要求：点赞 / 观看数跟分类总数用同一套紧凑格式
        assertThat(formatStatCount("0")).isEqualTo("0")
        assertThat(formatStatCount("999")).isEqualTo("999")
        assertThat(formatStatCount("1000")).isEqualTo("1.0k")
        assertThat(formatStatCount(" 3547 ")).isEqualTo("3.5k")
        assertThat(formatStatCount("12345")).isEqualTo("1.2w")
        assertThat(formatStatCount("354792")).isEqualTo("35.5w")
        // 接口给的不是数字（脏数据 / 其它形态）时原样展示，不吞掉
        assertThat(formatStatCount("--")).isEqualTo("--")
    }

    @Test
    fun `album add-time label comes from addtime and disappears when missing`() {
        // `/album` 只给 `addtime`（10 位秒级时间戳），没有单独的更新时间字段：
        // 详情页把它当"上架时间"显示在 JM 号那一行（用户 2026-10-20 要求）。
        assertThat(albumAddTimeLabel(null)).isNull()
        assertThat(albumAddTimeLabel("")).isNull()
        assertThat(albumAddTimeLabel("   ")).isNull()
        // 0 / 非正数当"没有时间"，不要显示"上架 0"
        assertThat(albumAddTimeLabel("0")).isNull()
        // 秒级时间戳 → 本地时区的 YYYY-MM-DD（不写死具体日期，避开时区差异）
        assertThat(albumAddTimeLabel("1655254028")).matches("\\d{4}-\\d{2}-\\d{2}")
        assertThat(albumAddTimeLabel(" 1655254028 ")).matches("\\d{4}-\\d{2}-\\d{2}")
        // 万一接口以后换成日期字符串：原样展示，不吞掉
        assertThat(albumAddTimeLabel("2022-06-15")).isEqualTo("2022-06-15")
    }

    @Test
    fun `chapter section title carries the progress and the local size`() {
        // 占用体积从封面区挪到这里（用户要求）："章节 · 0/1 已下载（7.4 MB）"
        assertThat(chapterSectionTitle(total = 1, downloaded = 0, localSizeBytes = 7759462))
            .isEqualTo("章节 · 0/1 已下载（7.4 MB）")
        // 本地没有文件时不加括号（不写"（0 B）"）
        assertThat(chapterSectionTitle(total = 10, downloaded = 3, localSizeBytes = 0))
            .isEqualTo("章节 · 3/10 已下载")
        // 章节清单还没拿到时只留"章节"，但本地确实占了空间就要说
        assertThat(chapterSectionTitle(total = 0, downloaded = 0, localSizeBytes = 2048))
            .isEqualTo("章节（2.0 KB）")
    }

    // ---------------- 库里 JSON 数组字段的解析 ----------------

    @Test
    fun `parseStringArray handles null blank empty and malformed values`() {
        assertThat(parseStringArray(null)).isEmpty()
        assertThat(parseStringArray("")).isEmpty()
        assertThat(parseStringArray("[]")).isEmpty()
        // 解析失败不抛异常：库里可能有旧版本写入的脏数据
        assertThat(parseStringArray("not json")).isEmpty()
        assertThat(parseStringArray("""["作者A","作者B"]""")).containsExactly("作者A", "作者B").inOrder()
    }

    // ---------------- 详情页作者行：逐作者可点 + 整体可复制 ----------------

    @Test
    fun `author line stays one whole string with one link per author`() {
        val clicked = mutableListOf<String>()
        val text = authorLinkText(
            authors = listOf("作者A", "作者B"),
            authorColor = Color.Red,
            separatorColor = Color.Gray,
            onAuthorClick = { clicked += it },
        )

        // 复制拿到的必须是**整体**文案：分隔符也在里面，没有被链接切成几段
        assertThat(text.text).isEqualTo("作者A / 作者B")

        // 每位作者一个链接，链接范围正好盖住作者名（分隔符不是链接）
        val links = text.getLinkAnnotations(0, text.length)
        assertThat(links).hasSize(2)
        assertThat(text.text.substring(links[0].start, links[0].end)).isEqualTo("作者A")
        assertThat(text.text.substring(links[1].start, links[1].end)).isEqualTo("作者B")

        // 点谁就带走谁（上层拿这个名字去在线搜索）
        links[1].item.linkInteractionListener?.onClick(links[1].item)
        assertThat(clicked).containsExactly("作者B")
    }

    @Test
    fun `author line with zero or one author is still the whole text`() {
        val single = authorLinkText(
            authors = listOf("有贺冬"),
            authorColor = Color.Red,
            separatorColor = Color.Gray,
            onAuthorClick = {},
        )
        // 只有一个作者时不带分隔符，整行仍是一段可复制的文本
        assertThat(single.text).isEqualTo("有贺冬")
        assertThat(single.getLinkAnnotations(0, single.text.length)).hasSize(1)

        // 没有作者（上层据此不渲染这一行）：空文本、零个链接，不抛
        val none = authorLinkText(
            authors = emptyList(),
            authorColor = Color.Red,
            separatorColor = Color.Gray,
            onAuthorClick = {},
        )
        assertThat(none.text).isEmpty()
        assertThat(none.getLinkAnnotations(0, none.text.length)).isEmpty()
    }

    // ---------------- 异常 → 用户可读文案 ----------------

    @Test
    fun `network errors are translated into actionable messages`() {
        assertThat(
            com.haruhi.jmcomic.core.network.NetworkError.Timeout("u", java.io.IOException()).toUserMessage(),
        ).isEqualTo("网络超时，请检查网络后重试")
        assertThat(
            com.haruhi.jmcomic.core.network.NetworkError.Unreachable("u", java.io.IOException()).toUserMessage(),
        ).isEqualTo("连不上服务器，请检查网络或代理设置")
        assertThat(
            com.haruhi.jmcomic.core.network.NetworkError.HttpStatus("u", 503).toUserMessage(),
        ).contains("503")
        assertThat(
            com.haruhi.jmcomic.core.network.NetworkError.Business("接口返回的 data 字段为空").toUserMessage(),
        ).isEqualTo("接口返回的 data 字段为空")
    }

    // ------------------------------------------------------------------

    private fun card(total: Int, downloaded: Int) = AlbumCard(
        album = AlbumEntity(id = 1, name = "本子"),
        totalChapters = total,
        downloadedChapters = downloaded,
        localSizeBytes = 0,
    )

    private fun chapter(total: Int, done: Int) = ChapterRow(
        chapterId = 1,
        title = "第1话",
        sortIndex = 1,
        totalImages = total,
        downloadedImages = done,
    )
}
