package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 登录页（`docs/account-design.md` §8）。
 *
 * 注册走独立的 [AccountSignUpScreen]（注册 → 邮箱验证码 → 自动登录）；
 * 「忘记密码」走独立的 [AccountForgotScreen]（`POST /forgot`：把用户名 + 新密码发到注册邮箱，
 * 旧密码当场失效）。
 *
 * 入口位置（2026-10-25 方案 B）：
 * - 「忘记密码？ →」放在**密码框右下方**（登录页的经典位置，用户会主动去这里找）；
 * - 卡外灰底只留一行两个与注册相关的入口，**左右贴边**：左边「注册新账号」，右边「继续验证」。
 * 这样底部不再是三行同构的"疑问句 + 链接"，也不是把三个动作塞进同一行。
 * 两个疑问句标签（还没有账号？/ 注册后没验证？）没有保留：一行里贴边放两段"标签 + 链接"
 * 在 360dp 上就放不下了；「继续验证」的语境由点进去之后的验证页说明（那里写着"验证码是注册时收到的"）。
 *
 * 独立页面而不是底部面板：表单要键盘、要滚动、要能放长错误文案。
 */
@Composable
fun AccountLoginScreen(
    state: LoginUiState,
    onBack: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onConsumeError: () -> Unit,
    /** 底部「注册新账号」：进注册页（`next` 由导航层带过去）。 */
    onOpenSignUp: () -> Unit,
    /** 底部「继续验证」：账号已创建但没验证完时，回来补邮箱验证码。 */
    onOpenVerify: () -> Unit,
    /** 密码框右下方的「忘记密码？ →」：`POST /forgot` 把用户名 + 新密码发到注册邮箱。 */
    onOpenForgot: () -> Unit,
    /** 登录成功（由调用方决定关页面 / 回跳）。 */
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 登录成功 → 交给导航层处理（关页面 + 有 next 就走 next）
    LaunchedEffect(state.done) {
        if (state.done) onLoggedIn()
    }

    // 服务端 / 网络 / 本地校验错误统一用弹窗展示：页内错误条会把输入框和登录按钮整体顶下去。
    state.error?.let { message ->
        MessageDialog(title = state.errorTitle, message = message, onDismiss = onConsumeError)
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
            Text("登录", style = MaterialTheme.typography.titleMedium)
        }

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
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                // 「灰底白卡」：标题、说明、字段与主按钮都在白卡内，注册 / 补验证码的链接留在卡外灰底上
                FormCard {
                    Text("用 JM 账号登录", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "登录后可以把漫画收进账号收藏夹；不登录也能正常浏览、下载与阅读。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = onUsernameChange,
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.usernameError != null,
                        label = { Text("用户名") },
                        supportingText = state.usernameError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.passwordError != null,
                        label = { Text("密码") },
                        supportingText = state.passwordError?.let { { Text(it) } },
                        visualTransformation = if (state.passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            // 用文字而不是眼睛图标：`material-icons-core` 里没有 Visibility，
                            // 手画一个只为这个功能不划算（见 `ui/theme/Icons.kt` 的取舍）
                            TextButton(
                                onClick = onTogglePasswordVisible,
                                enabled = !state.submitting,
                            ) {
                                Text(if (state.passwordVisible) "隐藏" else "显示")
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 忘记密码：紧跟密码框、右对齐（登录页最经典的位置）。
                    // 底部只留「注册 / 继续验证」两条与注册相关的入口，三行同构的问题就没了。
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        TextButton(
                            onClick = onOpenForgot,
                            enabled = !state.submitting,
                            // 右内边距收到 0，文字与输入框右边缘对齐（默认 12dp 会缩进一截）
                            contentPadding = PaddingValues(start = 12.dp, end = 0.dp),
                        ) {
                            Text("忘记密码？ →")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSubmit,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("正在登录…")
                        } else {
                            Text("登录")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                // 注册 / 继续验证：**同一行、左右贴边**。一行里并排放两段"疑问句标签 + 链接"
                // 在 360dp 上就放不下，所以标签去掉，只留两个动作；「继续验证」的语境由
                // 点进去之后的验证页说明（那里写着"验证码是注册时收到的"）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onOpenSignUp,
                        enabled = !state.submitting,
                        // 左右内边距收到 0：文字与卡片 / 输入框落在同一条边线上（默认 12dp 会缩进去）
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("注册新账号")
                    }
                    TextButton(
                        onClick = onOpenVerify,
                        enabled = !state.submitting,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("继续验证")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
