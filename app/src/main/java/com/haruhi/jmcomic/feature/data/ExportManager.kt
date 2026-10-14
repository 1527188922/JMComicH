package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.codec.ArchiveWriter
import com.haruhi.jmcomic.core.codec.PdfWriter
import com.haruhi.jmcomic.core.database.entity.DownloadStatus
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.database.entity.AlbumEntity
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.core.network.toUserMessage
import com.haruhi.jmcomic.core.storage.ExportPublisher
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.ApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** 导出任务的参数快照（`t_download_task.payload`）。**绝不放密码**（计划 §D.2 第 6 条）。 */
@Serializable
data class ExportPayload(
    val albumId: Long,
    /** 本子目录名：章节与图片都在它下面。 */
    val folderName: String,
    /** `zip` 或 `pdf`，决定产物类型。 */
    val format: String,
) {
    fun encode(): String = ApiJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String?): ExportPayload? {
            if (json.isNullOrBlank()) return null
            return runCatching { ApiJson.decodeFromString(serializer(), json) }.getOrNull()
        }
    }
}

/** 导出格式。 */
enum class ExportFormat(val taskType: DownloadType, val extension: String, val mimeType: String, val label: String) {
    ZIP(DownloadType.JM_EXPORT_ZIP, "zip", "application/zip", "ZIP"),
    PDF(DownloadType.JM_EXPORT_PDF, "pdf", "application/pdf", "PDF"),
}

/**
 * "这一本已经导出过这个格式了"，带上文件本身的信息。
 *
 * 详情页要显示文件名、大小与生成时间，所以直接把 [ExportPublisher.ExportFile] 带上，
 * 而不是只回一个布尔值再让界面去查一次。
 */
data class ExportedFormat(
    val format: ExportFormat,
    val file: com.haruhi.jmcomic.core.storage.ExportPublisher.ExportFile,
)

/**
 * 导出编排（计划 §10 P4）：
 * 「zip（AES 密码 / 不加密两种）；pdf（密码 / 不加密、书签、权限、页尺寸）；
 *  导出前的密码框交互；导出进度进入当前模式任务页，不强制跳转」。
 *
 * ## 为什么复用下载任务表
 *
 * `t_download_task` 的 `type` 里本来就有 `JM_EXPORT_ZIP` / `JM_EXPORT_PDF`，
 * 任务页、角标、暂停/取消/重试都是现成的。导出与下载的区别只有两点，都在这里体现：
 * - **不能续做**：中途取消就整份重来（计划 §9 第 9 条「导出不支持中途续做时仅提供取消」），
 *   所以不提供"继续"，只提供取消 + 重新生成；**退出应用时不暂停而是直接删除**
 *   （任务行 + 未完成的中转文件，见 [abortInProgressForExit]），用户回来重新点一次导出；
 * - **要落库到系统目录**：成品先写在 `files/library/export/`，完成后交给 [ExportPublisher]。
 *
 * ## 密码不进数据库
 *
 * 密码存在内存里（[passwords]），只活在本次进程内。理由：
 * 任务参数会进数据库与日志（计划 §D.2 第 6 条），凭据绝不能写进去。
 * 进程退出（不管是正常退出还是被杀）= 导出任务被删掉（本来就不能续做），
 * 密码随之消失是正确行为，不需要也不应该持久化。
 */
