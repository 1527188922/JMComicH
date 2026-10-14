package com.haruhi.haruhibox.feature.jm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import com.haruhi.haruhibox.core.settings.SettingDefaults

/** 阅读方向：纵向连续 / 横向分页（计划 §9 第 8 条）。 */
enum class ReadingMode(val label: String) {
    VERTICAL("纵向连续"),
    HORIZONTAL("横向分页"),
    ;

    companion object {
        fun fromCode(code: Int) =
            if (code == SettingDefaults.JM_READING_MODE_HORIZONTAL) HORIZONTAL else VERTICAL
    }
}

/** 存储用的编码值。写成顶层扩展而不是伴生对象里的成员扩展，外面才调得到。 */
fun ReadingMode.toCode(): Int = if (this == ReadingMode.HORIZONTAL) {
    SettingDefaults.JM_READING_MODE_HORIZONTAL
} else {
    SettingDefaults.JM_READING_MODE_VERTICAL
}

/** 横向分页时的翻页方向（仅横向有效，对齐设置项说明）。 */
enum class ReadingDirection(val label: String) {
    LTR("从左到右"),
    RTL("从右到左"),
    ;

    companion object {
        fun fromCode(code: Int) =
            if (code == SettingDefaults.JM_READING_DIRECTION_RTL) RTL else LTR
    }
}

fun ReadingDirection.toCode(): Int = if (this == ReadingDirection.RTL) {
    SettingDefaults.JM_READING_DIRECTION_RTL
} else {
    SettingDefaults.JM_READING_DIRECTION_LTR
}

/**
 * 阅读背景（计划 §9.3 的"阅读背景"）。
 *
 * **不含"护眼暖色"**（用户要求第 4 点：观看漫画界面去掉它）。
 */
enum class ReadingBackground(val label: String) {
    THEME("跟随主题"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun fromCode(code: Int) = when (code) {
            SettingDefaults.JM_READING_BACKGROUND_LIGHT -> LIGHT
            SettingDefaults.JM_READING_BACKGROUND_DARK -> DARK
            // 包括历史数据里可能存在的"护眼暖色"（3）：已经不支持，回到跟随主题
            else -> THEME
        }
    }
}

fun ReadingBackground.toCode(): Int = when (this) {
    ReadingBackground.THEME -> SettingDefaults.JM_READING_BACKGROUND_THEME
    ReadingBackground.LIGHT -> SettingDefaults.JM_READING_BACKGROUND_LIGHT
    ReadingBackground.DARK -> SettingDefaults.JM_READING_BACKGROUND_DARK
}

/**
 * 阅读器的偏好快照。
 *
 * **与 JM 设置共用同一份存储**（计划 §9 第 8 条："阅读设置…与 JM 设置使用同一份偏好"），
 * 所以在阅读器里改背景，进 JM 设置页看到的也是新值。
 */
data class ReaderPreferences(
    val mode: ReadingMode = ReadingMode.VERTICAL,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val background: ReadingBackground = ReadingBackground.THEME,
    /** 亮度是否跟随系统；false 表示用 [brightnessValue]。 */
    val brightnessFollowSystem: Boolean = true,
    /** 自定义亮度，0f～1f。 */
    val brightnessValue: Float = SettingDefaults.JM_BRIGHTNESS_VALUE_DEFAULT / 100f,
    val keepScreenOn: Boolean = true,
) {
    /** 横向分页时是否要反过来布局（Compose 的 RTL 布局方向）。 */
    val layoutDirection: LayoutDirection
        get() = if (mode == ReadingMode.HORIZONTAL && direction == ReadingDirection.RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
}

/** 阅读器背景色。`THEME` 用主题背景，其余是固定配色。 */
@Composable
@ReadOnlyComposable
fun readerBackgroundColor(background: ReadingBackground): Color = when (background) {
    ReadingBackground.THEME -> MaterialTheme.colorScheme.background
    ReadingBackground.LIGHT -> Color(0xFFF7F5F2)
    ReadingBackground.DARK -> Color(0xFF121212)
}

/** 该背景下文字应该用的颜色（控件栏在深色背景上不能用主题的 onBackground）。 */
@Composable
@ReadOnlyComposable
fun readerContentColor(background: ReadingBackground): Color = when (background) {
    ReadingBackground.THEME -> MaterialTheme.colorScheme.onBackground
    ReadingBackground.LIGHT -> Color(0xFF1B1B1B)
    ReadingBackground.DARK -> Color(0xFFE6E6E6)
}
