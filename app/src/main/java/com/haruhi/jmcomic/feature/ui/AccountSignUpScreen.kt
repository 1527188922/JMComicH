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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.feature.data.SIGN_UP_GENDERS
import com.haruhi.jmcomic.feature.data.SIGN_UP_USERNAME_MAX

/**
 * 注册页（`docs/account-design.md` §8）。
 *
 * 两步：填表 → 邮箱验证码。没有拆成两个路由，是因为两步共享同一份字段
 * （验证码重发要 `username + password`，验证成功后还要用那个密码自动登录）；
 * 拆开就得把密码在导航参数里传，既麻烦又危险。
 *
 * 返回键的三级语义也在这里：验证码步骤 → 注册表单 → 上一页。
 *
 * 另外有一个「已有验证码」模式（`verifyOnly`）：账号已经创建、但当时没验证完，
 * 从登录页直接进来补验证码——这一模式没有注册表单，字段只有用户名 / 密码 / 验证码。
 */
@Composable
fun AccountSignUpScreen(
    state: SignUpUiState,
    onBack: () -> Unit,
    /** 验证码步骤 → 注册表单。 */
    onBackToForm: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onGenderChange: (String) -> Unit,
    onAdultConfirmedChange: (Boolean) -> Unit,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onResendCode: () -> Unit,
    onConsumeError: () -> Unit,
    /** 账号已创建（本次注册成功过）后回到表单时，重新进入验证码步骤。 */
    onResumeVerification: () -> Unit,
    /** 注册 + 验证 + 登录全部完成。 */
    onSignedUp: () -> Unit,
    /** 验证成功但自动登录失败：带着用户名回登录页。 */
    onGoLogin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.done) {
        if (state.done) onSignedUp()
    }

    val atVerifyStep = state.step == SignUpStep.VERIFY && !state.verifiedNeedLogin
    // 验证码步骤按返回键回注册表单（还要能改错填的邮箱）；「已有验证码」模式直接退出。
    // 请求在飞时返回键不响应，避免把提交中的请求取消掉（与顶栏返回按钮一致）。
    BackHandler(enabled = atVerifyStep) {
        when {
            state.submitting -> Unit
            state.verifyOnly -> onBack()
            else -> onBackToForm()
        }
    }

    // **只把服务端 / 网络错误**做成弹窗（这类文案长、位置不定，放在页内会把控件顶下去）。
    // 表单校验、验证码校验、发送成功的提示仍然保持页内展示（用户 2026-10-23 明确要求）。
    // 「验证完成但自动登录失败」是独立面板，错误正文已经在面板里，不再叠弹窗。
    val dialogError = state.error
    if (!state.verifiedNeedLogin) {
        dialogError?.let { message ->
            MessageDialog(
                title = state.errorTitle.ifBlank { "提示" },
                message = message,
                onDismiss = onConsumeError,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AccountFormTopBar(
            title = when {
                state.verifiedNeedLogin -> "验证完成"
                state.step == SignUpStep.FORM -> "注册"
                else -> "验证邮箱"
            },
            onBack = {
                when {
                    // 提交中不退出：请求挂在页面的 viewModelScope 上，pop 会把它取消
                    state.submitting && atVerifyStep -> Unit
                    atVerifyStep && !state.verifyOnly -> onBackToForm()
                    else -> onBack()
                }
            },
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
                when {
                    state.verifiedNeedLogin -> VerifiedPanel(state = state, onGoLogin = onGoLogin)
                    state.step == SignUpStep.FORM -> SignUpForm(
                        state = state,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                        onPasswordConfirmChange = onPasswordConfirmChange,
                        onEmailChange = onEmailChange,
                        onGenderChange = onGenderChange,
                        onAdultConfirmedChange = onAdultConfirmedChange,
                        onTermsAcceptedChange = onTermsAcceptedChange,
                        onTogglePasswordVisible = onTogglePasswordVisible,
                        onSubmit = onSubmit,
                        onResumeVerification = onResumeVerification,
                        onBackToLogin = onBack,
                    )

                    else -> VerifyForm(
                        state = state,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                        onTogglePasswordVisible = onTogglePasswordVisible,
                        onCodeChange = onCodeChange,
                        onSubmitCode = onSubmitCode,
                        onResendCode = onResendCode,
                        onBackToForm = onBackToForm,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

/** 注册表单（第一屏）。 */
@Composable
private fun SignUpForm(
    state: SignUpUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onGenderChange: (String) -> Unit,
    onAdultConfirmedChange: (Boolean) -> Unit,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onResumeVerification: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    Text("注册 JM 账号", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        text = "注册后需要到邮箱完成验证，验证通过会自动登录。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // 重发成功时用户可能已经按返回回到了这里：提示也要能看到（仍然不弹窗）
    state.notice?.let { notice ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (state.registered) {
        // 注册请求已经成功、用户又按返回键回到了表单：账号已经存在，
        // 再点一次「注册」只会收到"用户名已经存在"。所以这里只给「继续验证」，
        // 同时把表单锁住——账号是用当时那组字段创建的，事后改这里不会同步到服务端。
        Spacer(Modifier.height(10.dp))
        Text(
            text = "这个账号已经提交过注册，还需要完成邮箱验证才能登录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    val formEnabled = !state.submitting && !state.registered

    // 「灰底白卡」：字段、勾选与主按钮在白卡内，返回登录的链接留在卡外灰底上
    Spacer(Modifier.height(16.dp))
    FormCard {
        FormTextField(
            label = "用户名",
            value = state.form.username,
            onValueChange = onUsernameChange,
            enabled = formEnabled,
            isError = state.validation.username != null,
            helper = state.validation.username ?: "最多 $SIGN_UP_USERNAME_MAX 个字符",
            keyboardType = KeyboardType.Ascii,
        )

        Spacer(Modifier.height(4.dp))
        FormTextField(
            label = "密码",
            value = state.form.password,
            onValueChange = onPasswordChange,
            enabled = formEnabled,
            isError = state.validation.password != null,
            helper = state.validation.password,
            keyboardType = KeyboardType.Password,
            visualTransformation = if (state.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                // 用文字而不是眼睛图标：`material-icons-core` 里没有 Visibility（与登录页同一个取舍）
                TextButton(
                    onClick = onTogglePasswordVisible,
                    enabled = formEnabled,
                ) {
                    Text(if (state.passwordVisible) "隐藏" else "显示")
                }
            },
        )

        Spacer(Modifier.height(4.dp))
        FormTextField(
            label = "重新输入密码",
            value = state.form.passwordConfirm,
            onValueChange = onPasswordConfirmChange,
            enabled = formEnabled,
            isError = state.validation.passwordConfirm != null,
            helper = state.validation.passwordConfirm,
            keyboardType = KeyboardType.Password,
            visualTransformation = if (state.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        )

        Spacer(Modifier.height(4.dp))
        FormTextField(
            label = "邮箱",
            value = state.form.email,
            onValueChange = onEmailChange,
            enabled = formEnabled,
            isError = state.validation.email != null,
            helper = state.validation.email ?: "验证码会发到这个邮箱",
            keyboardType = KeyboardType.Email,
        )

        Spacer(Modifier.height(12.dp))
        Text("性别", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SIGN_UP_GENDERS.forEach { (value, label) ->
                FilterChip(
                    selected = state.form.gender == value,
                    onClick = { onGenderChange(value) },
                    enabled = formEnabled,
                    label = { Text(label) },
                )
            }
        }
        state.validation.gender?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        FormCheckRow(
            text = "我已年满 18 岁",
            checked = state.form.adultConfirmed,
            onCheckedChange = onAdultConfirmedChange,
            enabled = formEnabled,
        )
        FormCheckRow(
            text = "我已阅读并同意用户协议与隐私政策",
            checked = state.form.termsAccepted,
            onCheckedChange = onTermsAcceptedChange,
            enabled = formEnabled,
        )
        state.validation.confirmation?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        if (state.registered) {
            // 账号已创建：唯一的下一步是完成邮箱验证，不能再提交一次注册
            Button(
                onClick = onResumeVerification,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("继续验证") }
        } else {
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
                    Text("正在提交…")
                } else {
                    Text("注册")
                }
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
            text = "已有账号？",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBackToLogin, enabled = !state.submitting) { Text("返回登录") }
    }
    Spacer(Modifier.height(24.dp))
}

/** 邮箱验证码（第二屏）。 */
@Composable
private fun VerifyForm(
    state: SignUpUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onResendCode: () -> Unit,
    onBackToForm: () -> Unit,
    onBack: () -> Unit,
) {
    Text("验证邮箱", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (state.verifyOnly) {
            "输入账号注册时收到的验证码，验证通过后会自动登录。"+
                    "\n注意：“重新发送验证码”按钮需要魔法才能发送成功！（其他步骤都不需要）"
        } else {
            "验证码已发送到 ${state.email.ifBlank { "注册邮箱" }}。\n" +
                "账号「${state.username}」验证通过后才能真正登录。"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // 注册成功 / 验证码重发成功：保持原来的页内提示（用户明确不要弹窗）
    state.notice?.let { notice ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    // 「灰底白卡」：字段与主按钮在白卡内；返回 / 重发是次级动作，留在卡外灰底上。
    // 本地校验错误与登录 / 注册表单一致：挂在**对应输入框下方**的 supportingText。
    Spacer(Modifier.height(16.dp))
    FormCard {
        // 从登录页「已有验证码」进来时，用户名与密码都还没有；重新发送验证码 / 自动登录都要它们。
        if (state.verifyOnly) {
            FormTextField(
                label = "用户名",
                value = state.form.username,
                onValueChange = onUsernameChange,
                enabled = !state.submitting,
                isError = state.verifyUsernameError != null,
                helper = state.verifyUsernameError,
                keyboardType = KeyboardType.Ascii,
            )
            Spacer(Modifier.height(4.dp))
            FormTextField(
                label = "密码",
                value = state.form.password,
                onValueChange = onPasswordChange,
                enabled = !state.submitting,
                isError = state.verifyPasswordError != null,
                helper = state.verifyPasswordError ?: "验证成功后会用这个密码自动登录",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (state.passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = onTogglePasswordVisible, enabled = !state.submitting) {
                        Text(if (state.passwordVisible) "隐藏" else "显示")
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
        }
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            singleLine = true,
            enabled = !state.submitting,
            isError = state.codeError != null,
            label = { Text("邮箱验证码") },
            supportingText = { Text(state.codeError ?: "没收到就检查一下垃圾邮件，或者重新发送") },
            keyboardOptions = KeyboardOptions(
                // `numcode` 看名字像纯数字，但官方输入框是普通文本框；用 ASCII 键盘
                // 既能打数字也能打字母，不为一个没实测过的格式把用户挡住。
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmitCode() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSubmitCode,
            // 重发验证信期间也禁止提交验证码：两个请求都依赖同一份用户名/密码，串行更稳
            enabled = !state.submitting && !state.resending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text("正在验证…")
            } else {
                Text("验证并登录")
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            onClick = if (state.verifyOnly) onBack else onBackToForm,
            enabled = !state.submitting,
        ) {
            Text(if (state.verifyOnly) "返回登录" else "返回上一步")
        }
        TextButton(
            onClick = onResendCode,
            enabled = state.canResend,
        ) {
            Text(
                when {
                    state.resending -> "正在发送…"
                    state.resendCooldown > 0 -> "重新发送（${state.resendCooldown}s）"
                    else -> "重新发送验证码"
                },
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** 验证通过但自动登录失败：给一条明确的出口，而不是让用户卡在验证码页。 */
@Composable
private fun VerifiedPanel(
    state: SignUpUiState,
    onGoLogin: (String) -> Unit,
) {
    // 与同页两个表单同一张「灰底白卡」，只是内容换成结果说明
    Spacer(Modifier.height(16.dp))
    FormCard {
        Text("邮箱验证成功", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "账号「${state.username}」已经可以登录了。",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onGoLogin(state.username) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("去登录")
        }
    }
    Spacer(Modifier.height(24.dp))
}