class ExportManager(
    private val repo: Repository,
    private val tasks: DownloadTaskRepository,
    private val dirs: LibraryDirs,
    private val archiveWriter: ArchiveWriter,
    private val pdfWriter: PdfWriter,
    private val publisher: ExportPublisher,
    private val scope: CoroutineScope,
    private val onTaskStarted: () -> Unit = {},
) {

    private val jobs = ConcurrentHashMap<Long, Job>()

    /** taskId → 导出密码（`null` = 用户选了"不设密码"）。只在内存里。 */
    private val passwords = ConcurrentHashMap<Long, String>()

    // ------------------------------------------------------------------
    // 缺图预检
    // ------------------------------------------------------------------

    /**
     * 导出前的检查：**缺图时不静默导出残缺内容**（计划 §9 第 7 条）。
     *
     * 返回每一话的"应有多少张 / 实际有多少张"，缺的那些列出来给用户，
     * 由用户决定先补下载还是直接导出（首版选择"先补下载"）。
     *
     * **连图片清单都没有的章节也算"没下完"**（用户反馈的 bug）：
     * 导出是**按磁盘上的本子目录打包**的，而一章的图片清单（`t_jm_chapter_image`）
     * 是"这一话应该有哪些图"的唯一来源——从没下载过、也没在阅读器里点开过的一话
     * 根本没有清单行。之前这种章节被 `continue` 掉，既不计入总数也不算缺图，
     * 于是"完全没下载的本子"预检直接通过、静默导出缺页的包；
     * 只有"下过一部分"的本子才会因为拿到清单而报出缺图。
     * 现在它们会以 [MissingChapter.hasImageList] = false 列进缺失清单，
     * 缺几张无从得知（补下载时会先拉清单再下）。
     */
    data class Precheck(
        val totalImages: Int,
        val readyImages: Int,
        val missingChapters: List<MissingChapter>,
    ) {
        val isComplete: Boolean get() = missingChapters.isEmpty()
        val missingImages: Int get() = totalImages - readyImages
    }

    /**
     * 缺图的一话。
     *
     * [hasImageList] = false 表示**这一话还没下载过**（图片清单都还没拉取），
     * 此时 [missing]/[total] 都是 0：缺几张要等清单拉下来才知道，界面不要显示"0/0"。
     */
    data class MissingChapter(
        val title: String,
        val missing: Int,
        val total: Int,
        val hasImageList: Boolean = true,
    )

    suspend fun precheck(album: AlbumEntity): Precheck = withContext(Dispatchers.IO) {
        val albumId = album.id
        val folder = album.albumFolderName ?: return@withContext Precheck(0, 0, emptyList())
        val chapters = repo.chaptersOf(album)
        var total = 0
        var ready = 0
        val missing = mutableListOf<MissingChapter>()
        for (chapter in chapters) {
            val images = repo.imagesOf(albumId, chapter.chapterId).map { it.imageFile }
            if (images.isEmpty()) {
                // 没清单 = 这一话从没下载过：导出包里不会有它，必须报出来
                // （不能 continue 跳过，那正是"完全没下载的本子预检通过"的原因）
                missing += MissingChapter(chapter.title, missing = 0, total = 0, hasImageList = false)
                continue
            }
            val have = repo.downloadedImageCount(folder, repo.chapterFolderName(chapter), images)
            total += images.size
            ready += have
            if (have < images.size) {
                missing += MissingChapter(chapter.title, images.size - have, images.size)
            }
        }
        Precheck(total, ready, missing)
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 提交一个导出任务。
     *
     * @param password `null`/空串表示"不设密码"
     */
    suspend fun submit(
        album: AlbumEntity,
        format: ExportFormat,
        password: String?,
    ): DownloadTaskRepository.SubmitResult {
        val folderName = album.albumFolderName
            ?: error("本子目录名缺失，无法导出")
        // 文件名用**本子目录名**（`<清洗后的标题>_JM<aid>`），与参考实现一致：
        // 它已经做过文件名清洗、也天然带上 JM 号，不会出现同名覆盖
        val displayName = "$folderName.${format.extension}"

        val existing = tasks.activeTask(format.taskType, album.id.toString())
        if (existing != null) {
            // 同一本同一格式只允许一个活跃导出：重复提交直接复用，不排第二份
            return DownloadTaskRepository.SubmitResult.AlreadyRunning(existing.id, existing.status)
        }

        val payload = ExportPayload(album.id, folderName, format.name.lowercase())
        val result = tasks.submit(
            type = format.taskType,
            bizKey = album.id.toString(),
            displayName = displayName,
            payload = payload.encode(),
            totalChapters = 0,
        )
        if (result is DownloadTaskRepository.SubmitResult.Created) {
            passwords[result.taskId] = password.orEmpty()
            start(result.taskId)
        }
        return result
    }

    fun cancel(taskId: Long) {
        scope.launch {
            runCatching { tasks.markCancelled(taskId) }
            jobs[taskId]?.cancel()
            passwords.remove(taskId)
        }
    }

    /**
     * 退出应用时**删除所有正在进行的导出任务**（用户拍板：导出 = 直接删，不做暂停/恢复）。
     *
     * 为什么是删除而不是暂停：
     * 1. 导出**不能续做**——zip/pdf 打包进行到一半，既没有"断点"可言，也没法像图片那样
     *    "文件已存在即跳过"；
     * 2. 任务行还在的话会**占住同 `(type, bizKey)` 的去重名额**：详情页再点导出会被
     *    [submit] 判成"已有任务"而挡掉，而旧实现又没有续跑的代码，等于卡死。
     *
     * 所以收尾动作就是把任务从根上清掉：取消正在跑的协程 → 删任务行（连同章节明细）→
     * 删掉未完成的中转文件。成品已在系统目录的不受影响；密码本来就只在内存，一并清掉。
     *
     * 与 `DownloadTaskRepository.recoverInterrupted` 里的"杀进程兜底删除"是同一语义的两条路径：
     * 正常退出走这里，杀进程没跑到钩子就在下次启动时由恢复流程删。
     */
    suspend fun abortInProgressForExit() {
        tasks.activeTasks().filter { it.type.isExport }.forEach { task ->
            runCatching {
                jobs.remove(task.id)?.cancel()
                passwords.remove(task.id)
                tasks.delete(task.id)
                // 中转文件写在私有目录（files/library/export/），删任务行时顺带清掉
                task.displayName?.let { File(dirs.export, it).delete() }
                Timber.tag("export").i("退出：删除进行中的导出任务 %d（%s）", task.id, task.displayName)
            }.onFailure { Timber.tag("export").w(it, "退出：清理导出任务失败 %d", task.id) }
        }
    }

    // ------------------------------------------------------------------
    // 成品查询 / 删除（打开至 / 发送至 / 删除文件都用得上）
    // ------------------------------------------------------------------

    /** 该本子这一种格式的成品文件名。 */
    fun outputNameOf(album: AlbumEntity, format: ExportFormat): String? =
        album.albumFolderName?.let { "$it.${format.extension}" }

    /**
     * 成品所在文件夹的 URI（详情页「打开文件夹」用）。
     *
     * 老系统上成品在应用私有目录里、别的应用浏览不了，这时返回 null，
     * 界面就不显示那颗按钮（用户反馈第 2 点）。
     */
    fun outputFolderUri(): android.net.Uri? = publisher.folderUri()

    /**
     * 这个成品现在还在不在（导出确认框用它提示"会覆盖已有文件"）。
     *
     * MediaStore 查询是 IO，所以放到 IO 线程上。
     */
    suspend fun outputExists(album: AlbumEntity, format: ExportFormat): Boolean =
        withContext(Dispatchers.IO) {
            val name = outputNameOf(album, format) ?: return@withContext false
            publisher.infoOf(name) != null
        }

    /**
     * 这一本现有的导出成品（详情页的"导出文件"区块）。
     *
     * 顺序固定 ZIP、PDF，与导出菜单里的顺序一致，界面不用再排。
     */
    suspend fun exportsOf(album: AlbumEntity): List<ExportedFormat> = withContext(Dispatchers.IO) {
        ExportFormat.entries.mapNotNull { format ->
            val name = outputNameOf(album, format) ?: return@mapNotNull null
            publisher.infoOf(name)?.let { ExportedFormat(format, it) }
        }
    }

    /**
     * 一批本子各有哪些成品，"按有没有 zip/pdf 筛选书库"用（用户要求第 1 点）。
     *
     * **一次查询拿全**再在内存里配名字：MediaStore 逐本查的话，一页 60 本就是 120 次查询。
     * 名字规则是 `<本子目录名>.<zip|pdf>`，所以从文件名反推即可，不需要额外映射表。
     *
     * 返回的 map **覆盖传进来的每一本**（没有成品的给空集合）：
     * 界面据此区分"查过、确实没有"与"还没查"，否则第一次进页面会闪一下空列表。
     */
    suspend fun presentFormats(albums: List<AlbumEntity>): Map<Long, Set<ExportFormat>> =
        withContext(Dispatchers.IO) {
            if (albums.isEmpty()) return@withContext emptyMap()
            val names = publisher.listAll().mapTo(mutableSetOf()) { it.displayName }
            albums.associate { album ->
                val folder = album.albumFolderName
                album.id to if (folder == null) {
                    emptySet()
                } else {
                    ExportFormat.entries.filterTo(mutableSetOf()) { "$folder.${it.extension}" in names }
                }
            }
        }

    suspend fun outputUri(displayName: String): android.net.Uri? = withContext(Dispatchers.IO) {
        publisher.infoOf(displayName)?.uri
    }

    /** 删除某一个成品文件（任务页与详情页的「删除文件」）。 */
    suspend fun deleteOutput(displayName: String): Boolean = withContext(Dispatchers.IO) {
        publisher.delete(displayName)
    }

    /**
     * 删除该本子的导出成品（zip + pdf）。
     *
     * "删除本地文件"与"从书库移除"都要连导出成品一起清掉（用户要求第 6、7 点）——
     * 成品在系统下载目录里，不在本子目录下，所以必须单独删。
     *
     * @return 删掉的文件个数
     */
    suspend fun deleteOutputs(album: AlbumEntity): Int = withContext(Dispatchers.IO) {
        ExportFormat.entries.count { format ->
            val name = outputNameOf(album, format) ?: return@count false
            // delete 本身对"文件不在"返回 false，不用先查一次
            publisher.delete(name)
        }
    }

    /**
     * 删除该本子在**私有目录**里的半成品中转文件（`files/library/export/<目录名>.zip|pdf`）。
     *
     * 与 [deleteOutputs] 的区别：那边删的是交给系统目录的**成品**（只有用户显式
     * "删除本地文件"才动），这边删的是没发布成功、或导出被中断后残留在应用私有目录的
     * **中间产物**。「从书库移除」只清半成品，**成品保留**（用户拍板）。
     *
     * @return 删掉的文件个数
     */
    suspend fun deleteTemps(album: AlbumEntity): Int = withContext(Dispatchers.IO) {
        val folder = album.albumFolderName ?: return@withContext 0
        ExportFormat.entries.count { format ->
            File(dirs.export, "$folder.${format.extension}").delete()
        }
    }

    private fun start(taskId: Long) {
        if (jobs[taskId]?.isActive == true) return
        onTaskStarted()
        jobs[taskId] = scope.launch {
            try {
                runTask(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 被取消/暂停时状态已经写好了，不能覆盖成"失败"
                if (tasks.findById(taskId)?.status?.isRunnable != true) {
                    Timber.tag("export").i("导出任务 %d 已停止（非失败）", taskId)
                } else {
                    Timber.tag("export").e(e, "导出任务 %d 失败", taskId)
                    runCatching { tasks.markFailed(taskId, e.toUserMessage()) }
                }
            } finally {
                jobs.remove(taskId)
                passwords.remove(taskId)
            }
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private suspend fun runTask(taskId: Long) {
        val task = tasks.findById(taskId) ?: return
        if (!task.status.isRunnable) return
        // 导出是"不能续做"的长任务，取消只靠这个 Job：写 PDF 的循环每张图问一次
        val job = currentCoroutineContext()[Job]

        val payload = ExportPayload.decode(task.payload)
        if (payload == null) {
            tasks.markFailed(taskId, "导出参数已损坏，请重新发起导出")
            return
        }
        val format = ExportFormat.entries.firstOrNull { it.name.equals(payload.format, true) }
        if (format == null) {
            tasks.markFailed(taskId, "不认识的导出格式")
            return
        }

        val albumDir = dirs.albumDir(payload.folderName)
        if (!albumDir.isDirectory) {
            tasks.markFailed(taskId, "本地没有这本漫画的图片，请先下载")
            return
        }

        tasks.markRunning(taskId)
        // 阶段文案统一带类型：任务页与通知栏都要能一眼看出这是 zip 还是 pdf（与任务卡的
        // 类型标签「生成 ZIP / 生成 PDF」保持同一口径）
        tasks.updateProgress(taskId, stageText = "生成 ${format.label}")

        val password = passwords[taskId].orEmpty()
        val out = File(dirs.export, task.displayName ?: "${payload.folderName}.${format.extension}")
        // 中转文件先清掉，避免上一次的残留被当成结果
        if (out.exists()) out.delete()

        val published = withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            runCatching {
                when (format) {
                    ExportFormat.ZIP -> exportZip(taskId, albumDir, out, password) { job?.isActive != false }
                    ExportFormat.PDF -> exportPdf(taskId, albumDir, out, password) { job?.isActive != false }
                }
            }.getOrElse { e ->
                out.delete()
                throw e
            }
        }

        // 成品交给系统目录，然后清掉私有目录里的中转文件
        val ready = withContext(Dispatchers.IO) {
            publisher.publish(out, out.name, format.mimeType)
        }
        out.delete()

        if (!isRunnable(taskId)) return
        tasks.updateProgress(taskId, stageText = "已保存到 ${ready.location}")
        tasks.markSuccess(taskId)
    }

    private fun exportZip(
        taskId: Long,
        albumDir: File,
        out: File,
        password: String,
        isActive: () -> Boolean,
    ) {
        val result = archiveWriter.albumToZip(albumDir, out, password.ifEmpty { null }) { done, total, chapter ->
            if (!isActive()) throw CancellationException("导出已取消")
            scope.launch {
                tasks.updateProgress(
                    id = taskId,
                    stageText = "生成 ZIP · $chapter",
                    doneImages = done,
                    totalImages = total,
                )
            }
        }
        Timber.tag("export").i("zip 完成：%d 个条目，加密=%s", result.entryCount, result.encrypted)
    }

    private fun exportPdf(
        taskId: Long,
        albumDir: File,
        out: File,
        password: String,
        isActive: () -> Boolean,
    ) {
        val result = pdfWriter.albumToPdf(
            albumDir = albumDir,
            out = out,
            password = password.ifEmpty { null },
            onProgress = { done, total, chapter ->
                scope.launch {
                    tasks.updateProgress(
                        id = taskId,
                        stageText = "生成 PDF · $chapter",
                        doneImages = done,
                        totalImages = total,
                    )
                }
            },
            isCancelled = { !isActive() },
        )
        Timber.tag("export").i(
            "pdf 完成：%d 页 / %d 话（切片 %d 张），加密=%s",
            result.pageCount,
            result.chapterCount,
            result.slicedImageCount,
            result.encrypted,
        )
    }

    private suspend fun isRunnable(taskId: Long): Boolean =
        tasks.findById(taskId)?.status?.isRunnable == true
}
