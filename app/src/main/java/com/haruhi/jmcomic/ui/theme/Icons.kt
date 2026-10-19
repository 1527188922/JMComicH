package com.haruhi.jmcomic.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 工程**没有引 `material-icons-extended`**（理由见 `app/build.gradle.kts`：
 * 那个模块含几千个矢量图标），`material-icons-core` 里只有 50 个常用图标，
 * 所以"core 里没有、但本工程用得上"的图标在这里手画。
 *
 * 写法与 core 里的图标一致：24×24 视口、`fill = SolidColor(Color.Black)`
 * （**填什么色都会被 `Icon` 的 tint 覆盖**，颜色由调用点决定），
 * 每个形状单独一条子路径、笔画用 `close()` 闭合，所以不依赖描边支持。
 */

/**
 * 下载图标：竖线 + 向下的箭头 + 底部托盘（与 material-icons-extended 的
 * `Download` 同一几何：2014 Material Design 版，各段坐标取自该图标的路径数据）。
 *
 * 用于章节行菜单的「下载本话」。core 里最接近的是 `KeyboardArrowDown`，
 * 但它只有箭头、没有底部托盘，"往下翻"和"下载到本地"分不开，所以不比着它用。
 */
val DownloadIcon: ImageVector = ImageVector.Builder(
    name = "Download",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // 箭头竖线：宽 2、从 y=3 到 y=12
        moveTo(11f, 3f)
        lineTo(13f, 3f)
        lineTo(13f, 12f)
        lineTo(11f, 12f)
        close()
        // 箭头两翼 + 尖端：右翼 (18,12.59) → 尖 (12,17) → 左翼 (6,12.59)
        moveTo(18f, 12.59f)
        lineTo(12f, 17f)
        lineTo(6f, 12.59f)
        close()
        // 底部托盘：5,19 → 19,21
        moveTo(5f, 19f)
        lineTo(5f, 21f)
        lineTo(19f, 21f)
        lineTo(19f, 19f)
        close()
    }
}.build()

/**
 * 网格图标：2×2 的四个方块。
 *
 * 分类页的「列表 / 卡片」切换按钮用它（core 里没有 GridView / Apps 之类的图标，
 * 最近的 `List` 与 `Menu` 都是横向三行，跟"卡片"表达不出区别）。
 * 方块留 2 的间距，缩到 20dp 渲染时四个块也不会糊成一片。
 */
val GridViewIcon: ImageVector = ImageVector.Builder(
    name = "GridView",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // 左上
        moveTo(3f, 3f)
        lineTo(11f, 3f)
        lineTo(11f, 11f)
        lineTo(3f, 11f)
        close()
        // 右上
        moveTo(13f, 3f)
        lineTo(21f, 3f)
        lineTo(21f, 11f)
        lineTo(13f, 11f)
        close()
        // 左下
        moveTo(3f, 13f)
        lineTo(11f, 13f)
        lineTo(11f, 21f)
        lineTo(3f, 21f)
        close()
        // 右下
        moveTo(13f, 13f)
        lineTo(21f, 13f)
        lineTo(21f, 21f)
        lineTo(13f, 21f)
        close()
    }
}.build()

/**
 * 眼睛图标（详情页的观看数）。
 *
 * 外圈是上下两段三次贝塞尔围成的眼型，中间用**奇偶填充**挖出虹膜、
 * 再填一颗实心瞳孔——同一条 path 里三层嵌套正好是"实 → 空 → 实"。
 *
 * **画得比原始几何大 15%**：眼型本身是扁的，原始几何只占 24×24 视口的 20×10.4，
 * 而同尺寸的 `Icons.Default.Favorite`（爱心）占到 20×19——两者都放在
 * `StatItem` 里按 `size(14.dp)` 渲染时，眼睛看着明显小一号。
 * 所以把坐标按 **1.15** 放大（绕视口中心 (12,12) 缩放：`c' = 12 + (c - 12) × 1.15`），
 * 视口不动——图标该多大仍由调用点的 `size(...)` 决定，这里只调**图形在视口里的占比**。
 */
val EyeIcon: ImageVector = ImageVector.Builder(
    name = "Eye",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // 眼型：上眼皮 (0.5,12) → 顶点 (12,6.0) → (23.5,12)，下眼皮对称回来
        moveTo(0.5f, 12f)
        curveTo(6.73f, 4.03f, 17.27f, 4.03f, 23.5f, 12f)
        curveTo(17.27f, 19.97f, 6.73f, 19.97f, 0.5f, 12f)
        close()
        // 虹膜（挖空）：半径 5.175 的圆，用四段贝塞尔近似
        moveTo(12f, 6.825f)
        curveTo(14.86f, 6.825f, 17.175f, 9.14f, 17.175f, 12f)
        curveTo(17.175f, 14.86f, 14.86f, 17.175f, 12f, 17.175f)
        curveTo(9.14f, 17.175f, 6.825f, 14.86f, 6.825f, 12f)
        curveTo(6.825f, 9.14f, 9.14f, 6.825f, 12f, 6.825f)
        close()
        // 瞳孔（再填实）：半径 2.875 的圆
        moveTo(12f, 9.125f)
        curveTo(13.59f, 9.125f, 14.875f, 10.41f, 14.875f, 12f)
        curveTo(14.875f, 13.59f, 13.59f, 14.875f, 12f, 14.875f)
        curveTo(10.41f, 14.875f, 9.125f, 13.59f, 9.125f, 12f)
        curveTo(9.125f, 10.41f, 10.41f, 9.125f, 12f, 9.125f)
        close()
    }
}.build()
