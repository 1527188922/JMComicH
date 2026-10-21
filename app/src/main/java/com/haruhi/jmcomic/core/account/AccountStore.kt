package com.haruhi.jmcomic.core.account

import com.haruhi.jmcomic.core.settings.SecretStore
import com.haruhi.jmcomic.core.settings.SettingKeys
import com.haruhi.jmcomic.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 账号凭证与资料快照的落盘。
 *
 * | 数据 | 位置 | 理由 |
 * |---|---|---|
 * | `jwttoken` / `s`（AVS Cookie） | [SecretStore]（Keystore AES-GCM） | 等价于密码，不能明文躺 DataStore |
 * | 会员资料快照（JSON） | DataStore `settings_jm` 的 `account_profile` | 「我的」页冷启动就能显示，不用等网络 |
 *
 * **不存密码**：官方 App 把账号密码明文存 `localStorage` 用来"静默重登"，这里不这么做。
 * token 失效就提示重新登录（`docs/account-design.md` §9）。
 */
class AccountStore(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 进程启动时的初始状态。
     *
     * 同步读：头像/昵称要在「我的」页第一帧就出来，异步读会先闪一下"未登录"。
     * DataStore 首次读取要等一次磁盘 IO，量很小（一个字符串），与 `AppContainer`
     * 启动时已有的 `runBlocking` 读设置是同一种取舍；调用点都在懒加载里，不会在热点路径反复触发。
     */
    fun loadInitialState(): AccountState {
        val profile = readProfile() ?: return AccountState.Anonymous
        return if (readToken().isNullOrBlank()) {
            AccountState.Expired(profile)
        } else {
            AccountState.SignedIn(profile)
        }
    }

    fun readProfile(): MemberProfile? = runCatching {
        val stored = runBlocking { settings.jmString(SettingKeys.Jm.ACCOUNT_PROFILE).first() }
        if (stored.isBlank()) null else json.decodeFromString(MemberProfile.serializer(), stored)
    }.onFailure { Timber.tag("jm").w(it, "读取账号资料快照失败") }
        .getOrNull()

    fun readToken(): String? = tokenCache ?: secrets.get(SettingKeys.SECRET_JWT)?.also { tokenCache = it }

    /**
     * 请求头的内存缓存。
     *
     * `SecretStore.get` 每读一次都要走一次 Keystore AES-GCM 解密；而这两个值**几乎每次请求都要用**
     * （`ApiClient.authProvider`），不解一次缓存等于把每条接口都拖慢一点。
     * 写/清凭证时同步置空，所以不会读到旧值。
     */
    @Volatile
    private var tokenCache: String? = null

    @Volatile
    private var avsCache: String? = null

    /** 请求头里的两个值；缺一个都算没登录（返回 null，[ApiClient] 就不加 header）。 */
    fun authHeaders(): com.haruhi.jmcomic.feature.api.AuthHeaders? {
        val jwt = tokenCache ?: secrets.get(SettingKeys.SECRET_JWT)?.also { tokenCache = it }
        val avs = avsCache ?: secrets.get(SettingKeys.SECRET_AVS)?.also { avsCache = it }
        if (jwt.isNullOrBlank() || avs.isNullOrBlank()) return null
        return com.haruhi.jmcomic.feature.api.AuthHeaders(jwt = jwt, avs = avs)
    }

    /** 登录成功：先写凭证（同步、能立刻生效），再写资料快照。 */
    suspend fun saveSession(profile: MemberProfile, jwt: String, avs: String) {
        secrets.put(SettingKeys.SECRET_JWT, jwt)
        secrets.put(SettingKeys.SECRET_AVS, avs)
        tokenCache = jwt
        avsCache = avs
        saveProfile(profile)
    }

    suspend fun saveProfile(profile: MemberProfile) {
        runCatching {
            settings.putJmString(SettingKeys.Jm.ACCOUNT_PROFILE, json.encodeToString(MemberProfile.serializer(), profile))
        }.onFailure { Timber.tag("jm").w(it, "保存账号资料快照失败") }
    }

    /** 只清凭证、保留资料快照（token 失效时用：页面上的昵称/收藏数不清空）。 */
    fun clearCredentials() {
        secrets.clear(SettingKeys.SECRET_JWT)
        secrets.clear(SettingKeys.SECRET_AVS)
        tokenCache = null
        avsCache = null
    }

    /**
     * 退出登录：凭证与资料快照一起清掉。
     *
     * 业务数据（书库、下载、本机最近浏览、本机收藏夹）**一概不动**——它们是设备级的，
     * 与账号无关（`docs/account-design.md` §5.4）。
     */
    suspend fun clearAll() {
        clearCredentials()
        runCatching { settings.putJmString(SettingKeys.Jm.ACCOUNT_PROFILE, "") }
            .onFailure { Timber.tag("jm").w(it, "清除账号资料快照失败") }
    }
}
