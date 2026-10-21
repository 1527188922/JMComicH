package com.haruhi.jmcomic.core.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

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
     * 解码 → 按 [blockNum] 解扰 → 写回 [file]。
     *
     * 语义严格对齐参考实现 `saveImg` + `stitchImg` + `copyImageBlock`：
     * 整行 ARGB 拷贝，块顺序倒置。
     *
     * **与参考实现的有意差异**（计划 §0 已确认的决策）：
     * 参考实现**无论是否解扰都重新编码成 webp**；
     * 本 App 在 [blockNum] == 0（或 `<= 1`）时**直接把原文件落盘，不重新编码**。
     * 所以调用方在 `blockNum == 0` 时**不应该调用本方法**，
     * 而应直接保留下载下来的原文件（[ImageDownloadTransform] 已经这样做了）。
     *
     * @return true 表示做了实际处理；false 表示无需处理（调用方应保留原文件）
     */
    fun descrambleInPlace(file: File, blockNum: Int, format: OutFormat = OutFormat.WEBP_LOSSLESS): Boolean {
        if (blockNum < 1) return false

        val source = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("无法解码图片：${file.absolutePath}")

        val processed = try {
            descramble(source, blockNum)
        } finally {
            source.recycle()
        }

        val temp = File(file.parentFile, file.name + ".descrambled")
        try {
            FileOutputStream(temp).use { out ->
                val ok = processed.compress(format.toCompressFormat(), format.qualityHint(), out)
                check(ok) { "图片编码失败：${file.absolutePath}" }
            }
            processed.recycle()
            check(temp.renameTo(file) || temp.copyTo(file, overwrite = true).let { temp.delete(); true }) {
                "替换解扰结果失败：${file.absolutePath}"
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return true
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
     * 这是"原图缓存"取图管线（`ReaderCacheMode.RAW`）的显示端：**不写盘、不重编码**，
     * 只做一次解码 + 若干次块搬移，所以比旧管线（解码→逐行拷贝→无损 WEBP 重编码→写盘→
     * Coil 再解码）少掉一大半工作。
     *
     * 与 [descramble] 的差别：
     * - 走 `Canvas.drawBitmap(src, srcRect, dstRect)` 的块搬移，而不是逐行
     *   `getPixels/setPixels`（后者每行两次 JNI 往返，长图要上千次）；
     * - 解码时可以**按显示宽度降采样**（[decodeSampleSize]），长图不会整张解进内存；
     * - 结果直接给 Compose 的 `Image` 用，不再落盘，也不需要第二次解码。
     *
     * 块布局仍由 [Descrambler.plan] 给出（与旧管线、导出、下载逐块一致），
     * 只是坐标落在**解码后**的位图上（等比，共 1px 之内的误差）。
     *
     * @param blockNum 0 / 负数表示不需要解扰，直接返回解码结果
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

    /** 参考实现会重新编码成 webp 且质量未知；这里用无损以对齐"逐像素一致"的验收要求。 */
    private const val QUALITY_LOSSLESS = 100

    /**
     * 显示端单张位图的像素上限（"原图缓存"管线的降采样依据）。
     *
     * 12 MP ≈ 常见长条页（800×12000 = 9.6 MP）的尺寸，超过才继续折半：
     * 再往上（800×20000）ARGB_8888 就是 64 MB 一张，阅读器不该扛这个。
     */
    const val MAX_DISPLAY_PIXELS = 12_000_000

    /** 超过这个高度或内存占用就算长图（JM 常见 800×12000 ≈ 36MB）。 */
    private const val TALL_IMAGE_THRESHOLD = 6_000
    private const val TALL_IMAGE_BYTES = 24L * 1024 * 1024

    private fun OutFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
        // minSdk 26：只能引用 API 30 之前就存在的 CompressFormat.WEBP。
        // 质量 100 时 Android 走无损编码，等价于 API 30+ 的 WEBP_LOSSLESS。
        OutFormat.WEBP_LOSSY, OutFormat.WEBP_LOSSLESS -> Bitmap.CompressFormat.WEBP
        OutFormat.JPEG -> Bitmap.CompressFormat.JPEG
        OutFormat.PNG -> Bitmap.CompressFormat.PNG
    }

    /** 该格式是否应该用无损质量编码。 */
    private fun OutFormat.qualityHint(): Int = when (this) {
        OutFormat.WEBP_LOSSY -> 80
        OutFormat.WEBP_LOSSLESS -> QUALITY_LOSSLESS
        OutFormat.JPEG -> 90
        OutFormat.PNG -> QUALITY_LOSSLESS
    }
}

/**
 * 给下载引擎用的"图片**发布前**处理"钩子（JM 解扰）。
 *
 * 计划 §0 已确认的决策：**`blockNum == 0` 时直接落原图，不重新编码**；
 * 只有需要解扰（`blockNum > 0`）才 解码→解扰→编码。
 * 这里把这条规则收在一处，避免调用方各写一遍。
 *
 * ## 必须在发布到最终路径之前调用
 *
 * 调用方判断"这张图在不在"只看目标文件是否存在（`FileDownloader.download` 的
 * `skipIfComplete` 与 `ImageStore.cachedFile`）。所以解扰**不能**放在发布之后：
 * 那样会留下一个"已经在最终路径上、内容却还是加扰的"中间态，而它会被当成成品图
 * 显示出来，并且之后再也不会被重新处理。正确的位置是
 * [com.haruhi.jmcomic.core.download.FileDownloader] 的 `beforePublish` 钩子。
 *
 * ## 为什么用传入的文件名，而不是 `file.name`
 *
 * 处理发生在 `.part` 上，物理名是 `00001.webp.part`：用它推扩展名会得到 `part`
 * （于是**静默跳过**解扰），用它算块数会变成 `00001.webp`（于是块数算错、图被解坏）。
 * 解扰参数一律只看**逻辑文件名**（章节里的图片名），物理文件只用于读写。
 *
 * @param scrambleId 章节的 `scramble_id`（由 `/chapter_view_template` 得到）
 * @param chapterId 章节 id
 */
class ImageDownloadTransform(
    private val scrambleId: Long,
    private val chapterId: Long,
) {
    /**
     * 就地处理 [file]。
     *
     * 这里**不再**自己记录"处理过哪些文件名"：调用点是引擎的 per-file 锁内（见类注释），
     * 一次真实下载只会调用一次。留着去重集合反而有害——文件被删掉后重新下载时，
     * 它会让第二次解扰被静默跳过，于是把加扰的字节发布出去。
     *
     * @param file 物理文件（可能是 `<目标>.part`）
     * @param fileName 逻辑文件名（章节图片名，如 `00001.webp`）
     * @return true 表示做了实际处理；false 表示无需处理（原样发布）
     * @throws Exception 处理失败。**调用方必须放弃发布**，否则最终路径上会出现加扰图。
     */
    fun transform(file: File, fileName: String): Boolean {
        val blockNum = blockNumOf(scrambleId, chapterId, fileName)
        if (blockNum <= 0) {
            // 未加扰：**直接保留原文件**，不重新编码（已确认的决策，也是 P3 的验收项）
            return false
        }
        return try {
            ImageCodec.descrambleInPlace(file, blockNum)
            true
        } catch (e: Throwable) {
            // 解扰不可逆、失败原因多半是本地解码：吞掉它就等于把加扰图当成品发布，
            // 所以这里必须把异常交给引擎，由引擎放弃发布。
            Timber.tag("jm").w(e, "解扰失败，放弃本次下载：%s", fileName)
            throw e
        }
    }

    companion object {
        /**
         * 这张图的解扰块数（0 = 不需要解扰）。
         *
         * 两条管线的**唯一**块数算法，别再各写一份：
         * - 旧管线（[ImageDownloadTransform.transform]）用它决定是否重编码；
         * - 新管线（`ReaderCacheMode.RAW`，显示端）用它决定 [ImageCodec.descrambleToBitmap] 搬几块。
         *
         * 只对 `.webp` 解扰（参考实现 `downloadChapter` 第 948～954 行）：
         * `.gif` 之类的旧漫画图从来没被切过。参数一律只看**逻辑文件名**，
         * 不能传物理名（`.part` / `.descrambled` 会让块数算错、把图解坏）。
         */
        fun blockNumOf(scrambleId: Long, chapterId: Long, fileName: String): Int {
            if (fileName.substringAfterLast('.', "").lowercase() != "webp") return 0
            return com.haruhi.jmcomic.core.protocol.Crypto.calculateBlockNum(
                scrambleId = scrambleId,
                chapterId = chapterId,
                filenameWithoutExt = fileName.substringBeforeLast('.'),
            )
        }
    }
}
