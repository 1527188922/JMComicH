package com.haruhi.jmcomic.core.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 导出编码设置：默认有损、编码往返、脏值安全回落。 */
class ImageEncodingTest {

    @Test
    fun `default is lossy`() {
        assertThat(ImageEncoding.DEFAULT).isEqualTo(ImageEncoding.LOSSY)
        assertThat(SettingDefaults.Jm.IMAGE_ENCODING)
            .isEqualTo(SettingDefaults.Jm.IMAGE_ENCODING_LOSSY)
    }

    @Test
    fun `stored codes round trip`() {
        ImageEncoding.entries.forEach { mode ->
            assertThat(ImageEncoding.fromCode(mode.toCode())).isEqualTo(mode)
        }
    }

    @Test
    fun `unknown or missing codes fall back to lossy`() {
        assertThat(ImageEncoding.fromCode(null)).isEqualTo(ImageEncoding.LOSSY)
        assertThat(ImageEncoding.fromCode(99)).isEqualTo(ImageEncoding.LOSSY)
        assertThat(ImageEncoding.fromCode(-1)).isEqualTo(ImageEncoding.LOSSY)
    }

    @Test
    fun `the two options have distinct labels`() {
        assertThat(ImageEncoding.entries.map { it.label }.toSet()).hasSize(2)
    }
}
