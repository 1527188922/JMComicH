package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import com.haruhi.jmcomic.feature.data.CommentSegment
import com.haruhi.jmcomic.feature.data.splitCommentSegments

/**
 * 评论正文（详情页「评论」tab 的主楼 / 内嵌回复、「我的 → 留言记录」共用）。
 *
 * 两件事：
 * 1. **可选中复制**：评论里常有"求车牌"这类文字，用户会想复制；
 * 2. 其中的 `[ 我推荐这本书 1246359 ]` 是**一条链接**，点了打开那本漫画的详情页
 *    （用户 2026-10-22 要求，行为与官方 App 的 `ForumList.renderContent` 一致）。
 *
 * 链接用的是 [LinkAnnotation.Clickable] + [withLink]，与详情页作者行（`authorLinkText`）
 * 完全同一套机制，所以取舍也一样：**长按链接那一段是"点击"，不会起选**；
 * 长按正文其余位置照样能选、能复制（细节见 `authorLinkText` 的注释）。
 *
 * @param text 已清洗成纯文本的评论正文（见 `cleanCommentHtml`）
 * @param onOpenAlbum 点链接：参数是要打开的本子号。**等于当前这本时由上层决定不跳**
 *   （官方也是 `queryId === splitId` 就不动）
 * @param style 正文样式（主楼是 `bodyMedium`，内嵌回复是 `bodySmall`）
 * @param lineHeight 行高；不传就跟随 [style]
 */
@Composable
internal fun CommentBody(
    text: String,
    onOpenAlbum: (Long) -> Unit,
    style: TextStyle,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    // 切分只跟文本有关：同一段文字重复重组时不必重算
    val segments = remember(text) { splitCommentSegments(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    // 记住的 AnnotatedString 里闭包捕获的是这个 State，所以回调换了也点得对
    val latestOnOpenAlbum by rememberUpdatedState(onOpenAlbum)
    val annotated = remember(segments, linkColor) {
        commentBodyText(segments, linkColor) { albumId -> latestOnOpenAlbum(albumId) }
    }
    SelectionContainer {
        Text(text = annotated, style = style, lineHeight = lineHeight)
    }
}

/**
 * [CommentBody] 的纯逻辑部分：片段 → 富文本。
 *
 * 单独拆出来是因为它**不碰 Compose 运行时**（传入颜色就够了），
 * 出问题时可以直接拿一串文字断言"哪一段是链接、id 是多少"。
 *
 * @param onOpenAlbum 点链接时的回调（本子号）
 */
internal fun commentBodyText(
    segments: List<CommentSegment>,
    linkColor: Color,
    onOpenAlbum: (Long) -> Unit,
): AnnotatedString = buildAnnotatedString {
    // 一条评论里可能有多个推荐，链接样式只造一份
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor))
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is CommentSegment.Text -> append(segment.text)
            is CommentSegment.AlbumLink -> withLink(
                LinkAnnotation.Clickable(
                    // tag 只是链接的标识（同一个本子出现两次也不冲突），带上序号更直观
                    tag = "comment-album-$index-${segment.albumId}",
                    styles = linkStyles,
                    linkInteractionListener = { onOpenAlbum(segment.albumId) },
                ),
            ) {
                append(segment.text)
            }
        }
    }
}
