package com.haruhi.jmcomic.feature.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.haruhi.jmcomic.feature.data.PROFILE_GENDERS
import com.haruhi.jmcomic.feature.data.PROFILE_LONG_TEXT_MAX
import com.haruhi.jmcomic.feature.data.PROFILE_RELATIONS
import com.haruhi.jmcomic.feature.data.PROFILE_SEXUALITIES
import com.haruhi.jmcomic.feature.data.PROFILE_TEXT_MAX
import com.haruhi.jmcomic.feature.data.UserProfileForm
import com.haruhi.jmcomic.feature.data.formatBirthday
import com.haruhi.jmcomic.feature.data.optionLabel
import com.haruhi.jmcomic.feature.data.parseBirthday
import java.time.LocalDate

/** DatePicker 用的是 UTC 毫秒；按"天"换算可以避开时区把生日差一天。 */
private const val MILLIS_PER_DAY = 86_400_000L

/** 未设置生日时日期选择器的初始落点（一个中立的值，不是 1970）。 */
private val DEFAULT_BIRTHDAY: LocalDate = LocalDate.of(2000, 1, 1)

/**
 * 个人资料编辑页（`docs/account-design.md` §7）。
 *
 * ## 回显（这是官方 App 的已知毛病，本页刻意绕开）
 *
 * 官方 `InfoModal.tsx` 用 `defaultValue={formData.xxx}`，而 `formData` 是
 * `GET /useredit` 回来之后才填的 —— 首帧渲染时还是空的，`defaultValue` 之后**不会**再更新，
 * 所以"已有信息回显不出来"。本页的做法：
 * 1. 进页面先联网拉最新资料，**加载完成前不渲染任何输入框**（只有 LoadingBox）；
 * 2. 数据到手后由 `UserEditDto.toProfileForm()` 生成**受控**状态，输入框永远显示状态值；
 * 3. 加载失败就整页报错 + 重试，**不允许在空表单上编辑**（否则一保存就把资料冲成默认值）。
 *
 * 这也顺带保证了"整条覆盖"的安全：用户改的一定是刚从服务端拿到的原值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountProfileEditScreen(
    state: AccountProfileEditUiState,
    onBack: () -> Unit,
    onFormChange: (UserProfileForm) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onMessage: (String) -> Unit,
    onConsumeMessage: () -> Unit,
    /** 关掉「保存失败」对话框。 */
    onConsumeSaveError: () -> Unit,
    /** 保存成功：回到资料页。 */
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    // 保存成功：**先弹提示再退页**。两者都在这里做，是因为页面一 pop，组合里的
    // `MessageEffect` 协程会被取消，提示可能来不及交给根部 Snackbar。
    LaunchedEffect(state.saved) {
        if (state.saved) {
            state.message?.let(onMessage)
            onConsumeMessage()
            onSaved()
        }
    }
    // 保存中不允许退出（请求挂在页面的 viewModelScope 上，一 pop 就会被取消）；
    // 保存成功那一帧也不允许再手动退，避免和下面的 `onSaved()` 一起 pop 两次。
    PageBackHandler(enabled = state.saved || state.updating || state.dirty) {
        if (!state.updating && !state.saved) showDiscardDialog = true
    }

    val requestBack: () -> Unit = {
        when {
            state.saved || state.updating -> Unit
            state.dirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AccountFormTopBar(
            title = "编辑个人资料",
            onBack = requestBack,
            actions = {
                // 顶栏固定在滚动区外面，长表单也不用滚回去找保存
                TextButton(onClick = onSave, enabled = state.canSave) {
                    if (state.updating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("保存")
                    }
                }
            },
        )

        // 本地校验错误（改密码只填一个 / 两次不一致）保持页内横幅，不弹框；
        // 服务端 / 网络错误才走文件末尾的 `MessageDialog`。
        state.localError?.let { error ->
            ErrorBanner(message = error, onRetry = null)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                !state.signedIn -> FormMessageBox(
                    title = "还没有登录",
                    hint = "登录后才能修改个人资料",
                )

                state.loading -> LoadingBox()

                state.error != null -> FormMessageBox(
                    title = "资料加载失败",
                    hint = state.error,
                    actionText = "重试",
                    onAction = onRetry,
                )

                else -> {
                    val form = state.form
                    if (form == null) {
                        FormMessageBox(title = "资料加载失败", actionText = "重试", onAction = onRetry)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .imePadding()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FormSection(title = "账户信息") {
                                FormReadOnlyRow(
                                    label = "登录名",
                                    value = state.username.ifBlank { "—" },
                                )
                                FormReadOnlyRow(
                                    label = "邮箱",
                                    value = state.email.ifBlank { "—" },
                                )
                                Text(
                                    text = "登录名与邮箱不能在这里修改；下面的密码留空表示不改密码。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                FormTextField(
                                    label = "昵称",
                                    value = form.nickName,
                                    onValueChange = { onFormChange(form.copy(nickName = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                    helper = "会显示在「我的」页；最多 $PROFILE_TEXT_MAX 个字符",
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "新密码",
                                    value = form.password,
                                    onValueChange = { onFormChange(form.copy(password = it)) },
                                    enabled = !state.updating,
                                    keyboardType = KeyboardType.Password,
                                    visualTransformation = if (passwordVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "重新输入新密码",
                                    value = form.passwordConfirm,
                                    onValueChange = { onFormChange(form.copy(passwordConfirm = it)) },
                                    enabled = !state.updating,
                                    keyboardType = KeyboardType.Password,
                                    visualTransformation = if (passwordVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Text(if (passwordVisible) "隐藏" else "显示")
                                        }
                                    },
                                )
                            }

                            FormSection(title = "个人信息") {
                                // 官方表单把 firstName / lastName 的中文标签写反了（firstName=「姓」、
                                // lastName=「名字」）；这里按字段本意显示，值本身原样提交。
                                FormTextField(
                                    label = "姓（lastName）",
                                    value = form.lastName,
                                    onValueChange = { onFormChange(form.copy(lastName = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "名（firstName）",
                                    value = form.firstName,
                                    onValueChange = { onFormChange(form.copy(firstName = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormDateField(
                                    label = "生日",
                                    valueLabel = form.birthday.ifBlank { "未设置" },
                                    enabled = !state.updating,
                                    onClick = { showDatePicker = true },
                                )
                                Spacer(Modifier.height(4.dp))
                                FormSelectField(
                                    label = "性别",
                                    valueLabel = optionLabel(PROFILE_GENDERS, form.gender),
                                    options = PROFILE_GENDERS,
                                    enabled = !state.updating,
                                    onSelect = { onFormChange(form.copy(gender = it)) },
                                )
                                Spacer(Modifier.height(4.dp))
                                FormSelectField(
                                    label = "关系",
                                    valueLabel = optionLabel(PROFILE_RELATIONS, form.relations),
                                    options = PROFILE_RELATIONS,
                                    enabled = !state.updating,
                                    onSelect = { onFormChange(form.copy(relations = it)) },
                                )
                                Spacer(Modifier.height(4.dp))
                                FormSelectField(
                                    label = "兴趣",
                                    valueLabel = optionLabel(PROFILE_SEXUALITIES, form.sexuality),
                                    options = PROFILE_SEXUALITIES,
                                    enabled = !state.updating,
                                    onSelect = { onFormChange(form.copy(sexuality = it)) },
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "网站",
                                    value = form.website,
                                    onValueChange = { onFormChange(form.copy(website = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                    keyboardType = KeyboardType.Uri,
                                )
                            }

                            FormSection(title = "位置信息") {
                                FormTextField(
                                    label = "出生地",
                                    value = form.birthPlace,
                                    onValueChange = { onFormChange(form.copy(birthPlace = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "城市",
                                    value = form.city,
                                    onValueChange = { onFormChange(form.copy(city = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "国家",
                                    value = form.country,
                                    onValueChange = { onFormChange(form.copy(country = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "职业",
                                    value = form.occupation,
                                    onValueChange = { onFormChange(form.copy(occupation = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "公司",
                                    value = form.company,
                                    onValueChange = { onFormChange(form.copy(company = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "学校",
                                    value = form.school,
                                    onValueChange = { onFormChange(form.copy(school = it)) },
                                    enabled = !state.updating,
                                    maxLines = 1,
                                    imeAction = ImeAction.Done,
                                )
                            }

                            FormSection(title = "其他信息") {
                                val longHelper = "最多 $PROFILE_LONG_TEXT_MAX 个字符"
                                FormTextField(
                                    label = "关于我",
                                    value = form.aboutMe,
                                    onValueChange = { onFormChange(form.copy(aboutMe = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                    helper = longHelper,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "这里的话",
                                    value = form.infoHere,
                                    onValueChange = { onFormChange(form.copy(infoHere = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "收藏的类别",
                                    value = form.collections,
                                    onValueChange = { onFormChange(form.copy(collections = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "理想中的伴侣",
                                    value = form.ideal,
                                    onValueChange = { onFormChange(form.copy(ideal = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "敏感区",
                                    value = form.erogenic,
                                    onValueChange = { onFormChange(form.copy(erogenic = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "最喜欢",
                                    value = form.favorite,
                                    onValueChange = { onFormChange(form.copy(favorite = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                )
                                Spacer(Modifier.height(4.dp))
                                FormTextField(
                                    label = "最讨厌",
                                    value = form.hate,
                                    onValueChange = { onFormChange(form.copy(hate = it)) },
                                    enabled = !state.updating,
                                    singleLine = false,
                                    minLines = 3,
                                    maxLines = 6,
                                )
                            }

                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    val editForm = state.form
    if (showDatePicker && editForm != null) {
        val initialDate = parseBirthday(editForm.birthday) ?: DEFAULT_BIRTHDAY
        val initialMillis = initialDate.toEpochDay() * MILLIS_PER_DAY
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            yearRange = 1900..LocalDate.now().year,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis ?: initialMillis
                        val date = LocalDate.ofEpochDay(millis / MILLIS_PER_DAY)
                        onFormChange(editForm.copy(birthday = formatBirthday(date)))
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    // 保存失败 / 本地校验失败都用弹窗：错误文案长度不定，放在页内会把整张表单顶来顶去。
    // 与「放弃修改」同时出现时让确认框优先，避免两个 AlertDialog 叠在一起。
    if (!showDiscardDialog) {
        state.saveError?.let { error ->
            MessageDialog(
                title = "保存失败",
                message = error,
                onDismiss = onConsumeSaveError,
            )
        }
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "放弃修改？",
            text = "还有没保存的修改，返回后这些内容会丢失。",
            confirmText = "放弃",
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }
}
