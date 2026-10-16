package com.haruhi.jmcomic.core.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PageMode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.haruhi.jmcomic.core.storage.FileNaming
import timber.log.Timber
import java.io.File

/**
 * PDF 生成（JM 本子 → 单文件 PDF）。
 *
 * 行为对齐参考实现 `JmcomicService.albumToPdf` / `addImageToPdf`（第 674～744 行）：
 * 1. 每张图片一页，**页面尺寸 = 图片像素尺寸**；
 * 2. 章节目录按 `第N话` 序号排序，每章一个书签，指向该章第一页；
 * 3. 根书签标题 `目录`，`PageMode = USE_OUTLINES`；
 * 4. 加密用 `StandardProtectionPolicy`，权限 `canModify=false` /
 *    `canExtractContent=false` / `canExtractForAccessibility=false`；
 * 5. 图片用 `LosslessFactory` 内嵌（无损 Flate 压缩，文件偏大但与参考实现一致）。
 *
 * [password] 为 `null` 或空串时**不加任何保护**（对应用户点「不设密码」）。
 *
 * ## 超长图：按高度切片（本工程对参考实现的一处**行为差异**）
 *
 * PDF 单页上限 14400×14400，参考实现不处理，超限的图在多数阅读器里会渲染异常。
 * 这里超过上限的图会**均匀切成多页**（见 [PdfPagePlan]），宽度超限则整体等比缩小。
 * 计划 §7 把这条列为需要单独确认的行为差异；选择"切片"是因为它不会失败、
 * 阅读体验也正常。没超限的图仍然是一页一张，与参考实现完全一致。
 *
 * ## 与参考实现的三处**已确认**差异（2026-10-08 用户决策）
 *
 * 1. **加密算法固定为 AES-128**（参考实现实际是 40 位 RC4，见下）。
 *    参考实现只调了 `policy.setEncryptionKeyLength(128)` 而没设 `preferAES`。
 *    实测（桌面版 PDFBox 3.0.2 与 pdfbox-android 2.0.27.0 行为一致）：
 *    单独设 `encryptionKeyLength` **被忽略**，产出 `V=1 / R=3 / Length=40`，
 *    即 **40 位 RC4**；只有同时设 `preferAES = true` 才会得到
 *    `V=4 / R=4 / Length=128 / StdCF=AESV2`。
 *    用户已确认改用 AES-128 —— 这是**有意的安全强化**，不是与参考实现对齐。
 *    `PdfWriterSpikeTest` 把 `V/R/Length/StdCF` 四项锁成回归断言，
 *    因为漏设任一项都会静默降级、不报错。
 * 2. **访问控制只看"能否打开"**。参考实现把 owner 与 user 密码设成同一个值，
 *    导致打开文档时判定为 owner 身份，权限位被忽略（实测 `isOwnerPermission == true`、
 *    `canModify()` 返回 true）。用户已确认接受这个行为：
 *    「只需要管控能否打开/阅读 pdf 就行，能打开就不管其他权限」。
 *    因此这里仍然传同一个密码，权限位只是按参考实现写入、不作为安全承诺。
 *    `PdfWriterSpikeTest` 把这一事实固定成断言，避免以后误以为它生效。
 * 3. 参考实现用 `ImageIO.read`，这里用 `BitmapFactory`（Android 上没有 ImageIO/AWT）。
 */
