package com.haruhi.jmcomic.core.storage

/**
 * `library/` 里一份图片文件的**存储形态**。
 *
 * 2026-10 之后的版本把"下载"改成**只存站点原始字节**（加扰、未解码、未重编码），
 * 阅读 / ZIP / PDF 用到时再现场解扰。但升级用户 `library/` 里可能还留着旧版本产出的
 * **已解扰成品**——两者都是合法的 webp/jpg，单看文件内容分不出来，所以必须把形态
 * 随图片清单一起记下来（`t_jm_chapter_image.storage_encoding`）。
 *
 * - [DESCRAMBLED]：旧版本解扰后的成品（或 0 块、非 webp 的原始字节），**直接用**；
 * - [RAW]：新版本下载的站点原始字节，**用前必须解扰**。
 *
 * 默认值是 [DESCRAMBLED]：升级前的行都没有这个字段，按旧成品处理；新下载写 [RAW]。
 */
object ImageStorage {

    /** 旧版本解扰后的成品（不需要再解扰）。 */
    const val DESCRAMBLED = 0

    /** 站点原始字节（需要解扰）。 */
    const val RAW = 1

    fun isRaw(value: Int?): Boolean = value == RAW
}
