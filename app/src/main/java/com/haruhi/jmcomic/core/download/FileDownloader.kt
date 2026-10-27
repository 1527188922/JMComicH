package com.haruhi.jmcomic.core.download

import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.core.network.OperationCancelledException
import com.haruhi.jmcomic.core.network.isTimeoutRetryable
import com.haruhi.jmcomic.core.settings.DownloadNetwork
import com.haruhi.jmcomic.core.storage.LibraryDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * 单文件下载器的进度快照。
 *
 * [resumedFrom] 非 0 表示这次是断点续传，UI 可以据此提示"从 12.3 MB 继续"。
 */
data class FileProgress(
    val url: String,
    val target: File,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val resumedFrom: Long,
    val attempt: Int,
) {
    /** 总长度未知时返回 null（服务端没给 Content-Length 且不支持 Range）。 */
    val fraction: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * 下载结果。
 *
 * [skipped] 表示目标文件已存在且完整（长度与服务端一致），**一个字节都没下**——
 * 对应参考实现 `downloadChapter` 里 `.filter(e -> !e.getImgFile().exists())` 的语义。
 */
sealed interface DownloadOutcome {
    data class Downloaded(val file: File, val bytes: Long, val resumedFrom: Long) : DownloadOutcome
    data class Skipped(val file: File, val bytes: Long) : DownloadOutcome
    data class Failed(val error: NetworkError) : DownloadOutcome
    data object Cancelled : DownloadOutcome
}

/**
 * 单文件下载引擎。
 *
 * 计划 §8「续传」一行的要求：
 * - 文件按 **HTTP Range 续传**；
 * - 断点续传的判定 = 目标文件是否已存在（JM 图片走 [downloadSkipIfExists]）；
 * - 服务端不支持 Range 时就整文件重下（见下面第 3 点）。
 *
 * 实现要点（对齐计划 §D.1 的高优先级项「临时文件校验后再原子发布」）：
 * 1. 中途文件写到 `<目标>.part`，**校验长度**后再 `renameTo` 原子发布到目标名；
 * 2. `206 Partial Content` → 从 `.part` 现有长度继续（校验 `Content-Range` 是否与请求一致）；
 * 3. `200 OK`（服务端忽略 Range）→ **丢弃 `.part` 从头写**，避免把整文件拼在半个文件后面；
 * 4. `416 Range Not Satisfiable` → 说明 `.part` 已经不小于资源总长度，直接核对长度后发布；
 * 5. 服务端没给 `Content-Length` 且不支持 Range → 整文件重下，进度按"未知总长"上报；
 * 6. 每次重试前重新解析直链是**调用方**的职责（JM 图片线路会轮换），
 *    引擎只接受一个 `urlProvider`，重试时重新调用它拿新地址。
 *
 * **重试**：次数来自设置页「高级 → 图片加载/下载失败重试次数」（每次下载现读，见
 * [attemptsProvider]），并且**只对网络超时重试**（[isTimeoutRetryable]）：
 * 连不上 / 5xx / 内容异常直接判失败，不再重发（用户 2026-10-20 要求）。
 *
 * **不在这里做**：网络策略判断（交给 [DownloadGate]）、并发调度（交给调用方或
 * [ParallelImageDownloader]）、进度持久化（交给任务仓库）。
 */
class FileDownloader(
    private val client: OkHttpClient,
    private val dirs: LibraryDirs,
    /**
     * **总尝试次数**（含第一次）。默认 3 = 第一次 + 失败后再试 2 次。
     *
     * 生产环境用 [attemptsProvider] 从设置里现读；这个值是给不接设置的地方
     * （单测、或将来其它用途）用的默认值。
     */
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 1_000L,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    /**
     * 每次下载时**现读**的总尝试次数（含第一次）；null = 用构造时的 [maxAttempts]。
     *
     * 设置页那一项是「失败后再试几次」（0 = 不重试），由调用方换算成 `重试次数 + 1`。
     * 做成 suspend 闭包是因为它读的是 DataStore，不该阻塞线程。
     */
    private val attemptsProvider: (suspend () -> Int)? = null,
) {

    /** 下载速度采样窗口（毫秒）。 */
    private val speedWindowMs = 2_000L

    /**
     * 每个目标文件一把锁。
     *
     * 为什么引擎层要管这个：**同一张图可能被两个调用方同时要**
     * （用户一边在线看、一边把这一话加进下载队列）。两边都会写同一个 `<目标>.part`，
     * 没有锁就是两个写入者交错写同一个文件——轻则内容损坏，重则发布出半张图。
     * 锁挂在实例上：阅读器与下载任务用的是同一个 [FileDownloader] 实例。
     */
    private val targetLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 下载 [url] 到 [target]。
     *
     * @param urlProvider 每次尝试时调用，返回**当前有效**的直链。
     *        传一个闭包而不是字符串，是因为 JM 图片线路会轮换，
     *        重试必须重新解析。
     * @param onProgress 进度回调；在 IO 线程上被高频调用，实现里不要做重活。
     * @param expectedTotal 已知的总长度（例如接口返回的 size）。为 0 时以服务端响应为准。
     * @param skipIfComplete 目标文件已存在时是否直接跳过。JM 图片是 `true`
     *        （对齐参考实现），单文件 mp4 也是 `true`（"是否已下载以磁盘文件为准"）。
     * @param beforePublish **发布前的后处理钩子**（JM 用它解扰）。收到的是**中转文件**
     *        （`<目标>.part`），此时 [target] 还不存在。它跑在 per-file 锁内，所以
     *        同一张图只会被处理一次；它抛异常就**放弃发布**（见 [PostProcessFailed]）。
     *
     *        **为什么后处理必须在这里做**：调用方判断"这张图在不在"只看目标文件是否存在
     *        （`skipIfComplete` / `ImageStore.cachedFile`）。如果先发布、再处理，那么
     *        "已发布但还没处理"的中间态会被当成**成品**：阅读器会直接把它显示出来，
     *        而且之后再也不会重新处理。所以不变量是
     *        **"目标路径上可见 ⟹ 已经处理完成"**，处理只能发生在发布之前。
     */
    suspend fun download(
        urlProvider: suspend () -> String,
        target: File,
        onProgress: ((FileProgress) -> Unit)? = null,
        expectedTotal: Long = 0L,
        skipIfComplete: Boolean = true,
        etag: String? = null,
        beforePublish: ((File) -> Unit)? = null,
        /**
         * **可选的本地种子文件**：已经下好、内容与目标相同的另一份字节
         * （升级用户遗留的 `reader-cache` 就是这种；新版本不再产生）。
         *
         * 给了它并且文件可用时**一个网络请求都不发**：把种子拷成 `.part`，
         * 走同一条「校验 → `beforePublish` → 原子发布」的路径。
         *
         * 为什么需要：`library/` 现在直接存站点原始字节，旧缓存里也是同一份内容，
         * 不该为同一张图联网两次。
         */
        seedFile: File? = null,
    ): DownloadOutcome {
        // 先算总尝试次数再抢锁：设置里的值是"失败后再试几次"，引擎要的是"总尝试次数"
        // （至少 1 次）。读设置是 suspend 的，放在锁外面不占别人的等待时间。
        val attemptLimit = attemptsProvider?.invoke()?.coerceAtLeast(1) ?: maxAttempts

        // 同一个目标串行化（见 targetLocks 的说明）。等锁期间别人可能已经下好了，
        // 所以拿到锁之后要重新判断一次"是不是已经完成"。
        val lock = targetLocks.computeIfAbsent(target.absolutePath) { Mutex() }
        return lock.withLock {
            if (skipIfComplete && target.isFile && target.length() > 0) {
                return@withLock DownloadOutcome.Skipped(target, target.length())
            }
            // 本地已经有同样的字节：不联网，直接当"已下载"走发布流程
            if (seedFile != null && seedFile.isFile && seedFile.length() > 0) {
                publishSeeded(
                    seedFile = seedFile,
                    target = target,
                    expectedTotal = expectedTotal,
                    beforePublish = beforePublish,
                )?.let { return@withLock it }
            }
            downloadLocked(
                urlProvider = urlProvider,
                target = target,
                onProgress = onProgress,
                expectedTotal = expectedTotal,
                skipIfComplete = skipIfComplete,
                etag = etag,
                beforePublish = beforePublish,
                maxAttempts = attemptLimit,
            )
        }
    }

    /**
     * 用本地种子文件代替下载。
     *
     * 语义与"下载成功"完全一致（校验 → 后处理 → 原子发布），只是不联网。
     * 中途出错**不重试、也不回落到网络**：种子拷不动说明本地环境有问题，
     * 重发一次 HTTP 也解决不了；返回 `null` 表示"种子这条路走不通"，交给调用方走网络。
     */
    private fun publishSeeded(
        seedFile: File,
        target: File,
        expectedTotal: Long,
        beforePublish: ((File) -> Unit)?,
    ): DownloadOutcome? {
        val partFile = File(target.parentFile, target.name + PART_SUFFIX)
        return try {
            target.parentFile?.mkdirs()
            seedFile.copyTo(partFile, overwrite = true)
            val total = if (expectedTotal > 0) expectedTotal else partFile.length()
            publishAfterValidation(
                partFile = partFile,
                target = target,
                total = partFile.length(),
                expectedTotal = total,
                resumedFrom = 0L,
                beforePublish = beforePublish,
            )
        } catch (e: PostProcessFailed) {
            // 后处理失败照旧：**绝不发布**（发布出去的就是没处理过的半成品）
            Timber.tag("dl").w(e, "种子文件后处理失败，放弃发布：%s", target.name)
            partFile.delete()
            DownloadOutcome.Failed(NetworkError.Malformed(target.name, "图片已下载但无法解扰，请重试"))
        } catch (e: Throwable) {
            Timber.tag("dl").w(e, "本地种子文件不可用，改走网络：%s", target.name)
            partFile.delete()
            null
        }
    }

    private suspend fun downloadLocked(
        urlProvider: suspend () -> String,
        target: File,
        onProgress: ((FileProgress) -> Unit)?,
        expectedTotal: Long,
        skipIfComplete: Boolean,
        etag: String?,
        beforePublish: ((File) -> Unit)?,
        maxAttempts: Int,
    ): DownloadOutcome {
        val partFile = File(target.parentFile, target.name + PART_SUFFIX)
        target.parentFile?.mkdirs()

        if (skipIfComplete && target.isFile && target.length() > 0) {
            return DownloadOutcome.Skipped(target, target.length())
        }

        var lastError: NetworkError? = null
        var attempt = 0
        // 本次尝试是否允许发送 Range。服务端返回 416 表示"你的偏移已经超出资源"，
        // 这时最优解是丢掉 .part 从头下；用一个标志位表达，而不是在 416 分支里直接重下，
        // 这样长度校验、原子发布、重试计数都只有一条路径。
        var allowRange = true

        while (attempt < maxAttempts) {
            attempt++
            try {
                coroutineContext.ensureActive()
                val url = urlProvider()
                val result = attemptOnce(
                    url = url,
                    target = target,
                    partFile = partFile,
                    attempt = attempt,
                    expectedTotal = expectedTotal,
                    allowRange = allowRange,
                    onProgress = onProgress,
                    beforePublish = beforePublish,
                )
                if (result != null) return result
                // result == null 表示这次尝试没有可用的长度信息，需要重来
                lastError = NetworkError.Malformed(url, "服务端未返回可用的内容长度")
            } catch (e: RangeNotSatisfiable) {
                Timber.tag("dl").d("服务端返回 416，丢弃本地偏移重新下载：%s", target.name)
                partFile.delete()
                allowRange = false
                lastError = NetworkError.Malformed(target.name, "服务端要求重新下载")
                continue
            } catch (e: RangeMismatch) {
                // 区间对不上：清掉本地偏移，下一次不带 Range 重来
                Timber.tag("dl").w("Content-Range 与本地偏移不一致，重新下载：%s", target.name)
                partFile.delete()
                allowRange = false
                lastError = NetworkError.Malformed(target.name, "续传区间不匹配")
                continue
            } catch (e: OperationCancelledException) {
                partFile.delete()
                return DownloadOutcome.Cancelled
            } catch (e: CancellationException) {
                // 结构化并发取消：保留 .part 以便下次续传
                throw e
            } catch (e: PostProcessFailed) {
                // 后处理（解扰）失败：**绝不发布**——发布出去的就是"没处理过的半成品"，
                // 而调用方只按"文件在不在"判断完成，会把它永远当成成品图。
                // 重试也没有意义（本地解码/编码问题，不是网络问题），所以直接判失败。
                Timber.tag("dl").w(e, "落盘后处理失败，放弃发布：%s", target.name)
                // 后处理阶段可能已经把 .part 写坏（`descrambleInPlace` 的拷贝兜底会原地截断），
                // 所以不保留它续传，避免把坏数据拼进下一次下载。
                partFile.delete()
                return DownloadOutcome.Failed(
                    // 这是本地处理失败，不是网络问题；`toUserMessage()` 会包成
                    // 「返回内容异常（...）」，所以这里只写清"发生了什么 + 该做什么"
                    NetworkError.Malformed(target.name, "图片已下载但无法解扰，请重试"),
                )
            } catch (e: Throwable) {
                val error = NetworkError.from(target.name, e)
                lastError = error
                Timber.tag("dl").w(e, "下载失败（第 %d/%d 次）：%s", attempt, maxAttempts, target.name)
                // **只有网络超时才重试**（用户 2026-10-20 要求）。次数来自设置页
                // 「高级 → 图片加载/下载失败重试次数」；连不上 / 5xx / 内容异常直接判失败。
                if (attempt >= maxAttempts || !error.isTimeoutRetryable()) break
                kotlinx.coroutines.delay(retryDelayMs * attempt)
            }
        }

        return DownloadOutcome.Failed(lastError ?: NetworkError.Malformed(target.name, "未知错误"))
    }

    /** 内部控制流异常：需要"换一种请求方式重来"，不是最终结果。 */
    private class RangeMismatch : Exception()

    /** 内部控制流异常：服务端要求客户端换一个区间。 */
    private class RangeNotSatisfiable : Exception()

    /**
     * 内部控制流异常：[download] 的 `beforePublish` 钩子失败。
     *
     * 与网络错误区分开：**不重试**（重试只会再跑一次同样的解码/编码），
     * 而且必须保证最终路径上什么都没有留下。
     */
    private class PostProcessFailed(cause: Throwable) : Exception(cause.message, cause)

    /**
     * 一次尝试。返回 null 表示"这次没拿到长度信息"，需要上层重试。
     */
    private suspend fun attemptOnce(
        url: String,
        target: File,
        partFile: File,
        attempt: Int,
        expectedTotal: Long,
        allowRange: Boolean,
        onProgress: ((FileProgress) -> Unit)?,
        beforePublish: ((File) -> Unit)?,
    ): DownloadOutcome? = withContext(Dispatchers.IO) {
        val existing = if (allowRange && partFile.isFile) partFile.length() else 0L
        val request = Request.Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body ?: throw NetworkError.Malformed(safeUrl(url), "响应体为空")
            val code = response.code
            val contentRange = response.header("Content-Range")
            val contentLength = body.contentLength()

            when {
                // 416：请求的区间超出资源长度。交给外层丢掉 .part 重来。
                code == 416 -> throw RangeNotSatisfiable()

                // 206：从 existing 继续。校验服务端返回的起点与我们的请求一致。
                code == 206 && existing > 0 -> {
                    val start = parseRangeStart(contentRange)
                    if (start != null && start != existing) throw RangeMismatch()
                    val total = parseRangeTotal(contentRange) ?: expectedTotal.takeIf { it > 0 } ?: -1L
                    val written = writeBody(
                        body = body,
                        partFile = partFile,
                        append = true,
                        alreadyDone = existing,
                        total = total,
                        url = url,
                        attempt = attempt,
                        onProgress = onProgress,
                    )
                    publishAfterValidation(partFile, target, total, expectedTotal, existing, beforePublish)
                }

                // 200：服务端忽略了 Range（或本来就没有 .part）。**必须从头写**。
                code == 200 -> {
                    if (existing > 0) {
                        Timber.tag("dl").d("服务端不支持 Range，丢弃已有 %d 字节重新下载", existing)
                        partFile.delete()
                    }
                    val total = if (contentLength > 0) contentLength else expectedTotal
                    val written = writeBody(
                        body = body,
                        partFile = partFile,
                        append = false,
                        alreadyDone = 0L,
                        total = total,
                        url = url,
                        attempt = attempt,
                        onProgress = onProgress,
                    )
                    publishAfterValidation(partFile, target, total, expectedTotal, 0L, beforePublish)
                }

                else -> throw NetworkError.HttpStatus(safeUrl(url), code)
            }
        }
    }

    /**
     * 把响应体写到 `.part`，返回本次会话结束后的文件长度。
     * 每读一块回调一次进度，并顺带按滑动窗口估算速度（UI 与日志用）。
     */
    private suspend fun writeBody(
        body: okhttp3.ResponseBody,
        partFile: File,
        append: Boolean,
        alreadyDone: Long,
        total: Long,
        url: String,
        attempt: Int,
        onProgress: ((FileProgress) -> Unit)?,
    ): Long {
        var written = alreadyDone
        val startedAt = System.currentTimeMillis()
        var bytesThisAttempt = 0L

        RandomAccessFile(partFile, "rw").use { raf ->
            if (append) raf.seek(raf.length()) else raf.setLength(0)
            body.byteStream().use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                    written += read
                    bytesThisAttempt += read
                    onProgress?.invoke(
                        FileProgress(
                            url = safeUrl(url),
                            target = partFile,
                            downloadedBytes = written,
                            totalBytes = total,
                            resumedFrom = alreadyDone,
                            attempt = attempt,
                        ),
                    )
                }
            }
        }

        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val speedKb = bytesThisAttempt * 1000 / elapsed / 1024
        Timber.tag("dl").d("写入完成 %s bytes=%d speed=%dKB/s", partFile.name, written, speedKb)
        return written
    }

    /**
     * 校验长度后把 `.part` 改名成正式文件。
     *
     * 校验规则（对应 §D.1「临时文件校验后再原子发布，排除零字节/损坏文件」）：
     * - 长度必须是已知的权威总长（服务端 Content-Length / Content-Range 总长 / 调用方给的
     *   接口 size）；对不上就抛错保留 `.part` 续传，**绝不发布半成品**；
     * - 权威总长未知时，至少要保证文件非空。
     *
     * 顺序是**先后处理、再发布**（见 [download] 的 `beforePublish`）：中间的 `renameTo`
     * 才是"这张图对外可见"的那一刻，必须保证它看到的内容已经是能直接显示的成品。
     */
    private fun publishAfterValidation(
        partFile: File,
        target: File,
        total: Long,
        expectedTotal: Long,
        resumedFrom: Long,
        beforePublish: ((File) -> Unit)?,
    ): DownloadOutcome {
        val actual = partFile.length()
        val authoritativeTotal = if (total > 0) total else expectedTotal
        if (authoritativeTotal > 0) {
            if (actual != authoritativeTotal) {
                throw NetworkError.Malformed(target.name, "下载不完整：$actual/$authoritativeTotal")
            }
        } else if (actual <= 0) {
            throw NetworkError.Malformed(target.name, "下载结果为空文件")
        }

        // 发布前处理（JM 解扰）。此时 target 还不存在，处理的输入输出都在 `.part` 上；
        // 钩子跑在 per-file 锁内，所以"同一张图被处理两次"在结构上不可能发生。
        if (beforePublish != null) {
            try {
                beforePublish(partFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw PostProcessFailed(e)
            }
        }

        publish(partFile, target)
        return DownloadOutcome.Downloaded(target, target.length(), resumedFrom)
    }

    /** 原子发布：同目录内的 rename 在同一文件系统上是原子的。 */
    private fun publish(partFile: File, target: File) {
        if (target.exists()) target.delete()
        if (!partFile.renameTo(target)) {
            // 少数机型上 rename 会失败，退化为拷贝后删除
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
        }
    }

    /** 已下载的字节数（用于恢复时按磁盘重算进度）。 */
    fun completedBytes(target: File): Long = target.takeIf { it.isFile }?.length() ?: 0L

    /** 是否已经下载完（长度与服务端一致）。 */
    fun isComplete(target: File, expectedTotal: Long): Boolean =
        target.isFile && target.length() > 0 && (expectedTotal <= 0 || target.length() == expectedTotal)

    companion object {
        const val PART_SUFFIX = ".part"

        /** Content-Range: `bytes 123-456/789`，取 123。 */
        internal fun parseRangeStart(header: String?): Long? {
            val slash = header?.indexOf('/') ?: return null
            if (slash <= 0) return null
            val dash = header!!.indexOf('-')
            if (dash <= 0 || dash > slash) return null
            return header.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull()
        }

        /** Content-Range: `bytes 123-456/789`，取 789；`*` 表示未知。 */
        internal fun parseRangeTotal(header: String?): Long? {
            val slash = header?.indexOf('/') ?: return null
            if (slash < 0 || slash == header!!.length - 1) return null
            val total = header.substring(slash + 1).trim()
            if (total == "*") return null
            return total.toLongOrNull()
        }

        private fun safeUrl(url: String): String =
            url.substringBefore('?').substringBefore('#')
    }
}

