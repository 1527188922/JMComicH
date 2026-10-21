package com.haruhi.jmcomic.core.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 取图管线的存储编码：默认原图缓存，旧值/脏值一律安全落地。 */
class ReaderCacheModeTest {

    @Test
    fun `default is the raw pipeline`() {
        assertThat(ReaderCacheMode.DEFAULT).isEqualTo(ReaderCacheMode.RAW)
        assertThat(SettingDefaults.Jm.READER_CACHE_MODE).isEqualTo(SettingDefaults.Jm.READER_CACHE_MODE_RAW)
    }

    @Test
    fun `stored codes round trip`() {
        ReaderCacheMode.entries.forEach { mode ->
            assertThat(ReaderCacheMode.fromCode(mode.toCode())).isEqualTo(mode)
        }
    }

    @Test
    fun `legacy and unknown codes fall back safely`() {
        // 旧行为（解扰后落盘）仍然认得出来：切回来就是原来那套
        assertThat(ReaderCacheMode.fromCode(SettingDefaults.Jm.READER_CACHE_MODE_DESCRAMBLED))
            .isEqualTo(ReaderCacheMode.DESCRAMBLED)
        // 没设置过 / 脏数据：走默认
        assertThat(ReaderCacheMode.fromCode(null)).isEqualTo(ReaderCacheMode.DEFAULT)
        assertThat(ReaderCacheMode.fromCode(99)).isEqualTo(ReaderCacheMode.DEFAULT)
        assertThat(ReaderCacheMode.fromCode(-1)).isEqualTo(ReaderCacheMode.DEFAULT)
    }

    @Test
    fun `labels are distinct and user facing`() {
        assertThat(ReaderCacheMode.entries.map { it.label }.toSet())
            .containsExactly("省空间（推荐）", "存成品图")
    }
}
