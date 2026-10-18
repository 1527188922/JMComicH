package com.haruhi.jmcomic.feature.data

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.feature.ui.AlbumDetailTab
import com.haruhi.jmcomic.feature.ui.AlbumDetailUiState
import org.junit.Test

/**
 * 详情页「评论」tab 的**纯逻辑**单元测试（不发网络、不碰界面）。
 *
 * 覆盖四类容易出错的地方：
 * 1. 接口正文是 HTML → 展示前必须清洗（`cleanCommentHtml`）；
 * 2. `addtime` 是英文月日年 → 归一成中文界面看着顺眼的日期（`commentTimeLabel`）；
 * 3. 翻页会重复 → 合并时按 `cid` 去重（重复 key 会让 LazyColumn 直接崩）；
 * 4. 缓存按 jm 号隔离、能被整体清空（用户要求"退出 App 清空"）。
 *
 * 真实响应样本取自 `aid=114514`（见 `docs/official-api-2.1.11.md` §6 与实测）。
 */
class CommentLogicTest {

    // ---------------- HTML 清洗 ----------------

    @Test
    fun `comment html wrapper is stripped and text stays`() {
        val raw = "<div style='flex-direction:row;flex-wrap:wrap;'>喂，你看得很開心嘛</div>"
        assertThat(cleanCommentHtml(raw)).isEqualTo("喂，你看得很開心嘛")
    }

    @Test
    fun `inline emoji image keeps its alt text`() {
        // 实测正文里的 <img> 只有表情这一种，整条丢掉会让句子突然断掉
        val raw = "<div style='flex-direction:row;flex-wrap:wrap;'>共用一个评论区" +
            "<img style=\"width:18px;height:18px\" src=\"https://x/media/emoji/a.png\" alt=\"笑哭\" /></div>"
        assertThat(cleanCommentHtml(raw)).isEqualTo("共用一个评论区笑哭")
    }

    @Test
    fun `img without alt is dropped entirely`() {
        assertThat(cleanCommentHtml("<div>看图<img src=\"a.png\" /></div>")).isEqualTo("看图")
    }

    @Test
    fun `br and closing block tags become real line breaks`() {
        assertThat(cleanCommentHtml("第一行<br>第二行")).isEqualTo("第一行\n第二行")
        assertThat(cleanCommentHtml("第一行<br/>第二行")).isEqualTo("第一行\n第二行")
        assertThat(cleanCommentHtml("<div>第一行</div><div>第二行</div>")).isEqualTo("第一行\n第二行")
    }

    @Test
    fun `unknown tags are stripped and entities are decoded`() {
        val raw = "<span>a &amp; b</span>&nbsp;&lt;tag&gt;&#39;q&#x1F600;"
        assertThat(cleanCommentHtml(raw)).isEqualTo("a & b <tag>'q\uD83D\uDE00")
    }

    @Test
    fun `blank and tag-only content becomes an empty string`() {
        assertThat(cleanCommentHtml(null)).isEmpty()
        assertThat(cleanCommentHtml("   ")).isEmpty()
        assertThat(cleanCommentHtml("<div></div>")).isEmpty()
        // 未知实体原样保留，不要悄悄吃掉
        assertThat(cleanCommentHtml("&unknown;")).isEqualTo("&unknown;")
    }

    @Test
    fun `repeated line breaks collapse into at most one blank line`() {
        // `<br><br>` 在网页端就是"分段"，所以留**一个**空行；再多也只留一个
        val raw = "第一行<br><br><br>第二行   \n\n\n  第三行"
        assertThat(cleanCommentHtml(raw)).isEqualTo("第一行\n\n第二行\n\n第三行")
        // 单换行仍然只是一次换行
        assertThat(cleanCommentHtml("第一行<br>第二行")).isEqualTo("第一行\n第二行")
    }

    // ---------------- 时间文案 ----------------

    @Test
    fun `english addtime is normalized to a plain date`() {
        assertThat(commentTimeLabel("Oct 11, 2026")).isEqualTo("2026-10-11")
        assertThat(commentTimeLabel("Sep 08, 2026")).isEqualTo("2026-09-08")
        assertThat(commentTimeLabel("  Aug 31, 2026 ")).isEqualTo("2026-08-31")
    }

    @Test
    fun `unknown or placeholder addtime falls back gracefully`() {
        assertThat(commentTimeLabel(null)).isNull()
        assertThat(commentTimeLabel("")).isNull()
        assertThat(commentTimeLabel("0")).isNull()
        assertThat(commentTimeLabel("null")).isNull()
        // 认不出的格式原样给出，总比空白好
        assertThat(commentTimeLabel("2026-10-16 12:34")).isEqualTo("2026-10-16 12:34")
    }

    // ---------------- 点赞数 ----------------