/**
 * JM 章节图片的并发下载器。
 *
 * 计划 §3.3 与 §8 的语义，全部对齐参考实现 `downloadChapter`：
 * - 按磁盘上是否已存在**跳过**已下载的图片（`.filter { !it.exists() }`）；
 * - 「本次需下载数和上次需下载数相同则终止」的**防死循环重试**；
 * - 并发分片（参考实现是 `CommonUtil.split(list, threads)`，
 *   这里改为滑动并发的 `async` 池，语义等价但不会让某一片特别慢）；
 * - 每张图先落 `.tmp`（引擎的 `.part`）再发布，失败不影响其它图。
 *
 * 进度语义：`done` = 已经存在于磁盘上的图片数（含本次成功的），
 * 这样恢复时重算不会回退、也不会重复累加（§4.5）。
 */
class ParallelImageDownloader(
    private val downloader: FileDownloader,
    private val defaultConcurrency: Int = 4,
) {

    data class ChapterResult(
        val total: Int,
        val done: Int,
        val failed: List<String>,
        val cancelled: Boolean,
    ) {
        val isComplete: Boolean get() = done == total
    }

    /**
     * 下载一批图片。
     *
     * @param images 目标文件名（相对 [chapterDir]）到下载地址的映射；地址用闭包以支持直链刷新
     * @param chapterDir 章节落盘目录（现在存的是**站点原始字节**）
     * @param concurrency 并发数；`<= 0` 时用 [defaultConcurrency]
     * @param seedFor 单张图的**本地种子**（旧版 reader-cache 里的原始字节）：给得出来就省掉
     *        这次网络请求；新版本不再产生这个目录。见 [FileDownloader.download] 的 `seedFile`。
     * @param onFileDownloaded 单张图**真的发生了下载**时回调（跳过已存在的不算）。
     *        调用方用它把"这张图是原始字节"写进图片清单；解扰不在这里做。
     * @param onProgress `(已存在+已成功的数量, 总数)`
     * @param onFileDone 单张图完成（用于仓库层持久化章节计数）
     */
    suspend fun downloadImages(
        images: Map<String, suspend () -> String>,
        chapterDir: File,
        concurrency: Int = defaultConcurrency,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onFileDone: (suspend (fileName: String, success: Boolean) -> Unit)? = null,
        onFileDownloaded: (suspend (fileName: String) -> Unit)? = null,
        seedFor: ((fileName: String) -> File?)? = null,
    ): ChapterResult = coroutineScope {
        chapterDir.mkdirs()
        val total = images.size
        val alreadyOnDisk = images.keys.count { File(chapterDir, it).isFile }
        val doneCounter = AtomicLong(alreadyOnDisk.toLong())
        val failures = ConcurrentHashMap.newKeySet<String>()
        onProgress?.invoke(alreadyOnDisk, total)

        val gate = Mutex()
        val active = AtomicLong(0)
        val limit = if (concurrency > 0) concurrency else defaultConcurrency
        var cancelled = false

        try {
            val jobs = images.map { (fileName, urlProvider) ->
                async(Dispatchers.IO) {
                    val target = File(chapterDir, fileName)
                    if (target.isFile && target.length() > 0) {
                        onFileDone?.invoke(fileName, true)
                        return@async
                    }

                    // 滑动并发：持有令牌直到本轮结束，最慢的一片不会拖住其它图
                    while (true) {
                        coroutineContext.ensureActive()
                        val hasSlot = gate.withLock { active.get() < limit }
                        if (hasSlot) break
                        kotlinx.coroutines.delay(SLOT_POLL_INTERVAL_MS)
                    }
                    active.incrementAndGet()
                    try {
                        val outcome = downloader.download(
                            urlProvider = urlProvider,
                            target = target,
                            skipIfComplete = true,
                            // 原样发布：不解扰、不重编码（解扰在阅读 / 导出时做）
                            beforePublish = null,
                            // 旧版 reader-cache 里已有这张图的原始字节？那就别再联网下一遍
                            seedFile = seedFor?.invoke(fileName),
                        )
                        when (outcome) {
                            is DownloadOutcome.Downloaded -> {
                                // 真的发生了下载：调用方据此标记"原始字节"
                                onFileDownloaded?.invoke(fileName)
                                val done = doneCounter.incrementAndGet()
                                onFileDone?.invoke(fileName, true)
                                onProgress?.invoke(done.toInt(), total)
                            }
                            is DownloadOutcome.Skipped -> {
                                val done = doneCounter.incrementAndGet()
                                onFileDone?.invoke(fileName, true)
                                onProgress?.invoke(done.toInt(), total)
                            }
                            is DownloadOutcome.Failed -> {
                                failures += fileName
                                onFileDone?.invoke(fileName, false)
                                Timber.tag("dl").w("图片下载失败：%s %s", fileName, outcome.error.message)
                            }
                            DownloadOutcome.Cancelled -> {
                                cancelled = true
                            }
                        }
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
            jobs.awaitAll()
        } catch (e: CancellationException) {
            throw e
        }

        ChapterResult(
            total = total,
            done = doneCounter.get().toInt(),
            failed = failures.toList(),
            cancelled = cancelled,
        )
    }

    private companion object {
        const val SLOT_POLL_INTERVAL_MS = 20L
    }
}

/**
 * 下载网络策略闸门。
 *
 * 计划 §9.2：「改为更严格的下载网络策略时，当前传输在**安全检查点**暂停；
 * 恢复前重新检查条件」，以及 §9.5：「移动网络下展示等待原因和前往修改设置入口，
 * **不偷偷开始大下载**」。
 *
 * 所以引擎在每个安全检查点（每张图开始前、每个重试前）问一次这个闸门；
 * 闸门返回 false 时任务进入 `WAITING_NETWORK` 状态而不是失败。
 */
fun interface DownloadGate {
    /** @return true 表示当前网络条件允许开始新的传输。 */
    fun allowNewTransfer(): Boolean

    companion object {
        val AlwaysAllow = DownloadGate { true }

        /** 按策略与"当前是否计费网络"构造闸门。 */
        fun of(policy: DownloadNetwork, isMetered: Boolean): DownloadGate = DownloadGate {
            when (policy) {
                DownloadNetwork.ANY -> true
                DownloadNetwork.WIFI_ONLY -> !isMetered
            }
        }
    }
}

/** 下载任务用的协程作用域标签：方便在日志里认出是哪个任务。 */
fun CoroutineScope.downloadJob(name: String): Job = launch(Dispatchers.IO) {
    Timber.tag("dl").d("启动下载任务 %s", name)
}
