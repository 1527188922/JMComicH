package com.haruhi.jmcomic.feature.data

import android.graphics.Bitmap
import com.haruhi.jmcomic.core.codec.ImageCodec
import com.haruhi.jmcomic.core.codec.ImageScramble
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
 * 章节图片的**本地优先取图器**：阅读器与下载任务共用同一个 `library/` 落点。
 *
 * ## 新架构（2026-10）
 *
 * `library/<本子>/<章节>/` 里存的是**站点原始字节**（加扰、未解码、未重编码）。
 * "下载"只做一件事：把字节取回来落盘；有字节就算已下载。
 * 阅读器显示时现场解码 + 解扰（[displayBitmap]），ZIP / PDF 导出时现场解扰再编码。
 *
 * 旧版本留下的 `library/` 里是**解扰后的成品**（`storage_encoding = 0`）；
 * 新下载写 `storage_encoding = 1`（见 [com.haruhi.jmcomic.core.storage.ImageStorage]）。
 * 阅读器 / 导出按这个标记决定"要不要解扰"，所以升级用户不需要迁移文件。
 *
 * ## 为什么不再有 `reader-cache`
 *
 * 两套目录（`library` 成品 + `reader-cache` 原图）曾经是为了"在线阅读快"——
 * 旧成品要无损重编码一份、体积大、慢。现在 `library` 本身就是原始字节，
 * 阅读器不再落第二份解扰成品，所以 `reader-cache` 没有存在意义：
 * 新版本不再写它，旧目录只保留清理入口（`LibraryDirs.readerCache`）。
 *
 * ## 并发
 *
 * 同一张图可能被阅读器与下载任务同时要（一边看一边下）。两边抢的是 [FileDownloader]
 * 的同一把 per-file 锁，而且都只是"下载原始字节 → 原子发布"，所以不会有两个写入者
 * 抢同一个 `.part`，后到者拿到锁时看到的是已发布的原始字节，安全地走 `Skipped`。
 *
 * 这里的 [locks] 是第二道闸：把阅读器自己的多次并发请求合并成一次网络往返。
 */
