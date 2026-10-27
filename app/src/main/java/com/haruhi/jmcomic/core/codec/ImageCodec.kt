package com.haruhi.jmcomic.core.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import timber.log.Timber
import java.io.File

/** 图片基本元信息。 */
data class ImageMeta(
    val width: Int,
    val height: Int,
    val format: OutFormat,
)

/** 输出格式。解扰后重新编码时用。 */
enum class OutFormat(val extension: String, val mime: String) {
    /**
     * 有损 WebP。对应 `Bitmap.CompressFormat.WEBP` + 质量 < 100。
     * 注意 API 30 之前只有 `CompressFormat.WEBP`（有损），`WEBP_LOSSY`/`WEBP_LOSSLESS`
     * 两个常量是 API 30 才加的——本工程 minSdk 26，所以统一用 `WEBP`，
     * 无损与否由质量参数决定（100 = 无损）。
     */
    WEBP_LOSSY("webp", "image/webp"),

    /** 无损 WebP（`CompressFormat.WEBP` + 质量 100）。解扰后落盘用这个。 */
    WEBP_LOSSLESS("webp", "image/webp"),

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

/**
 * Android 图片处理门面骨架（计划 §3.2 的 `ImageCodec`）。
 *
 * 计划要求把桌面 JVM 的 `ImageIO`/AWT 换掉。这里给出最关键的三个能力，
 * 并把**内存策略**写死在实现里（§8「阅读器」与 §D.2 第 1 条）：
 *
 * - 解码统一用 `ARGB_8888`：JM 的解扰算法是整行 ARGB 拷贝，
 *   只有 ARGB_8888 能保证与参考实现**逐像素一致**；
 *   `RGB_565` 会掉色，只能用于阅读预览这类允许有损的场景。
 * - 长图（JM 常见 800×12000）必须评估内存：解码前先只读边界
 *   （[readMeta] 的 `inJustDecodeBounds`），调用方据此决定是否串行/分块。
 *
 * **本类目前只实现"元信息 / 解扰落盘 / 转 JPEG"三件事**；
 * 阅读器需要的区域解码（`BitmapRegionDecoder`）、缩略图与采样解码在 P3 补，
 * 因为它们的参数（目标尺寸、是否分块）要到实际页面才知道。
 */
object ImageCodec {

    /** 只读图片尺寸与格式，不分配像素内存（`inJustDecodeBounds`）。 */
    fun readMeta(file: File): ImageMeta {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val format = when (options.outMimeType?.lowercase()) {
            "image/webp" -> OutFormat.WEBP_LOSSLESS
            "image/jpeg" -> OutFormat.JPEG
            "image/png" -> OutFormat.PNG
            else -> OutFormat.WEBP_LOSSLESS
        }
        return ImageMeta(options.outWidth, options.outHeight, format)
    }

    /**
     * 解码 → 按 [mode] 重新编码成 WebP 字节（**不解扰**）。
     *
     * 用于 ZIP 里"本来就是普通图、但用户选了有损"的情况（旧版本留存的解扰成品、
     * 未加扰章节的 webp）：统一把体积压到官方量级。`blockNum > 0` 的原始字节请用
     * [descrambleToBytes]，那条路会多一步搬块。
     */
    fun reencodeToBytes(file: File, mode: ImageEncodeMode): ByteArray {
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("无法解码图片：${file.absolutePath}")
        return try {
            val out = java.io.ByteArrayOutputStream()
            val quality = when (mode) {
                ImageEncodeMode.LOSSY -> LOSSY_WEBP_QUALITY
                ImageEncodeMode.LOSSLESS -> QUALITY_LOSSLESS
            }
            check(source.compress(Bitmap.CompressFormat.WEBP, quality, out)) {
                "图片编码失败：${file.absolutePath}"
            }
            out.toByteArray()
        } finally {
            source.recycle()
        }
    }

    /**
     * 解码 → 按 [blockNum] 解扰 → 以 [mode] 重新编码成 WebP 字节（不写盘）。
     *
     * ZIP 导出用：`library/` 里是加扰的站点原始字节，包内必须是能直接看的普通图片，
     * 所以在打包时现场解扰并编码。**一次只处理一张图**，调用方负责逐张回收，
     * 不要把所有结果攒在内存里。
     *
     * `blockNum < 1` 时**不重新编码**：调用方应直接把原文件流进 ZIP，保持字节不变。
     *
     * @throws Exception 解码 / 编码失败；导出任务应放弃整个产物，不能把半成品交付出去
     */
    fun descrambleToBytes(file: File, blockNum: Int, mode: ImageEncodeMode): ByteArray {
        require(blockNum >= 1) { "blockNum < 1 时不应重新编码" }
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("无法解码图片：${file.absolutePath}")

        val processed = try {
            descramble(source, blockNum)
        } catch (e: Throwable) {
            source.recycle()
            throw e
        }

        return try {
            val out = java.io.ByteArrayOutputStream()
            val quality = when (mode) {
                ImageEncodeMode.LOSSY -> LOSSY_WEBP_QUALITY
                ImageEncodeMode.LOSSLESS -> QUALITY_LOSSLESS
            }
            check(processed.compress(Bitmap.CompressFormat.WEBP, quality, out)) {
                "图片编码失败：${file.absolutePath}"
            }
            out.toByteArray()
        } finally {
            processed.recycle()
            source.recycle()
        }
    }

