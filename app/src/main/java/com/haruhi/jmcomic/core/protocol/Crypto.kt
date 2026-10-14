package com.haruhi.jmcomic.core.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JM 接口的签名与响应解密。
 *
 * 逐行对齐参考实现 `JmcomicService`：
 * - `headerParam(long ts)`（第 1485～1494 行）：普通接口签名，secret = `18comicAPP`
 * - `getScrambleId`（第 1443～1448 行）：内容类接口签名，secret = `18comicAPPContent`
 * - `decryptData(long ts, String)`（第 1496～1519 行）：AES 解密
 * - `calculateBlockNum`（第 1072～1085 行）：解扰块数
 *
 * **签名只依赖时间戳，不依赖登录态**，所以本 App 不做 JM 账号。
 * 重试时必须沿用第一次请求的 ts，否则响应无法用同一个 key 解密。
 */
object Crypto {

    /** 普通接口（/album、/search、/chapter）的签名密钥 */
    const val APP_TOKEN_SECRET = "18comicAPP"

    /** 内容类接口（/chapter_view_template）的签名密钥 */
    const val APP_TOKEN_SECRET_CONTENT = "18comicAPPContent"

    /** 响应解密的密钥种子 */
    const val APP_DATA_SECRET = "185Hcomic3PAPP7R"

    /**
     * JM App 协议版本，参与 `tokenparam`。
     * 参考实现为常量 2.0.13；本工程留成可覆盖参数，便于日后远端接口变更。
     */
    const val APP_VERSION = "2.0.13"

    private const val HEX = "0123456789abcdef"

    private val md5: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    fun md5Hex(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

    fun md5Hex(bytes: ByteArray): String {
        val digest = md5.get()!!.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 普通接口的签名 header。
     * `token = md5(ts + "18comicAPP")`，`tokenparam = "$ts,2.0.13"`。
     */
    fun headerParams(tsSeconds: Long, appVersion: String = APP_VERSION): HeaderParams =
        headerParams(tsSeconds, APP_TOKEN_SECRET, appVersion)

    /** 内容类接口（/chapter_view_template）的签名 header。 */
    fun contentHeaderParams(tsSeconds: Long, appVersion: String = APP_VERSION): HeaderParams =
        headerParams(tsSeconds, APP_TOKEN_SECRET_CONTENT, appVersion)

    private fun headerParams(tsSeconds: Long, secret: String, appVersion: String): HeaderParams =
        HeaderParams(
            token = md5Hex("$tsSeconds$secret"),
            tokenParam = "$tsSeconds,$appVersion",
        )

    /**
     * 解密响应 JSON 的 `data` 字段。
     *
     * 关键点（写错会导致解密失败）：key 不是 md5 摘要的前 16 字节，而是
     * `md5(ts + "185Hcomic3PAPP7R")` 得到的 **32 字符十六进制字符串**，取其
     * **ASCII 字节**（32 字节）作为 AES-256 密钥。
     */
    fun decryptData(tsSeconds: Long, encryptedBase64: String): String {
        val keyHex = md5Hex("$tsSeconds$APP_DATA_SECRET")
        val keyBytes = keyHex.toByteArray(Charsets.US_ASCII)
        require(keyBytes.size == 32) { "JM AES key must be 32 bytes, got ${keyBytes.size}" }
        val cipher = newJmCipher(Cipher.DECRYPT_MODE, keyBytes)
        val decrypted = cipher.doFinal(decodeBase64(encryptedBase64))
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * JM 协议固定使用 `AES/ECB/PKCS5Padding`（参考实现 `decryptData` 第 1507 行）。
     * ECB 在这里不安全也不由我们选择：这是服务端的既定协议，改模式会导致解密失败。
     * lint 的 `GetInstance` 警告因此在本文件被抑制。
     */
    @Suppress("GetInstance")
    private fun newJmCipher(mode: Int, keyBytes: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"))
        return cipher
    }

    /**
     * 与 `org.apache.commons.codec.binary.Base64.decodeBase64` 行为一致：
     * 宽松模式（允许换行、忽略非法字符、容忍缺失的 `=` 填充），而不是 java.util.Base64 的严格模式。
     */
    private fun decodeBase64(text: String): ByteArray {
        val cleaned = StringBuilder(text.length)
        for (c in text) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            if (c == '=') break
            cleaned.append(c)
        }
        if (cleaned.isEmpty()) return ByteArray(0)
        // 补足到 4 的倍数，等价于 commons-codec 的非严格解码
        while (cleaned.length % 4 != 0) cleaned.append('=')
        return java.util.Base64.getDecoder().decode(cleaned.toString())
    }

    /** 仅测试用：用与 [decryptData] 相同的密钥做一次加密，用于验证密钥推导。 */
    internal fun encryptDataForTest(tsSeconds: Long, plainText: String): String {
        val keyHex = md5Hex("$tsSeconds$APP_DATA_SECRET")
        val cipher = newJmCipher(Cipher.ENCRYPT_MODE, keyHex.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
    }

    /**
     * 解扰块数（参考实现 `JmcomicService.calculateBlockNum`）。
     *
     * 规则：
     * 1. `chapterId < scrambleId` → 未加扰，返回 0
     * 2. `chapterId < 268850` → 固定 10 块
     * 3. 否则 `x = if (chapterId < 421926) 10 else 8`，
     *    `blockNum = (md5(chapterId + filenameWithoutExt) 最后一个字符的 code point) % x * 2 + 2`
     *
     * **注意**：最后一步是对十六进制字符的**字符码点**取模（`'a'` = 97、`'3'` = 51），
     * 不是把该字符解析成十六进制数值。参考实现写的就是 `(int) lastChar % x`。
     *
     * @param filenameWithoutExt 图片文件名去掉扩展名后的部分
     */
    fun calculateBlockNum(scrambleId: Long, chapterId: Long, filenameWithoutExt: String): Int {
        if (chapterId < scrambleId) return 0
        if (chapterId < 268_850L) return 10
        val x = if (chapterId < 421_926L) 10 else 8
        val md5Hex = md5Hex("$chapterId$filenameWithoutExt")
        val lastChar = md5Hex[md5Hex.length - 1]
        return (lastChar.code % x) * 2 + 2
    }
}

/** JM 请求需要的两个 header，与参考实现 `headerParam(ts)` 一一对应。 */
data class HeaderParams(
    val token: String,
    val tokenParam: String,
)
