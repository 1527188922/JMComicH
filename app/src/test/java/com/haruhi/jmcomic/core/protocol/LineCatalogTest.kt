package com.haruhi.jmcomic.core.protocol

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.feature.api.LineProbeResult
import com.haruhi.jmcomic.feature.api.LineSpeedLevel
import com.haruhi.jmcomic.feature.api.NORMAL_UPPER_MS
import com.haruhi.jmcomic.feature.api.SMOOTH_UPPER_MS
import com.haruhi.jmcomic.feature.api.speedLevelOf
import org.junit.Test

/**
 * 线路清单的验收测试。
 *
 * 这份清单是**硬编码**的（用户要求"不每次加载线路清单，新增线路改代码再发版"），
 * 所以这里守住三件事：
 * 1. 工程当前在用的默认线路**一定在**清单里、且排第一（「恢复默认」就回落到它）；
 * 2. 清单**去重**（同一个域名不能出现两行），格式规整（没有 scheme / 空格 / 大写）；
 * 3. 存储值的解析：空值→默认、清单内→带名字的那条、清单外→原样保留、老格式→取第一行。
 *
 * 顺带打表验证测速灯号的三个阈值（600 / 1200 ms，来自官方 App 的 speedTestUtil）。
 */
class LineCatalogTest {

    // ---------------- 清单本身 ----------------

    @Test
    fun `api lines start with the line the project ships with`() {
        assertThat(LineCatalog.apiLines).isNotEmpty()
        // 默认项排第一：「恢复默认」与首次启动看到的都是它
        assertThat(LineCatalog.apiLines.first().host).isEqualTo(Defaults.DEFAULT_API_DOMAIN)
        assertThat(LineCatalog.apiLines.first().label).isEqualTo("默认线路")
        assertThat(LineCatalog.apiLines.map { it.host }).contains(Defaults.DEFAULT_API_DOMAIN)
    }

    @Test
    fun `api lines carry every line from the remote line list`() {
        val hosts = LineCatalog.apiLines.map { it.host }
        // 官方清单接口（newsvr-2025.txt）解密出来的 5 条，一条都不能少
        assertThat(hosts).containsAtLeast(
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club",
            "www.cdnutc.me",
        )
        // 名字用来在界面上区分「线路 N」
        assertThat(LineCatalog.apiLines.single { it.host == "www.cdngwc.cc" }.label).isEqualTo("线路 2")
    }

    @Test
    fun `image lines start with the image domain the project ships with`() {
        assertThat(LineCatalog.imageLines.first().host).isEqualTo(Defaults.IMAGE_DOMAIN)
        assertThat(LineCatalog.imageLines.map { it.host }).contains(Defaults.IMAGE_DOMAIN)
        // 实测从 /setting 收上来的那批也要在（抽三条代表）
        assertThat(LineCatalog.imageLines.map { it.host }).containsAtLeast(
            "cdn-msp.jmapiproxy3.cc",
            "cdn-msp3.jmapiproxy1.cc",
            "tencent.jmdanjonproxy.xyz",
        )
    }

    @Test
    fun `both line lists are deduplicated and well formed`() {
        val apiHosts = LineCatalog.apiLines.map { it.host }
        assertThat(apiHosts).containsNoDuplicates()
        val imageHosts = LineCatalog.imageLines.map { it.host }
        assertThat(imageHosts).containsNoDuplicates()

        // 写进清单的必须是"裸域名"：不然后面拼 https:// 会拼出两层 scheme
        for (host in apiHosts + imageHosts) {
            assertThat(host).isNotEmpty()
            assertThat(host).doesNotContain("://")
            assertThat(host).doesNotContain("/")
            assertThat(host).doesNotContain(" ")
            assertThat(host).isEqualTo(host.lowercase())
        }
    }

    // ---------------- 存储值 → 可选项 ----------------

    @Test
    fun `blank or missing stored value resolves to the default line`() {
        assertThat(LineCatalog.apiLineOf("")).isEqualTo(LineCatalog.defaultApiLine)
        assertThat(LineCatalog.apiLineOf(null)).isEqualTo(LineCatalog.defaultApiLine)
        assertThat(LineCatalog.apiLineOf("   \n  ")).isEqualTo(LineCatalog.defaultApiLine)
        assertThat(LineCatalog.imageLineOf("")).isEqualTo(LineCatalog.defaultImageLine)
    }

