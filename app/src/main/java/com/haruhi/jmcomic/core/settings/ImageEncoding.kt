package com.haruhi.jmcomic.core.settings

import com.haruhi.jmcomic.core.codec.ImageEncodeMode

/**
 * 导出时"解扰后重新编码"的方式（设置页「导出 → 导出图片压缩」）。
 *
 * 2026-10 起下载只保存站点原始字节，ZIP / PDF 生成时才解扰；解扰后的像素要不要
 * 再无损保存，由这一项决定：
 *
 * - [LOSSY]（默认）：WebP q85 / JPEG q90。实测官方 36 页样本用 libwebp q85 重压
 *   总量 4,141,144 B，官方包 4,079,390 B，差 1.5%；视觉几乎无差。
 * - [LOSSLESS]：WebP q100 / PDF Flate。像素完全不变，但 ZIP 体积约是官方的 9.2 倍。
 *
 * **只影响导出**；阅读器现场解扰只在内存里，不落盘、不重新编码。
 */
enum class ImageEncoding(val label: String) {
    /** 有损（体积小，接近官方下载包）。 */
    LOSSY("有损压缩"),

    /** 无损（像素完全不变，体积大）。 */
    LOSSLESS("无损压缩"),

    ;

    fun toCode(): Int = when (this) {
        LOSSY -> SettingDefaults.Jm.IMAGE_ENCODING_LOSSY
        LOSSLESS -> SettingDefaults.Jm.IMAGE_ENCODING_LOSSLESS
    }

    /** 给 ZIP / PDF 写入器用的编码方式。 */
    fun toEncodeMode(): ImageEncodeMode = when (this) {
        LOSSY -> ImageEncodeMode.LOSSY
        LOSSLESS -> ImageEncodeMode.LOSSLESS
    }

    companion object {
        /** 没设置过 / 认不出的值都按有损来（用户要求默认有损）。 */
        val DEFAULT = LOSSY

        fun fromCode(code: Int?): ImageEncoding = when (code) {
            SettingDefaults.Jm.IMAGE_ENCODING_LOSSLESS -> LOSSLESS
            else -> LOSSY
        }
    }
}
