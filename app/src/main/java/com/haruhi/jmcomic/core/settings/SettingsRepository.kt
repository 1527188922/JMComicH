package com.haruhi.jmcomic.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.haruhi.jmcomic.core.protocol.ApiLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设置仓库。
 *
 * 历史上有**全局 / 漫画两个作用域**（`settings_global` / `settings_jm` 两个 DataStore 文件），
 * 网络设置用「跟随全局 / 本作用域自定义」合并——那是「JM / B站 双模式」时期的设计。
 * 现在只有漫画一种内容，全局这一层已经取消：
 *
 * - **唯一**的偏好存储是 `settings_jm`，所有 key 都在 [SettingKeys.Jm] 里；
 * - 网络配置（超时 / 代理 / 下载网络）直接读漫画作用域的值，不再有覆盖开关；
 * - 旧的 `settings_global` 文件**只读一次**，用来把老用户的全局值搬进漫画作用域
 *   （见 [migrateLegacyGlobalScope]），之后不再有任何写入。
 *
 * 敏感凭据（zip/pdf 密码、代理密码）**不放这里**，见 [SecretStore]。
 */
class SettingsRepository(
    private val appStore: DataStore<Preferences>,
    /** 旧「全局作用域」文件；只为一次性迁移而存在，迁移完成后不再被读取。 */
    private val legacyGlobalStore: DataStore<Preferences>,
) {

    // ------------------------------------------------------------------
    // 通用读取
    // ------------------------------------------------------------------

    fun jmInt(key: String, default: Int): Flow<Int> =
        appStore.data.map { it[intPreferencesKey(key)] ?: default }

    fun jmString(key: String, default: String = ""): Flow<String> =
        appStore.data.map { it[stringPreferencesKey(key)] ?: default }

    fun jmBoolean(key: String, default: Boolean): Flow<Boolean> =
        appStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    // ------------------------------------------------------------------
    // 写入（开关与单选即时保存，见计划 §9.2）
    // ------------------------------------------------------------------

    suspend fun putJmInt(key: String, value: Int) = appStore.edit { it[intPreferencesKey(key)] = value }
    suspend fun putJmString(key: String, value: String) = appStore.edit { it[stringPreferencesKey(key)] = value }
    suspend fun putJmBoolean(key: String, value: Boolean) = appStore.edit { it[booleanPreferencesKey(key)] = value }

    // ------------------------------------------------------------------
    // 恢复默认
    // ------------------------------------------------------------------

    /** 恢复默认：清空唯一的偏好存储。业务数据、已下载文件与凭据都不受影响。 */
    suspend fun resetJm() = appStore.edit { it.clear() }

    // ------------------------------------------------------------------
    // 网络配置解析
    // ------------------------------------------------------------------

    /**
     * 解析网络配置。**这是网络层唯一应该依赖的类型**：读的就是漫画作用域的值，
     * 不再有「跟随全局」的合并，调用方也不需要判断作用域。
     *
     * 下载网络的旧存储值 `-1`（"跟随全局"）由 [DownloadNetwork.ofOrdinal] 兜底成默认策略
     * （允许移动网络），不静默掐掉下载。
     */
    suspend fun resolveJmNetwork(secrets: SecretStore): ResolvedNetworkConfig =
        ResolvedNetworkConfig(
            timeoutSeconds = jmInt(
                SettingKeys.Jm.REQUEST_TIMEOUT_SECONDS,
                SettingDefaults.Jm.REQUEST_TIMEOUT_SECONDS,
            ).first(),
            imageTimeoutSeconds = jmInt(
                SettingKeys.Jm.IMAGE_REQUEST_TIMEOUT_SECONDS,
                SettingDefaults.Jm.IMAGE_REQUEST_TIMEOUT_SECONDS,
            ).first(),
            proxyType = ResolvedNetworkConfig.proxyTypeOf(
                jmInt(SettingKeys.Jm.PROXY_TYPE, SettingDefaults.Jm.PROXY_NONE).first(),
            ),
            proxyHost = ResolvedNetworkConfig.normalizedHost(
                jmString(SettingKeys.Jm.PROXY_HOST).first(),
            ),
            proxyPort = jmInt(SettingKeys.Jm.PROXY_PORT, 0).first(),
            proxyUsername = jmString(SettingKeys.Jm.PROXY_USERNAME).first(),
            proxyPassword = secrets.get(SettingKeys.SECRET_JM_PROXY_PASSWORD),
            downloadNetwork = downloadNetworkOf(
                jmInt(
                    SettingKeys.Jm.DOWNLOAD_NETWORK,
                    SettingDefaults.Jm.DOWNLOAD_NETWORK_ANY,
                ).first(),
            ),
        )

    // ------------------------------------------------------------------
    // 接口语言解析
    // ------------------------------------------------------------------

    /**
     * 当前接口语言（请求里的 `lang`）。
     *
     * 官方 App 把它存在 `localStorage.lang`，`fetchGet` 会给每个 GET 请求自动补上；
     * 本工程对应设置页「语言 → 接口语言」，默认简中。
     * 读取容错见 [ApiLanguage.fromCode]：空值 / 认不出的值都回落到默认。
     *
     * 与网络配置分开：它不影响连接怎么建（超时 / 代理 / 下载网络），只影响接口返回的文案。
     */
    suspend fun resolveJmApiLanguage(): ApiLanguage = ApiLanguage.fromCode(
        jmString(SettingKeys.Jm.API_LANGUAGE, ApiLanguage.DEFAULT.code).first(),
    )

    // ------------------------------------------------------------------
    // 旧「全局作用域」的一次性迁移
    // ------------------------------------------------------------------

    @Volatile
    private var legacyMigrated = false

    /**
     * 把旧全局作用域的值搬进漫画作用域。
     *
     * 只在**旧值存在、且漫画作用域还没有同名值**时写入，所以：
     * - 全新安装（没有旧文件）什么都不做；
     * - 升级上来的用户，之前在全局页改过的超时 / 代理不会丢；
     * - 迁移是幂等的：跑完 [legacyMigrated] 置位，同一进程不再读旧文件。
     *
     * 由调用方在进程启动时（`AppContainer` 创建仓库那一刻）跑一次；不跑也不会出错——
     * 所有读取都对缺省值有兜底，这里只是别让老设置凭空空掉。
     */
    suspend fun migrateLegacyGlobalScope(secrets: SecretStore) {
        if (legacyMigrated) return
        legacyMigrated = true

        val legacy = legacyGlobalStore.data.first()
        appStore.edit { jm ->
            copyLegacyInt(legacy, jm, LEGACY_TIMEOUT, SettingKeys.Jm.REQUEST_TIMEOUT_SECONDS)
            copyLegacyInt(legacy, jm, LEGACY_PROXY_TYPE, SettingKeys.Jm.PROXY_TYPE)
            copyLegacyString(legacy, jm, LEGACY_PROXY_HOST, SettingKeys.Jm.PROXY_HOST)
            copyLegacyInt(legacy, jm, LEGACY_PROXY_PORT, SettingKeys.Jm.PROXY_PORT)
            copyLegacyString(legacy, jm, LEGACY_PROXY_USERNAME, SettingKeys.Jm.PROXY_USERNAME)
            copyLegacyInt(legacy, jm, LEGACY_DOWNLOAD_NETWORK, SettingKeys.Jm.DOWNLOAD_NETWORK)
        }

        // 旧全局代理密码也是"全局"的值，同样搬进漫画作用域；搬完就删（凭据不重复保存）
        val legacyProxyPassword = secrets.get(SettingKeys.SECRET_LEGACY_GLOBAL_PROXY_PASSWORD)
        if (!legacyProxyPassword.isNullOrEmpty()) {
            if (secrets.get(SettingKeys.SECRET_JM_PROXY_PASSWORD).isNullOrEmpty()) {
                secrets.put(SettingKeys.SECRET_JM_PROXY_PASSWORD, legacyProxyPassword)
            }
            secrets.clear(SettingKeys.SECRET_LEGACY_GLOBAL_PROXY_PASSWORD)
        }
    }

    companion object {
        // 旧全局作用域里需要搬走的 key。两个作用域本来就用同一批名字，所以旧 key 直接用
        // 字符常量写在这里（它们已经没有对应的生产代码入口了）。
        private const val LEGACY_DOWNLOAD_NETWORK = "download_network"
        private const val LEGACY_TIMEOUT = "request_timeout_seconds"
        private const val LEGACY_PROXY_TYPE = "proxy_type"
        private const val LEGACY_PROXY_HOST = "proxy_host"
        private const val LEGACY_PROXY_PORT = "proxy_port"
        private const val LEGACY_PROXY_USERNAME = "proxy_username"

        /**
         * 搬运一个整数项：**旧值存在、且漫画作用域还没有同名值**时才写。
         * 旧文件里的其余项（"主题"等）已经没有消费方，不搬。
         */
        private fun copyLegacyInt(
            legacy: Preferences,
            jm: MutablePreferences,
            legacyKey: String,
            jmKey: String,
        ) {
            val legacyPreferencesKey = intPreferencesKey(legacyKey)
            val jmPreferencesKey = intPreferencesKey(jmKey)
            if (legacyPreferencesKey in legacy && jmPreferencesKey !in jm) {
                legacy[legacyPreferencesKey]?.let { jm[jmPreferencesKey] = it }
            }
        }

        private fun copyLegacyString(
            legacy: Preferences,
            jm: MutablePreferences,
            legacyKey: String,
            jmKey: String,
        ) {
            val legacyPreferencesKey = stringPreferencesKey(legacyKey)
            val jmPreferencesKey = stringPreferencesKey(jmKey)
            if (legacyPreferencesKey in legacy && jmPreferencesKey !in jm) {
                legacy[legacyPreferencesKey]?.let { jm[jmPreferencesKey] = it }
            }
        }

        private fun downloadNetworkOf(stored: Int): DownloadNetwork = DownloadNetwork.ofOrdinal(stored)

        /**
         * 创建仓库：两个 DataStore 文件（唯一偏好 + 旧全局）都在这里挂上，
         * 迁移由 `AppContainer` 在启动时调 [migrateLegacyGlobalScope] 触发。
         */
        fun create(context: Context): SettingsRepository = SettingsRepository(
            appStore = context.applicationContext.jmSettingsStore,
            legacyGlobalStore = context.applicationContext.legacyGlobalSettingsStore,
        )
    }
}

private val Context.jmSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_jm")

/** 旧「全局作用域」文件：只被迁移读一次，见 [SettingsRepository.migrateLegacyGlobalScope]。 */
private val Context.legacyGlobalSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings_global")
