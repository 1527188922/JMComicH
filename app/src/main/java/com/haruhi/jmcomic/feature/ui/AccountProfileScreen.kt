package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.core.account.MemberProfile
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.feature.api.UserEditDto

/**
 * 个人资料（**只读**，从「我的」页的账户卡点进来）。
 *
 * 本版只展示，不做编辑：编辑要用 `POST /useredit/{uid}`，而它是**整条覆盖**
 * （实测少传字段会被写成默认值），所以留给下一版按"全量提交"实现
 * （见 `docs/account-design.md` §7）。
 *
 * 会员相关信息（`超级会员 · 至 …`）**只在是会员时出现**（用户要求）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountProfileScreen(
    state: AccountProfileUiState,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    /** 下拉刷新 / 报错后的「重试」（进页面不再自动联网，见 `AccountProfileViewModel`）。 */
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Text("个人资料", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 下拉刷新：内置"已经有资料、正在重拉"时才转下拉圈（进页面不再自动联网）
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    if (!state.loading && !state.refreshing) onRefresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val profile = state.profile
                    if (!state.signedIn || profile == null) {
                        EmptyBox("还没有登录", "登录后才能查看个人资料")
                        return@Column
                    }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileAvatar(profile)
                        Spacer(Modifier.size(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.username.ifBlank { "JM 用户" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = buildString {
                                    append("Lv.").append(profile.level)
                                    if (profile.levelName.isNotBlank()) append(" · ").append(profile.levelName)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            state.memberLine?.let { line ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                InfoCard {
                    InfoRow("经验", expText(profile))
                    InfoRow("J Coin", profile.coin)
                    InfoRow("收藏", "${profile.albumFavorites} / ${profile.albumFavoritesMax}")
                    state.detail?.nickName?.takeIf { it.isNotBlank() }?.let { InfoRow("昵称", it) }
                    state.detail?.gender?.takeIf { it.isNotBlank() }?.let { InfoRow("性别", genderText(it)) }
                    state.detail?.birthday?.takeIf { it.isNotBlank() && !it.startsWith("0000") }?.let {
                        InfoRow("生日", it)
                    }
                    state.detail?.aboutMe?.takeIf { it.isNotBlank() }?.let { InfoRow("关于我", it) }
                }

                    state.error?.let { error ->
                        Text(
                            text = "部分资料未能加载：$error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRefresh) { Text("重试") }
                    }

                    TextButton(
                        onClick = onLogout,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("退出登录")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun ProfileAvatar(profile: MemberProfile) {
    val shape = CircleShape
    val size = Modifier.size(64.dp)
    val initial = profile.username.trim().take(1).ifBlank { "JM" }
    Box(
        modifier = size.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.titleLarge)
        profile.photo?.takeIf { it.isNotBlank() }?.let { photo ->
            AvatarImage(
                model = Defaults.buildAvatarUrl(photo, LocalImageDomain.current),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
    }
}

private fun expText(profile: MemberProfile): String =
    if (profile.nextLevelExp > 0) "${profile.exp} / ${profile.nextLevelExp}" else profile.exp.toString()

private fun genderText(raw: String): String = when (raw.lowercase()) {
    "male" -> "男"
    "female" -> "女"
    else -> raw
}
