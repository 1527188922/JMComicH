package com.haruhi.jmcomic.core.protocol

import java.util.Locale

/**
 * 一条**接口线路**（漫画详情 / 搜索 / 章节清单走的域名）。
 *
 * @property host 域名，不含 scheme 与路径（`www.cdngwc.cc`）。
 * @property label 给用户看的名字（`线路 2`）。清单里没给名字的线路就用域名本身。
 * @property builtIn 是不是 [LineCatalog] 硬编码清单里的线路。**false 只出现在一种情况**：
 *   老版本允许手填域名，升级上来的用户存了一个清单里没有的域名。那时把它当成"当前线路"
 *   原样显示，不去改用户的配置，也不让它从列表里消失。
 */
data class ApiLine(
    val host: String,
    val label: String,
    val builtIn: Boolean = true,
)

/** 一条**图片线路**（章节图片 CDN 域名）。清单接口没给名字，所以只认域名。 */
data class ImageLine(
    val host: String,
    val builtIn: Boolean = true,
)

/**
 * 线路清单（**硬编码快照**）。
 *
 * 为什么不每次启动都去拉官方那份加密清单（`newsvr-2025.txt`）：
 * 用户要求"直接把线路清单中的线路定义到一个 list 里面即可，不每次加载线路清单，
 * 如果有新增线路改硬编码代码再发版就行"。所以这里就是那份清单的本地副本，
 * **改线路 = 改这个文件 + 发版**，别的地方不要再写一份域名。
 *
 * 两个清单的来源与"默认项"规则：
 *
 * | 清单 | 来源 | 默认项（「恢复默认」回落到哪） |
 * |---|---|---|
 * | [apiLines] | 官方线路清单接口解密出来的 `jm3_Server`（2026-10-18 实测快照）+ 工程当前在用的线路 | [DEFAULT_API_HOST] |
 * | [imageLines] | 对每条接口线路、每个图源（`app_img_shunt=1..4`）实际请求 `/setting` 收集到的 `img_host` + 工程当前在用的图片线路 | [DEFAULT_IMAGE_HOST] |
 *
 * **工程当前在用的那两条必须留在清单里**（用户明确要求）：
 * [DEFAULT_API_HOST] = `www.cdnbea.net`、[DEFAULT_IMAGE_HOST] = `cdn-msp2.jmapiproxy2.cc`
 * （就是 [Defaults] 里那两个常量，改默认值只有那一处）。它们在列表里排第一，
 * 并带「默认」标记；「恢复默认」按钮回到的就是它们。
 *
 * 去重：两个清单在构造时都按 host `distinctBy` 过一遍，所以往上贴重复线路不会出现两行。
 */
object LineCatalog {

    /** 默认接口线路：工程当前在用的那条（= [Defaults.DEFAULT_API_DOMAIN]）。 */
    const val DEFAULT_API_HOST: String = Defaults.DEFAULT_API_DOMAIN

    /** 默认图片线路：工程当前在用的那条（= [Defaults.IMAGE_DOMAIN]）。 */
    const val DEFAULT_IMAGE_HOST: String = Defaults.IMAGE_DOMAIN

    /** 默认接口线路在列表里显示的名字（它不在官方清单里，没有"線路N"编号）。 */
    private const val DEFAULT_API_LABEL = "默认线路"

    /**
     * 官方线路清单接口返回的线路（`jm3_Server` 的 `[域名, 线路名]`，名字改成简体）。
     *
     * 这份清单里**不含** `www.cdnbea.net`——那是本工程一直在用的线路，
     * 由 [DEFAULT_API_HOST] 单独插到列表最前面。
     */
    private val LISTED_API_LINES: List<ApiLine> = listOf(
        ApiLine("www.cdnhjk.net", "线路 1"),
        ApiLine("www.cdngwc.cc", "线路 2"),
        ApiLine("www.cdngwc.net", "线路 3"),
        ApiLine("www.cdngwc.club", "线路 4"),
        ApiLine("www.cdnutc.me", "线路 5"),
    )