class PdfWriter(
    private val bitmapFactory: (File) -> Bitmap? = { path ->
        BitmapFactory.decodeFile(path.absolutePath)
    },
    /**
     * 只解码图片的一块区域（超长图切片时用，避免为了一张长图分配几十 MB）。
     * 默认走 [BitmapRegionDecoder]；测试里会注入一个基于整图裁切的实现。
     */
    private val regionFactory: (File, android.graphics.Rect) -> Bitmap? = ::decodeRegion,
) {

    /** 生成结果，用于给 UI 展示"已加密 / 未加密"以及页数。 */
    data class Result(
        val file: File,
        val pageCount: Int,
        val chapterCount: Int,
        val encrypted: Boolean,
        /** 有多少张图因为超过 PDF 单页上限被切成了多页。 */
        val slicedImageCount: Int = 0,
    )

    /**
     * 把 [albumDir] 下的章节目录打成 PDF。
     *
     * @param albumDir 本子目录，直接子目录视为章节
     * @param out      输出文件（会被覆盖）
     * @param password 打开密码；`null`/空串表示不加密
     * @param onProgress 进度回调 `(已完成页数, 总页数, 当前章节名)`；总页数未知时为 -1
     * @param isCancelled 每张图开始前询问一次；返回 true 时抛出 [CancellationException]
     */
    fun albumToPdf(
        albumDir: File,
        out: File,
        password: String?,
        onProgress: ((done: Int, total: Int, chapter: String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): Result {
        val chapters = chapterDirs(albumDir)
        require(chapters.isNotEmpty()) { "本子目录下没有章节文件夹：${albumDir.absolutePath}" }

        // 页数要先把每张图的切片方案算出来（超长图会占多页）
        val chapterPages = chapters.map { chapter ->
            val images = chapterImages(chapter).map { it to planFor(it) }
            chapter to images
        }.filter { it.second.isNotEmpty() }

        val totalPages = chapterPages.sumOf { (_, images) -> images.sumOf { it.second.slices.size } }
        require(totalPages > 0) { "本子目录下没有图片：${albumDir.absolutePath}" }

        out.parentFile?.mkdirs()

        PDDocument().use { document ->
            applyProtection(document, password)

            val documentOutline = PDDocumentOutline()
            document.documentCatalog.documentOutline = documentOutline
            val rootOutline = PDOutlineItem().apply { title = "目录" }
            documentOutline.addLast(rootOutline)

            var donePages = 0
            var slicedImages = 0
            for ((chapterDir, images) in chapterPages) {
                val chapterOutline = PDOutlineItem().apply { title = chapterDir.name }
                rootOutline.addLast(chapterOutline)

                val chapterStartPage = document.numberOfPages
                for ((image, plan) in images) {
                    require(!isCancelled()) { "导出已取消" }
                    if (plan.slices.size > 1) slicedImages++
                    addImageToPdf(document, image, plan)
                    donePages += plan.slices.size
                    onProgress?.invoke(donePages, totalPages, chapterDir.name)
                }

                // 书签指向该章第一页（参考实现用 PDPageFitWidthDestination）
                val destination = PDPageFitWidthDestination()
                destination.page = document.getPage(chapterStartPage)
                chapterOutline.destination = destination
            }

            document.documentCatalog.pageMode = PageMode.USE_OUTLINES
            document.save(out)

            return Result(
                file = out,
                pageCount = document.numberOfPages,
                chapterCount = chapterPages.size,
                encrypted = !password.isNullOrEmpty(),
                slicedImageCount = slicedImages,
            )
        }
    }

    private fun applyProtection(document: PDDocument, password: String?) {
        if (password.isNullOrEmpty()) return

        val permission = AccessPermission().apply {
            // PDFBox 的 setter 是 setCanModify/setCanExtractContent/…，
            // Kotlin 的属性语法会解析成 getter，必须显式调用 setter。
            //
            // 注意：owner 与 user 用同一个密码时这些权限位**不会生效**
            // （打开者被判定为 owner）。用户已确认接受这一点，这里保留写入只是
            // 为了与参考实现产物一致，不构成安全承诺。见类注释第 2 条。
            setCanModify(false)
            setCanExtractContent(false)
            setCanExtractForAccessibility(false)
        }

        val policy = StandardProtectionPolicy(password, password, permission).apply {
            // ------------------------------------------------------------------
            // **两行都必须设**，缺一个都会静默降级（实测，见 docs/p0/spike2-pdf.md）：
            //   什么都不设            -> V=1 R=3 Length=40  StdCF=(none)  ← 40 位 RC4
            //   只设 encryptionKeyLength -> V=1 R=3 Length=40  StdCF=(none)  ← 长度被忽略
            //   只设 preferAES         -> V=1 R=3 Length=40  StdCF=(none)
            //   两个都设              -> V=4 R=4 Length=128 StdCF=AESV2   ← AES-128 ✅
            //
            // 参考实现（桌面版 PDFBox 3.0.2）只设了 encryptionKeyLength，
            // 也就是它**实际产出的是 40 位 RC4**。用户已确认本 App 改用 AES-128：
            // 这不与参考实现对产物的对齐，而是有意的安全强化。
            // ------------------------------------------------------------------
            encryptionKeyLength = AES_KEY_BITS
            isPreferAES = true
        }
        document.protect(policy)
    }

    /**
     * 把一张图按切片方案写进 PDF。
     *
     * 没超限的图（绝大多数）走快路径：整图解码一次、一页画完，
     * 与参考实现的行为完全一致；超限的图才逐块解码，避免为一张长图分配几十 MB。
     */
    private fun addImageToPdf(document: PDDocument, imageFile: File, plan: PdfImagePlan) {
        if (plan.slices.size == 1) {
            val bitmap = bitmapFactory(imageFile)
                ?: error("无法解码图片：${imageFile.absolutePath}")
            try {
                val slice = plan.slices.first()
                val image = LosslessFactory.createFromImage(document, bitmap)
                val page = PDPage(PDRectangle(plan.pageWidth.toFloat(), slice.pageHeight.toFloat()))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    // 目标尺寸用"缩放后的页面尺寸"：宽度超限时由这里等比缩小
                    stream.drawImage(image, 0f, 0f, plan.pageWidth.toFloat(), slice.pageHeight.toFloat())
                }
            } finally {
                bitmap.recycle()
            }
            return
        }

        for (slice in plan.slices) {
            val bitmap = decodeSlice(imageFile, slice)
                ?: error("无法解码图片：${imageFile.absolutePath}")
            try {
                val image = LosslessFactory.createFromImage(document, bitmap)
                val page = PDPage(PDRectangle(plan.pageWidth.toFloat(), slice.pageHeight.toFloat()))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(image, 0f, 0f, plan.pageWidth.toFloat(), slice.pageHeight.toFloat())
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** 解码长图的一段；优先只解这一块，失败才退回"整图解码再裁"。 */
    private fun decodeSlice(imageFile: File, slice: PdfPageSlice): Bitmap? {
        val rect = android.graphics.Rect(0, slice.sourceY, 1, slice.sourceY + slice.sourceHeight)
        // 宽度用原图宽度：regionFactory 只关心纵向范围，宽度由调用方按尺寸判断
        val width = readMeta(imageFile)?.first ?: 0
        if (width > 0) rect.right = width
        return regionFactory(imageFile, rect) ?: bitmapFactory(imageFile)?.let { full ->
            val cropped = runCatching {
                Bitmap.createBitmap(full, 0, slice.sourceY, full.width, slice.sourceHeight)
            }.getOrNull()
            if (cropped !== full) full.recycle()
            cropped
        }
    }

    /** 只读尺寸，不分配像素内存。 */
    private fun readMeta(file: File): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.getOrNull()

    /** 这张图要占几页（超长图会切片）。宽高读不到时按"一页"处理，交给后续报错。 */
    private fun planFor(imageFile: File): PdfImagePlan {
        val (width, height) = readMeta(imageFile) ?: return PdfImagePlan(
            sourceWidth = 1,
            sourceHeight = 1,
            pageWidth = 1,
            pageHeightPerSlice = 1,
            slices = listOf(PdfPageSlice(0, 1, 0, 1)),
        )
        return PdfPagePlan.plan(width, height)
    }

    /** 章节目录：`albumDir` 的直接子目录，按 `第N话` 序号升序。 */
    fun chapterDirs(albumDir: File): List<File> {
        val dirs = albumDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.sortedBy { FileNaming.extractChapterNumber(it.name) }
    }

    /** 章节图片：按去扩展名后的数字升序，只保留参考实现认得的图片扩展名。 */
    fun chapterImages(chapterDir: File): List<File> {
        val files = chapterDir.listFiles { file -> file.isFile && isImage(file.name) } ?: return emptyList()
        return files.sortedBy { FileNaming.extractImageNumber(FileNaming.baseName(it.name)) }
    }

    private fun isImage(fileName: String): Boolean =
        FileNaming.extension(fileName) in IMAGE_EXTENSIONS

    companion object {
        private val IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "gif", "bmp")

        /** 加密密钥长度。与参考实现一致（128 位）。 */
        const val AES_KEY_BITS = 128

        /**
         * 默认的区域解码：`BitmapRegionDecoder`。
         *
         * 用带 `isShareable` 的两参重载：单参的那个在新 SDK 里被标成了"需要 API 31"
         * （其实是 API 10 就有的老接口，只是被废弃了），两参版本从 API 10 起就可用。
         */
        @Suppress("DEPRECATION")
        private fun decodeRegion(file: File, rect: android.graphics.Rect): Bitmap? = try {
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(file.absolutePath, false)
            try {
                decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } finally {
                decoder.recycle()
            }
        } catch (e: Throwable) {
            Timber.tag("pdf").w(e, "区域解码失败，回退整图解码：%s", file.name)
            null
        }

        init {
            // pdfbox-android 要求显式初始化资源加载器；正常路径由 AppContainer 用真实
            // Context 初始化（这里只是兜底，null 会失败——所以失败只记日志不影响生成）。
            runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(null) }
                .onFailure { Timber.tag("pdf").d(it, "PDFBoxResourceLoader 尚未初始化（由 AppContainer 负责）") }
        }
    }
}
