package com.haruhi.jmcomic.core.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.io.File

/**
 * 把导出成品交给系统（计划 §8「**导出 zip/pdf 走 MediaStore（Downloads）或 SAF**」）。
 *
 * 为什么要经过这一层：成品先落在应用私有的 `files/library/export/`（打包过程中的中转），
 * 完成后必须搬到用户能找到的地方，否则"导出"等于没导出。
 *
 * 按系统版本分两条路：
 * - **API 29+**：`MediaStore.Downloads`，**不需要任何权限**，
 *   落到 `Download/JMComicH/`，系统文件管理器里直接能看到；
 * - **API 26～28**：MediaStore 的 Downloads 集合还不存在，写入公共 Downloads 需要
 *   `WRITE_EXTERNAL_STORAGE` 运行时权限。本 App 不主动申请存储权限（计划 §9.1
 *   只要求"首次需要后台任务时"申请通知权限），所以这一档退到应用的外部私有目录
 *   `Android/data/<pkg>/files/Download/`，并把**确切位置**告诉用户。
 *
 * 除了"写进去"，还要能**读回来**：详情页要展示成品的文件名、大小与生成时间，
 * 书库要按"有没有 zip/pdf"筛选，所以这里有 [infoOf] 与 [listAll]。
 */
interface ExportPublisher {
    data class Published(
        /**
         * 系统里的**实际**文件名。
         *
         * 不一定等于请求的名字：下载目录里若已有同名文件（典型来源是上一次安装
         * 留下的、当前应用看不到也删不掉的文件），MediaStore 会在落盘时改名成
         * `xxx (1).zip`，这里回读到的就是那个真实名字。
         */
        val displayName: String,
        /** 给用户看的位置说明（"下载/JMComicH"之类）。 */
        val location: String,
        /** 能拿到内容 Uri 的话带上（用于"打开文件"）。 */
        val uri: Uri?,
    )

    /**
     * 一个已经躺在下载目录里的成品。
     *
     * 详情页要把它**当成文件来介绍**（多大、什么时候生成的、能不能打开），
     * 所以除了名字还要大小与时间。
     */
    data class ExportFile(
        val displayName: String,
        /** 字节数；系统没给这个字段时是 -1。 */
        val sizeBytes: Long,
        /** 生成时间（毫秒，取文件的最后修改时间）；拿不到就是 0，界面显示"时间未知"。 */
        val createdAt: Long,
        /** 内容 Uri；老系统（API 26～28）没有，返回 null。 */
        val uri: Uri?,
    )

    /** 成品放在哪（给用户看的短文案），详情页用它说明"文件在哪儿"。 */
    val locationLabel: String

    /**
     * 成品所在**文件夹**的 URI，用来"打开文件夹"（用户反馈第 2 点）。
     *
     * 能打开的前提是文件落在**公共下载目录**里、且系统有文档提供者（DocumentsUI）：
     * - API 29+ 走 MediaStore 下载目录，返回 `com.android.externalstorage.documents`
     *   的 `primary:Download/JMComicH` 文档 URI，系统文件管理器（DocumentsUI / 文件）
     *   会直接定位到该目录；
     * - API 26～28 那一档成品在应用外部私有目录（`Android/data/<pkg>/files/...`），
     *   **别的应用无权浏览**，所以返回 null——界面据此不显示按钮。
     *
     * 注意这是"让系统去打开"，不是我们自己去读目录：本 App 不需要任何存储权限。
     */
    fun folderUri(): Uri?

    fun publish(source: File, displayName: String, mimeType: String): Published

    /** 查一个成品，不存在返回 null。导出前的"会覆盖"提示、详情页的文件信息都用它。 */
    fun infoOf(displayName: String): ExportFile?

    /**
     * 列出本 App 写进下载目录的**全部**成品。
     *
     * 供"按有没有 zip/pdf 筛选书库"使用：一次查询拿全，避免每本漫画各查一次
     * （一页 60 本的话就是 120 次 MediaStore 查询）。
     */
    fun listAll(): List<ExportFile>

    /** 删除成品。返回是否真的删掉了。 */
    fun delete(displayName: String): Boolean
}

/** 真机实现（见接口注释里的版本分支）。 */
class AndroidExportPublisher(private val context: Context) : ExportPublisher {

