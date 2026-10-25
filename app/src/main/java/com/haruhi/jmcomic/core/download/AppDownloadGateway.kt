package com.haruhi.jmcomic.core.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import timber.log.Timber

/**
 * 系统下载（[DownloadManager]）的**唯一收口**。
 *
 * ## 为什么要收口
 *
 * `DownloadManager.query()` 按 UID 过滤，返回的是**本应用 enqueue 过的全部下载记录**，
 * 它没有"按用途过滤"的能力（Query 只支持按 id / 状态）。如果各处直接使用，将来
 * 第二个系统下载场景一出现，"哪些记录是干什么的"就无从分辨。所以这里规定：
 * **所有系统下载必须经由此类**——enqueue 时把用途标记写进 `COLUMN_DESCRIPTION`
 * （用户不可见的机器字段，通知里显示的是 title），找回历史记录时按标记过滤。
 *
 * ## 记录即持久层，不做本地持久化
 *
 * 不存 downloadId / 版本号：DownloadManager 的数据库在系统侧、跨进程存活，
 * 记录还在 `records(marker)` 就能找回；应用卸载时系统会连记录带未完成的下载
 * 一起清理。需要"找回上次下载"的场景（如自更新）按标记查询即可。
 *
 * ## remove 的语义（重要）
 *
 * `remove(id)` 在**任意状态**都有效：进行中的会中止，**半成品文件与记录一并删除**
 * （不需要手动清理文件）；但它对已完成的下载同样会**连成品文件一起删**——所以
 * "退出收尾"只应对未完成的记录调用（见 [cancelIncomplete]），已完成的是用户资产。
 *
 * 另外注意：DownloadManager **没有暂停/恢复**，取消即从零重来；网络重试
 * （STATUS_PAUSED + 等待网络）由系统自动处理。
 */
class AppDownloadGateway(context: Context) {

    private val context = context.applicationContext
    private val manager = context.getSystemService(DownloadManager::class.java)

    /** 一条本应用发起的下载记录（[records] 的返回形态）。 */
    data class Record(
        val id: Long,
        /** enqueue 时写入的标题（系统通知里也显示它）。 */
        val title: String,
        /** `DownloadManager.STATUS_*`。 */
        val status: Int,
        val bytesSoFar: Long,
        /** 总字节；服务器没给 Content-Length 时可能为 -1 或 0（界面按"未知"处理）。 */
        val totalBytes: Long,
    ) {
        val successful: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL

        /** 已到终态（成功或失败），不再有进度。 */
        val finished: Boolean
            get() = status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED
    }

    /**
     * 提交一个下载。目标固定落在公共下载目录的 `Download/JMComicH/<subPath>`
     * （与导出成品同根，用户在系统文件管理器里看得到）。
     *
     * @return 下载 id，后续查进度 / 取文件 / 删除都用它。
     * @throws RuntimeException 系统下载服务被禁用（部分定制 ROM）等，调用方需兜底。
     */
    fun enqueue(
        url: String,
        subPath: String,
        title: String,
        marker: String,
        mimeType: String,
    ): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(marker)
            .setMimeType(mimeType)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$SUBFOLDER/$subPath")
            // 下载中显示系统进度通知，完成后保留（点击通知可直接进入安装）
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        return manager.enqueue(request)
    }

    /**
     * 找回某个用途的**全部**下载记录（按 [marker] 过滤）。
     *
     * 这是"跨进程死亡恢复状态"的唯一手段：记录在系统侧，进程被杀它还在。
     * 未完成的记录（排队 / 下载中 / 暂停）也查得到，调用方据此恢复进度展示。
     */
    fun records(marker: String): List<Record> {
        val cursor = runCatching { manager.query(DownloadManager.Query()) }
            .getOrElse { e ->
                Timber.tag("download").w(e, "查询系统下载记录失败")
                return emptyList()
            } ?: return emptyList()
        return cursor.use { c ->
            buildList {
                val idIdx = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleIdx = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val descIdx = c.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
                val statusIdx = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val bytesIdx = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    // 标记不匹配（别的用途的记录）直接跳过
                    if (c.getString(descIdx) != marker) continue
                    add(
                        Record(
                            id = c.getLong(idIdx),
                            title = c.getString(titleIdx).orEmpty(),
                            status = c.getInt(statusIdx),
                            bytesSoFar = c.getLong(bytesIdx),
                            totalBytes = c.getLong(totalIdx),
                        ),
                    )
                }
            }
        }
    }

    /** 取消并删除记录与文件（见类注释里的语义警告）。 */
    fun remove(vararg ids: Long) {
        if (ids.isEmpty()) return
        runCatching { manager.remove(*ids) }
            .onFailure { Timber.tag("download").w(it, "删除系统下载记录失败：%s", ids.joinToString()) }
    }

    /** 已完成下载的内容 Uri（安装 / 分享用）。记录不在或未完成返回 null。 */
    fun downloadedFileUri(id: Long): Uri? =
        runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()

    /**
     * 已完成的下载是否**真的还能读**：记录成功但文件被用户在文件管理器删掉，
     * 或清除应用数据后 URI 权限失效时返回 false。
     *
     * 自更新用它决定弹框显示「安装」还是退回「下载」，避免出现点了必报
     * “无法启动安装”的死按钮。调用方应放在 IO 线程。
     */
    fun isDownloadedFileReadable(id: Long): Boolean {
        val uri = downloadedFileUri(id) ?: return false
        return runCatching {
            // 长度为 UNKNOWN_LENGTH(-1) 也视为可用，只有 0 字节的坏包不算
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } == true
        }.getOrDefault(false)
    }

    /**
     * 退出应用时的收尾：取消**未完成**的下载（半成品文件随之清理），
     * **保留已完成**的——成品要留给下次检查时在弹框里显示「安装」。
     */
    fun cancelIncomplete(marker: String) {
        val ids = records(marker).filter { !it.finished }.map { it.id }
        if (ids.isNotEmpty()) remove(*ids.toLongArray())
    }

    companion object {
        /** 公共下载目录下的根子目录（与导出成品的 `Download/JMComicH` 同名同处）。 */
        const val SUBFOLDER = "JMComicH"

        /**
         * 用途标记登记处。新增系统下载场景时在这里加一个常量，
         * 并沿用"enqueue 打标记、records 按标记找回"的约定。
         */
        const val MARKER_APP_UPDATE = "app-update"

        /**
         * APK 的标准 MIME。注意是 `android.package`（点号），
         * 写成 `android-package` 会匹配不到系统安装器，表现为“无法启动安装”。
         */
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
