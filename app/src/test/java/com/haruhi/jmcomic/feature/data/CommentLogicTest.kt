package com.haruhi.jmcomic.feature.data

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.feature.api.ApiJson
import com.haruhi.jmcomic.feature.api.CommentEditResultDto
import com.haruhi.jmcomic.feature.api.ForumItemDto
import com.haruhi.jmcomic.feature.api.ForumRespDto
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

    // ---------------- 发留言 / 回复的本地逻辑（2026-10-22） ----------------

    @Test
    fun `draft is truncated to 200 code points and never splits an emoji`() {
        val plain = "留".repeat(200)
        assertThat(limitCommentDraft(plain)).isEqualTo(plain)
        assertThat(limitCommentDraft("留".repeat(201))).hasLength(200)

        // 表情是代理对（2 个 char）：按 char 截会留下半个字符，发出去就是乱码
        val emoji = "\uD83D\uDE00"
        val full = emoji.repeat(COMMENT_MAX_LENGTH)
        assertThat(limitCommentDraft(full)).isEqualTo(full)
        val over = emoji.repeat(COMMENT_MAX_LENGTH) + "留"
        val cut = limitCommentDraft(over)
        assertThat(cut).isEqualTo(full)
        assertThat(cut.length % 2).isEqualTo(0)
    }

    @Test
    fun `draft error only rejects blank and oversized input`() {
        assertThat(commentDraftError("")).isEqualTo("请输入留言内容")
        assertThat(commentDraftError("   \n ")).isEqualTo("请输入留言内容")
        assertThat(commentDraftError("你好")).isNull()
        // 英文与数字服务端会拒，但那是服务端策略（"暂时"），本地不拦——原样让它发出去
        assertThat(commentDraftError("abc 123")).isNull()
        assertThat(commentDraftError("留".repeat(COMMENT_MAX_LENGTH))).isNull()
        assertThat(commentDraftError("留".repeat(COMMENT_MAX_LENGTH + 1)))
            .isEqualTo("留言最多 $COMMENT_MAX_LENGTH 字")
    }

    @Test
    fun `success message loses the html line breaks`() {
        // 实测成功文案：`評論成功發布！…<br>您已完成每日發表評論，獲得「3」金幣`
        assertThat(commentMessage("評論成功發布！<br>您已完成每日發表評論，獲得「3」金幣"))
            .isEqualTo("評論成功發布！ 您已完成每日發表評論，獲得「3」金幣")
        assertThat(commentMessage("")).isNull()
        assertThat(commentMessage(null)).isNull()
    }

    @Test
    fun `own comment is detected by uid only`() {
        val mine = comment("1").copy(uid = "6404735")
        assertThat(mine.isAuthoredBy("6404735")).isTrue()
        assertThat(mine.isAuthoredBy(" 6404735 ")).isTrue()
        assertThat(mine.isAuthoredBy("12964198")).isFalse()
        // 任何一边缺失都判断不了，一律"不是我的"（否则界面上会冒出删不掉的删除按钮）
        assertThat(mine.isAuthoredBy(null)).isFalse()
        assertThat(comment("2").isAuthoredBy("6404735")).isFalse()
    }

    @Test
    fun `source label falls back from name to album id`() {
        assertThat(comment("1").copy(albumId = 1484920, albumName = "JM1484920").sourceLabel)
            .isEqualTo("JM1484920")
        assertThat(comment("1").copy(albumId = 1484920).sourceLabel).isEqualTo("JM1484920")
        assertThat(comment("1").sourceLabel).isNull()
    }

    @Test
    fun `reply rows are flagged and main floors are not`() {
        assertThat(comment("1").isReply).isFalse()
        assertThat(comment("1").copy(parentCid = "9").isReply).isTrue()
    }

    // ---------------- 正文里的「我推荐这本书 <jmid>」（2026-10-22） ----------------

    @Test
    fun `recommend book marker becomes a link and the rest stays text`() {
        val text = "原来天堂不只有篇😭[ 我推荐这本书 1246359 ]"
        val segments = splitCommentSegments(text)
        assertThat(segments).hasSize(2)
        assertThat(segments[0]).isEqualTo(CommentSegment.Text("原来天堂不只有篇😭"))
        assertThat(segments[1]).isEqualTo(
            CommentSegment.AlbumLink(text = "[ 我推荐这本书 1246359 ]", albumId = 1246359L),
        )
    }

    @Test
    fun `recommend book marker accepts traditional form full width brackets and jm prefix`() {
        // 服务端会把正文统一成繁体；官方渲染两种写法都认
        assertThat(splitCommentSegments("[ 我推薦這本書 1246359 ]"))
            .containsExactly(CommentSegment.AlbumLink("[ 我推薦這本書 1246359 ]", 1246359L))
        // 全角括号 / 少一个空格 / 带 JM 前缀
        assertThat(splitCommentSegments("好本［我推荐这本书JM1246359］"))
            .containsExactly(
                CommentSegment.Text("好本"),
                CommentSegment.AlbumLink("［我推荐这本书JM1246359］", 1246359L),
            )
    }

    @Test
    fun `text without markers or with a bad id is left alone`() {
        val plain = "求车牌，评论区没人发过"
        assertThat(splitCommentSegments(plain)).containsExactly(CommentSegment.Text(plain))
        // 认不出数字 id：保持原文，不画一条点了没反应的链接
        val bogus = "[ 我推荐这本书 一二三 ]"
        assertThat(splitCommentSegments(bogus)).containsExactly(CommentSegment.Text(bogus))
        assertThat(splitCommentSegments("")).isEmpty()
    }

    @Test
    fun `several markers in one comment are all recognized`() {
        val text = "[ 我推荐这本书 111 ] 和 [ 我推薦這本書 JM222 ]"
        assertThat(splitCommentSegments(text)).containsExactly(
            CommentSegment.AlbumLink("[ 我推荐这本书 111 ]", 111L),
            CommentSegment.Text(" 和 "),
            CommentSegment.AlbumLink("[ 我推薦這本書 JM222 ]", 222L),
        ).inOrder()
    }

    // ---------------- DTO 解析（真实响应形态） ----------------

    @Test
    fun `forum item maps album id parent uid and html content`() {
        // 实测「我的留言」响应（2026-10-22，账号 6404735）
        val json = """
            {
              "AID": "1484920",
              "BID": null,
              "CID": "11190972",
              "NID": null,
              "NCID": null,
              "UID": "6404735",
              "username": "1527188922",
              "nickname": "1527188922",
              "likes": "0",
              "addtime": "Oct 22, 2026",
              "parent_CID": "0",
              "name": "JM1484920",
              "content": "<div style='flex-direction:row;flex-wrap:wrap;'>還有涼宮春日的本子</div>",
              "photo": "nopic-Male.gif",
              "spoiler": "1"
            }
        """.trimIndent()
        val row = ApiJson.decodeFromString(ForumItemDto.serializer(), json).toCommentRow()
        assertThat(row.cid).isEqualTo("11190972")
        assertThat(row.uid).isEqualTo("6404735")
        assertThat(row.albumId).isEqualTo(1484920L)
        assertThat(row.albumName).isEqualTo("JM1484920")
        assertThat(row.sourceLabel).isEqualTo("JM1484920")
        assertThat(row.parentCid).isNull()
        assertThat(row.isReply).isFalse()
        assertThat(row.text).isEqualTo("還有涼宮春日的本子")
        assertThat(row.author).isEqualTo("1527188922")
        assertThat(row.likes).isNull()
        assertThat(row.timeLabel).isEqualTo("2026-10-22")
    }

    @Test
    fun `forum total tolerates both string and number`() {
        // 同一接口实测过两种形态（`"851"` 与 `5000`），两种都不能把整页解析搞崩
        val asString = ApiJson.decodeFromString(ForumRespDto.serializer(), """{"total":"851"}""")
        assertThat(asString.total).isEqualTo("851")
        val asNumber = ApiJson.decodeFromString(ForumRespDto.serializer(), """{"total":5000}""")
        assertThat(asNumber.total).isEqualTo("5000")
    }

    @Test
    fun `comment edit result reads ok and fail shapes`() {
        // 发成功：cid 是数字，msg 带 <br>
        val ok = ApiJson.decodeFromString(
            CommentEditResultDto.serializer(),
            """{"msg":"評論成功發布！<br>獲得「5」經驗值","status":"ok","aid":1484920,"cid":11191109,"spoiler":"1"}""",
        )
        assertThat(ok.isOk).isTrue()
        assertThat(ok.cid).isEqualTo("11191109")
        assertThat(commentMessage(ok.msg)).isEqualTo("評論成功發布！ 獲得「5」經驗值")

        // 删除别人的：HTTP 200 但 status=fail
        val fail = ApiJson.decodeFromString(
            CommentEditResultDto.serializer(),
            """{"msg":"無法刪除評論！ ？","status":"fail","cid":11187248,"aid":114514,"bid":0,"nid":0,"gid":0}""",
        )
        assertThat(fail.isOk).isFalse()
        assertThat(commentMessage(fail.msg)).isEqualTo("無法刪除評論！ ？")
    }

    @Test
    fun `detail tab codes round trip`() {
        assertThat(AlbumDetailTab.fromCode("comments")).isEqualTo(AlbumDetailTab.COMMENTS)
        assertThat(AlbumDetailTab.fromCode("chapters")).isEqualTo(AlbumDetailTab.CHAPTERS)
        // 陌生值（旧路由的空串 / 手写深链）一律回默认，不抛异常
        assertThat(AlbumDetailTab.fromCode("")).isEqualTo(AlbumDetailTab.CHAPTERS)
        assertThat(AlbumDetailTab.fromCode(null)).isEqualTo(AlbumDetailTab.CHAPTERS)
    }

    @Test
    fun `remove drops a main floor together with its replies`() {
        val root = comment("1").copy(replies = listOf(comment("2"), comment("3")))
        val other = comment("9")
        val left = removeComment(listOf(root, other), "1")
        assertThat(left.map { it.cid }).containsExactly("9")
    }

    @Test
    fun `remove only detaches a reply from its parent`() {
        val root = comment("1").copy(replies = listOf(comment("2"), comment("3")))
        val left = removeComment(listOf(root), "2")
        assertThat(left).hasSize(1)
        assertThat(left[0].cid).isEqualTo("1")
        assertThat(left[0].replies.map { it.cid }).containsExactly("3")
    }

    @Test
    fun `remove tolerates unknown cid and blank input`() {
        val list = listOf(comment("1"))
        assertThat(removeComment(list, "404")).isEqualTo(list)
        assertThat(removeComment(list, "")).isEqualTo(list)
        assertThat(removeComment(emptyList(), "1")).isEmpty()
    }

    // ------------------------------------------------------------------

    private fun comment(cid: String) = CommentRow(cid = cid, author = "u$cid")
}