    /**
     * 实测收集到的图片线路（`/setting` 的 `img_host`，去掉 scheme、按域名去重，2026-10-18）。
     *
     * 每个接口线路的 `app_img_shunt=1..4` 返回的图片 CDN 是**随机轮换**的，
     * 这 12 条是那几轮请求里真正出现过的全部取值，之后又逐条取了一次
     * `https://{host}/media/users/nopic-.gif` 探活：`cdn-msp*` 那几条能通，
     * `.jmdanjonproxy.vip` / `cdn-msp12` / `tencent.*` 本次超时——但它们确实是接口给出的地址，
     * 用户那边能不能连取决于自己的网络，所以**照原样保留**（选了连不上的会自动失败，
     * 和以前手填一个死域名是同一结果）。
     */
    private val OBSERVED_IMAGE_HOSTS: List<String> = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy3.cc",
        "cdn-msp.jmdanjonproxy.vip",
        "cdn-msp.jmdanjonproxy.xyz",
        "cdn-msp12.jmdanjonproxy.xyz",
        "cdn-msp2.jmapiproxy1.cc",
        "cdn-msp2.jmapiproxy3.cc",
        "cdn-msp2.jmdanjonproxy.vip",
        "cdn-msp3.jmapiproxy1.cc",
        "cdn-msp3.jmapiproxy3.cc",
        "cdn-msp3.jmdanjonproxy.vip",
        "tencent.jmdanjonproxy.xyz",
    )

    /**
     * 接口线路清单（可选列表，默认项排第一）。
     *
     * 顺序就是选择列表里的顺序：先"默认线路"，再按官方清单的 線路1…線路5。
     */
    val apiLines: List<ApiLine> = buildList {
        add(ApiLine(DEFAULT_API_HOST, DEFAULT_API_LABEL))
        addAll(LISTED_API_LINES)
    }.distinctBy { it.host }

    /** 图片线路清单（可选列表，默认项排第一）。 */
    val imageLines: List<ImageLine> = buildList {
        add(ImageLine(DEFAULT_IMAGE_HOST))
        OBSERVED_IMAGE_HOSTS.forEach { add(ImageLine(it)) }
    }.distinctBy { it.host }

    /** 默认接口线路在列表里的那一条（一定存在，构造时插进去的）。 */
    val defaultApiLine: ApiLine get() = apiLines.first()

    /** 默认图片线路在列表里的那一条（一定存在，构造时插进去的）。 */
    val defaultImageLine: ImageLine get() = imageLines.first()

    fun isDefaultApi(host: String): Boolean = normalizeHost(host) == DEFAULT_API_HOST

    fun isDefaultImage(host: String): Boolean = normalizeHost(host) == DEFAULT_IMAGE_HOST

    /**
     * 存储值 → 可展示的接口线路。
     *
     * - 空串 / 全空白 → 默认线路（老用户没改过、或者刚「恢复默认」清掉了偏好）；
     * - 清单里有 → 那一条（带官方名字）；
     * - 清单里没有 → 原样保留成 `builtIn = false` 的"当前线路"。
     *
     * 老版本的存储格式是**换行分隔的域名列表、第一行生效**，所以这里先取第一行
     * （见 [effectiveHost]），升级用户不会因为格式变了而丢掉自己的线路。
     */
    fun apiLineOf(stored: String?): ApiLine {
        val host = normalizeHost(effectiveHost(stored.orEmpty()))
        if (host.isEmpty()) return defaultApiLine
        return apiLines.firstOrNull { it.host == host }
            ?: ApiLine(host = host, label = host, builtIn = false)
    }

    /** 存储值 → 可展示的图片线路。规则同 [apiLineOf]。 */
    fun imageLineOf(stored: String?): ImageLine {
        val host = normalizeHost(effectiveHost(stored.orEmpty()))
        if (host.isEmpty()) return defaultImageLine
        return imageLines.firstOrNull { it.host == host } ?: ImageLine(host = host, builtIn = false)
    }

    /**
     * 老格式（换行分隔的多行域名列表）里"生效的那一行"。
     *
     * 现在的写入方只写单个域名，这个函数是为了让升级用户的历史值仍然可用：
     * 以前只有第一行才生效，其余行从来没被读过。
     */
    private fun effectiveHost(raw: String): String =
        raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    /**
     * 域名规整：去掉首尾空白、`scheme://`、路径与查询串，转小写。
     *
     * 为什么要转小写：`/setting` 的 `base_url` 与用户手填的历史值大小写不统一，
     * 不归一会让"同一条线路"在列表里对不上、选中态点不亮。
     */
    fun normalizeHost(raw: String): String {
        var value = raw.trim()
        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) value = value.substring(schemeIndex + 3)
        value = value.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return value.trim().lowercase(Locale.ROOT)
    }
}
