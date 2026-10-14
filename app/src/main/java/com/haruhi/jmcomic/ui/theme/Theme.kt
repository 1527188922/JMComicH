package com.haruhi.jmcomic.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 「灰底白卡」浅色配色。
 *
 * 这是 `res/values/themes.xml` 里 `Theme.JMComicH.CardWhite` 那套 XML 主题在
 * Compose 侧的标准实现。**改这里才有效** —— Compose 的组件
 * （`Scaffold` / `TopAppBar` / `NavigationBar` / `Card` / `Button`…）从不读
 * `android:colorPrimary` 这类 XML 属性，它们只读 `MaterialTheme.colorScheme`。
 *
 * 每一行的行尾注释标出对应的 XML 属性，方便两边对照。
 */
private val LightColorScheme = lightColorScheme(
    // ---- 强调色 #FF3F8CFF（XML colorAccent）----
    // onPrimary / primaryContainer 必须自己定：XML 的 colorAccent 只有一个色值，
    // 而 M3 需要"能画在强调色上的前景色"和"强调色的容器色"这两个派生角色。
    primary = CardWhiteAccent, // XML colorAccent
    onPrimary = CardWhiteSurface,
    primaryContainer = CardWhiteAccent,
    onPrimaryContainer = CardWhiteSurface,

    // ---- 次强调 / 第三强调：XML 没给，按强调色色相族补中性色与浅底变体 ----
    secondary = Color(0xFF5F6368), // 取自 M3 light 基准（无 XML 对应）
    onSecondary = CardWhiteSurface,
    secondaryContainer = Color(0xFFE3EAF4), // 选中态浅底（NavigationBar 选中胶囊等）
    onSecondaryContainer = Color(0xFF1A1C1E),
    tertiary = CardWhiteAccent,
    onTertiary = CardWhiteSurface,
    tertiaryContainer = Color(0xFFD9E7FF),
    onTertiaryContainer = Color(0xFF0A1F3D),

    // ---- 错误：XML 没给，取 M3 light 基准红 ----
    // （XML 里的"警示"语义当前由固定的 WarningOrangeLight 承担，见 Color.kt）
    error = Color(0xFFB3261E),
    onError = CardWhiteSurface,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    // ---- 内容区底色 #FFF5F5F5（XML colorBackground / windowBackground）----
    background = CardWhiteBackground, // XML colorBackground
    onBackground = CardWhiteOnSurface, // XML textColorPrimary

    // ---- 表面分层 ----
    // XML 里 background 与 colorBackgroundFloating 是两个并列色值；M3 把它们展开成
    // 一族带层级的 surface 槽位。这里**全部映射到纯白**：App 里同时用了 Card 的默认
    // 容器色（M3 读 surfaceContainerLow）和显式 `containerColor = colorScheme.surface`
    // / `surfaceVariant` 两种写法，只有都白才能都表现为"白卡"。
    surface = CardWhiteSurface, // XML colorPrimary / colorBackgroundFloating
    onSurface = CardWhiteOnSurface, // XML textColorPrimary
    surfaceVariant = CardWhiteSurface, // App 里当卡片/封面底用，与上面同值
    onSurfaceVariant = CardWhiteOnSurfaceVariant, // XML textColorSecondary
    surfaceContainerLowest = CardWhiteSurface,
    surfaceContainerLow = CardWhiteSurface, // M3 Card 的默认容器色
    surfaceContainer = CardWhiteSurface, // M3 NavigationBar 的默认容器色
    surfaceContainerHigh = CardWhiteSurface,
    surfaceContainerHighest = CardWhiteSurface,
    surfaceBright = CardWhiteSurface,
    surfaceDim = CardWhiteBackground,

    // ---- 描边 / 分隔线 ----
    outline = Color(0xFF9AA0A6),
    outlineVariant = CardWhiteOutlineVariant,

    // ---- 反色表面（Snackbar 用它们）----
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = CardWhiteSurface,
    inversePrimary = Color(0xFFA8C8FF),

    // ---- 弹窗遮罩：对应 XML backgroundDimAmount=0.4 ----
    scrim = Color(0xFF000000),
)

/**
 * 深色配色。
 *
 * XML 里没有对应的深色版（`values/themes.xml` 的 `Theme.JMComicH.Dark` 只是
 * `android:Theme.Material.NoActionBar` 的壳，Compose 读不到），所以这里只把
 * 「白卡」换成「深灰卡」并保证文字可读，其余槽位沿用 M3 深色基准值。
 *
 * 计划 §外观（docs/android-app-plan.md）要把主题做成设置项（跟随系统 / 浅色 / 深色），
 * 那一步再把这套值补全，同时要改 XML 侧（`values-night/themes.xml` + Manifest）
 * 和 `enableEdgeToEdge()` 的系统栏明暗，不能只改这里。当前 [JmComicTheme] 恒走浅色，
 * 所以这套值暂时不会被用到，保留是为了让结构符合标准、后续直接接开关。
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF00315F),

    background = CardWhiteDarkBackground,
    onBackground = Color(0xFFE3E3E3),

    surface = CardWhiteDarkSurface,
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = CardWhiteDarkSurface,
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLowest = CardWhiteDarkSurface,
    surfaceContainerLow = CardWhiteDarkSurface,
    surfaceContainer = CardWhiteDarkSurface,
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
)

/**
 * App 主题入口（Compose 标准写法：一个 ColorScheme + 一个开关）。
 *
 * @param dynamicColor 动态取色（Monet，Android 12+ 从壁纸取色）。
 *   **默认 false**：因为 [LightColorScheme] 是固定的品牌配色，开了动态取色会被
 *   壁纸色整体顶掉。要恢复"跟随壁纸"，把这里传 true（或从设置里接）。
 * @param darkTheme 深浅色开关。**当前恒为 false（只走浅色）**，理由见 [DarkColorScheme]：
 *   Manifest 里 Activity 用的是 XML 的 `Theme.JMComicH.CardWhite`（浅色、`windowLightStatusBar`），
 *   系统一旦进入深色模式，就不再符合 [LightColorScheme] 的前提。此时若 Compose 单独切深色，
 *   `enableEdgeToEdge()` 会按系统深色模式把状态栏图标画成浅色，而白色顶栏之上就是浅色图标
 *   —— 直接看不清。所以深浅色必须和 XML 侧一起改，这一步还没做。
 */
@Composable
fun JmComicTheme(
    dynamicColor: Boolean = false,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
