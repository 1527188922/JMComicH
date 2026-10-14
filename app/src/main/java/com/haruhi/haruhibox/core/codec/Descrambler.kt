package com.haruhi.haruhibox.core.codec

/**
 * JM 图片解扰的**块布局计算**。
 *
 * 单独抽成纯 Kotlin（不依赖 android.graphics）是为了：
 * 1. 能在普通 JVM 单元测试里与参考实现逐块对比，不需要模拟器；
 * 2. 阅读器/导出流程可以复用同一份块边界。
 *
 * 语义完全对齐参考实现 `JmcomicService.stitchImg`（第 1103～1164 行）：
 * 图按**垂直方向**切成 [blockNum] 块，第 0 块吸收 `height % blockNum` 的余数，
 * 然后**块顺序倒置**写回。
 */
object Descrambler {

    /**
     * 一块的搬运描述：把源图 `[0, srcY)` 起的 `height` 行整行搬到目标图 `[0, dstY)`。
     */
    data class BlockMove(val srcY: Int, val dstY: Int, val height: Int)

    /**
     * 计算 [blockNum] 块时的搬运计划。
     *
     * 参考实现原文：
     * ```
     * int remainderHeight = height % blockNum;
     * int blockHeight = height / blockNum;              // 每次循环内重新计算，值恒定
     * if (i == 0) blockHeight += remainderHeight;       // 第 0 块吸收余数
     * int srcYStart = (i == 0) ? height - blockHeight
     *                          : height - blockHeight * (i + 1) - remainderHeight;
     * int dstYStart = (i == 0) ? 0 : blockHeight * i + remainderHeight;
     * ```
     *
     * @param height   图片高度（像素）
     * @param blockNum 解扰块数；`< 1` 时返回空计划（表示不需要解扰）
     */
    fun plan(height: Int, blockNum: Int): List<BlockMove> {
        if (blockNum < 1 || height <= 0) return emptyList()

        val remainderHeight = height % blockNum
        val baseBlockHeight = height / blockNum

        val moves = ArrayList<BlockMove>(blockNum)
        for (i in 0 until blockNum) {
            var blockHeight = baseBlockHeight
            if (i == 0) blockHeight += remainderHeight

            val srcY = if (i == 0) {
                height - blockHeight
            } else {
                height - blockHeight * (i + 1) - remainderHeight
            }
            val dstY = if (i == 0) 0 else blockHeight * i + remainderHeight

            moves += BlockMove(srcY, dstY, blockHeight)
        }
        return moves
    }
}
