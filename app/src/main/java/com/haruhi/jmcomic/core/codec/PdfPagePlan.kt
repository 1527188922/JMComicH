package com.haruhi.jmcomic.core.codec

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 一张图在 PDF 里占的**一页**。
 *
 * 坐标有两套：
 * - `page*` 是 PDF 页面坐标系（已经按 [PdfPagePlan.MAX_PAGE_SIDE] 缩放过）；
 * - `source*` 是原图的像素坐标（解码时用它裁切）。
 */
data class PdfPageSlice(
    val pageY: Int,
    val pageHeight: Int,
    val sourceY: Int,
    val sourceHeight: Int,
)

/** 一张图排进 PDF 的完整方案。 */
data class PdfImagePlan(
    /** 原图尺寸（解码时用）。 */
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** 每页的尺寸（缩放后）。 */
    val pageWidth: Int,
    val pageHeightPerSlice: Int,
    val slices: List<PdfPageSlice>,
)

/**
 * 计算"一张图要切成几页、每页取哪一段"。
 *
 * ## 为什么需要它
 *
 * PDF 规范里单页最大 14400×14400（200 英寸 @72dpi），而 JM 的长图经常超过这个高度。
 * 参考实现直接 `new PDRectangle(image.getWidth(), image.getHeight())`，**不处理这个上限**，
 * 超限的图在多数阅读器里会渲染异常甚至打不开。
 *
 * 本工程的处理是**按高度均匀切片**（计划 §7 把"按高度切片"列为需要单独确认的行为差异，
 * 这里选择它是因为它不会失败、阅读体验也正常；只有真的超限才会切）。
 * 宽度超限的情况更罕见，处理是按宽度等比缩小——缩放交给 PDF 的 `drawImage`，
 * 不需要在内存里重新采样一张大图。
 *
 * 切成 n 段时每段高度取 `i * scaledHeight / n`（整数运算），
 * 这样各段高度最多相差 1，不会出现"最后一段只有几像素"的碎页。
 */
object PdfPagePlan {

    /** PDF 单页单边上限：200 英寸 × 72dpi。 */
    const val MAX_PAGE_SIDE = 14_400

    /** 一张图最多切几页（防御性上限：再长的图也不至于切成上百页）。 */
    const val MAX_SLICES = 64

    fun plan(width: Int, height: Int): PdfImagePlan {
        require(width > 0 && height > 0) { "图片尺寸非法：${width}x$height" }

        // 只有宽度超限才整体缩小；高度靠切片解决
        val scale = if (width > MAX_PAGE_SIDE) MAX_PAGE_SIDE.toFloat() / width else 1f
        val pageWidth = maxOf(1, (width * scale).roundToInt())
        val scaledHeight = maxOf(1, (height * scale).roundToInt())

        val sliceCount = ceil(scaledHeight.toDouble() / MAX_PAGE_SIDE).toInt().coerceIn(1, MAX_SLICES)
        val slices = (0 until sliceCount).map { index ->
            val pageY = index * scaledHeight / sliceCount
            val pageBottom = (index + 1) * scaledHeight / sliceCount
            val sourceY = (pageY / scale).roundToInt().coerceIn(0, height - 1)
            val sourceBottom = (pageBottom / scale).roundToInt().coerceIn(sourceY + 1, height)
            PdfPageSlice(
                pageY = pageY,
                pageHeight = (pageBottom - pageY).coerceAtLeast(1),
                sourceY = sourceY,
                sourceHeight = (sourceBottom - sourceY).coerceAtLeast(1),
            )
        }

        return PdfImagePlan(
            sourceWidth = width,
            sourceHeight = height,
            pageWidth = pageWidth,
            pageHeightPerSlice = slices.firstOrNull()?.pageHeight ?: scaledHeight,
            slices = slices,
        )
    }
}
