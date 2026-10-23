package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haruhi.jmcomic.feature.data.CommentRow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 列表离底部还剩几条时就追加下一页（与详情页评论同一套手感）。 */
private const val MY_COMMENT_LOAD_AHEAD = 3

/**
 * 「我的 → 留言记录」（`docs/account-design.md` §6）。
 *
 * 内容来源是 `GET /forum?uid={自己}`（10 条/页）：每一行是**我发过的一条留言**，
 * 行内直接给两件事——「来自 JMxxxx ›」（点了跳那本漫画的评论 tab）与「删除」。
 *
 * 为什么不做成详情页评论区那样带头像/等级：
 * 这一屏所有留言都是"我"发的，头像与昵称每行重复 N 遍没有任何信息量；
 * 把位置让给"来自哪本 + 什么时候发的"，才是这个页面要回答的问题。
 *
 * 删除是不可撤销的（服务端删了就没了），所以走 [ConfirmDialog] 二次确认，
 * 确认后由 [AccountCommentsViewModel.delete] 就地摘掉这一行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountCommentsScreen(
    state: AccountCommentsUiState,
    onBack: () -> Unit,
    /** 点「来自 JMxxxx」或整行：打开那本漫画（并自动切到评论 tab）。 */
    onOpenAlbum: (Long) -> Unit,
    /** 下拉刷新（没有跨页面缓存，进页面拉一次、下拉再拉一次）。 */
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** 确认删除某一条（上层调接口，成功后这一行会消失）。 */
    onDelete: (CommentRow) -> Unit,
    /** 未登录 / 已失效时的「去登录」。 */
    onLogin: () -> Unit,
    onMessage: (String) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待删除的那一条（null = 没有待确认的删除）
    var pendingDelete by remember { mutableStateOf<CommentRow?>(null) }
    val listState = rememberLazyListState()
    val latestState by rememberUpdatedState(state)

    MessageEffect(state.message, onConsume = onConsumeMessage, onShow = onMessage)

    // 列表滑到底自动追加下一页。
    //
    // 两个坑与详情页评论相同（见 `AlbumDetailScreen`）：不能用 `derivedStateOf` 读布局快照
    // （会读到过期值，表现为"必须先滑回顶部再滑下去才加载"）；`LaunchedEffect` 的 key
    // 也不能带"当前条数"，否则每追加一次就重启监听，滚动检测永远停在某一批。
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                val s = latestState
                if (s.loading || s.loadingMore || s.refreshing || !s.hasMore) return@collect
                if (totalItems > 0 && lastVisibleIndex >= totalItems - MY_COMMENT_LOAD_AHEAD) onLoadMore()
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 12.dp)
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "留言记录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.total > 0) {
                Text(
                    text = "共 ${state.total} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    if (!state.loading && !state.refreshing) onRefresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    // 首屏失败：整块错误 + 重试（有内容时失败只弹提示，不清列表）
                    state.error != null && state.comments.isEmpty() -> CommentsMessageBox {
                        Text(
                            text = "留言记录加载失败",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onRetry) { Text("重试") }
                    }

                    // 未登录（会话失效）：引导重新登录
                    !state.signedIn -> CommentsMessageBox {
                        Text("还没有登录", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "登录后才能查看自己发过的留言",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onLogin) { Text("去登录") }
                    }

                    state.loading && state.comments.isEmpty() -> CommentsMessageBox {
                        CircularProgressIndicator()
                    }

                    state.isEmpty -> CommentsMessageBox {
                        Text("还没有留言记录", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "在漫画详情页的评论区可以发留言",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.comments, key = { "my_comment_${it.cid}" }) { comment ->
                            MyCommentCard(
                                comment = comment,
                                deleting = state.deletingCid == comment.cid,
                                onOpenAlbum = onOpenAlbum,
                                onDelete = { pendingDelete = comment },
                            )
                        }
                        item(key = "my_comments_footer") {
                            MyCommentsFooter(
                                loadingMore = state.loadingMore,
                                reachedEnd = state.reachedEnd,
                                onLoadMore = onLoadMore,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除这条留言？",
            text = "删除后无法恢复，这条留言会从「${target.sourceLabel ?: "这本漫画"}」的评论区里消失。",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                onDelete(target)
            },
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 整页提示态（加载 / 空 / 失败 / 未登录）的外壳。
 *
 * 为什么必须自己铺 `fillMaxWidth()`：`ScrollableFullScreenBox` 里那层 Box 只约束了高度，
 * 宽度会收缩到内容宽度并靠左摆——直接放一个 `CircularProgressIndicator` 会贴在左边，
 * 看起来"没居中"（用户 2026-10-22 报的就是这个）。
 * `EmptyBox` 没这个问题，因为它自己带了 `fillMaxSize()`。
 */
@Composable
private fun CommentsMessageBox(content: @Composable ColumnScope.() -> Unit) {
    ScrollableFullScreenBox {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * 一条「我的留言」。
 *
 * 布局：时间 / 「回复」标记 + 删除（右上）→ 正文 → 来自 JMxxxx ›（左下）。
 * 整张卡可点（跳那本漫画），「删除」是卡内独立的一段点击区，不会误触发跳转。
 */
@Composable
private fun MyCommentCard(
    comment: CommentRow,
    deleting: Boolean,
    onOpenAlbum: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val albumId = comment.albumId
    val shape = RoundedCornerShape(12.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // 先按卡片圆角裁一次，再挂整卡点击：否则 ripple 是**正方形**的，
            // 会在 12dp 圆角的卡片上露出四个直角（用户 2026-10-22 反馈）。
            .clip(shape)
            .then(
                if (albumId != null) {
                    Modifier.clickable(onClickLabel = "打开这本漫画") { onOpenAlbum(albumId) }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 回复：主楼是「留言」，回复是挂在别人楼下的，来源行之外还得标出来
                if (comment.isReply) {
                    Text(
                        text = "回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // 时间占满中间剩下的宽度，「删除」才会被顶到**卡片右上角**。
                // 之前这里给时间 text 与 spacer 各 `weight(1f)`，两份权重把剩余宽度对半劈，
                // 删除就停在了大约 2/3 宽的位置（用户 2026-10-22 反馈）。
                if (comment.timeLabel != null) {
                    Text(
                        text = comment.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (deleting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClickLabel = "删除留言", onClick = onDelete)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (comment.text.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // 与详情页评论一致：正文可长按选中复制；其中的「我推荐这本书 <jmid>」可点
                CommentBody(
                    text = comment.text,
                    onOpenAlbum = onOpenAlbum,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "来自 ${comment.sourceLabel ?: "未知漫画"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (albumId != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 列表底部：正在追加 / 到底了 / 兜底的「加载更多」。 */
@Composable
private fun MyCommentsFooter(
    loadingMore: Boolean,
    reachedEnd: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在加载…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            reachedEnd -> Text(
                text = "没有更多留言了",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text("加载更多")
            }
        }
    }
}
