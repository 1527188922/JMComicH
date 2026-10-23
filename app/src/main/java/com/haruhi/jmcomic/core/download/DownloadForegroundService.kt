package com.haruhi.jmcomic.core.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.haruhi.jmcomic.JmComicApp
import com.haruhi.jmcomic.core.database.entity.DownloadType
import com.haruhi.jmcomic.core.notification.DownloadNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 下载前台服务。
 *
 * 计划 §8「下载」：长下载用**前台服务 + 常驻通知**（进度、暂停/取消）。
 * §D.1 还要求明确"WorkManager 与前台服务谁执行任务"，本工程的取舍是：
 *
 * - **前台服务负责"进程活着且用户可见的传输"**：进度通知、暂停/取消按钮；
 * - **WorkManager 负责"调度决策"**：把任务排进队列、在约束满足（例如仅 Wi-Fi）时拉起服务；
 *   本轮只落了服务的骨架与恢复编排，WorkManager 的 Worker 与约束在 P1 收尾时接。
 *
 * 服务本身不直接跑下载逻辑，只订阅仓库的活跃任务并把进度映射成通知——
 * 这样"谁执行"只有 [DownloadTaskExecutor] 一个答案，服务是可替换的展示层。
 */
class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
        startForegroundCompat()
        observeJob = scope.launch {
            val container = JmComicApp.from(this@DownloadForegroundService)
            val repository = container.downloadTasks
            repository.observeActive().collectLatest { tasks ->
                if (tasks.isEmpty()) {
                    stopSelfSafely()
                    return@collectLatest
                }
                val running = tasks.firstOrNull { it.status == com.haruhi.jmcomic.core.database.entity.DownloadStatus.RUNNING }
                    ?: tasks.first()
                val total = when {
                    running.totalBytes > 0 -> running.totalBytes.toInt()
                    running.totalImages > 0 -> running.totalImages
                    else -> 0
                }
                val done = when {
                    running.totalBytes > 0 -> running.downloadedBytes.toInt()
                    running.totalImages > 0 -> running.doneImages
                    else -> 0
                }
                // 文字隐私（隐私模式 && 文字隐私）：通知标题会露书名，换成 JM 号。
                // 两个开关都是同步初值的 StateFlow，服务里直接读当前值即可。
                val textPrivacy = container.privacyMode.value && container.textPrivacy.value
                val albumId = running.bizKey.toLongOrNull()
                val title = if (textPrivacy && albumId != null && running.type.name.startsWith("JM")) {
                    "JM$albumId"
                } else {
                    running.displayName ?: "下载中"
                }
                DownloadNotifications.notify(
                    this@DownloadForegroundService,
                    DownloadNotifications.buildProgressNotification(
                        context = this@DownloadForegroundService,
                        title = title,
                        text = buildText(running, tasks.size),
                        progress = done,
                        max = total,
                        indeterminate = total <= 0,
                    ),
                )
            }
        }
    }

    private fun buildText(
        task: com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity,
        activeCount: Int,
    ): String {
        val stage = task.stageText ?: defaultStageText(task)
        val detail = when {
            task.totalImages > 0 -> "${task.doneImages}/${task.totalImages} 张"
            task.totalBytes > 0 -> "${task.downloadedBytes / 1024 / 1024}MB/" +
                "${task.totalBytes / 1024 / 1024}MB"
            task.totalChapters > 0 -> "${task.doneChapters}/${task.totalChapters} 话"
            else -> ""
        }
        val suffix = if (activeCount > 1) "（共 $activeCount 个任务）" else ""
        return listOf(stage, detail).filter { it.isNotEmpty() }.joinToString(" · ") + suffix
    }

    /**
     * 任务还没上报阶段文案时的兜底（刚提交、还在排队的那几秒）。
     *
     * 至少要让人看出这是"下载图片 / 生成 ZIP / 生成 PDF"哪一种，
     * 而不是笼统的"下载中"——任务真正跑起来后由执行器写入带话数的完整文案。
     */
    private fun defaultStageText(
        task: com.haruhi.jmcomic.core.database.entity.DownloadTaskEntity,
    ): String = when (task.type) {
        // core 层不反向依赖 feature，所以这里的文案与 DownloadManager.STAGE_DOWNLOADING 同字面量
        DownloadType.JM_ALBUM -> "下载图片"
        DownloadType.JM_EXPORT_ZIP -> "生成 ZIP"
        DownloadType.JM_EXPORT_PDF -> "生成 PDF"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> startForegroundCompat()
        }
        // 不用 START_STICKY：任务状态由数据库驱动，进程重启后由启动恢复流程收尾
        // （下载恢复为"已暂停"、导出直接删除），让系统"复活"一个空服务没有意义。
        return START_NOT_STICKY
    }

    /**
     * 用户"划掉最近任务"退出时的收尾：**下载暂停、导出任务删除**（用户拍板的行为）。
     *
     * 具体逻辑统一放在 `AppContainer.handleAppExit()` 里，这里不自己实现一套——
     * 返回键退出（`MainActivity.onDestroy`）走的是同一个入口，两条路径必须结果一致；
     * 而且划掉任务时两个钩子可能都被调用，入口本身是幂等的。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching { JmComicApp.from(this).handleAppExit() }
            .onFailure { Timber.tag("svc").w(it, "退出收尾失败") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        DownloadNotifications.cancel(this)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                DownloadNotifications.PROGRESS_NOTIFICATION_ID,
                DownloadNotifications.buildProgressNotification(
                    context = this,
                    title = "下载中",
                    text = "准备中",
                    progress = 0,
                    max = 0,
                    indeterminate = true,
                ),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.onFailure { Timber.tag("svc").w(it, "启动前台服务失败（可能缺少通知权限或系统限制）") }
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.haruhi.jmcomic.action.STOP_DOWNLOAD"

        /** 由任务执行器调用：有活跃任务时拉起服务。重复调用是安全的。 */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.tag("svc").w(it, "拉起下载服务失败") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
