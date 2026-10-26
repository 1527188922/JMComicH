package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 平板外壳（侧栏 / 常驻抽屉）判定的短边阈值。 */
internal val TABLET_MIN_SHORT_SIDE: Dp = 600.dp

/** 阅读型页面的内容列最大宽度；手机窗口比它窄，等同于铺满。 */
internal val CONTENT_MAX_WIDTH: Dp = 840.dp

/** 分类页常驻抽屉的触发宽度与抽屉自身宽度。 */
internal val PERMANENT_DRAWER_MIN_WIDTH: Dp = 840.dp
internal val PERMANENT_DRAWER_WIDTH: Dp = 320.dp

/** 当前窗口宽度（dp）。分屏/小窗下按实际窗口算，不读整块屏幕宽度。 */
@Composable
internal fun windowWidthDp(): Dp {
    val density = LocalDensity.current
    return with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
}

/**
 * 是否使用平板外壳（侧栏、常驻抽屉）。
 *
 * 用窗口的**短边**判断而不是宽度：手机横屏宽度也可能超过 600dp，
 * 但短边仍只有 300~400dp，不该被当成平板（否则底栏会突然变成侧栏）。
 */
@Composable
internal fun isWideWindow(): Boolean {
    val density = LocalDensity.current
    val size = LocalWindowInfo.current.containerSize
    return with(density) {
        minOf(size.width, size.height).toDp() >= TABLET_MIN_SHORT_SIDE
    }
}

/**
 * 网格列数：按**实际可用宽度**切列，最少 [minColumns] 列。
 *
 * 手机 360dp 减去两侧 [GRID_CONTENT_PADDING] 后仍落在 3 列，观感与改动前一致；
 * 平板按约 140dp 一张卡往上加列。
 */
internal fun adaptiveGridColumns(
    availableWidth: Dp,
    minColumns: Int = 3,
    minCardWidth: Dp = 140.dp,
): Int {
    val usable = availableWidth - GRID_CONTENT_PADDING - GRID_CONTENT_PADDING
    return (usable / minCardWidth).toInt().coerceAtLeast(minColumns)
}

/**
 * 阅读型页面的内容列：最大 [maxWidth]、水平居中、高度占满。
 *
 * 窗口比 [maxWidth] 窄时（手机）与直接铺满完全一致。
 */
@Composable
internal fun CenteredContent(
    maxWidth: Dp = CONTENT_MAX_WIDTH,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxHeight(),
        ) {
            content()
        }
    }
}
