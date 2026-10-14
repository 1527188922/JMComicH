package com.haruhi.haruhibox.core.jm

import com.google.common.truth.Truth.assertThat
import com.haruhi.haruhibox.testing.GoldenData
import org.junit.Test

/**
 * P0 spike 1 的验收测试：JM 签名 + AES 解密 + 解扰块数。
 *
 * 期望值全部来自参考实现编译产物（见 `docs/p0/README.md`），
 * 因此这些断言等价于"和服务器端行为完全一致"。
 *
 * 纯 JUnit（不需要 Robolectric）：`CryptoProbeTest` 已确认在
 * `unitTests.isReturnDefaultValues = false` 下 JCE 可用。
 */
class JmCryptoGoldenTest {

    // ---------------- G1：签名 ----------------

    @Test
    fun `signature matches reference implementation for fixed timestamps`() {
        val rows = GoldenData.of("G1")
        assertThat(rows).isNotEmpty()

        for (row in rows) {
            val ts = row.long(0)
            val expectedToken = row[1]
            val expectedTokenParam = row[2]

            val params = JmCrypto.headerParams(ts)
            assertThat(params.token).isEqualTo(expectedToken)
            assertThat(params.tokenParam).isEqualTo(expectedTokenParam)
        }
    }

    @Test
    fun `content signature uses the second secret`() {
        for (row in GoldenData.of("G1C")) {
            val ts = row.long(0)
            val expected = row[1]
            assertThat(JmCrypto.contentHeaderParams(ts).token).isEqualTo(expected)
            // 两个 secret 必须产生不同的签名，防止把 secret 写混
            assertThat(JmCrypto.contentHeaderParams(ts).token)
                .isNotEqualTo(JmCrypto.headerParams(ts).token)
        }
    }

    @Test
    fun `tokenparam format is ts comma version`() {
        assertThat(JmCrypto.headerParams(1_700_000_000L).tokenParam).isEqualTo("1700000000,2.0.13")
    }

    // ---------------- G2：AES 解密 ----------------

    @Test
    fun `derived AES key is the 32 character hex string as ASCII bytes`() {
        for (row in GoldenData.of("G2KEY")) {
            val ts = row.long(0)
            val expectedKeyHex = row[1]
            val expectedLength = row.int(2)

            val actualHex = JmCrypto.md5Hex("${ts}${JmCrypto.APP_DATA_SECRET}")
            assertThat(actualHex).isEqualTo(expectedKeyHex)
            // AES-256：32 个 ASCII 字符，不是 md5 摘要的前 16 字节
            assertThat(actualHex.toByteArray(Charsets.US_ASCII).size).isEqualTo(expectedLength)
            assertThat(expectedLength).isEqualTo(32)
        }
    }

    @Test
    fun `decryptData decrypts reference produced ciphertext`() {
        for (row in GoldenData.of("G2")) {
            val ts = row.long(0)
            val cipherText = row[1]
            val expectedPlainText = GoldenData.percentDecode(row[2])

            assertThat(JmCrypto.decryptData(ts, cipherText)).isEqualTo(expectedPlainText)
        }
    }

    @Test
    fun `encrypt and decrypt round trip`() {
        val payload = """{"id":480854,"name":"标题","series":[{"id":"1","sort":"1"}]}"""
        val cipher = JmCrypto.encryptDataForTest(1_700_000_000L, payload)
        assertThat(JmCrypto.decryptData(1_700_000_000L, cipher)).isEqualTo(payload)
    }

    @Test
    fun `decryptData normalises base64 without padding`() {
        val payload = "padding-test"
        val cipher = JmCrypto.encryptDataForTest(1_700_000_000L, payload)
        val withoutPadding = cipher.trimEnd('=')
        assertThat(JmCrypto.decryptData(1_700_000_000L, withoutPadding)).isEqualTo(payload)
    }

    @Test
    fun `decryptData returns utf8 for non ascii payload`() {
        val payload = "中文标题 + emoji \uD83D\uDE00 + ascii"
        val cipher = JmCrypto.encryptDataForTest(1_700_000_002L, payload)
        assertThat(JmCrypto.decryptData(1_700_000_002L, cipher)).isEqualTo(payload)
    }

