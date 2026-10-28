package com.haruhi.jmcomic.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 配色原始色值。
 *
 * **这里只放"色值"，"槽位 → 色值"的映射统一在 [Theme.kt] 的两个 ColorScheme 里。**
 * 这是 Compose 的标准结构：色值用描述性命名（一个色值可能被多个槽位复用），
 * 语义角色交给 `lightColorScheme(...)` / `darkColorScheme(...)` 的具名参数表达。
 *
 * 为什么不直接写 `@color/xxx`：Compose 读不到 XML 主题属性，而且主题需要深浅两套
 * 加动态取色替换，走资源引用只会把两套色板拆到两个语言里。见 res/values/themes.xml 的注释。
 */

// ---------------------------------------------------------------------------
// 「灰底白卡」浅色主题
// —— 逐条对应 res/values/themes.xml 里 Theme.JMComicH.CardWhite 的属性
// ---------------------------------------------------------------------------

/** 底栏 / 顶栏 / 卡片：纯白。对应 XML 的 `colorPrimary`、`colorBackgroundFloating` */
val CardWhiteSurface = Color(0xFFFFFFFF)

/** 内容区底色：浅灰。对应 XML 的 `colorBackground` / `windowBackground` */
val CardWhiteBackground = Color(0xFFF5F5F5)

/** 强调色：按钮、选中、链接、开关。对应 XML 的 `colorAccent` */
val CardWhiteAccent = Color(0xFF3F8CFF)

/** 主文字。对应 XML 的 `textColorPrimary`（#DE000000，87% 黑） */
val CardWhiteOnSurface = Color(0xDE000000)

/** 次要文字 / 图标。对应 XML 的 `textColorSecondary`（#8A000000，54% 黑） */
val CardWhiteOnSurfaceVariant = Color(0x8A000000)

/**
 * 分隔线、描边。
 *
 * XML 里只给了 `colorControlNormal=#8A000000` 当"列表分隔线"，但那在 M3 里是
 * 前景色槽位（见 [Theme.kt] 的说明）。等价的浅灰描边取 #14000000
 * （M3 基准 dark 主题的 `outlineVariant` 也是这个值）。
 */
val CardWhiteOutlineVariant = Color(0x14000000)

/**
 * 「跟随主题」深色底。
 *
 * XML 里没有深色版（`Theme.JMComicH.Dark` 只是 `android:Theme.Material.NoActionBar`
 * 的壳，Compose 读不到），所以深色方案按 CardWhite 的结构反相取值，只保证"白卡"
 * 变成"深灰卡"、文字仍然可读。
 */
val CardWhiteDarkSurface = Color(0xFF1E1E1E)
val CardWhiteDarkBackground = Color(0xFF121212)

// ---------------------------------------------------------------------------
// 语义色：Material 3 没有对应槽位，留作扩展
// ---------------------------------------------------------------------------

/**
 * 警示橙（"会覆盖文件"这类提醒）。
 *
 * 主题开了动态取色时 `tertiary` 在真机上可能是粉的也可能是别的颜色，承载不了
 * "警告"这个语义，所以警示单独固定成橙色；深浅色各取一个值，保证在白底和黑底
 * 上都看得清。
 *
 * 按 Compose 标准，这类"M3 没有的角色"应当用 `staticCompositionLocalOf` 自建一个
 * 色板（例如 `LocalWarningColors`），而不是让调用点自己 `isSystemInDarkTheme()`
 * 二选一。当前调用点见 `AlbumDetailScreen.kt`，尚未收编；
 * 自建色板的写法已经在 [ExtendedColors]（`JmTheme.colors`）里跑通，收编时照着搬。
 */
val WarningOrangeLight = Color(0xFFB45309)
val WarningOrangeDark = Color(0xFFFFB74D)

// ---------------------------------------------------------------------------
// 封面主 tag 徽标：与官方 App 完全一致
// ---------------------------------------------------------------------------

/**
 * 封面右上角「主 tag」徽标的底色：**官方 App 的 `bg-og`**（`#FF7B00`）。
 *
 * 色值抄自官方 2.1.11 WebView 包（`static/css/main.css`：
 * `.bg-og{background-color:#ff7b00}`），官方列表卡片上的 tag 就是
 * `<div class="... bg-og text-white ...">` —— 橙色底 + 白字。
 * 它是**固定品牌色**，不跟随本工程深浅主题变化，所以单独放在这里而不是色板槽位。
 */
val CoverTagBackground = Color(0xFFFF7B00)

/** 封面主 tag 徽标的文字色：官方用 `text-white`，恒为纯白。 */
val CoverTagText = Color(0xFFFFFFFF)

// ---------------------------------------------------------------------------
// 「关于」页存储分段条：五类本地占用各一色
// ---------------------------------------------------------------------------

/**
 * 存储分段条的类型色（漫画图片 / 封面缓存 / 导出 ZIP / 导出 PDF / 阅读缓存）。
 *
 * 五段需要同时可区分，Material 色板的语义槽位只有三个主槽，所以按
 * [WarningOrangeLight] 的先例单独放原始色值。都选**白卡上可读的中明度**；
 * 深色主题当前未启用（见 `Theme.kt` 的说明），将来接开关时照
 * `WarningOrangeLight/Dark` 的先例补一组深色值、再按主题二选一。
 */
val StorageLibrary = Color(0xFF1E88E5) // 蓝：下载的漫画图片（通常是最大头）
val StorageCovers = Color(0xFFF9A825) // 琥珀：封面缓存
val StorageExportZip = Color(0xFF8E24AA) // 紫：导出 ZIP
val StorageExportPdf = Color(0xFFE53935) // 红：导出 PDF
val StorageOfficialZip = Color(0xFF43A047) // 绿：官方 ZIP
val StorageReaderCache = Color(0xFF00897B) // 青：阅读缓存
