package com.haruhi.jmcomic.core.settings

import java.util.Locale

/** 代理类型。 */
enum class ProxyType { NONE, HTTP, SOCKS }

/** 主题。序号与 `SettingDefaults.Jm.THEME_FOLLOW_SYSTEM` 的存储值一一对应。 */
enum class AppTheme { FOLLOW_SYSTEM, LIGHT, DARK }

/** 下载网络策略。序号与 `SettingDefaults.Jm.DOWNLOAD_NETWORK_*` 的存储值一一对应。 */
enum class DownloadNetwork(val label: String) {
    WIFI_ONLY("仅 Wi-Fi"),
    ANY("允许移动网络"),
    ;

    companion object {
        /**
         * 未知取值按"允许移动网络"处理，不悄悄掐掉下载。
         * 旧版本用 `-1` 表示"跟随全局"（这个概念已取消），也走这条兜底。
         */
        fun ofOrdinal(ordinal: Int): DownloadNetwork =
            entries.getOrElse(ordinal) { ANY }
    }
}

/**
 * 解析后的网络配置。**这是网络层唯一应该依赖的类型**：
 * 读的就是漫画设置作用域的值，调用方不需要再判断作用域。
 */
data class ResolvedNetworkConfig(
    /** 接口请求超时（秒）：漫画详情 / 章节 / 搜索。 */
    val timeoutSeconds: Int,
    /** 图片下载超时（秒）：章节图片、封面。 */
    val imageTimeoutSeconds: Int,
    val proxyType: ProxyType,
    val proxyHost: String,
    val proxyPort: Int,
    val proxyUsername: String,
    val proxyPassword: String?,
    val downloadNetwork: DownloadNetwork,
) {
    val usesProxy: Boolean get() = proxyType != ProxyType.NONE && proxyHost.isNotBlank()

    /**
     * OkHttp 的代理地址串，例如 `http://127.0.0.1:7890`。
     * 没有配置代理时返回 null。
     */
    fun proxyUrlOrNull(): String? {
        if (!usesProxy) return null
        val scheme = if (proxyType == ProxyType.SOCKS) "socks" else "http"
        return "$scheme://$proxyHost:$proxyPort"
    }

    /** 有效期检查：host 必须是合法主机名或 IP，端口在 1..65535。 */
    fun validate(): List<NetworkConfigError> = buildList {
        if (timeoutSeconds !in SettingLimits.REQUEST_TIMEOUT_SECONDS) {
            add(NetworkConfigError.TimeoutOutOfRange)
        }
        if (imageTimeoutSeconds !in SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS) {
            add(NetworkConfigError.ImageTimeoutOutOfRange)
        }
        if (proxyType == ProxyType.NONE) return@buildList
        if (proxyHost.isBlank()) add(NetworkConfigError.ProxyHostBlank)
        if (proxyPort !in SettingLimits.PORT) add(NetworkConfigError.ProxyPortOutOfRange)
        if (proxyHost.isNotBlank() && !HOST_PATTERN.matches(proxyHost)) {
            add(NetworkConfigError.ProxyHostInvalid)
        }
    }

    companion object {
        /** 主机名 / IPv4 / 简单 IPv6：字母数字点横线冒号，不允许空格与 scheme。 */
        private val HOST_PATTERN = Regex("^[A-Za-z0-9._:\\[\\]-]+$")

        fun proxyTypeOf(ordinal: Int): ProxyType =
            ProxyType.entries.getOrElse(ordinal) { ProxyType.NONE }

        fun downloadNetworkOf(ordinal: Int): DownloadNetwork = DownloadNetwork.ofOrdinal(ordinal)

        fun normalizedHost(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    }
}

/** 网络配置校验失败的原因（UI 需要给出可理解的文案，不要暴露技术细节）。 */
enum class NetworkConfigError(val message: String) {
    TimeoutOutOfRange("请求超时需要在 ${SettingLimits.REQUEST_TIMEOUT_SECONDS.first}～" +
        "${SettingLimits.REQUEST_TIMEOUT_SECONDS.last} 秒之间"),
    ImageTimeoutOutOfRange("图片下载超时需要在 ${SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS.first}～" +
        "${SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS.last} 秒之间"),
    ProxyHostBlank("请填写代理地址"),
    ProxyHostInvalid("代理地址只能包含字母、数字、点、横线和冒号"),
    ProxyPortOutOfRange("代理端口需要在 1～65535 之间"),
}
