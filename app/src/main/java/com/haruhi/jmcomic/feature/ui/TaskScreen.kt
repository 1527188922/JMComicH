package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.core.database.entity.DownloadStatus

/**
 * 漫画任务页（计划 §9 第 9 条）。
 *
 * 三件事必须让用户一眼看清：
 * 1. **状态**：排队 / 下载中 / 已暂停 / 等待网络 / 未完成 / 已完成，各自独立文案，
 *    失败**不算**"正在运行"；
 * 2. **进度**：章节数与图片数一起给（"2/12 话 · 18/240 张"），这是 P3 的验收项；
 * 3. **能做什么**：暂停/继续、取消、重试、清空完成历史；「删除记录」是卡片右上角的
 *    叉叉（任务停下之后才出现，跑着的时候先「取消」）。
 *
 * 任务跑在应用级作用域上，所以离开这个页面不会影响下载——
 * 文案里明确写出这一点，避免用户以为"退出就停了"。
 */
@Composable
fun TaskScreen(
    state: TaskUiState,
    /** 返回上一级（本页是「更多」下面的二级页，底栏在这一层隐藏，所以需要可见的返回按钮）。 */
    onBack: () -> Unit,
    onToggleExpanded: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClearFinished: () -> Unit,
    /** 点封面进这本漫画的详情页（只有漫画任务有封面）。 */
    onOpenAlbum: (Long) -> Unit,
    /** 导出成品的「打开至」：用其他应用打开。 */
    onOpenOutput: (String) -> Unit,
    /** 导出成品的「发送至」：分享给别的 App。 */
    onSendOutput: (String) -> Unit,
    /** 导出成品的「删除文件」。 */
    onDeleteOutput: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 删成品是不可撤销的（20MB 的 PDF 重新生成要花时间），所以先确认一次
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 左边留 8dp：IconButton 自带 48dp 触控区，再叠 16dp 会让标题明显右移
                .padding(start = 8.dp, end = 8.dp, top = 12.dp)
                // 内容区不低于按钮最小高度：按钮不存在时整行不塌缩，标题留白与底边位置保持不变
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "下载任务",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.finished.isNotEmpty()) {
                TextButton(onClick = onClearFinished) { Text("清空完成记录") }
            }
        }

        if (state.failedCount > 0) {
            Text(
                text = "有 ${state.failedCount} 个任务没下完，展开后可以重试补下",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 正文区铺灰色底；头部「下载任务 / 清空完成记录」那一行留白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                state.loading -> LoadingBox()
                state.isEmpty -> EmptyBox(
                    title = "还没有下载任务",
                    hint = "在漫画详情页选好章节后点「下载」",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.active.isNotEmpty()) {
                        item { SectionHeader("进行中（${state.active.size}）") }
                        items(state.active, key = { "active-${it.task.id}" }) { row ->
                            TaskCard(
                                row = row,
                                onToggleExpanded = onToggleExpanded,
                                onPause = onPause,
                                onResume = onResume,
                                onCancel = onCancel,
                                onRetry = onRetry,
                                onRemove = onRemove,
                                onOpenAlbum = onOpenAlbum,
                                onOpenOutput = onOpenOutput,
                                onSendOutput = onSendOutput,
                                onDeleteOutput = onDeleteOutput,
                            )
                        }
                    }
                    if (state.finished.isNotEmpty()) {
                        item { SectionHeader("已完成（${state.finished.size}）") }
                        items(state.finished, key = { "done-${it.task.id}" }) { row ->
                            TaskCard(
                                row = row,
                                onToggleExpanded = onToggleExpanded,
                                onPause = onPause,
                                onResume = onResume,
                                onCancel = onCancel,
                                onRetry = onRetry,
                                onRemove = onRemove,
                                onOpenAlbum = onOpenAlbum,
                                onOpenOutput = onOpenOutput,
                                onSendOutput = onSendOutput,
                                onDeleteOutput = { id, name -> pendingDelete = id to name },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { (taskId, name) ->
        ConfirmDialog(
            title = "删除这个导出文件？",
            text = "会从系统下载目录里删掉「$name」，已经发送或打开过的副本不受影响。",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                onDeleteOutput(taskId, name)
            },
            confirmButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun TaskCard(
    row: TaskRow,
    onToggleExpanded: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenOutput: (String) -> Unit,
    onSendOutput: (String) -> Unit,
    onDeleteOutput: (Long, String) -> Unit,
) {
    val task = row.task
    // 文字隐私：漫画任务显示 JM 号；没有 albumId 的任务（非 JM 类型）保留原名
    val displayName = row.albumId?.let { privacyAlbumTitle(it, row.displayName) } ?: row.displayName
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 封面（用户要求第 1 点）：与书库同一套加载方式——应用级 Coil 加载器 +
                // files/covers 磁盘缓存，所以"看过的封面优先读本地"。点它进详情页。
                row.albumId?.let { albumId ->
                    // 任务页**不传 tag**：用户明确要求下载任务里不显示封面主 tag。
                    Cover(
                        albumId = albumId,
                        contentDescription = displayName,
                        modifier = Modifier
                            .size(width = 48.dp, height = 66.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenAlbum(albumId) },
                    )
                    Spacer(Modifier.width(10.dp))
                }
                // 有章节可看（或已知总话数）时整块名称+状态区都可点：点一下展开/收起章节。
                // 这是原来右侧箭头按钮的位置与语义，按钮已经去掉，所以判断条件保持不变。
                val expandable = row.chapters.isNotEmpty() || task.totalChapters > 0
                Column(modifier = Modifier.weight(1f)) {
                    // 任务类型标签（用户要求第 4 点）：导出任务一眼看出是 zip 还是 pdf
                    row.typeLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 漫画名称 + 状态/进度两行合成一个点击区域——不再需要 KeyboardArrowUp/Down 按钮
                    Column(
                        modifier = if (expandable) {
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(
                                    onClickLabel = if (row.expanded) "收起章节" else "展开章节",
                                ) { onToggleExpanded(task.id) }
                        } else {
                            Modifier
                        },
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${row.statusText} · ${row.progressText}",
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor(task.status),
                        )
                    }
                }
                // 删除记录：从卡片底部的文字按钮挪到右上角的叉叉（用户要求），
                // 一眼看清"这条记录可以拿掉"。语义没变——**只删记录，不动已下载的文件**。
                //
                // 任务在跑的时候不显示叉叉：那时候底部有「取消」，否则一个图标既要
                // "停下"又要"删掉"，用户分不清点下去会发生什么。
                if (!row.canCancel) {
                    IconButton(
                        onClick = { onRemove(task.id) },
                        // 贴齐封面顶边（那一行是垂直居中的），落位在卡片右上角
                        modifier = Modifier
                            .align(Alignment.Top)
                            .size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "删除记录",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (task.status == DownloadStatus.RUNNING && task.totalImages > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        (task.doneImages.toFloat() / task.totalImages).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            row.detailText?.let { detail ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            row.outputName?.let { name ->
                Spacer(Modifier.height(4.dp))
                Row( modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
                ) {
                    TextButton(onClick = { onOpenOutput(name) }) { Text("打开至") }
                    TextButton(onClick = { onSendOutput(name) }) { Text("发送至") }
                    TextButton(onClick = { onDeleteOutput(task.id, name) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除文件",
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Text("删除文件")
                    }
                }
            }

            // 底部的动作行只剩"任务还在跑"相关的操作；删除记录已经移到右上角的叉叉，
            // 所以一条动作都不剩时整行不占高度（否则卡片底部会多出一截空白）。
            if (row.canPause || row.canResume || row.canRetry || row.canCancel) {
                Spacer(Modifier.height(4.dp))
                Row( modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
                ) {
                    if (row.canPause) {
                        TextButton(onClick = { onPause(task.id) }) { Text("暂停") }
                    }
                    if (row.canResume) {
                        TextButton(onClick = { onResume(task.id) }) { Text("继续") }
                    }
                    if (row.canRetry) {
                        TextButton(onClick = { onRetry(task.id) }) { Text("重试") }
                    }
                    if (row.canCancel) {
                        TextButton(onClick = { onCancel(task.id) }) { Text("取消") }
                    }
                }
            }


            if (row.expanded && row.chapters.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                row.chapters.forEach { chapter ->
                    ChapterProgressRow(chapter)
                }
            }
        }
    }
}

@Composable
private fun ChapterProgressRow(chapter: com.haruhi.jmcomic.core.database.entity.DownloadTaskChapterEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.chapterTitle ?: chapter.chapterId.toString(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (chapter.status) {
                DownloadStatus.SUCCESS -> "已完成"
                DownloadStatus.FAIL -> chapter.message ?: "未完成"
                DownloadStatus.RUNNING -> "${chapter.doneImages}/${chapter.totalImages} 张"
                DownloadStatus.PAUSED -> "已暂停"
                DownloadStatus.WAITING_NETWORK -> "等待网络"
                DownloadStatus.CANCELLED -> "已取消"
                DownloadStatus.QUEUED -> "排队中"
            },
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(chapter.status),
        )
    }
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAIL -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
