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
import com.haruhi.jmcomic.feature.data.PROFILE_GENDERS
import com.haruhi.jmcomic.feature.data.PROFILE_RELATIONS
import com.haruhi.jmcomic.feature.data.PROFILE_SEXUALITIES
import com.haruhi.jmcomic.feature.data.isUnsetBirthday
import com.haruhi.jmcomic.feature.data.optionLabel

/**
 * 个人资料（从「我的」页的账户卡点进来）。
 *
 * 只展示；右上角「编辑」进 [AccountProfileEditScreen]。编辑页用的是
 * `GET /useredit` 的**最新**数据，并按 `POST /useredit` 的"整条覆盖"语义全量提交
 * （见 `docs/account-design.md` §7）。
 *
 * 会员相关信息（`超级会员 · 至 …`）**只在是会员时出现**（用户要求）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountProfileScreen(
    state: AccountProfileUiState,
    onBack: () -> Unit,
    /** 下拉刷新 / 报错后的「重试」（进页面不再自动联网，见 `AccountProfileViewModel`）。 */
    onRefresh: () -> Unit,
    /** 右上角「编辑」：进编辑页。 */
    onEdit: () -> Unit,
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
            // 只有真的拿到了资料才给编辑入口：编辑页必须先 GET 成功才能渲染表单，
            // 在"还没登录 / 加载失败"时显示「编辑」只会把人带进一个空页面。
            if (state.signedIn && state.profile != null) {
                TextButton(onClick = onEdit) { Text("编辑") }
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
                val profile = state.profile
                when {
                    !state.signedIn || profile == null ->
                        EmptyBox("还没有登录", "登录后才能查看个人资料")

                    // 本地没有资料快照、正在联网拉 `/useredit`（首次进入或失败后重试）：
                    // 整页转圈，避免资料卡先出来、生日/昵称等字段再一条条出现（用户要求 2026-10-23）。
                    state.loading && state.detail == null -> LoadingBox()

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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

                        InfoCard(
                            rows = profileRows(profile, state.detail),
                        )

                        state.error?.let { error ->
                            Text(
                                text = "部分资料未能加载：$error",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRefresh) { Text("重试") }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                InfoRow(label, value)
                // 分隔线只画在行与行之间，最后一行下面不留横线（用户要求 2026-10-23）。
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
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
}

@Composable
private fun ProfileAvatar(profile: MemberProfile) {
    val shape = CircleShape
    val size = Modifier.size(64.dp)
    val initial = profile.displayName.trim().take(1).ifBlank { "JM" }
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

/**
 * 资料页要展示的行：账号数据（来自登录快照）+ 官方 24 项里的可读字段。
 *
 * 只显示**非空**项（空串 / 服务端归一出来的空生日都不占一行），
 * 与官方"更多资料"折叠区的内容对齐——编辑页能改的，这里都能看到。
 */
private fun profileRows(profile: MemberProfile, detail: UserEditDto?): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>(
        "经验" to expText(profile),
        "J Coin" to profile.coin,
        "收藏" to "${profile.albumFavorites} / ${profile.albumFavoritesMax}",
    )
    if (detail == null) return rows

    fun add(label: String, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty()) rows += label to text
    }

    add("昵称", detail.nickName)
    // 官方表单把 firstName / lastName 的中文标签写反了；这里按字段本意拼成「姓 名」
    val fullName = listOfNotNull(
        detail.lastName?.trim()?.takeIf { it.isNotEmpty() },
        detail.firstName?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" ")
    add("姓名", fullName)
    add("性别", detail.gender?.takeIf { it.isNotBlank() }?.let { optionLabel(PROFILE_GENDERS, it) })
    add("生日", detail.birthday?.takeUnless { isUnsetBirthday(it) })
    add("关系", detail.relations?.takeIf { it.isNotBlank() }?.let { optionLabel(PROFILE_RELATIONS, it) })
    add("兴趣", detail.sexuality?.takeIf { it.isNotBlank() }?.let { optionLabel(PROFILE_SEXUALITIES, it) })
    add("网站", detail.website)
    add("出生地", detail.birthPlace)
    add("城市", detail.city)
    add("国家", detail.country)
    add("职业", detail.occupation)
    add("公司", detail.company)
    add("学校", detail.school)
    add("关于我", detail.aboutMe)
    add("这里的话", detail.infoHere)
    add("收藏的类别", detail.collections)
    add("理想中的伴侣", detail.ideal)
    add("敏感区", detail.erogenic)
    add("最喜欢", detail.favorite)
    add("最讨厌", detail.hate)
    return rows
}
