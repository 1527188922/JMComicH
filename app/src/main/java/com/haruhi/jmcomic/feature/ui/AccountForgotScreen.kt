package com.haruhi.jmcomic.feature.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 「忘记密码」页（`docs/official-api-manga.md` §7.5）。
 *
 * 官方接口是 `POST /forgot {email}`：**只填注册邮箱**，成功后服务端把
 * 「用户名 + 系统生成的新密码」发到该邮箱，**旧密码当场作废**（2026-10-25 实测）。
 * 所以本页没有验证码、也没有"设置新密码"这一步——重置完全在服务端完成。
 *
 * 两个阶段与注册页同一套「灰底白卡」：
 * 1. 填邮箱 → `POST /forgot`；
 * 2. 「新密码已发送」结果面板（服务端文案原样展示 + 「去登录」）。
 *
 * 独立页面而不是底部面板：要键盘、要能放长错误文案、返回键语义单一（直接回登录页）。
 */
@Composable
fun AccountForgotScreen(
    state: ForgotPasswordUiState,
    onBack: () -> Unit,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onConsumeError: () -> Unit,
    /** 结果面板 →「换个邮箱重试」：回到表单。 */
    onEditAgain: () -> Unit,
    /** 结果面板 / 底部 →「返回登录」。 */
    onGoLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 请求在飞时拦住返回键：这个请求**不重试**，中途退出会让"服务端到底重置没重置"变得不确定。
    // （请求挂在页面的 viewModelScope 上，pop 会把它取消，但服务端可能已经处理完了。）
    BackHandler(enabled = state.submitting) { }

    // 服务端 / 网络错误走弹窗（与登录 / 注册页一致）：文案长、放页内会把控件顶下去。
    state.error?.let { message ->
        MessageDialog(title = state.errorTitle, message = message, onDismiss = onConsumeError)
    }

    Column(modifier = modifier.fillMaxSize()) {
        AccountFormTopBar(
            title = "忘记密码",
            onBack = { if (!state.submitting) onBack() },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                if (state.sent) {
                    SentPanel(state = state, onGoLogin = onGoLogin, onEditAgain = onEditAgain)
                } else {
                    ForgotForm(
                        state = state,
                        onEmailChange = onEmailChange,
                        onSubmit = onSubmit,
                        onGoLogin = onGoLogin,
                    )
                }
            }
        }
    }
}

/** 第一屏：填注册邮箱 →「发送新密码到邮箱」。 */
@Composable
private fun ForgotForm(
    state: ForgotPasswordUiState,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoLogin: () -> Unit,
) {
    // 「灰底白卡」：标题、说明、字段与主按钮都在白卡内；返回登录留在卡外灰底上（与登录 / 注册页一致）
    FormCard {
        Text("找回密码", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "填入注册时用的邮箱，服务端会把「用户名 + 新密码」发到该邮箱。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 这条必须显眼：实测发送成功后**旧密码立刻失效**，用户要先确认邮箱能用再去点。
        Spacer(Modifier.height(10.dp))
        Text(
            text = "注意：发送成功后旧密码会立即失效。请先确认能打开这个邮箱，再用邮件里的新密码登录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(16.dp))
        FormTextField(
            label = "注册邮箱",
            value = state.email,
            onValueChange = onEmailChange,
            enabled = !state.submitting,
            isError = state.emailError != null,
            helper = state.emailError ?: "就是注册账号时填的那个邮箱",
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text("正在发送…")
            } else {
                Text("发送新密码到邮箱")
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "想起来了？",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onGoLogin, enabled = !state.submitting) { Text("返回登录") }
    }
    Spacer(Modifier.height(24.dp))
}

/** 第二屏：新密码已发送到邮箱。 */
@Composable
private fun SentPanel(
    state: ForgotPasswordUiState,
    onGoLogin: () -> Unit,
    onEditAgain: () -> Unit,
) {
    FormCard {
        Text("新密码已发送", style = MaterialTheme.typography.titleLarge)
        state.notice?.let { notice ->
            Spacer(Modifier.height(8.dp))
            // 服务端原文（实测是「用戶名和新密碼已通過電子EMAIL發送給您！」），不自造文案
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "请到注册邮箱查收「用户名 + 新密码」，然后回来用新密码登录。" +
                "旧密码已经失效，不再可用。没收到就检查一下垃圾邮件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoLogin, modifier = Modifier.fillMaxWidth()) {
            Text("去登录")
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "邮箱填错了？",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onEditAgain) { Text("换个邮箱重试") }
    }
    Spacer(Modifier.height(24.dp))
}
