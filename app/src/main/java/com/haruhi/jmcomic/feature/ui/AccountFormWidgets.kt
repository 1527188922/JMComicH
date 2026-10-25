package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 账号表单页（登录 / 注册 / 资料编辑）共用的顶栏。
 *
 * 与 `AccountFavoritesScreen` 里的私有 `TopBar` 同一套观感；那边没动，
 * 这里抽出来给新增的两个页面用，避免第三、第四份复制。
 */
@Composable
fun AccountFormTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
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
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * 白卡表单容器（登录 / 注册 / 邮箱验证页，「灰底白卡」主题）。
 *
 * 页首大标题、说明与字段、主按钮都在这张卡内（标题由调用方给，风格
 * `titleLarge`）；次级链接（返回登录 / 注册新账号之类）留在卡外的灰底上。
 * 与 [FormSection] 同一张卡，只是那边的标题是卡内固定的分区小标题。
 */
@Composable
fun FormCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            content()
        }
    }
}

/** 白色分组卡 + 分区标题（资料编辑页的四个分区）。 */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    FormCard(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 表单里的普通单行 / 多行输入框。 */
@Composable
fun FormTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    helper: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    /**
     * 软键盘动作（例如 `ImeAction.Done` 直接提交）。
     *
     * 默认什么都不做（与以前一致）；只有「忘记密码」这类单字段表单需要回车即提交。
     */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        isError = isError,
        supportingText = helper?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 只读的"展示行"（登录名 / 邮箱）。
 *
 * 用 `Text` 而不是禁用输入框：这两个值**不能改**，画成输入框只会让人去点。
 */
@Composable
fun FormReadOnlyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = value.ifBlank { "未绑定" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 假的"只读输入框 + 点击弹出菜单"。
 *
 * 为什么不用 `ExposedDropdownMenuBox`：它在 material3 1.4.0 仍是实验 API，
 * 而这个页面只需要"点一下选一个值"。这里用一层透明 `Box` 接住点击，
 * 视觉与其它输入框完全一致，交互就是标准 `DropdownMenu`。
 */
@Composable
fun FormSelectField(
    label: String,
    valueLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valueLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = { expanded = true },
                ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (raw, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(raw)
                    },
                )
            }
        }
    }
}

/** 点击弹系统日期选择器的只读输入框（生日）。 */
@Composable
fun FormDateField(
    label: String,
    valueLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector = Icons.Default.DateRange,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valueLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(icon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        )
    }
}

/** 勾选行（18+ / 用户协议）。整行可点，不只是那个小方块。 */
@Composable
fun FormCheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.size(4.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 账号表单页的错误 / 提示对话框。
 *
 * 为什么要用弹窗而不是页内红字：注册页的字段一多，任何一行错误（尤其是服务端一次返回
 * 三四条）都会把下面的控件整体顶下去，用户正在点的按钮会"跳走"。
 * 弹窗是覆盖层，**不参与布局**，出现/消失都不会改变任何控件位置。
 *
 * 关掉对话框只是收起提示；字段自己的红框由 `isError` 负责，仍然保留到用户修改为止。
 */
@Composable
fun MessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmText: String = "知道了",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // 服务端一次可能返回三四条错误；小屏 / 大字号下给一层滚动，别让正文被裁掉
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(message)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirmText) }
        },
    )
}

/**
 * 整页消息（加载失败 / 未登录 / 空态）+ 一个动作按钮。
 *
 * `EmptyBox` 没有动作槽，账号页又都是"失败 + 重试"的形态，所以补这个小外壳。
 * 内容**水平垂直都居中**（宽度必须 `fillMaxWidth`，否则只有 `wrapContent` 宽、会靠左）。
 */
@Composable
fun FormMessageBox(
    title: String,
    hint: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}
