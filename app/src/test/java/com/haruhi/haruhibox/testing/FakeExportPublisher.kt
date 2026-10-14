package com.haruhi.haruhibox.testing

import com.haruhi.haruhibox.core.storage.ExportPublisher
import java.io.File

/**
 * 假的成品交付器：把成品放在一个临时目录里，完全不碰 MediaStore。
 *
 * 三个测试类都要用（导出编排、删除编排、按成品筛选），所以抽到 `testing` 里共用一份——
 * 各写一份的话，接口一变就要改三处（`exists`/`uriOf` 换成 `infoOf`/`listAll` 时就踩过）。
 */
class FakeExportPublisher(val dir: File) : ExportPublisher {

    /** 走过 [publish] 的文件名，按调用顺序（导出编排用它断言"交了什么"）。 */
    val publishedNames = mutableListOf<String>()

    /** [listAll] 被调了几次：书库筛选必须**整页只查一次**，不是每本查一次。 */
    var listAllCalls = 0
        private set

    override val locationLabel: String = "下载/HaruHiBox"

    /** 假实现默认"打不开文件夹"（真机上只有公共下载目录那条路能打开）。 */
    var folder: android.net.Uri? = null

    override fun folderUri(): android.net.Uri? = folder

    override fun publish(source: File, displayName: String, mimeType: String): ExportPublisher.Published {
        source.copyTo(File(dir, displayName), overwrite = true)
        publishedNames += displayName
        return ExportPublisher.Published(displayName, locationLabel, null)
    }

    /** 内容 Uri：老系统那条路给 null，这里照做（"打开至"在单测里用不到真 Uri）。 */
    override fun infoOf(displayName: String): ExportPublisher.ExportFile? =
        File(dir, displayName).takeIf { it.isFile }?.toExportFile()

    override fun listAll(): List<ExportPublisher.ExportFile> {
        listAllCalls++
        return dir.listFiles().orEmpty().filter { it.isFile }.map { it.toExportFile() }
    }

    override fun delete(displayName: String): Boolean = File(dir, displayName).delete()

    /**
     * 直接在"下载目录"里造一个成品，等价于"之前导出过"。
     *
     * @param modifiedAt 生成时间；不传就用当前时间（MediaStore 给的是文件时间，同源）。
     */
    fun seed(displayName: String, bytes: Int = 16, modifiedAt: Long? = null): File {
        val file = File(dir, displayName)
        file.writeBytes(ByteArray(bytes))
        if (modifiedAt != null) file.setLastModified(modifiedAt)
        return file
    }

    private fun File.toExportFile() = ExportPublisher.ExportFile(
        displayName = name,
        sizeBytes = length(),
        createdAt = lastModified(),
        uri = null,
    )
}