    @Test
    fun `stored host resolves to its catalog entry`() {
        val line = LineCatalog.apiLineOf("www.cdngwc.cc")
        assertThat(line.host).isEqualTo("www.cdngwc.cc")
        assertThat(line.label).isEqualTo("线路 2")
        assertThat(line.builtIn).isTrue()

        assertThat(LineCatalog.imageLineOf("cdn-msp3.jmapiproxy1.cc").host)
            .isEqualTo("cdn-msp3.jmapiproxy1.cc")
    }

    @Test
    fun `legacy multiline value keeps using the first line`() {
        // 老格式是"换行分隔的域名列表，第一行生效"，升级用户的值必须还能用
        assertThat(LineCatalog.apiLineOf("www.cdngwc.club\nwww.cdnutc.me").host)
            .isEqualTo("www.cdngwc.club")
        assertThat(LineCatalog.imageLineOf("\n cdn-msp.jmapiproxy3.cc \ncdn-msp2.jmapiproxy1.cc").host)
            .isEqualTo("cdn-msp.jmapiproxy3.cc")
    }

    @Test
    fun `host is normalized before matching`() {
        // scheme / 路径 / 大小写 / 首尾空白都要能对上同一条线路（老用户手填过这些写法）
        assertThat(LineCatalog.apiLineOf("https://WWW.CDNGWC.CC/").host).isEqualTo("www.cdngwc.cc")
        assertThat(LineCatalog.normalizeHost("  HTTPS://www.cdnutc.me/jm/album?id=1 ")).isEqualTo("www.cdnutc.me")
    }

    @Test
    fun `a host outside the catalog is kept as the current line`() {
        // 清单外的历史手填值不能凭空消失，也不能被悄悄改成别的线路
        val line = LineCatalog.apiLineOf("old.example.com")
        assertThat(line.host).isEqualTo("old.example.com")
        assertThat(line.builtIn).isFalse()
        // 标题就用域名本身，不假装它是"线路 N"
        assertThat(line.label).isEqualTo("old.example.com")

        assertThat(LineCatalog.imageLineOf("img.example.com").builtIn).isFalse()
    }

    @Test
    fun `default predicates ignore scheme and case`() {
        assertThat(LineCatalog.isDefaultApi(Defaults.DEFAULT_API_DOMAIN)).isTrue()
        assertThat(LineCatalog.isDefaultApi("https://WWW.CDNBEA.NET/")).isTrue()
        assertThat(LineCatalog.isDefaultApi("www.cdngwc.cc")).isFalse()
        assertThat(LineCatalog.isDefaultImage(Defaults.IMAGE_DOMAIN)).isTrue()
        assertThat(LineCatalog.isDefaultImage("cdn-msp.jmapiproxy3.cc")).isFalse()
    }

    // ---------------- 测速灯号阈值 ----------------

    @Test
    fun `speed level thresholds match the official app`() {
        assertThat(speedLevelOf(0)).isEqualTo(LineSpeedLevel.SMOOTH)
        assertThat(speedLevelOf(SMOOTH_UPPER_MS - 1)).isEqualTo(LineSpeedLevel.SMOOTH)
        // 600 正好进橙灯（官方是 latency < 600 才算绿）
        assertThat(speedLevelOf(SMOOTH_UPPER_MS)).isEqualTo(LineSpeedLevel.NORMAL)
        assertThat(speedLevelOf(NORMAL_UPPER_MS - 1)).isEqualTo(LineSpeedLevel.NORMAL)
        // 1200 及以上是红灯
        assertThat(speedLevelOf(NORMAL_UPPER_MS)).isEqualTo(LineSpeedLevel.LAGGY)
        assertThat(speedLevelOf(60_000)).isEqualTo(LineSpeedLevel.LAGGY)
    }

    @Test
    fun `probe result defaults to not measured`() {
        val result = LineProbeResult()
        assertThat(result.level).isEqualTo(LineSpeedLevel.UNKNOWN)
        assertThat(result.latencyMs).isNull()
    }
}
