package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.BuildConfig
import com.haruhi.jmcomic.core.account.AccountState
import com.haruhi.jmcomic.core.protocol.Defaults

/**
 * 「我的」页（原「更多」页，`docs/account-design.md` §3.2）。
 *
 * 三块：
 * 1. **账户摘要卡** —— 头像 / 昵称 / 等级·称号 / 会员行（**非会员不显示**）；点卡片进个人资料；
 * 2. **账号** —— 收藏（官方在线收藏，替代原来的本机收藏 tab）；
 * 3. **本机** —— 最近浏览（仍是本机数据）、下载任务、应用设置、关于。
 *
 * 未登录时账号区照常显示，副标题写「登录后可用」，点击直接去登录页
 * （由导航层记住目标，登录成功直达）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(
    state: MyUiState,
    taskState: TaskUiState,
    onOpenFavorites: () -> Unit,
    /** 留言记录（我发过的留言）；未登录时由导航层先带去登录页。 */
    onOpenComments: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 关于页（版本号 + 检查更新 + 存储统计）。 */
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    /** 下拉刷新：重拉收藏夹名单，覆盖本地缓存并更新「· N 个收藏夹」。 */
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp)
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的", style = MaterialTheme.typography.titleMedium)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 下拉刷新：重新拉一次收藏夹名单（`/favorite` 的 `folder_list` 全量返回），
            // 覆盖本地缓存后「· N 个收藏夹」立刻跟着变。
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    if (!state.refreshing) onRefresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    AccountSummaryCard(
                        state = state,
                        onOpenProfile = onOpenProfile,
                        onLogin = onLogin,
                    )

                    GroupCard {
                        MyEntryRow(
                            title = "收藏",
                            subtitle = if (state.signedIn) {
                                state.favoritesSummary ?: "查看账号收藏夹"
                            } else {
                                "登录后可用"
                            },
                            onClick = onOpenFavorites,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 留言记录（用户要求 2026-10-22）：我发过的留言，进去可以删
                        MyEntryRow(
                            title = "留言记录",
                            subtitle = if (state.signedIn) {
                                "查看我发过的留言"
                            } else {
                                "登录后可用"
                            },
                            onClick = onOpenComments,
                        )
                    }

                    GroupCard {
                        MyEntryRow(
                            title = "最近浏览",
                            subtitle = if (state.recentCount > 0) "浏览过 ${state.recentCount} 本" else "还没有浏览记录",
                            onClick = onOpenRecent,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MyEntryRow(
                            title = "下载任务",
                            subtitle = taskSummary(taskState),
                            badgeCount = taskState.badgeCount,
                            hasFailure = taskState.failedCount > 0,
                            onClick = onOpenTasks,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MyEntryRow(
                            title = "应用设置",
                            subtitle = "阅读与显示、下载、导出、网络、线路",
                            onClick = onOpenSettings,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 版本号挪进「关于」的副标题（原页脚那行随之删除，不再重复展示）
                        MyEntryRow(
                            title = "关于",
                            subtitle = "版本 v${BuildConfig.VERSION_NAME.substringBefore('-')}",
                            onClick = onOpenAbout,
                        )
                    }

                    // 退出登录放在「应用设置」下面（用户要求 2026-10-21）：
                    // 它是本机相关的收尾动作，跟上面的设备级入口同一组更顺。
                    // 未登录时不占位置。
                    if (state.signedIn || state.account is AccountState.Expired) {
                        // 与上面「本机」那一组拉开距离（用户要求 2026-10-21）：24dp 而不是默认 8dp
                        GroupCard(topPadding = 24.dp) {
                            LogoutRow(
                                loggingOut = state.loggingOut,
                                onClick = { showLogoutDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        ConfirmDialog(
            title = "退出登录？",
            text = "只清除这台设备上的登录状态。账号收藏仍保留在服务器；" +
                "书库、最近浏览、下载与导出文件都不会被删除。",
            confirmText = "退出",
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onConfirm = {
                showLogoutDialog = false
                onLogout()
            },
            onDismiss = { showLogoutDialog = false },
        )
    }
}

/** 账户摘要卡：未登录 = 登录入口；已登录 = 头像 + 昵称 + 等级·称号（+ 会员行）。 */
@Composable
private fun AccountSummaryCard(
    state: MyUiState,
    onOpenProfile: () -> Unit,
    onLogin: () -> Unit,
) {
    val profile = state.profile
    val expired = state.account is AccountState.Expired

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)
            // 未登录时整张卡就是登录入口（用户要求 2026-10-22）；已登录点卡片进个人资料
            .clickable(onClick = if (profile == null) onLogin else onOpenProfile),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(photo = profile?.photo, nickname = profile?.displayName)

            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (profile == null) {
                    Text("未登录", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "登录后可以收藏漫画、查看个人资料",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 摘要卡显示**昵称优先**、没有才用登录名（用户 2026-10-23 要求）；
                    // 登录名仍然可以在「个人资料 / 编辑资料」里看到（只读）
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.levelLine.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // 会员信息：**非会员一行都不显示**（用户要求）
                    state.memberLine?.let { line ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expired) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "登录已失效，请重新登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (profile == null) {
                // 本版只接了登录（注册/验证码在官方网页版，见登录页底部说明）
//                OutlinedButton(onClick = onLogin) { Text("登录") }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 头像：接口给的 `photo` 是文件名（拼图片线路）；加载失败/没有时显示昵称首字。
 *
 * 用 `currentImageLoader()`（带 JM 的 UA / 代理）而不是另建 ImageLoader——
 * 与 `CONVENTIONS.md` 第 1 条"头像不属于封面、但要复用应用级加载器"的规定一致。
 */
@Composable
private fun AccountAvatar(photo: String?, nickname: String?) {
    val shape = CircleShape
    val size = Modifier.size(56.dp)
    val initial = nickname?.trim()?.take(1)?.ifBlank { null } ?: "JM"
    Box(
        modifier = size
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (!photo.isNullOrBlank()) {
            val model = Defaults.buildAvatarUrl(photo, LocalImageDomain.current)
            AvatarImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        }
    }
}

/**
 * 分组白卡（与「更多」页原来那张白卡一致：「灰底白卡」主题）。
 *
 * @param topPadding 与上一组之间的间距，默认 8dp；「退出登录」那张卡传更大的值单独拉开。
 */
@Composable
private fun GroupCard(
    topPadding: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = topPadding, end = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 「退出登录」行。
 *
 * 红字 + 右侧出口图标（`ExitToApp`），**不带箭头**：它不是"进入下一页"，而是一个就地动作；
 * 点一下先弹确认框。文字与图标整体**左右居中**（用户要求 2026-10-21）；
 * 退出中在最右侧转小圈并禁点（防重复提交）。
 *
 * 图标用的是 `Icons.AutoMirrored.Filled.ExitToApp`（就是 `Icons.Default.ExitToApp` 的
 * 镜像版：视觉一致，但 RTL 语言下会自动翻转——工程里多选条的「移出书库」也是这个）。
 */
@Composable
private fun LogoutRow(
    loggingOut: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loggingOut, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (loggingOut) "正在退出…" else "退出登录",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        if (loggingOut) {
            Spacer(Modifier.size(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MyEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
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
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 任务行的说明文案：把"进行中"与"失败"分开说（计划 §9 第 9 条）。 */
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
