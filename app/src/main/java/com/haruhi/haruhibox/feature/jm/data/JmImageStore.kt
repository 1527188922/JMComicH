package com.haruhi.haruhibox.feature.jm.data

import com.haruhi.haruhibox.core.codec.ImageCodec
import com.haruhi.haruhibox.core.codec.ImageDownloadTransform
import com.haruhi.haruhibox.core.database.entity.JmAlbumEntity
import com.haruhi.haruhibox.core.download.DownloadOutcome
import com.haruhi.haruhibox.core.download.FileDownloader
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.storage.LibraryDirs
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
class JmImageStore(
    private val repo: JmRepository,
    private val downloader: FileDownloader,
    private val dirs: LibraryDirs,
    private val environment: JmDownloadEnvironment,
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
    fun chapterDir(album: JmAlbumEntity, chapterFolder: String): File =
        dirs.chapterDir(album.albumFolderName ?: "JM${album.id}", chapterFolder)

    /** 某张图在磁盘上的位置（不判断是否存在）。 */
    fun targetFile(album: JmAlbumEntity, chapterFolder: String, imageFile: String): File =
        File(chapterDir(album, chapterFolder), imageFile)

    /** 已经在磁盘上且非空的图（**不发任何网络请求**）。0 字节残留不算命中。 */
    fun cachedFile(album: JmAlbumEntity, chapterFolder: String, imageFile: String): File? =
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
        album: JmAlbumEntity,
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
                urlProvider = { JmDefaults.buildImgUrl(chapterId, imageFile, domain) },
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
            JmDefaults.FALLBACK_SCRAMBLE_ID
        }
        scrambleIds[chapterId] = value
        return value
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0
}
