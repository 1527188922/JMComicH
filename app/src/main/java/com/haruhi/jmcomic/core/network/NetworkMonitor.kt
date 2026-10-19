package com.haruhi.jmcomic.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 当前网络状况快照。
 *
 * [metered] 的含义是"这次传输会不会花用户的钱"：移动网络、按流量计费的 Wi-Fi 都算。
 * `DownloadGate.of(policy, isMetered)` 用它把"仅 Wi-Fi"策略翻译成"现在能不能开始传输"。
 */
data class NetworkState(
    val online: Boolean = true,
    val metered: Boolean = false,
)

/**
 * 网络状况监视器（计划 §9.5「移动网络下展示等待原因，**不偷偷开始大下载**」）。
 *
 * 为什么不用 WorkManager 的 `NetworkType.UNMETERED` 约束来决定下载：
 * 那会把"是否开始"交给系统调度，用户点下载后可能几分钟都不动，
 * 而计划要求的是**立刻反馈**（任务进入"等待网络"并说明原因，网络恢复后自动继续）。
 * 所以真正的闸门在这里，WorkManager 的约束留给"进程不在前台时"的兜底（P6）。
 *
 * 只暴露一个 [StateFlow]，不暴露 `ConnectivityManager`：下载层只关心"能不能传"。
 */
class NetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * 初值给"允许"的保守默认：真实值还没读出来之前，不要先把下载按住。
     * 真正的判定在 [init] 的第一次 [refresh] 里取得（所以这里不能反过来调用 [read]——
     * 那样会在 `_state` 自身初始化完成前读它）。
     */
    private val _state = MutableStateFlow(NetworkState())

    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        // 直接用回调递进来的能力快照，不再回查 `activeNetwork`：
        // 后者在回调触发的瞬间可能还没更新完，会读到与事件不一致（甚至为空）的瞬时快照。
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _state.value = stateOf(capabilities)
        }
    }

    init {
        // 先读一次：即使回调注册失败（或没触发），也有一个已生效的初始状态
        refresh()
        runCatching {
            // registerDefaultNetworkCallback 需要 ACCESS_NETWORK_STATE（Manifest 已声明）
            manager?.registerDefaultNetworkCallback(callback)
        }.onFailure { Timber.tag("net").w(it, "注册网络回调失败，退化为一次性读取") }
    }

    /** 重新读取一次当前网络状况。 */
    fun refresh() {
        _state.value = read()
    }

    private fun read(): NetworkState {
        val cm = manager ?: return NetworkState(online = true, metered = false)
        // 连默认网络句柄都取不到 = 真的没有可用网络
        val active = cm.activeNetwork ?: return NetworkState(online = false, metered = false)
        // 句柄在、能力却一时读不到（连接切换的瞬间很常见）：沿用上一次判定，
        // 别把一次瞬时读数当成"离线"——那会让下载被永久挂在"等待网络"。
        val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
            ?: return _state.value
        return stateOf(caps)
    }

    private fun stateOf(caps: NetworkCapabilities): NetworkState {
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // NET_CAPABILITY_NOT_METERED 缺失 = 计费网络
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkState(online = online, metered = metered)
    }
}