    // ---------------- G3：解扰块数 ----------------

    @Test
    fun `calculateBlockNum matches reference implementation`() {
        val rows = GoldenData.of("G3")
        assertThat(rows.size).isAtLeast(100)

        for (row in rows) {
            val scrambleId = row.long(0)
            val chapterId = row.long(1)
            val filename = GoldenData.percentDecode(row[2])
            val expected = row.int(3)

            assertThat(JmCrypto.calculateBlockNum(scrambleId, chapterId, filename))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `calculateBlockNum covers all three ranges and both thresholds`() {
        val scrambleId = 220_980L

        // 1. chapterId < scrambleId -> 未加扰
        assertThat(JmCrypto.calculateBlockNum(scrambleId, 220_979L, "00001")).isEqualTo(0)
        assertThat(JmCrypto.calculateBlockNum(scrambleId, 0L, "00001")).isEqualTo(0)

        // 2. 268850 阈值：以下固定 10，以上进入散列分支
        assertThat(JmCrypto.calculateBlockNum(scrambleId, 268_849L, "00001")).isEqualTo(10)
        val atThreshold = JmCrypto.calculateBlockNum(scrambleId, 268_850L, "00001")
        assertThat(atThreshold).isIn(listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20))

        // 3. 421926 阈值：x 从 10 变成 8，因此块数上界从 20 降到 18
        val beforeSwitch = JmCrypto.calculateBlockNum(scrambleId, 421_925L, "00001")
        val afterSwitch = JmCrypto.calculateBlockNum(scrambleId, 421_926L, "00001")
        assertThat(beforeSwitch).isAtMost(20)
        assertThat(afterSwitch).isAtMost(18)
    }

    @Test
    fun `calculateBlockNum hashes chapter id concatenated with filename`() {
        // 参考实现是 md5(chapterId + filename) 的最后一个十六进制字符的 code point 取模。
        // 这里用同一条规则独立算一遍，防止把"字符码点取模"误写成"十六进制值取模"。
        val chapterId = 300_000L
        val filename = "00012"
        val hex = JmCrypto.md5Hex("$chapterId$filename")
        val lastChar = hex.last()
        val expected = (lastChar.code % 10) * 2 + 2
        assertThat(JmCrypto.calculateBlockNum(0L, chapterId, filename)).isEqualTo(expected)
    }

    // ---------------- G9：常量与 URL ----------------

    @Test
    fun `constants match the reference implementation`() {
        val constants = GoldenData.of("G9").associate { it[0] to it[1] }
        assertThat(constants["DEFAULT_API_DOMAIN"]).isEqualTo(JmDefaults.DEFAULT_API_DOMAIN)
        assertThat(constants["IMAGE_DOMAIN"]).isEqualTo(JmDefaults.IMAGE_DOMAIN)
        assertThat(constants["COVER_DOMAIN"]).isEqualTo(JmDefaults.COVER_DOMAIN)
        assertThat(constants["JM_DEFAULT_PASSWORD"]).isEqualTo(JmDefaults.DEFAULT_EXPORT_PASSWORD)
        assertThat(constants["SEARCH_PAGE_SIZE"]?.toInt()).isEqualTo(JmDefaults.SEARCH_PAGE_SIZE)
    }

    @Test
    fun `buildImgUrl and buildCoverUrl follow the reference url rules`() {
        assertThat(JmDefaults.buildCoverUrl(480854L))
            .isEqualTo("https://cdn-msp2.jmapiproxy2.cc/media/albums/480854.jpg")
        assertThat(JmDefaults.buildCoverUrl(480854L, "cdn-msp3.18comic.vip"))
            .isEqualTo("https://cdn-msp3.18comic.vip/media/albums/480854.jpg")
        assertThat(JmDefaults.buildImgUrl(500001L, "00001.webp"))
            .isEqualTo("https://cdn-msp2.jmapiproxy2.cc/media/photos/500001/00001.webp")
    }

    @Test
    fun `md5Hex is lowercase hex`() {
        assertThat(JmCrypto.md5Hex("")).isEqualTo("d41d8cd98f00b204e9800998ecf8427e")
        assertThat(JmCrypto.md5Hex("abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72")
    }
}
