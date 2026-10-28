package com.haruhi.jmcomic

import android.app.Application
import android.content.Context
import com.haruhi.jmcomic.core.di.AppContainer
import com.haruhi.jmcomic.core.download.DownloadTaskRepository
import com.haruhi.jmcomic.core.notification.DownloadNotifications
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口。
 *
 * 职责（P0/P1 基线，全部是壳级别的准备工作）：
 * 1. 初始化日志（Timber）＋ **崩溃日志本地留存**（计划 §8「崩溃日志本地留存」）；
 * 2. 创建依赖注入容器（[AppContainer]）；
 * 3. 建好本地目录。
 *
 * 这里**不做**权限申请、不做首次引导（计划 §9 第 1 条：开屏只有漫画/视频两个入口）。
 */
class JmComicApp : Application() {

    /** 依赖注入容器。Activity / ViewModel 通过 [from] 取。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installLogging()
        container = AppContainer(this)
        DownloadNotifications.ensureChannel(this)
        recoverInterruptedDownloads()
        Timber.i(
            "JMComicH 启动 version=%s debug=%s library=%s",
            BuildConfig.VERSION_NAME,
            BuildConfig.DEBUG,
            container.libraryDirs.root.absolutePath,
        )
    }

    /**
     * 启动时恢复被中断的下载任务：**只收尾，不自动续跑**（用户拍板的行为）。
     *
     * 退出应用（返回键 / 划掉最近任务）时正常路径是 `AppContainer.handleAppExit`
     * 已经把任务暂停/删除好了；这里负责的是**进程被杀、退出钩子没跑到**的兜底：
     * - 漫画图片下载 → 按磁盘现状重算进度后置为 `PAUSED`，显示"已暂停"，
     *   等用户在任务页点"继续"续传；
     * - zip/pdf 导出 → 直接删除（任务行 + 未完成的中转文件），用户重新点导出即可。
     *
     * 所以**不会**在这里调用任何 `start` / `resumeQueued`：重启后不自己接着下，
     * 一切都等用户明确操作（任务页的"继续"、详情页的重新提交）。
     *
     * 章节清单从数据库取（`t_jm_chapter_image` 是权威来源），不需要联网。
     */
    private fun recoverInterruptedDownloads() {
        container.appScope.launch {
            runCatching {
                val recovered = container.downloadTasks.recoverInterrupted { task ->
                    val albumId = task.bizKey.toLongOrNull() ?: return@recoverInterrupted emptyList()
                    val album = container.database.albumDao().findById(albumId)
                        ?: return@recoverInterrupted emptyList()
                    val folderName = album.albumFolderName
                        ?: return@recoverInterrupted emptyList()

                    // 章节目录名要用**当前**章节列表算出来的唯一存储键：章节行里的
                    // `chapter_title` 是展示名，重名章节（同一个 sort 出现两次）会指向同一个
                    // 目录，按它算进度会张冠李戴。取不到才退回章节行里的名字。
                    val chapterFolders = runCatching {
                        container.jmRepository.chapterFolders(albumId)
                    }.getOrDefault(emptyMap())

                    val chapters = container.database.chapterDao().chapterList(albumId)
                    chapters.map { chapter ->
                        val chapterFolder = chapterFolders[chapter.chapterId]
                            ?: chapter.chapterTitle
                            ?: chapter.chapterName
                            ?: chapter.chapterId.toString()
                        val imageFiles = container.database.chapterDao()
                            .images(albumId, chapter.chapterId)
                            .map { it.imageFile }
                        DownloadTaskRepository.ChapterRediscovery(
                            chapterId = chapter.chapterId,
                            chapterDir = container.libraryDirs.chapterDir(folderName, chapterFolder),
                            imageFiles = imageFiles,
                        )
                    }
                }
                if (recovered.isEmpty()) return@launch
                Timber.i("启动恢复：%d 个被中断的任务已暂停（导出任务已删除）", recovered.size)
            }.onFailure { Timber.w(it, "恢复中断任务失败") }
        }
    }

    private fun installLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 线上与调试都落一份崩溃日志：用户报问题时可以直接导出这个文件
        val crashDir = File(filesDir, "crash")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(crashDir, thread, throwable) }
                .onFailure { Timber.w(it, "写崩溃日志失败") }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(crashDir: File, thread: Thread, throwable: Throwable) {
        crashDir.mkdirs()
        // 只保留最近 20 个崩溃文件，避免无限增长
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES - 1)
            ?.forEach { it.delete() }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(crashDir, "crash-$timestamp.txt")
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        file.writeText(
            buildString {
                appendLine("time: $timestamp")
                appendLine("thread: ${thread.name}")
                appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("debug: ${BuildConfig.DEBUG}")
                appendLine()
                append(stackTrace)
            },
            Charsets.UTF_8,
        )
    }

    companion object {
        private const val MAX_CRASH_FILES = 20

        /** 从任意 [Context] 取容器。 */
        fun from(context: Context): AppContainer =
            (context.applicationContext as JmComicApp).container
    }
}
