package com.haruhi.jmcomic.core.download

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.settings.DownloadNetwork
import org.junit.Test

/**
 * 下载网络闸门的策略矩阵（计划 §9.5「移动网络下展示等待原因和前往修改设置入口，
 * **不偷偷开始大下载**」，以及 §9.2「改为更严格的下载网络策略时，当前传输在
 * 安全检查点暂停；恢复前重新检查条件」）。
 *
 * 这是纯函数，所以把它单独锁住：执行器那边的"等待网络"状态机依赖它的语义，
 * 而状态机本身要靠设备/网络切换才能观察到。
 */
class DownloadGateTest {

    @Test
    fun `wifi only policy blocks metered networks`() {
        val gate = DownloadGate.of(DownloadNetwork.WIFI_ONLY, isMetered = true)
        assertThat(gate.allowNewTransfer()).isFalse()
    }

    @Test
    fun `wifi only policy allows unmetered networks`() {
        val gate = DownloadGate.of(DownloadNetwork.WIFI_ONLY, isMetered = false)
        assertThat(gate.allowNewTransfer()).isTrue()
    }

    @Test
    fun `any network policy allows both metered and unmetered`() {
        assertThat(DownloadGate.of(DownloadNetwork.ANY, isMetered = true).allowNewTransfer()).isTrue()
        assertThat(DownloadGate.of(DownloadNetwork.ANY, isMetered = false).allowNewTransfer()).isTrue()
    }

    @Test
    fun `always allow gate is the default`() {
        assertThat(DownloadGate.AlwaysAllow.allowNewTransfer()).isTrue()
    }

    @Test
    fun `download network labels are the user facing wording`() {
        // 计划 §9：界面只展示易懂的名称，不展示内部枚举或配置 key
        assertThat(DownloadNetwork.WIFI_ONLY.label).isEqualTo("仅 Wi-Fi")
        assertThat(DownloadNetwork.ANY.label).isEqualTo("允许移动网络")
    }
}
