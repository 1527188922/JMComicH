package com.haruhi.jmcomic.core.codec

import java.io.File

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

/**
 * 导出时真正要打的章节目录：**只打应用认识的章节**。
 *
 * `chapterPlans` 按章节目录名索引，且覆盖"章节列表里的每一话"（`ExportManager.buildChapterPlans`
 * 对没有清单的话也会放一条空计划）。所以拿它的 key 当白名单，可以滤掉磁盘上那些
 * **已经不属于任何一话的遗留目录**，最典型的就是标题重名被唯一化之前的共享目录
 * （例：两话曾经共用 `第1话/`，现在两话各用 `第1话-<chapterId>/`，老目录只剩残字节）。
 *
 * 不过滤的话，那批字节会被当成"额外一章"打进 zip / pdf，而且因为没有对应的解扰计划，
 * 加扰图会原样出现在成品里。
 *
 * `chapterPlans` 为空表示"没有计划数据"（老调用 / 单元测试），此时保持老行为：
 * 目录里有几话就打几话。
 */
fun exportChapterDirs(
    chapterDirs: List<File>,
    chapterPlans: Map<String, ChapterImagePlan>,
): List<File> = if (chapterPlans.isEmpty()) chapterDirs else chapterDirs.filter { it.name in chapterPlans }