class ImageStore(
    private val repo: Repository,
    private val downloader: FileDownloader,
    private val dirs: LibraryDirs,
    private val environment: DownloadEnvironment,
) {

    /** 每一话的 `scramble_id`：同一话内恒定，取一次记住即可（只有显示端需要）。 */
    private val scrambleIds = ConcurrentHashMap<Long, Long>()

    /** 每张图的下载锁：保证同一张图只被取一次。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 已经量过宽高比的图（按"路径 + 长度"缓存），切章节来回时不必重读文件头。 */
    private val aspects = ConcurrentHashMap<String, Float>()

    /**
     * 本进程内刚下载、确定是原始字节的文件。
     *
     * 正常情况下 [Repository.markImageRaw] 已经把标记写进数据库；这个集合是内存兜底：
     * 写入失败 / 还没落库时，阅读器也不会把刚下的加扰图当成旧成品直接显示。
     */
    private val knownRaw = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * 已经在磁盘上且非空的图（**不发任何网络请求**）。
     *
     * 内容可能是：
     * - 新版本下载的站点原始字节（加扰，用前要解扰）；
     * - 旧版本解扰后的成品（直接用）；
     * - 未加扰章节 / 非 webp 的原始字节（两种处理等价）。
     * 形态由 [isRaw] 判断。0 字节残留不算命中。
     */
    fun cachedFile(album: AlbumEntity, chapterFolder: String, imageFile: String): File? =
        targetFile(album, chapterFolder, imageFile).takeIf { isUsable(it) }

    // ------------------------------------------------------------------
    // 取图
    // ------------------------------------------------------------------

    /**
     * 取一张图：本地优先，没有就联网下载**原始字节**并落盘。
     *
     * 与旧实现的区别：这里**没有** `beforePublish` 解扰钩子。下载产物就是站点字节，
     * 解扰推迟到阅读显示（[displayBitmap]）或导出（ZIP / PDF 写入器）时。
     *
     * 整段在 [Dispatchers.IO] 上跑，避免把网络/写盘压在主线程。
     *
     * @throws Exception 网络失败、写盘失败都会抛出来，由调用方转成用户文案
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

            // 文件一发布就是原始字节：先记标记，避免下载成功后进程被杀 / 数据库写入失败
            // 导致下次启动把加扰图当成旧成品直接显示。
            markRaw(target, album.id, chapterId, imageFile)

            val domain = environment.imageDomain()
            val outcome = downloader.download(
                urlProvider = { Defaults.buildImgUrl(chapterId, imageFile, domain) },
                target = target,
                // 已经有了就不重复下载（上面已经提前返回，这里是二次保险）
                skipIfComplete = true,
                // 原样发布：不解扰、不解码、不重编码
                beforePublish = null,
                // 旧版本的 reader-cache 里可能已有同一张原图：直接拿它当种子，不再联网。
                // 新版本不再产生这个目录，这里只是升级用户的单向复用。
                seedFile = legacyReaderSeed(album.id, chapterId, imageFile),
            )
            when (outcome) {
                is DownloadOutcome.Downloaded -> {
                    // 发布完成后再写一次（幂等）：数据库写失败就必须删掉这份"没标记的原始字节"，
                    // 否则下次启动会把它当成旧成品直接显示，出现乱序图。
                    if (!markRaw(target, album.id, chapterId, imageFile)) {
                        target.delete()
                        throw IllegalStateException("无法记录图片存储形态：$imageFile")
                    }
                }

                is DownloadOutcome.Skipped -> Unit
                is DownloadOutcome.Failed -> throw outcome.error
                DownloadOutcome.Cancelled -> throw IllegalStateException("图片下载已取消：$imageFile")
            }

            target
        }
    }

    /**
     * 这张图是不是**站点原始字节**（用前需要解扰）。
     *
     * 先看本进程的兜底集合，再查数据库。数据库里没有这一行（清单还没入库）时
     * 按旧成品处理，与 `ImageStorage.DESCRAMBLED` 默认值一致。
     */
    suspend fun isRaw(
        source: File,
        albumId: Long,
        chapterId: Long,
        imageFile: String,
    ): Boolean {
        if (knownRaw.contains(source.absolutePath)) return true
        return repo.isImageRaw(albumId, chapterId, imageFile)
    }

    /** 标记某张图是原始字节（数据库 + 进程内兜底）。返回数据库是否写成功。 */
    suspend fun markRaw(
        source: File,
        albumId: Long,
        chapterId: Long,
        imageFile: String,
    ): Boolean {
        knownRaw.add(source.absolutePath)
        return runCatching { repo.markImageRaw(albumId, chapterId, imageFile) }
            .onFailure { Timber.tag("jm").w(it, "标记原始字节失败：%s", imageFile) }
            .isSuccess
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
     * 块数就算错了，而解扰不可逆——图会被显示成乱序（内存里，不影响磁盘文件）。
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
    // 显示端：现场解扰（不写盘、不重编码）
    // ------------------------------------------------------------------

    /**
     * 把一张 `library/` 里的图片解码成可直接显示的位图。
     *
     * - [raw] = true：站点原始字节，先算块数、就地解扰；
     * - [raw] = false：旧版本解扰成品（或本就不需要解扰），只解码。
     *
     * 不写盘、不重编码；降采样与 OOM 兜底都在 [ImageCodec.descrambleToBitmap] 里。
     *
     * @param targetWidthPx 显示宽度，用于算降采样倍数（长图别整张解进内存）
     */
    suspend fun displayBitmap(
        chapterId: Long,
        imageFile: String,
        source: File,
        raw: Boolean,
        targetWidthPx: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        val blockNum = if (raw) {
            ImageScramble.blockNumOf(
                scrambleId = scrambleIdOf(chapterId),
                chapterId = chapterId,
                fileName = imageFile,
            )
        } else {
            0
        }
        ImageCodec.descrambleToBitmap(
            file = source,
            blockNum = blockNum,
            targetWidthPx = targetWidthPx,
        )
    }

    /**
     * 旧版本 `reader-cache` 里的同一张原图（没有就返回 null）。
     *
     * 新版本不再写这个目录；这里只是让升级用户"边看边下"时不重复联网。
     * 文件被清掉 / 不存在都返回 null，下载自然走网络。
     */
    private fun legacyReaderSeed(albumId: Long, chapterId: Long, imageFile: String): File? =
        File(dirs.readerCacheChapterDir(albumId, chapterId), imageFile).takeIf { isUsable(it) }
}