    @Test
    fun `zero likes are dropped instead of rendered`() {
        assertThat(commentLikeCount("0")).isNull()
        assertThat(commentLikeCount("  ")).isNull()
        assertThat(commentLikeCount(null)).isNull()
        assertThat(commentLikeCount("null")).isNull()
        assertThat(commentLikeCount("12")).isEqualTo("12")
    }

    // ---------------- 翻页合并去重 ----------------

    @Test
    fun `merge keeps order and drops duplicated cids`() {
        val first = listOf(comment("1"), comment("2"), comment("3"))
        val next = listOf(comment("3"), comment("4"), comment("5"))
        val merged = mergeComments(first, next)
        assertThat(merged.map { it.cid }).containsExactly("1", "2", "3", "4", "5").inOrder()
    }

    @Test
    fun `merge tolerates empty incoming page and empty existing list`() {
        val existing = listOf(comment("1"))
        assertThat(mergeComments(existing, emptyList())).isEqualTo(existing)
        assertThat(mergeComments(emptyList(), existing)).isEqualTo(existing)
        assertThat(mergeComments(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun `merge does not mutate the inputs`() {
        val first = listOf(comment("1"))
        val next = listOf(comment("2"))
        mergeComments(first, next)
        assertThat(first).hasSize(1)
        assertThat(next).hasSize(1)
    }

    // ---------------- 内存缓存（按 jm 号隔离 / 退出清空） ----------------

    @Test
    fun `cache keeps one snapshot per album id`() {
        val cache = CommentCache()
        val a = CommentCache.Snapshot(listOf(comment("1")), total = 1, nextPage = 2, reachedEnd = false)
        val b = CommentCache.Snapshot(listOf(comment("9")), total = 9, nextPage = 2, reachedEnd = true)

        cache.put(114514, a)
        cache.put(480854, b)

        assertThat(cache.get(114514)).isEqualTo(a)
        assertThat(cache.get(480854)).isEqualTo(b)
        assertThat(cache.get(350234)).isNull()
    }

    @Test
    fun `cache can drop a single album or everything`() {
        val cache = CommentCache()
        val snapshot = CommentCache.Snapshot(listOf(comment("1")), total = 1, nextPage = 1, reachedEnd = true)
        cache.put(114514, snapshot)
        cache.put(480854, snapshot)

        cache.clear(114514)
        assertThat(cache.get(114514)).isNull()
        assertThat(cache.get(480854)).isNotNull()

        // 退出 App：整份清空
        cache.clearAll()
        assertThat(cache.get(480854)).isNull()
    }

    // ---------------- 头像 / 首字 ----------------

    @Test
    fun `avatar url is built on the image line`() {
        assertThat(Defaults.buildAvatarUrl("nopic-Male.gif"))
            .isEqualTo("https://${Defaults.IMAGE_DOMAIN}/media/users/nopic-Male.gif")
        // 名字前多写了斜杠也不要拼出双斜杠
        assertThat(Defaults.buildAvatarUrl("/22094869.jpg"))
            .isEqualTo("https://${Defaults.IMAGE_DOMAIN}/media/users/22094869.jpg")
    }

    @Test
    fun `comment initial falls back to the first non blank character`() {
        assertThat(comment("1").copy(author = "lzsyzxcb").initial).isEqualTo("l")
        assertThat(comment("1").copy(author = " 狂夢小時").initial).isEqualTo("狂")
        assertThat(comment("1").copy(author = "   ").initial).isEqualTo("?")
    }

    // ---------------- tab 上的评论数 ----------------

    @Test
    fun `comment tab count prefers the loaded total`() {
        val state = AlbumDetailUiState(
            album = AlbumEntity(id = 114514, name = "本子", commentTotal = "851"),
            commentTotal = 900,
            commentsLoaded = true,
        )
        assertThat(state.commentTabCount).isEqualTo(900)
    }

    @Test
    fun `comment tab count falls back to album comment total before loading`() {
        val state = AlbumDetailUiState(
            album = AlbumEntity(id = 114514, name = "本子", commentTotal = "851"),
        )
        assertThat(state.commentTabCount).isEqualTo(851)
        // 两个都没有时是 0，界面据此不显示数字
        assertThat(AlbumDetailUiState().commentTabCount).isEqualTo(0)
    }

    @Test
    fun `detail tab defaults to chapters`() {
        // 进详情页的第一诉求还是"从这一话开始读"，默认停在章节
        assertThat(AlbumDetailUiState().tab).isEqualTo(AlbumDetailTab.CHAPTERS)
        assertThat(AlbumDetailTab.entries.map { it.label }).containsExactly("章节", "评论").inOrder()
    }

    // ------------------------------------------------------------------

    private fun comment(cid: String) = CommentRow(cid = cid, author = "u$cid")
}