    /**
     * 解扰：把图按垂直方向分成 [blockNum] 块，第 0 块吸收 `height % blockNum` 的余数，
     * 然后块顺序倒置写回。
     *
     * 搬运用**逐行** `getPixels/setPixels` 而不是 `Canvas.drawBitmap`：
     * - 语义与参考实现的 `Raster.getDataElements/setDataElements` 一一对应（都是 ARGB int 拷贝）；
     * - 不经过 Skia 采样，不会引入颜色空间转换；
     * - 单元测试（Robolectric）与真机行为一致（`Canvas` 在 Robolectric 下是空实现，
     *   而 `setPixels` 对多行矩形只写第一行，所以必须一行一行来——见 `docs/p0/README.md`）。
     */
    fun descramble(source: Bitmap, blockNum: Int): Bitmap {
        require(blockNum >= 1) { "blockNum 必须 >= 1" }
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val target = Bitmap.createBitmap(source.width, source.height, config)
        val row = IntArray(source.width)
        for (move in Descrambler.plan(source.height, blockNum)) {
            for (offset in 0 until move.height) {
                source.getPixels(row, 0, source.width, 0, move.srcY + offset, source.width, 1)
                target.setPixels(row, 0, source.width, 0, move.dstY + offset, source.width, 1)
            }
        }
        return target
    }

    /**
     * 解码原图（可降采样）→ 解扰 → 返回**可直接显示**的位图。
     *
     * 这是阅读器的显示端：`library/` 里是站点原始字节（或旧版成品），
     * 这里**不写盘、不重编码**，只做一次解码 + 若干次块搬移，所以比
     * "解码→逐行拷贝→无损 WEBP 重编码→写盘→Coil 再解码"少掉一大半工作。
     *
     * 与 [descramble] 的差别：
     * - 走 `Canvas.drawBitmap(src, srcRect, dstRect)` 的块搬移，而不是逐行
     *   `getPixels/setPixels`（后者每行两次 JNI 往返，长图要上千次）；
     * - 解码时可以**按显示宽度降采样**（[decodeSampleSize]），长图不会整张解进内存；
     * - 结果直接给 Compose 的 `Image` 用，不再落盘，也不需要第二次解码。
     *
     * 块布局仍由 [Descrambler.plan] 给出（与旧成品、导出逐块一致），
     * 只是坐标落在**解码后**的位图上（等比，共 1px 之内的误差）。
     *
     * @param blockNum 0 / 负数表示不需要解扰（旧版成品、未加扰章节），直接返回解码结果
     * @param targetWidthPx 显示宽度（像素）。用于算降采样倍数
     * @param maxPixels 单张位图的像素上限，超过就继续降采样（防长图 OOM）
     */
    fun descrambleToBitmap(
        file: File,
        blockNum: Int,
        targetWidthPx: Int,
        maxPixels: Int = MAX_DISPLAY_PIXELS,
    ): Bitmap = try {
        decodeAndDescramble(file, blockNum, targetWidthPx, maxPixels)
    } catch (e: OutOfMemoryError) {
        // 极端长条 + 低内存机：先把预算砍到 1/4 再试一次。
        // 宁可这一页糊一点，也不要整个阅读器崩掉（导出那边也是同样的取舍）。
        Timber.tag("jm").w("解扰解码内存不足，降采样重试：%s", file.name)
        decodeAndDescramble(file, blockNum, targetWidthPx, (maxPixels / 4).coerceAtLeast(1))
    }

