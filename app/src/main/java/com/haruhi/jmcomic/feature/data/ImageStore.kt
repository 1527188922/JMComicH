package com.haruhi.jmcomic.feature.data

import android.graphics.Bitmap
import com.haruhi.jmcomic.core.codec.ImageCodec
import com.haruhi.jmcomic.core.codec.ImageDownloadTransform
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.download.DownloadOutcome
import com.haruhi.jmcomic.core.download.FileDownloader
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.storage.LibraryDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 章节图片的**本地优先取图器**：阅读器与下载任务共用同一份落盘位置和同一套解扰规则。
 *
 * ## 两套取图管线（设置页「阅读与显示 → 图片缓存方式」，见 `ReaderCacheMode`）
 *
 * | 管线 | 落盘内容 | 目录 | 入口 |
 * |---|---|---|---|
 * | 解扰后落盘（旧，默认之前的行为） | 解扰 + **无损重编码**后的成品 | `library/<本子>/<章节>/` | [file] / [cachedFile] |
 * | 原图缓存（默认） | **站点原始字节**（未解扰、未重编码） | `reader-cache/<aid>/<cid>/` | [fetchRawFile] / [displayBitmap] |
 *
 * 旧管线一行没删：用户随时可以切回去；下载任务（[com.haruhi.jmcomic.feature.data.DownloadManager]）
 * **永远**走旧管线的成品落盘——下载产物要能直接给 ZIP/PDF 导出用，必须是解扰好的。
 *
 * ## 为什么需要它
 *
 * 用户的要求是"**能看就能看，不必先下载**"：
 * - 阅读器遇到磁盘上没有的图，直接联网取回来、**落盘缓存**，下次再读不再请求；
 * - 用户点下载时，**每张图先看缓存**（就是同一个文件），有就不再请求；
 * - 于是"一边看一边下载"天然不冲突——两边只是同一个"文件在不在"的检查。
 *
 * 这也顺便解决了"删掉本地文件后再进阅读器显示图片缺失"的问题：
 * 文件不在了就当缓存未命中，重新取一次即可。
 *
 * ## 与下载引擎的分工
 *
 * 传输、Range 续传、**发布前解扰**、原子发布都在 [FileDownloader] 里；
 * 这一层只负责三件事：**算路径**、**命中判断**、**给引擎提供解扰钩子**。
 * 解扰必须在这里接上，否则阅读器直接拿到的会是加扰过的图。
 *
 * **解扰跑在引擎的 `beforePublish` 钩子里，也就是"这张图在最终路径上可见之前"**。
 * 这一点是硬要求：命中判断（[cachedFile]）只看"文件在不在、非不非空"，如果先发布
 * 再解扰，那个中间态就会被后面的读取当成成品图——用户快速退出阅读器再进来时就会看到
 * 没解扰的图，而且它再也不会被重新处理。
 *
 * ## 并发
 *
 * 同一张图可能被阅读器与下载任务同时要（一边看一边下）。两边抢的是 [FileDownloader]
 * 的**同一把 per-file 锁**（同一个实例），而且"下载 → 解扰 → 发布"全在锁内，所以：
 * - 不会有两个写入者抢同一个 `.part`；
 * - 同一张图只会被解扰一次（解扰不可逆，重复执行会毁图）；
 * - 后到者拿到锁时看到的一定是**已经解扰完的成品**，于是安全地走 `Skipped`。
 *
 * 这里的 [locks] 是第二道闸：把"阅读器自己的多次并发请求"合并成一次网络往返
 * （拿到锁后再查一次磁盘，别人已经下好就直接返回）。
 */
