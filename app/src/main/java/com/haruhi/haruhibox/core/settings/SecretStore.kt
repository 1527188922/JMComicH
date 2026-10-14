package com.haruhi.haruhibox.core.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感凭据存储（zip/pdf 密码、B站 Cookie、代理密码）。
 *
 * 计划 §9.2 / §D.2 第 6 条的要求：
 * Cookie、密码不应明文进入日志、任务 payload 或默认备份；凭据存储可评估 Android Keystore。
 *
 * 实现：
 * - 用一个 **Android Keystore 里的 AES-256-GCM 密钥**（`jm-secrets`）加解密；
 * - 密文（IV + 数据）base64 后存进私有 SharedPreferences（`secrets.xml`）；
 * - Keystore 密钥不可导出；卸载应用即销毁；
 * - **不写入任何日志**（只在解密失败时打一条不含内容的告警）；
 * - 备份规则里排除该文件（见 `res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`）。
 *
 * 接口刻意是同步的：设置读取发生在网络请求构造路径上，包一层 suspend 只会增加复杂度。
 * 实际开销是一次 Keystore 解密（微秒级）加上一次 SharedPreferences 读取。
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读一个凭据；不存在或解密失败返回 null。 */
    fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(encoded) }
            .onFailure { Timber.w(it, "凭据解密失败，按未配置处理") }
            .getOrNull()
    }

    /** 写一个凭据；[value] 为 null 或空串时删除该项（"清除凭据"语义）。 */
    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            clear(key)
            return
        }
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** 是否已配置（UI 只展示"已配置/未配置"，不展示内容）。 */
    fun isConfigured(key: String): Boolean = get(key).isNullOrEmpty().not()

    /** 清除某一组凭据，返回清除的条数。 */
    fun clearAll(vararg keys: String): Int {
        var removed = 0
        prefs.edit().also { editor ->
            for (key in keys) {
                if (prefs.contains(key)) {
                    editor.remove(key)
                    removed++
                }
            }
        }.apply()
        return removed
    }

    // ------------------------------------------------------------------

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, 1)
        encrypted.copyInto(packed, 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = packed[0].toInt()
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val payload = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：设置页读取凭据时不应该弹指纹
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "secrets"
        private const val KEY_ALIAS = "jm-secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
