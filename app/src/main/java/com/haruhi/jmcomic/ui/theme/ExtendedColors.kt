package com.haruhi.jmcomic.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * 扩展配色：App 自己的"槽位 → 色值"映射。
 *
 * [Color.kt] 只放色值、[Theme.kt] 的两个 `ColorScheme` 放 M3 官方槽位，
 * **M3 没有对应槽位的语义色**放这里。这样调用点只认一个名字
 * （`JmTheme.colors.xxx`），换配色 / 接动态取色都不用改 UI 代码。
 *
 * 与 [Color.kt] 里 `WarningOrangeLight` 的区别：那里是写死的原始色值，
 * 这里的值是**从当前 ColorScheme 推导出来的**（所以必须放在主题里面算，
 * 不能写成常量，否则动态取色一开就对不上）。
 */
@Immutable
data class JmExtendedColors(
    /**
     * 卡片"按下态"容器色：按下时叠在容器上的状态层（state layer）预先合成为不透明实色。
     *
     * Material 3 的 CardColors 没有 `pressedContainerColor` 这种 token：按下时看到的其实是
     * **状态层** —— 内容色以 10% 不透明度叠在容器色之上
     * （m3.material.io → Foundations → Interaction → States → State layers：Pressed = 0.1）。
     * 拿来做卡片常态底色，卡片就一直是"按着"的样子，一眼能看出"这是一块可点的次级面板"。
     * 目前用于书库的「继续阅读」卡片与详情页的「导出文件」卡片。
     *
     * 用它当 `containerColor` 时记得**同时显式给 `contentColor = colorScheme.onSurfaceVariant`**：
     * 它不在 ColorScheme 里，`contentColorFor()` 认不出来，只给 containerColor 的话
     * 文字色会退回环境色（`LocalContentColor`，比 `onSurfaceVariant` 更深）。
     */
    val pressedCardContainer: Color,
)

/**
 * 默认值：用基准浅色方案的槽位现算一份。
 *
 * 只为了 Preview / 单测里没套 [JmComicTheme] 时也有个合理颜色，正常运行时会被覆盖。
 */
private val DefaultExtendedColors = lightColorScheme().let { it.toExtendedColors() }

/**
 * M3 状态层（state layer）合成为不透明实色。
 *
 * 颜色带 alpha 时（例如 `Color(0x8A000000)`）直接当背景会和底下的东西透叠，
 * `compositeOver` 把它按 `over` 压到不透明底色上，得到一个等价的不透明色。
 * 深浅两套方案都走这一个公式（浅色是"变灰"，深色是"变亮"），不用各写一个值。
 */
private fun ColorScheme.toExtendedColors(): JmExtendedColors = JmExtendedColors(
    pressedCardContainer = onSurfaceVariant
        .copy(alpha = StateLayerPressed)
        .compositeOver(surfaceVariant),
)

/** M3 的 Pressed 状态层不透明度（m3.material.io → Foundations → Interaction → States）。 */
private const val StateLayerPressed = 0.10f

/**
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：整套配色只在主题切换时整体替换，
 * 读它的组件本来就要跟着重组，不需要按读取点细粒度追踪（同 `LocalImageLoader`）。
 */
internal val LocalJmExtendedColors = staticCompositionLocalOf { DefaultExtendedColors }

/**
 * 全局扩展色板入口：`JmTheme.colors.pressedCardContainer`。
 *
 * 从 [MaterialTheme] 里读，而不是让调用点自己 `isSystemInDarkTheme()` 二选一 ——
 * 这样深浅色与动态取色都只由 [JmComicTheme] 决定。
 */
object JmTheme {
    val colors: JmExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalJmExtendedColors.current
}

/**
 * 把扩展色板挂到主题树上，必须在 `MaterialTheme` **里面**调用（配色要从 `colorScheme` 推导）。
 * 由 [JmComicTheme] 统一调用，业务代码不要直接用。
 */
@Composable
internal fun ProvideJmExtendedColors(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalJmExtendedColors provides colorScheme.toExtendedColors(),
        content = content,
    )
}
