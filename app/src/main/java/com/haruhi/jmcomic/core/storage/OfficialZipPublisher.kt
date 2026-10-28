package com.haruhi.jmcomic.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File

/**
 * 官方 ZIP 的落盘器：把下载好的 zip 放到
 *
 * ```text
 * Download/JMComicH/官方zip/{本子目录名}/{第几话}.zip
 * ```
 *
 * 与 [ExportPublisher] 分开：导出成品有自己的命名/回读规则，官方 ZIP 只是原样落盘，
 * 不需要和 `下载/JMComicH/<本子>.zip` 混在一起。
 *
 * API 29+ 走 MediaStore.Downloads（不需要权限）；API 26～28 退到应用外部私有目录
 * （别的文件管理器看不到，与现有导出在旧系统上的取舍一致）。
 */
class OfficialZipPublisher(private val context: Context) {

    data class Published(
        val displayName: String,
        val location: String,
        val uri: Uri?,
    )

    /** 公共目录里的一本漫画的官方 zip（只含**直接放在本子目录下**的 `.zip`）。 */
    data class OfficialZipFile(
        val albumFolder: String,
        val displayName: String,
        val sizeBytes: Long,
        val uri: Uri?,
    )

    /**
     * 列出全部官方 zip，按本子目录分组前的原始文件。
     *
     * `官方zip/<本子>/<文件>.zip` 的直接文件才算；`官方zip/<本子>/<解压目录>/...`
     * 里即使有 zip 也不统计、不删除（用户要求"解压的文件夹不用统计也不用删除"）。
     */
    fun listAll(): List<OfficialZipFile> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listMediaStore()
        } else {
            listOldDir()
        }

    /** 删除某一本的全部官方 zip（只删直接子文件里的 `.zip`），返回释放字节数。 */
    fun deleteAlbum(albumFolder: String): Long {
        val folder = safeFolder(albumFolder)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val files = listAll().filter { it.albumFolder == folder }
            var freed = 0L
            files.forEach { file ->
                val uri = file.uri ?: return@forEach
                if (runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                        .getOrDefault(false)
                ) {
                    freed += file.sizeBytes.coerceAtLeast(0L)
                }
            }
            freed
        } else {
            var freed = 0L
            oldDir(folder).listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
                .forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed += size
                }
            freed
        }
    }

    /** 目标 zip 是否已经在公共目录里（用来跳过重复下载）。 */
    fun exists(albumFolder: String, displayName: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findRows(albumFolder, displayName).isNotEmpty()
        } else {
            File(oldDir(albumFolder), displayName).isFile
        }

    fun publish(source: File, albumFolder: String, displayName: String): Published =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(source, albumFolder, displayName)
        } else {
            publishToExternalDir(source, albumFolder, displayName)
        }

    private fun publishToMediaStore(
        source: File,
        albumFolder: String,
        displayName: String,
    ): Published {
        val resolver = context.contentResolver
        val folder = safeFolder(albumFolder)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER/官方zip/$folder/"

        // 同名旧文件先删：MediaStore 会改成 "xxx (1).zip"，回读/打开/删除都会对不上。
        findRows(albumFolder, displayName)
            .mapNotNull { it.first }
            .forEach { uri ->
                runCatching { resolver.delete(uri, null, null) }
                    .onFailure { Timber.tag("officialzip").w(it, "删除旧官方 ZIP 失败：%s", uri) }
            }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_ZIP)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录创建官方 ZIP（MediaStore 返回空）")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入下载目录")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        val actualName = displayNameOf(uri) ?: displayName
        return Published(
            displayName = actualName,
            location = "下载/$SUBFOLDER/官方zip/$folder",
            uri = uri,
        )
    }

    private fun publishToExternalDir(
        source: File,
        albumFolder: String,
        displayName: String,
    ): Published {
        val dir = oldDir(albumFolder).apply { if (!exists() && !mkdirs()) error("无法创建官方 ZIP 目录") }
        val target = File(dir, displayName)
        if (target.exists()) target.delete()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Published(
            displayName = target.name,
            location = "Android/data/${context.packageName}/files/Download/$SUBFOLDER/官方zip/${safeFolder(albumFolder)}",
            uri = null,
        )
    }

    /**
     * 查同一子目录下同名文件。
     *
     * 返回 `(uri, displayName)`；MediaStore 的 `RELATIVE_PATH` 可能带结尾 `/`，
     * 所以用 `LIKE %官方zip/{album}/%` 匹配，避免多一个/少一个斜杠查不到。
     */
    private fun findRows(albumFolder: String, displayName: String): List<Pair<Uri?, String>> {
        val folder = safeFolder(albumFolder)
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        // MediaStore 有的版本存 `.../foo/`、有的存 `.../foo`，两种都匹配
        val basePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER/官方zip/$folder"
        val selection = "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR " +
            "${MediaStore.Downloads.RELATIVE_PATH} = ?) AND " +
            "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val args = arrayOf("$basePath/", basePath, displayName)
        val rows = mutableListOf<Pair<Uri?, String>>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: displayName
                rows += MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    .buildUpon()
                    .appendPath(id.toString())
                    .build() to name
            }
        }
        return rows
    }

    private fun displayNameOf(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Downloads.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun listMediaStore(): List<OfficialZipFile> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.SIZE,
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%官方zip/%")
        val rows = mutableListOf<OfficialZipFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.endsWith(".zip", ignoreCase = true)) continue
                val album = albumFolderOf(cursor.getString(pathCol)) ?: continue
                val id = cursor.getLong(idCol)
                rows += OfficialZipFile(
                    albumFolder = album,
                    displayName = name,
                    sizeBytes = cursor.getLong(sizeCol),
                    uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        .buildUpon()
                        .appendPath(id.toString())
                        .build(),
                )
            }
        }
        return rows
    }

    private fun listOldDir(): List<OfficialZipFile> =
        oldRoot().listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { albumDir ->
                albumDir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
                    .map { file ->
                        OfficialZipFile(
                            albumFolder = albumDir.name,
                            displayName = file.name,
                            sizeBytes = file.length(),
                            uri = null,
                        )
                    }
            }

    /**
     * `Download/JMComicH/官方zip/<album>/` → `<album>`；嵌套目录（解压文件夹）返回 null。
     */
    private fun albumFolderOf(relativePath: String?): String? {
        val path = relativePath ?: return null
        val marker = "官方zip/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        val rest = path.substring(index + marker.length).trim('/')
        if (rest.isEmpty() || '/' in rest) return null
        return rest
    }

    private fun oldRoot(): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download"),
        "$SUBFOLDER/官方zip",
    )

    private fun oldDir(albumFolder: String): File = File(oldRoot(), safeFolder(albumFolder))

    private fun safeFolder(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "未命名" }

    private companion object {
        const val SUBFOLDER = "JMComicH"
        const val MIME_ZIP = "application/zip"
    }
}