    override val locationLabel: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "下载/$SUBFOLDER"
        } else {
            "Android/data/${context.packageName}/files/Download"
        }

    override fun publish(source: File, displayName: String, mimeType: String): ExportPublisher.Published =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(source, displayName, mimeType)
        } else {
            publishToExternalDir(source, displayName)
        }

    /**
     * API 29+ 才有公共下载目录这条路径（老系统在应用私有目录里，别的应用看不到），
     * 所以只在 Q 以上给 URI。
     *
     * 文档 id 必须是 **`primary:Download/JMComicH`** 这种带卷名的形式：
     * 少了 `primary:` 前缀时 DocumentsUI 解析不出这个文档，会退回到"下载"根目录
     * （实测如此），那就等于没定位到成品文件夹。
     */
    override fun folderUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_PROVIDER,
                "$PRIMARY_VOLUME:${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER",
            )
        } else {
            null
        }

    /**
     * API 29+ 才有 `MediaStore.Downloads` 集合；调用点已经用 `SDK_INT` 分支挡住，
     * 这里再标一次是为了让 lint 能跨方法看到这个前提。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(
        source: File,
        displayName: String,
        mimeType: String,
    ): ExportPublisher.Published {
        val resolver = context.contentResolver
        // **先删同类旧文件**：MediaStore 遇到重名会自动改名成"xxx (1).zip"。
        // 除了本安装上一次写出的成品，带系统序号的那些也要一起算进来——否则
        // "旧安装留下的同名文件"占住原名后，每次导出都会变成 (1)、(2)……
        // （旧安装的文件当前应用看不到也删不掉，只能保证序号不再往上加。）
        findRows(displayName = null)
            .filter { FileNaming.matchesExportName(it.displayName, displayName) }
            .mapNotNull { it.uri }
            .forEach { old ->
                runCatching { resolver.delete(old, null, null) }
                    .onFailure { Timber.tag("export").w(it, "覆盖旧成品失败：%s", old) }
            }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录创建文件（MediaStore 返回空）")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入下载目录")

            // 写完才对外可见，避免文件管理器看到半成品
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        // 回读系统最终落盘的文件名：物理目录里若有旧安装留下的同名文件，
        // MediaStore 会改名成 "xxx (1).zip"，必须把真实名字传出去（检测、统计、
        // 打开/删除都按这个真实名字走）。
        val actualName = displayNameOf(uri) ?: displayName
        if (actualName != displayName) {
            Timber.tag("export").w(
                "成品被系统改名：%s → %s（下载目录里有旧安装留下的同名文件，当前应用无法覆盖）",
                displayName,
                actualName,
            )
        }
        Timber.tag("export").i("已导出到下载目录：%s", actualName)
        return ExportPublisher.Published(
            displayName = actualName,
            location = "下载/$SUBFOLDER",
            uri = uri,
        )
    }

    private fun publishToExternalDir(source: File, displayName: String): ExportPublisher.Published {
        // 老系统不申请存储权限，退到应用外部私有目录；位置在文案里说清楚
        val target = File(externalDir(), displayName)
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        Timber.tag("export").i("已导出（应用目录）：%s", target.absolutePath)
        return ExportPublisher.Published(
            displayName = displayName,
            location = locationLabel,
            uri = null,
        )
    }

    /**
     * 查一个成品，不存在返回 null。
     *
     * 先按确切文件名查；查不到再忽略系统重名序号找一次（`xxx (1).zip` 也算
     * `xxx.zip`），这样"旧安装留下的同名文件"把名字挤走之后，仍然认得出
     * "这一本已经有成品了"。
     */
    override fun infoOf(displayName: String): ExportPublisher.ExportFile? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findRows(displayName).firstOrNull()
                ?: findRows(displayName = null)
                    .firstOrNull { FileNaming.matchesExportName(it.displayName, displayName) }
        } else {
            fileOf(displayName)
        }

    override fun listAll(): List<ExportPublisher.ExportFile> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findRows(displayName = null)
        } else {
            externalDir().listFiles().orEmpty().filter { it.isFile }.map { toExportFile(it) }
        }

    override fun delete(displayName: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findRows(displayName).firstOrNull()?.uri ?: return false
            runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                .onFailure { Timber.tag("export").w(it, "删除导出文件失败：%s", displayName) }
                .getOrDefault(false)
        } else {
            File(externalDir(), displayName).delete()
        }

    /** 回读某一行在系统里的真实文件名（MediaStore 重名时会自动改名）。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * 在下载目录里查成品。
     *
     * `displayName` 传 null 就是"列出全部"（书库筛选、存储统计用）；
     * 传名字则按**确切**文件名查——需要容忍系统重名序号的调用方用 [FileNaming.matchesExportName]。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findRows(displayName: String?): List<ExportPublisher.ExportFile> = runCatching {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        val selection = buildString {
            append("${MediaStore.Downloads.RELATIVE_PATH} LIKE ?")
            if (displayName != null) append(" AND ${MediaStore.Downloads.DISPLAY_NAME} = ?")
        }
        val args = if (displayName == null) {
            arrayOf("%$SUBFOLDER%")
        } else {
            arrayOf("%$SUBFOLDER%", displayName)
        }
        val rows = mutableListOf<ExportPublisher.ExportFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toExportFile()
        }
        rows
    }.onFailure { Timber.tag("export").w(it, "查询导出文件失败：%s", displayName) }
        .getOrDefault(emptyList())

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun Cursor.toExportFile(): ExportPublisher.ExportFile {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Downloads._ID))
        return ExportPublisher.ExportFile(
            displayName = getString(getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)).orEmpty(),
            sizeBytes = getLong(getColumnIndexOrThrow(MediaStore.Downloads.SIZE)),
            // DATE_MODIFIED 是**秒**，界面要毫秒
            createdAt = getLong(getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)) * 1000L,
            uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
        )
    }

    /** 老系统那条路的单条查询（应用外部私有目录里的普通文件）。 */
    private fun fileOf(displayName: String): ExportPublisher.ExportFile? {
        val file = File(externalDir(), displayName)
        return if (file.isFile) toExportFile(file) else null
    }

    /**
     * 老系统没有 content Uri：要分享得靠 FileProvider，本 App 没配，
     * 所以这里给 null，界面按 [locationLabel] 告诉用户文件在哪。
     */
    private fun toExportFile(file: File) = ExportPublisher.ExportFile(
        displayName = file.name,
        sizeBytes = file.length(),
        createdAt = file.lastModified(),
        uri = null,
    )

    private fun externalDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    companion object {
        /** 下载目录下的子目录名，避免和用户自己的文件混在一起。 */
        const val SUBFOLDER = "JMComicH"

        /** 系统"外部存储"文档提供者；`primary:` 就是主共享存储，覆盖绝大多数机器。 */
        const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

        /** 主共享存储的卷名。 */
        const val PRIMARY_VOLUME = "primary"
    }
}
