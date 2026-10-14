package com.haruhi.haruhibox.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.haruhi.haruhibox.MainActivity
import com.haruhi.haruhibox.R

/**
 * 下载通知。
 *
 * 计划 §8「下载」：长下载用**前台服务 + 常驻通知**（进度、暂停/取消）。
 * §9.5：通知权限"显示系统授权状态，提供系统设置入口"，并且
 * "**系统权限状态不伪装为能直接控制的普通开关**"。
 *
 * 所以这里只做两件事：建渠道、发/更新通知；
 * 权限申请与状态展示交给设置页（P6），本类提供 [isNotificationPermissionGranted] 查询。
 */
object DownloadNotifications {

    /** 前台服务通知 id。同一个 id 复用，避免通知栏堆积。 */
    const val PROGRESS_NOTIFICATION_ID = 1001

    private const val CHANNEL_ID = "download_progress"

    /** 渠道在 API 26+ 必须显式创建；只在应用启动时建一次。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_download),
            // LOW：下载进度是持续更新的低频信息，不应该响铃或弹横幅
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_download_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建/更新进度通知。
     *
     * @param indeterminate 总长度未知或刚起任务时用不确定进度条
     * @param cancelIntent 「取消」按钮的 PendingIntent；传 null 则不显示该按钮
     */
    fun buildProgressNotification(
        context: Context,
        title: String,
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        cancelIntent: PendingIntent? = null,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(max.coerceAtLeast(1), progress.coerceIn(0, max.coerceAtLeast(1)), indeterminate)
            .apply { if (cancelIntent != null) addAction(0, context.getString(R.string.action_cancel), cancelIntent) }
            .build()
    }

    /** 发或更新通知。没有通知权限时静默跳过——前台服务仍然能跑，只是不显示。 */
    fun notify(context: Context, notification: Notification) {
        // 权限判断收在 requireNotificationPermission 里：注解必须贴着真正调用的那一行，
        // 所以这里显式再查一次权限（lint 的 MissingPermission 只认 @RequiresPermission
        // 或同一方法体内的 checkSelfPermission）。
        if (!isNotificationPermissionGranted(context)) return
        runCatching {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID) }
    }

    /**
     * 通知权限当前是否可用。
     * API 33 以下默认可用；33+ 需要 `POST_NOTIFICATIONS` 运行时权限。
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
