package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「关于」页存储统计的**归属纯逻辑**测试：目录名 / 成品文件基名 → 本子的解析规则
 * （`<清洗后的标题>_JM<aid>` 尾缀约定，见 `FileNaming.albumFolderName`）。
 */
class StorageAttributionTest {

    @Test
    fun `本子目录名解析出 aid`() {
        assertEquals(123L, StorageStatsCollector.aidFromFolderName("秘密的漫画_JM123"))
        assertEquals(1246359L, StorageStatsCollector.aidFromFolderName("[汉化]某本子_JM1246359"))
    }

    @Test
    fun `成品文件基名同样能解析`() {
        // listAll 拿到的是 `<本子目录名>.zip`，按 baseName 去扩展名后走同一条解析
        assertEquals(123L, StorageStatsCollector.aidFromFolderName("秘密的漫画_JM123"))
    }

    @Test
    fun `名字不符合约定时解析为空`() {
        assertNull(StorageStatsCollector.aidFromFolderName("随便一个文件夹"))
        // 没有下划线前缀、或尾缀不是纯数字，都不算
        assertNull(StorageStatsCollector.aidFromFolderName("JM123"))
        assertNull(StorageStatsCollector.aidFromFolderName("x_JM123abc"))
        assertNull(StorageStatsCollector.aidFromFolderName(""))
    }

    @Test
    fun `标题优先作为展示名`() {
        val album = AlbumBaseEntity(id = 42, name = "标题")
        assertEquals("标题", StorageStatsCollector.titleOf(album))
    }

    @Test
    fun `标题空白时展示名回落 JM 号`() {
        assertEquals("JM42", StorageStatsCollector.titleOf(AlbumBaseEntity(id = 42, name = "  ")))
        assertEquals("JM42", StorageStatsCollector.titleOf(AlbumBaseEntity(id = 42, name = null)))
    }
}
