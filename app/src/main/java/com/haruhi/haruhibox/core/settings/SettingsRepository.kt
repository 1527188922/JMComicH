package com.haruhi.haruhibox.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 三个作用域的设置仓库。
 *
 * 计划 §9.2 的要求：
 * - 普通偏好分成 **Global / JM / Bili 三个独立存储作用域**，互不覆盖；
 * - 模式网络设置采用「跟随全局 / 本模式自定义」，**不把全局默认复制成两份**；
 * - 各模式"恢复默认"只重置自身。
 *
 * 实现上三个作用域用**三个独立的 DataStore 文件**，这是最不容易出错的做法：
 * 一个文件被误清空也只会影响一个作用域。
 *
 * 敏感凭据（zip/pdf 密码、Cookie、代理密码）**不放这里**，见 [SecretStore]。
 */
class SettingsRepository(
    private val globalStore: DataStore<Preferences>,
    private val jmStore: DataStore<Preferences>,
    private val biliStore: DataStore<Preferences>,
) {

    // ------------------------------------------------------------------
    // 通用读取
    // ------------------------------------------------------------------

    fun globalInt(key: String, default: Int): Flow<Int> =
        globalStore.data.map { it[intPreferencesKey(key)] ?: default }

    fun globalString(key: String, default: String = ""): Flow<String> =
        globalStore.data.map { it[stringPreferencesKey(key)] ?: default }

    fun globalBoolean(key: String, default: Boolean): Flow<Boolean> =
        globalStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    fun jmInt(key: String, default: Int): Flow<Int> =
        jmStore.data.map { it[intPreferencesKey(key)] ?: default }

    fun jmString(key: String, default: String = ""): Flow<String> =
        jmStore.data.map { it[stringPreferencesKey(key)] ?: default }

    fun jmBoolean(key: String, default: Boolean): Flow<Boolean> =
        jmStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    fun biliInt(key: String, default: Int): Flow<Int> =
        biliStore.data.map { it[intPreferencesKey(key)] ?: default }

    fun biliString(key: String, default: String = ""): Flow<String> =
        biliStore.data.map { it[stringPreferencesKey(key)] ?: default }

    fun biliBoolean(key: String, default: Boolean): Flow<Boolean> =
        biliStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    // ------------------------------------------------------------------
    // 写入（开关与单选即时保存，见计划 §9.2）
    // ------------------------------------------------------------------

    suspend fun putGlobalInt(key: String, value: Int) = globalStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putGlobalString(key: String, value: String) = globalStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putGlobalBoolean(key: String, value: Boolean) = globalStore.edit { it[booleanPreferencesKey(key)] = value }

    suspend fun putJmInt(key: String, value: Int) = jmStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putJmString(key: String, value: String) = jmStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putJmBoolean(key: String, value: Boolean) = jmStore.edit { it[booleanPreferencesKey(key)] = value }

    suspend fun putBiliInt(key: String, value: Int) = biliStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putBiliString(key: String, value: String) = biliStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putBiliBoolean(key: String, value: Boolean) = biliStore.edit { it[booleanPreferencesKey(key)] = value }

    // ------------------------------------------------------------------
    // 恢复默认（只影响自身作用域）
    // ------------------------------------------------------------------

    /** 恢复全局默认：只清全局作用域。跟随全局的模式会随之变化，业务数据与文件不受影响。 */
    suspend fun resetGlobal() = globalStore.edit { it.clear() }

    /** 恢复 JM 默认：只清 JM 作用域，不动全局、不动 B站、不动书库与文件。 */
    suspend fun resetJm() = jmStore.edit { it.clear() }

    /** 恢复 B站默认：只清 B站作用域，**不清访问凭据**（凭据在 SecretStore，需单独清除）。 */
    suspend fun resetBili() = biliStore.edit { it.clear() }

    // ------------------------------------------------------------------
    // 网络配置解析：把"跟随全局 / 自定义"合并成有效值
    // ------------------------------------------------------------------

    /**
     * 解析 JM 模式的有效网络配置。
     * [JM_NETWORK_OVERRIDE] 为 0 时用全局值（含全局下载网络策略），否则用 JM 自己的覆盖值。
     */
    suspend fun resolveJmNetwork(secrets: SecretStore): ResolvedNetworkConfig =
        resolveNetwork(
            scopeOverride = jmInt(SettingKeys.JM_NETWORK_OVERRIDE, 0).first(),
            scopeTimeout = jmInt(SettingKeys.JM_REQUEST_TIMEOUT_SECONDS, SettingDefaults.REQUEST_TIMEOUT_SECONDS).first(),
            scopeProxyType = jmInt(SettingKeys.JM_PROXY_TYPE, SettingDefaults.PROXY_NONE).first(),
            scopeProxyHost = jmString(SettingKeys.JM_PROXY_HOST).first(),
            scopeProxyPort = jmInt(SettingKeys.JM_PROXY_PORT, 0).first(),
            scopeProxyUser = jmString(SettingKeys.JM_PROXY_USERNAME).first(),
            scopeProxyPasswordKey = SettingKeys.SECRET_JM_PROXY_PASSWORD,
            scopeDownloadNetwork = jmInt(
                SettingKeys.JM_DOWNLOAD_NETWORK,
                SettingDefaults.NETWORK_FOLLOW_GLOBAL,
            ).first(),
            secrets = secrets,
        )

    /** 解析 B站模式的有效网络配置。规则同 [resolveJmNetwork]，作用域独立。 */
    suspend fun resolveBiliNetwork(secrets: SecretStore): ResolvedNetworkConfig =
        resolveNetwork(
            scopeOverride = biliInt(SettingKeys.B_NETWORK_OVERRIDE, 0).first(),
            scopeTimeout = biliInt(SettingKeys.B_REQUEST_TIMEOUT_SECONDS, SettingDefaults.REQUEST_TIMEOUT_SECONDS).first(),
            scopeProxyType = biliInt(SettingKeys.B_PROXY_TYPE, SettingDefaults.PROXY_NONE).first(),
            scopeProxyHost = biliString(SettingKeys.B_PROXY_HOST).first(),
            scopeProxyPort = biliInt(SettingKeys.B_PROXY_PORT, 0).first(),
            scopeProxyUser = biliString(SettingKeys.B_PROXY_USERNAME).first(),
            scopeProxyPasswordKey = SettingKeys.SECRET_B_PROXY_PASSWORD,
            scopeDownloadNetwork = biliInt(
                SettingKeys.B_DOWNLOAD_NETWORK,
                SettingDefaults.NETWORK_FOLLOW_GLOBAL,
            ).first(),
            secrets = secrets,
        )

    private suspend fun resolveNetwork(
        scopeOverride: Int,
        scopeTimeout: Int,
        scopeProxyType: Int,
        scopeProxyHost: String,
        scopeProxyPort: Int,
        scopeProxyUser: String,
        scopeProxyPasswordKey: String,
        scopeDownloadNetwork: Int,
        secrets: SecretStore,
    ): ResolvedNetworkConfig {
        val followGlobal = scopeOverride == 0

        val globalTimeout = globalInt(
            SettingKeys.G_REQUEST_TIMEOUT_SECONDS,
            SettingDefaults.REQUEST_TIMEOUT_SECONDS,
        ).first()
        val globalProxyType = globalInt(SettingKeys.G_PROXY_TYPE, SettingDefaults.PROXY_NONE).first()
        val globalProxyHost = globalString(SettingKeys.G_PROXY_HOST).first()
        val globalProxyPort = globalInt(SettingKeys.G_PROXY_PORT, 0).first()
        val globalProxyUser = globalString(SettingKeys.G_PROXY_USERNAME).first()
        val globalDownloadNetwork = globalInt(
            SettingKeys.G_DOWNLOAD_NETWORK,
            // 默认允许移动网络（2026-10-09 按用户要求放开，见 SettingDefaults 的说明）
            SettingDefaults.DOWNLOAD_NETWORK_ANY,
        ).first()

        val timeout = if (followGlobal) globalTimeout else scopeTimeout
        val proxyTypeOrdinal = if (followGlobal) globalProxyType else scopeProxyType
        val proxyHost = ResolvedNetworkConfig.normalizedHost(if (followGlobal) globalProxyHost else scopeProxyHost)
        val proxyPort = if (followGlobal) globalProxyPort else scopeProxyPort
        val proxyUser = if (followGlobal) globalProxyUser else scopeProxyUser
        val passwordKey = if (followGlobal) SettingKeys.SECRET_GLOBAL_PROXY_PASSWORD else scopeProxyPasswordKey

        // 下载网络策略也是"跟随全局"的一层，但它是独立字段（-1 表示跟随）
        val effectiveNetworkOrdinal = if (scopeDownloadNetwork == SettingDefaults.NETWORK_FOLLOW_GLOBAL) {
            globalDownloadNetwork
        } else {
            scopeDownloadNetwork
        }

        return ResolvedNetworkConfig(
            timeoutSeconds = timeout,
            proxyType = ResolvedNetworkConfig.proxyTypeOf(proxyTypeOrdinal),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUser,
            proxyPassword = secrets.get(passwordKey),
            followedGlobal = followGlobal,
            downloadNetwork = ResolvedNetworkConfig.downloadNetworkOf(effectiveNetworkOrdinal),
        )
    }

    companion object {
        /** 三个作用域各一个 DataStore 文件，互不影响。 */
        fun create(context: Context): SettingsRepository = SettingsRepository(
            globalStore = context.applicationContext.globalSettingsStore,
            jmStore = context.applicationContext.jmSettingsStore,
            biliStore = context.applicationContext.biliSettingsStore,
        )
    }
}

private val Context.globalSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_global")
private val Context.jmSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_jm")
private val Context.biliSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_bili")
