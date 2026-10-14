package com.haruhi.jmcomic.core.settings

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
 * 两个作用域的设置仓库（全局 / 漫画）。
 *
 * 计划 §9.2 的要求：
 * - 普通偏好分成 **Global / Jm 两个独立存储作用域**，互不覆盖；
 * - 网络设置采用「跟随全局 / 本作用域自定义」，**不把全局默认复制成两份**；
 * - "恢复默认"只重置自身。
 *
 * 实现上两个作用域用**两个独立的 DataStore 文件**（`settings_global` / `settings_jm`），
 * 这是最不容易出错的做法：一个文件被误清空也只会影响一个作用域。
 * 作用域由文件划分，所以 key 不需要再带前缀，见 [SettingKeys]。
 *
 * 敏感凭据（zip/pdf 密码、代理密码）**不放这里**，见 [SecretStore]。
 */
class SettingsRepository(
    private val globalStore: DataStore<Preferences>,
    private val appStore: DataStore<Preferences>,
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
        appStore.data.map { it[intPreferencesKey(key)] ?: default }

    fun jmString(key: String, default: String = ""): Flow<String> =
        appStore.data.map { it[stringPreferencesKey(key)] ?: default }

    fun jmBoolean(key: String, default: Boolean): Flow<Boolean> =
        appStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    // ------------------------------------------------------------------
    // 写入（开关与单选即时保存，见计划 §9.2）
    // ------------------------------------------------------------------

    suspend fun putGlobalInt(key: String, value: Int) = globalStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putGlobalString(key: String, value: String) = globalStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putGlobalBoolean(key: String, value: Boolean) = globalStore.edit { it[booleanPreferencesKey(key)] = value }

    suspend fun putJmInt(key: String, value: Int) = appStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putJmString(key: String, value: String) = appStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putJmBoolean(key: String, value: Boolean) = appStore.edit { it[booleanPreferencesKey(key)] = value }

    // ------------------------------------------------------------------
    // 恢复默认（只影响自身作用域）
    // ------------------------------------------------------------------

    /** 恢复全局默认：只清全局作用域。跟随全局的项会随之变化，业务数据与文件不受影响。 */
    suspend fun resetGlobal() = globalStore.edit { it.clear() }

    /** 恢复漫画默认：只清漫画作用域，不动全局、不动书库与文件。 */
    suspend fun resetJm() = appStore.edit { it.clear() }

    // ------------------------------------------------------------------
    // 网络配置解析：把"跟随全局 / 自定义"合并成有效值
    // ------------------------------------------------------------------

    /**
     * 解析漫画作用域的有效网络配置。
     * `Jm.NETWORK_OVERRIDE` 为 0 时用全局值（含全局下载网络策略），否则用本作用域的覆盖值。
     */
    suspend fun resolveJmNetwork(secrets: SecretStore): ResolvedNetworkConfig =
        resolveNetwork(
            scopeOverride = jmInt(SettingKeys.Jm.NETWORK_OVERRIDE, 0).first(),
            scopeTimeout = jmInt(SettingKeys.Jm.REQUEST_TIMEOUT_SECONDS, SettingDefaults.REQUEST_TIMEOUT_SECONDS).first(),
            scopeProxyType = jmInt(SettingKeys.Jm.PROXY_TYPE, SettingDefaults.PROXY_NONE).first(),
            scopeProxyHost = jmString(SettingKeys.Jm.PROXY_HOST).first(),
            scopeProxyPort = jmInt(SettingKeys.Jm.PROXY_PORT, 0).first(),
            scopeProxyUser = jmString(SettingKeys.Jm.PROXY_USERNAME).first(),
            scopeProxyPasswordKey = SettingKeys.SECRET_JM_PROXY_PASSWORD,
            scopeDownloadNetwork = jmInt(
                SettingKeys.Jm.DOWNLOAD_NETWORK,
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
            SettingKeys.REQUEST_TIMEOUT_SECONDS,
            SettingDefaults.REQUEST_TIMEOUT_SECONDS,
        ).first()
        val globalProxyType = globalInt(SettingKeys.PROXY_TYPE, SettingDefaults.PROXY_NONE).first()
        val globalProxyHost = globalString(SettingKeys.PROXY_HOST).first()
        val globalProxyPort = globalInt(SettingKeys.PROXY_PORT, 0).first()
        val globalProxyUser = globalString(SettingKeys.PROXY_USERNAME).first()
        val globalDownloadNetwork = globalInt(
            SettingKeys.DOWNLOAD_NETWORK,
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
        /** 两个作用域各一个 DataStore 文件，互不影响。 */
        fun create(context: Context): SettingsRepository = SettingsRepository(
            globalStore = context.applicationContext.globalSettingsStore,
            appStore = context.applicationContext.jmSettingsStore,
        )
    }
}

private val Context.globalSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_global")
private val Context.jmSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_jm")
