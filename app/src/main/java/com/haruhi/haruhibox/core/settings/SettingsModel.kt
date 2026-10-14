package com.haruhi.haruhibox.core.settings

import java.util.Locale

/** 代理类型。 */
enum class ProxyType { NONE, HTTP, SOCKS }

/** 主题。 */
enum class AppTheme { FOLLOW_SYSTEM, LIGHT, DARK }

/** 打开应用时的行为。 */
enum class StartMode { ASK, LAST_USED, JM, BILI }

/** 下载网络策略。序号与 `SettingDefaults.DOWNLOAD_NETWORK_*` 的存储值一一对应。 */
enum class DownloadNetwork(val label: String) {
    WIFI_ONLY("仅 Wi-Fi"),
    ANY("允许移动网络"),
    ;

    companion object {
        /** 未知取值（例如旧版本写下的别的数字）按"允许移动网络"处理，不悄悄掐掉下载。 */
        fun ofOrdinal(ordinal: Int): DownloadNetwork =
            entries.getOrElse(ordinal) { ANY }
    }
}

/**
 * 设置作用域：JM 与 B站各自可"跟随全局"或"自定义"网络参数。
 *
 * 计划 §9.2：模式网络设置采用「跟随全局 / 本模式自定义」，UI 展示**有效值**，
 * 例如「跟随全局：仅 Wi-Fi」；切回跟随后保留自定义草稿但不生效。
 * **不要把全局默认复制成两份**，否则全局修改失效——所以这里存的是
 * "覆盖开关 + 覆盖值"，解析时才与全局合并。
 */
data class NetworkOverride(
    val overridden: Boolean,
    val timeoutSeconds: Int,
    val proxyType: ProxyType,
    val proxyHost: String,
    val proxyPort: Int,
    val proxyUsername: String,
)

/**
 * 解析后的网络配置。**这是网络层唯一应该依赖的类型**，
 * 它已经把"跟随全局"合并完了，调用方不需要再判断作用域。
 */
data class ResolvedNetworkConfig(
    val timeoutSeconds: Int,
    val proxyType: ProxyType,
    val proxyHost: String,
    val proxyPort: Int,
    val proxyUsername: String,
    val proxyPassword: String?,
    /** 供 UI 展示用："跟随全局：仅 Wi-Fi" 这类文案由它拼。 */
    val followedGlobal: Boolean,
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
    ProxyHostBlank("请填写代理地址"),
    ProxyHostInvalid("代理地址只能包含字母、数字、点、横线和冒号"),
    ProxyPortOutOfRange("代理端口需要在 1～65535 之间"),
}
