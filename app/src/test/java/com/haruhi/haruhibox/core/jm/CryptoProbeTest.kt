package com.haruhi.haruhibox.core.jm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * **探针测试**（保留作为环境回归）：确认单元测试环境下 `MessageDigest` 与
 * `javax.crypto.Cipher` 是否可用。
 *
 * 背景：AGP 的 mockable `android.jar`（`unitTests.isReturnDefaultValues = true`）
 * 会把 JCE 一起打桩，`Cipher.getInstance` 会抛
 * `NoClassDefFoundError: javax.crypto.JarVerifier`；
 * Robolectric 的沙箱又会把 Conscrypt provider 泄漏给同一 JVM 的后续测试。
 *
 * 这两条结论都记在 `docs/p0/README.md`（D-5、D-6）。
 * **如果这两个用例失败，说明构建配置被人改动了**，请先看那两条决策再看业务代码。
 */
class CryptoProbeTest {

    @Test
    fun `message digests are available in unit tests`() {
        val md5 = MessageDigest.getInstance("MD5").digest("abc".toByteArray())
        assertThat(md5).hasLength(16)
    }

    @Test
    fun `AES cipher is available in unit tests`() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        assertThat(cipher.doFinal("hello".toByteArray())).hasLength(16)
    }
}
