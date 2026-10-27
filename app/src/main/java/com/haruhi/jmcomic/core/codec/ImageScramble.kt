package com.haruhi.jmcomic.core.codec

import com.haruhi.jmcomic.core.protocol.Crypto

/**
 * JM 图片解扰的**块数计算**（唯一入口）。
 *
 * 阅读器（显示时现场解扰）、ZIP / PDF 导出都从这里取块数，避免各写一份算法
 * 导致"同一条广告在阅读器里正常、导出的包里却是乱的"。
 *
 * 只对 `.webp` 解扰（参考实现 `downloadChapter` 第 948～954 行）：
 * `.gif` / `.jpg` 之类的旧漫画图从来没被切过。参数一律看**逻辑文件名**
 * （章节里的图片名），不能传 `.part` / `.descrambled` 这类物理名。
 */
object ImageScramble {

    /**
     * 这张图的解扰块数（0 = 不需要解扰）。
     *
     * @param scrambleId 章节的 `scramble_id`（由 `/comic_read` 得到并随图片清单入库）
     * @param chapterId  章节 id
     * @param fileName   逻辑文件名（如 `00001.webp`）
     */
    fun blockNumOf(scrambleId: Long, chapterId: Long, fileName: String): Int {
        if (fileName.substringAfterLast('.', "").lowercase() != "webp") return 0
        return Crypto.calculateBlockNum(
            scrambleId = scrambleId,
            chapterId = chapterId,
            filenameWithoutExt = fileName.substringBeforeLast('.'),
        )
    }
}
