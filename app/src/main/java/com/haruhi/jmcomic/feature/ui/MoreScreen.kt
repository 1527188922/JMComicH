package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.BuildConfig
import com.haruhi.jmcomic.feature.data.Repository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

/**
 * 「更多」页的 ViewModel。
 *
 * 只做一件事：给"最近浏览"那一行报个条数。
 * 下载任务那行的数字不从这里来——任务状态挂在 Activity 级的 [TaskViewModel] 上
 * （底栏角标也要用同一个实例），页面直接收它的 state，两处看到的一定一致。
 */
class MoreViewModel(repo: Repository) : ViewModel() {

    /** 浏览记录条数。查不到（数据库异常）时按 0 处理，不让它把整页拖成错误态。 */
    val recentCount: StateFlow<Int> = repo.observeRecentAlbums()
        .map { it.size }
        .catch { e ->
            Timber.tag("jm").w(e, "最近浏览条数查询失败")
            emit(0)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
}

/**
 * 「更多」页（用户 2026-10-15 定的底栏第 4 项）。
 *
 * 页面上没有账号、没有头像——本 App 没有账号体系，计划 §9 第 3 条也明确
 * **不做账号型"我的"页面**，所以这里是一个纯工具收纳页：
 * 「最近浏览」「下载任务」「应用设置」三行入口，末尾一行静态版本号。
 *
 * 每行都带实时状态（浏览条数 / 任务进行情况），不进去也能看出里面有没有东西。
 * 版本号那行**不可点击**（用户要求），所以不画右侧箭头。
 */
@Composable
fun MoreScreen(
    recentCount: Int,
    taskState: TaskUiState,
    onOpenRecent: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp)
                // 与其它页头部对齐：内容区不低于按钮最小高度，整行高度不塌缩
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("更多", style = MaterialTheme.typography.titleMedium)
        }

        // 正文区铺灰色底；头部「更多」那一行留白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 三行入口装在**一张白卡**里（主题是「灰底白卡」，见 ui/theme/Theme.kt）：
                // 卡外的灰底只用来放分组标签与版本号，卡内才放可点的行。
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 顶部 12dp 是「更多」那一行与卡片之间的呼吸位，底部保持 4dp——
                        // 卡片下面还有版本号自己的 20dp 内边距，不用在这里再加。
                        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MoreEntryRow(
                            title = "最近浏览",
                            subtitle = if (recentCount > 0) "浏览过 $recentCount 本" else "还没有浏览记录",
                            badgeCount = 0,
                            onClick = onOpenRecent,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreEntryRow(
                            title = "下载任务",
                            subtitle = taskSummary(taskState),
                            // 角标数字沿用底栏那一套：只统计排队/执行中的任务，失败另用提示点
                            badgeCount = taskState.badgeCount,
                            hasFailure = taskState.failedCount > 0,
                            onClick = onOpenTasks,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreEntryRow(
                            title = "应用设置",
                            subtitle = "阅读与显示、下载、导出、网络、线路",
                            badgeCount = 0,
                            onClick = onOpenSettings,
                        )
                    }
                }
                // 版本号：静态文本，不可点击（用户要求），所以不进白卡、也不带箭头
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
        }
    }
}

/** 任务行的说明文案：把"进行中"和"失败"分开说，不把失败混进正在运行里（计划 §9 第 9 条）。 */
private fun taskSummary(state: TaskUiState): String {
    val running = state.active.size
    val failed = state.failedCount
    return when {
        running > 0 && failed > 0 -> "$running 个进行中 · $failed 个失败"
        running > 0 -> "$running 个进行中"
        failed > 0 -> "$failed 个失败，展开后可以重试"
        state.loading -> "读取中…"
        else -> "没有进行中的任务"
    }
}

/**
 * 「更多」页的一行入口：名称 + 一句说明 +（可选角标）+ 右侧箭头。
 *
 * @param badgeCount > 0 时右侧画数字角标；[hasFailure] 为 true 但 [badgeCount] 为 0 时
 *        画一个空角标当提示点——与底栏 [TaskBadgeIcon] 是同一套规则。
 */
@Composable
private fun MoreEntryRow(
    title: String,
    subtitle: String,
    badgeCount: Int,
    onClick: () -> Unit,
    hasFailure: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badgeCount > 0) {
            Badge { Text(badgeCount.toString()) }
        } else if (hasFailure) {
            Badge {}
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
