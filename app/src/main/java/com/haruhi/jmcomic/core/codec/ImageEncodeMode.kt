package com.haruhi.jmcomic.core.codec

/**
 * 解扰后**重新编码**的方式（ZIP 包内图片 / PDF 内嵌图片）。
 *
 * 只影响导出产物，不影响 `library/` 里下载保存的站点原始字节。
 * 默认用 [LOSSY]：与官方下载包的体积基本一致（实测 36 页样本约 9.2× 无损体积，
 * 官方 WebP 与 libwebp q85 的总量相差不到 1.5%）。
 */
enum class ImageEncodeMode {
    /** 有损 WebP / JPEG：体积小，肉眼几乎无差，但像素会变。 */
    LOSSY,

    /** 无损 WebP / PDF Flate：像素完全不变，体积大约是官方包的 9 倍。 */
    LOSSLESS,
}