    /** [descrambleToBitmap] 的实现体（拆出来只为了 OOM 时能整体重试一次）。 */
    private fun decodeAndDescramble(
        file: File,
        blockNum: Int,
        targetWidthPx: Int,
        maxPixels: Int,
    ): Bitmap {
        val meta = readMeta(file)
        val sample = decodeSampleSize(meta.width, meta.height, targetWidthPx, maxPixels)
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("无法解码图片：${file.absolutePath}")

        if (blockNum < 1) return source

        val moves = Descrambler.plan(source.height, blockNum)
        if (moves.isEmpty()) return source

        val target = Bitmap.createBitmap(
            source.width,
            source.height,
            source.config ?: Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(target)
            val paint = Paint().apply {
                // 只有真的降采样过才需要插值；1:1 搬运必须**逐像素**原样拷
                isFilterBitmap = sample > 1
                isAntiAlias = false
            }
            val src = Rect()
            val dst = Rect()
            moves.forEachIndexed { index, move ->
                src.set(0, move.srcY, source.width, move.srcY + move.height)
                // 缩放搬运时块与块之间会因取整露出 1px 缝：非最后一块往下多垫 1px 盖住
                // （官方 `onImageLoaded` 的做法，见文档 §8.3）
                val pad = if (sample > 1 && index < moves.lastIndex) 1 else 0
                dst.set(0, move.dstY, source.width, (move.dstY + move.height + pad).coerceAtMost(source.height))
                canvas.drawBitmap(source, src, dst, paint)
            }
        } catch (e: Throwable) {
            target.recycle()
            throw e
        } finally {
            source.recycle()
        }
        return target
    }

    /**
     * 解码降采样倍数（2 的幂，与 `BitmapFactory.inSampleSize` 的语义一致）。
     *
     * 两条约束：
     * 1. **不要解得比屏幕还宽**：`width / sample` 保持 ≥ 显示宽度，避免把 3000px 宽的图整张解出来；
     * 2. **像素总数封顶** [maxPixels]：长条页（800×12000 = 9.6 MP）不会被降得太狠，
     *    但超过上限（例如 800×20000）就继续折半，防止一张图吃掉几十 MB。
     *
     * 纯函数，便于单测（不依赖 android.graphics）。
     */
    fun decodeSampleSize(
        width: Int,
        height: Int,
        targetWidthPx: Int,
        maxPixels: Int = MAX_DISPLAY_PIXELS,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        val target = if (targetWidthPx > 0) targetWidthPx else width
        var sample = 1
        while (width / (sample * 2) >= target) sample *= 2
        while (maxPixels > 0 && (width / sample).toLong() * (height / sample).toLong() > maxPixels) {
            sample *= 2
        }
        return sample
    }

    /**
     * 转成 JPEG 字节（PDF 内嵌用：**PDF 不支持 WebP**）。
     *
     * 有损转换会掉画质，所以默认质量取 90（计划 §7 的约束）。
     * 注意：这条路只在"需要压缩体积"时用；`LosslessFactory` 无损内嵌不经过这里。
     */
    fun toJpeg(file: File, quality: Int = 90): ByteArray {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("无法解码图片：${file.absolutePath}")
        return try {
            val out = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                "JPEG 编码失败：${file.absolutePath}"
            }
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** 估算 [width]×[height] 的 ARGB_8888 位图占用字节数，用于并发与分块决策。 */
    fun estimateArgb8888Bytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 4L

    /** JM 图片是否可能是"超长图"（阅读器需要按高度分块或降采样）。 */
    fun isVeryTallImage(width: Int, height: Int): Boolean =
        height > TALL_IMAGE_THRESHOLD || estimateArgb8888Bytes(width, height) > TALL_IMAGE_BYTES

    /** 无损 WebP / PNG 编码质量。 */
    private const val QUALITY_LOSSLESS = 100

    /**
     * 有损 WebP 的质量（导出 ZIP 用）。
     *
     * **实测对齐官方下载包**：36 页样本（960×1440）用 libwebp q85 重压总量
     * 4,141,144 B，官方包 4,079,390 B，差 1.5%；q80 差 -10.4%，q90 差 +21.6%。
     * 所以默认有损档取 85。
     */
    const val LOSSY_WEBP_QUALITY = 85

    /** 有损 PDF 的 JPEG 质量：JPEG 对线稿的伪影更明显，取 90。 */
    const val LOSSY_JPEG_QUALITY = 90

    /**
     * 显示端单张位图的像素上限（阅读器降采样依据）。
     *
     * 12 MP ≈ 常见长条页（800×12000 = 9.6 MP）的尺寸，超过才继续折半：
     * 再往上（800×20000）ARGB_8888 就是 64 MB 一张，阅读器不该扛这个。
     */
    const val MAX_DISPLAY_PIXELS = 12_000_000

    /** 超过这个高度或内存占用就算长图（JM 常见 800×12000 ≈ 36MB）。 */
    private const val TALL_IMAGE_THRESHOLD = 6_000
    private const val TALL_IMAGE_BYTES = 24L * 1024 * 1024
}
