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
 * 眼睛图标（详情页的观看数）。
 *
 * 外圈是上下两段三次贝塞尔围成的眼型，中间用**奇偶填充**挖出虹膜、
 * 再填一颗实心瞳孔——同一条 path 里三层嵌套正好是"实 → 空 → 实"。
 */
val EyeIcon: ImageVector = ImageVector.Builder(
    name = "Eye",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // 眼型：上眼皮 (2,12) → 顶点 (12,6.8) → (22,12)，下眼皮对称回来
        moveTo(2f, 12f)
        curveTo(7.33f, 5.07f, 16.67f, 5.07f, 22f, 12f)
        curveTo(16.67f, 18.93f, 7.33f, 18.93f, 2f, 12f)
        close()
        // 虹膜（挖空）：半径 4.5 的圆，用四段贝塞尔近似
        moveTo(12f, 7.5f)
        curveTo(14.49f, 7.5f, 16.5f, 9.51f, 16.5f, 12f)
        curveTo(16.5f, 14.49f, 14.49f, 16.5f, 12f, 16.5f)
        curveTo(9.51f, 16.5f, 7.5f, 14.49f, 7.5f, 12f)
        curveTo(7.5f, 9.51f, 9.51f, 7.5f, 12f, 7.5f)
        close()
        // 瞳孔（再填实）：半径 2.5 的圆
        moveTo(12f, 9.5f)
        curveTo(13.38f, 9.5f, 14.5f, 10.62f, 14.5f, 12f)
        curveTo(14.5f, 13.38f, 13.38f, 14.5f, 12f, 14.5f)
        curveTo(10.62f, 14.5f, 9.5f, 13.38f, 9.5f, 12f)
        curveTo(9.5f, 10.62f, 10.62f, 9.5f, 12f, 9.5f)
        close()
    }
}.build()
