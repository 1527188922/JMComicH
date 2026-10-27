package com.haruhi.jmcomic.core.codec

/**
 * 导出某一话时需要的解扰信息（`ExportManager` 从数据库查好后交给 ZIP / PDF 写入器）。
 *
 * @param chapterId 章节 id
 * @param scrambleId 章节的 `scramble_id`；[rawFiles] 为空时可以是 0
 * @param rawFiles **这一话里哪些文件是站点原始字节**（需要现场解扰）。
 *        旧版本留下的解扰成品不在这个集合里，直接使用。
 */
data class ChapterImagePlan(
    val chapterId: Long,
    val scrambleId: Long,
    val rawFiles: Set<String>,
)