class ImageStore(
    private val repo: Repository,
    private val downloader: FileDownloader,
    private val dirs: LibraryDirs,
    private val environment: DownloadEnvironment,
) {

    /** 每一话的 `scramble_id`：同一话内恒定，取一次记住即可。 */
    private val scrambleIds = ConcurrentHashMap<Long, Long>()

    /** 每张图的解扰锁：保证同一张图只被处理一次。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 已经量过宽高比的图（按"路径 + 长度"缓存），切章节来回时不必重读文件头。 */
    private val aspects = ConcurrentHashMap<String, Float>()

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /**
     * 某一话的落盘目录。
     *
     * [chapterFolder] 由调用方按 `title → name → id` 的规则算好
     * （单话本子固定「第1话」），**下载与阅读必须传同一个值**，
     * 否则会出现"下好了却显示未下载"。
     */
    fun chapterDir(album: AlbumEntity, chapterFolder: String): File =
        dirs.chapterDir(album.albumFolderName ?: "JM${album.id}", chapterFolder)

    /** 某张图在磁盘上的位置（不判断是否存在）。 */
    fun targetFile(album: AlbumEntity, chapterFolder: String, imageFile: String): File =
        File(chapterDir(album, chapterFolder), imageFile)

    /** 已经在磁盘上且非空的图（**不发任何网络请求**）。0 字节残留不算命中。 */
    fun cachedFile(album: AlbumEntity, chapterFolder: String, imageFile: String): File? =
        targetFile(album, chapterFolder, imageFile).takeIf { isUsable(it) }

    // ------------------------------------------------------------------
    // 取图
    // ------------------------------------------------------------------

    /**
     * 取一张图：本地优先，没有就联网下载并**落盘缓存**。
     *
     * **整段都在 [Dispatchers.IO] 上跑**：解扰是纯 CPU 的重活（JM 长图 800×12000，
     * 解码成 ARGB_8888 就要 36MB，还要逐行 `getPixels/setPixels` 再重新编码）。
     * 调用方是 `viewModelScope`（主线程调度器），如果这里不切线程，
     * **每取一页就会把 UI 卡住一次**——用户反馈的"看漫画时 UI 卡住"正是这个原因。
     *
     * 解扰通过 `beforePublish` 交给引擎，**发生在图片发布到最终路径之前**，所以
     * 最终路径上只要看得见文件，它就已经是解扰好的成品。
     *
     * @throws Exception 网络失败、解扰失败、写盘失败都会抛出来，由调用方转成用户文案
     */
    suspend fun file(
        album: AlbumEntity,
        chapterId: Long,
        chapterFolder: String,
        imageFile: String,
    ): File = withContext(Dispatchers.IO) {
        cachedFile(album, chapterFolder, imageFile)?.let { return@withContext it }

        val target = targetFile(album, chapterFolder, imageFile)
        val lock = locks.computeIfAbsent(target.absolutePath) { Mutex() }
        lock.withLock {
            // 等锁期间别人可能已经下好了（阅读器与下载任务抢同一张图是常态）
            cachedFile(album, chapterFolder, imageFile)?.let { return@withLock it }

            // scramble_id 要在下载之前拿到：解扰作为 `beforePublish` 跑在引擎内部，
            // 那里不该再去发网络请求（会捏着 per-file 锁等接口）。
            // 代价是"下载失败/被别人跳过"时这一次请求白做，但它是按话缓存的
            // （[scrambleIds]），每话最多多打一次。
            val transform = ImageDownloadTransform(
                scrambleId = scrambleIdOf(chapterId),
                chapterId = chapterId,
            )

            val domain = environment.imageDomain()
            val outcome = downloader.download(
                urlProvider = { Defaults.buildImgUrl(chapterId, imageFile, domain) },
                target = target,
                // 已经有了就不重复下载（上面已经提前返回，这里是二次保险）
                skipIfComplete = true,
                // 解扰在发布之前完成；它抛异常就放弃发布，绝不会把加扰图落到最终路径上
                beforePublish = { part: File -> transform.transform(part, imageFile) },
            )
            when (outcome) {
                is DownloadOutcome.Downloaded, is DownloadOutcome.Skipped -> Unit
                is DownloadOutcome.Failed -> throw outcome.error
                DownloadOutcome.Cancelled -> throw IllegalStateException("图片下载已取消：$imageFile")
            }

            target
        }
    }

    /**
     * 图片宽高比（高/宽）。读不到时给占位值，保证长图滚动不跳。
     *
     * 读文件头也是解码（`inJustDecodeBounds` 仍要打开并解析图片），所以**同样切到 IO**：
     * 打开一章时要把每一页的比例都算一遍，放在主线程就是"进阅读器卡一下"。
     */
    suspend fun aspectRatioOf(file: File, fallback: Float): Float = withContext(Dispatchers.IO) {
        aspects.getOrPut("${file.absolutePath}:${file.length()}") {
            runCatching {
                val meta = ImageCodec.readMeta(file)
                if (meta.width > 0 && meta.height > 0) {
                    meta.height.toFloat() / meta.width.toFloat()
                } else {
                    fallback
                }
            }.getOrDefault(fallback)
        }
    }

    /**
     * 这一话的 `scramble_id`；取不到时用参考实现的兜底值。同一话只请求一次。
     *
     * **取消必须原样抛出**：`runCatching` 会把 `CancellationException` 一起吞掉，
     * 于是"用户快速退出阅读器"就变成了"用兜底 id 继续解扰"。兜底值与真实值不一致时
     * 块数就算错了，而解扰不可逆——图会被永久毁掉并落盘，之后还会被当成缓存命中。
     */
    private suspend fun scrambleIdOf(chapterId: Long): Long {
        scrambleIds[chapterId]?.let { return it }
        val value = try {
            repo.scrambleIdOf(chapterId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag("jm").w(e, "取 scramble_id 失败，使用兜底值：chapterId=%d", chapterId)
            Defaults.FALLBACK_SCRAMBLE_ID
        }
        scrambleIds[chapterId] = value
        return value
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0

    // ------------------------------------------------------------------
    // 新管线（设置页「图片缓存方式 = 原图缓存」）：只存原始字节，显示时就地解扰
    // ------------------------------------------------------------------
    //
    // 与上面 [file] 的分工：
    // - [file] / [cachedFile] 走 `library/<本子>/<章节>/`，里面**永远**是解扰后的成品
    //   （下载产物、导出源、旧管线的缓存）；
    // - 这里的 `raw*` 走 `reader-cache/<aid>/<cid>/`，里面**永远**是站点原始字节
    //   （未解扰、未重编码），纯粹是"少下一遍"的缓存，删掉不影响任何用户资产。
    //
    // 分开存放是为了让两套管线互不污染：原图缓存里的加扰字节绝不能被"是否已下载"
    // 的判断命中（否则下载会跳过、导出会把加扰图打进 zip），切回旧管线时也不会读到加扰图。

    /** 某张图的**原图**缓存位置（不判断是否存在）。 */
    fun rawTargetFile(albumId: Long, chapterId: Long, imageFile: String): File =
        File(dirs.readerCacheChapterDir(albumId, chapterId), imageFile)

    /** 磁盘上已有的原图（**不发任何网络请求**）。0 字节残留不算命中。 */
    fun cachedRawFile(albumId: Long, chapterId: Long, imageFile: String): File? =
        rawTargetFile(albumId, chapterId, imageFile).takeIf { isUsable(it) }

    /** 某一本的原图缓存目录（删本时整目录清掉）。 */
    fun rawAlbumDir(albumId: Long): File = dirs.readerCacheAlbumDir(albumId)

    /**
     * 取一张图的**原始字节**（新管线）：本地有就直接用，没有就联网取回来并落盘缓存。
     *
     * 与 [file] 的关键差别：**没有 `beforePublish`**——字节原样落盘，不解码、不解扰、
     * 不重编码。于是这一趟的本地成本≈0，解扰推迟到真正要显示时（[displayBitmap]）。
     *
     * 并发控制不在这一层：调用方（`ReaderViewModel`）用「同时下载图片数」做信号量，
     * 阅读器与预取共用同一批名额。
     */
    suspend fun fetchRawFile(
        albumId: Long,
        chapterId: Long,
        imageFile: String,
    ): File = withContext(Dispatchers.IO) {
        cachedRawFile(albumId, chapterId, imageFile)?.let { return@withContext it }

        val target = rawTargetFile(albumId, chapterId, imageFile)
        val lock = locks.computeIfAbsent(target.absolutePath) { Mutex() }
        lock.withLock {
            cachedRawFile(albumId, chapterId, imageFile)?.let { return@withLock it }

            val domain = environment.imageDomain()
            val outcome = downloader.download(
                urlProvider = { Defaults.buildImgUrl(chapterId, imageFile, domain) },
                target = target,
                skipIfComplete = true,
                // 原样发布：不解扰、不重编码
                beforePublish = null,
            )
            when (outcome) {
                is DownloadOutcome.Downloaded, is DownloadOutcome.Skipped -> Unit
                is DownloadOutcome.Failed -> throw outcome.error
                DownloadOutcome.Cancelled -> throw IllegalStateException("图片下载已取消：$imageFile")
            }
            target
        }
    }

    /**
     * 把一张**原图**在内存里解扰成可直接显示的位图（不写盘、不重编码）。
     *
     * 块数用 [ImageDownloadTransform.blockNumOf]（与下载/旧管线同一条算法）：
     * 章节的 `scramble_id` 按话缓存在 [scrambleIds] 里，同一话只查一次库/接口。
     *
     * @param targetWidthPx 显示宽度，用于算降采样倍数（长图别整张解进内存）
     * @return 可显示的位图；**不需要解扰**时也返回解码结果（调用方统一用 `Image` 渲染）
     */
    suspend fun displayBitmap(
        chapterId: Long,
        imageFile: String,
        raw: File,
        targetWidthPx: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        val blockNum = ImageDownloadTransform.blockNumOf(
            scrambleId = scrambleIdOf(chapterId),
            chapterId = chapterId,
            fileName = imageFile,
        )
        ImageCodec.descrambleToBitmap(
            file = raw,
            blockNum = blockNum,
            targetWidthPx = targetWidthPx,
        )
    }
}
