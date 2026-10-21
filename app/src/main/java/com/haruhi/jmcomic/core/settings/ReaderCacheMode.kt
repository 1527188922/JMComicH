package com.haruhi.jmcomic.core.settings

/**
 * 阅读器**取图管线**（设置页「阅读与显示 → 图片缓存方式」）。
 *
 * 同一个"看图"动作有两套完全不同的实现，用户可随时切换，切换后对**新取的页**立即生效
 * （已经在屏幕上的页不重建）：
 *
 * | | [DESCRAMBLED]（旧，2026-10 之前的行为） | [RAW]（默认，本轮的方案） |
 * |---|---|---|
 * | 落地内容 | **解扰后的成品图**（解码 → 搬块 → **无损 WEBP 重编码**） | **站点原始字节**（65 KB 级 webp，原样保存） |
 * | 存放位置 | `files/library/<本子>/<章节>/`（与"下载"共用，删本即删） | `files/reader-cache/<aid>/<cid>/`（纯缓存，可整体清理） |
 * | 显示 | `AsyncImage(model = File)`（Coil 再解码一次） | 就地解码 + `Canvas` 搬块 → `Image(bitmap)` |
 * | 单页本地成本 | 解码 + 逐行拷贝 + **无损编码** + 写盘 + 再解码 | 采样解码 + 搬块（无编码、无写盘、无第二次解码） |
 * | 首屏观感 | 每页都要等那 0.3～1.5 s 的编码 | 只需等字节到齐 + 一次解码 |
 *
 * 为什么默认是 [RAW]：上一轮的实测（`docs/PROGRESS.md`「阅读器取图管线」）表明，
 * 旧方案慢的主因不是图片线路（一页只有 65 KB），而是把源图**无损重编码**（体积 ×7.8、
 * 耗时 ×4）并写盘，再让 Coil 解第二次。旧方案完整保留，切回 [DESCRAMBLED] 即回到原行为。
 *
 * 注意：**"下载本话"永远走旧方案的成品落盘**，与这个设置无关——下载出来的东西要能
 * 直接给 ZIP/PDF 导出用，必须是解扰好的成品。
 */
enum class ReaderCacheMode(val label: String) {
    /** 原图缓存：只存站点原始字节，显示时就地解扰（默认）。 */
    RAW("使用缓存"),

    /** 解扰后落盘：下完就地解扰 + 无损重编码成成品（旧行为，可与下载共用文件）。 */
    DESCRAMBLED("直接下载"),

    ;

    companion object {
        /** 没设置过 / 认不出的值都按这个来。 */
        val DEFAULT = RAW

        fun fromCode(code: Int?): ReaderCacheMode = when (code) {
            SettingDefaults.Jm.READER_CACHE_MODE_DESCRAMBLED -> DESCRAMBLED
            else -> RAW
        }
    }

    fun toCode(): Int = when (this) {
        RAW -> SettingDefaults.Jm.READER_CACHE_MODE_RAW
        DESCRAMBLED -> SettingDefaults.Jm.READER_CACHE_MODE_DESCRAMBLED
    }
}
