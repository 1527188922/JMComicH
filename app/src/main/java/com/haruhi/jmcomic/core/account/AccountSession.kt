package com.haruhi.jmcomic.core.account

import com.haruhi.jmcomic.feature.api.AuthHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 账号会话的唯一真相源（进程内单例，挂在 `AppContainer` 上）。
 *
 * 三处读它：
 * - `ApiClient.authProvider` —— 决定每个请求带不带 `Authorization` / `Cookie`；
 * - 「我的」页 —— 决定账户卡显示"登录"还是昵称；
 * - 账号页 —— 决定显示数据还是"请先登录"。
 *
 * **它不碰业务数据**：登录/退出都不动书库、下载、本机最近浏览与本机收藏夹。
 */
class AccountSession(private val store: AccountStore) {

    private val _state = MutableStateFlow(store.loadInitialState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** 当前资料快照（含 [AccountState.Expired] 的旧快照）。 */
    val profile: MemberProfile? get() = _state.value.profileOrNull

    val isSignedIn: Boolean get() = _state.value.isSignedIn

    /** 给 `ApiClient` 用的请求头；未登录/已失效时返回 null（失效时凭证已被清掉）。 */
    fun authHeaders(): AuthHeaders? = if (isSignedIn) store.authHeaders() else null

    suspend fun signIn(profile: MemberProfile, jwt: String, avs: String) {
        store.saveSession(profile, jwt, avs)
        _state.value = AccountState.SignedIn(profile)
    }

    /** 资料有变化时刷新快照（昵称/收藏数等）。不影响登录态。 */
    suspend fun updateProfile(profile: MemberProfile) {
        store.saveProfile(profile)
        _state.update { current ->
            when (current) {
                is AccountState.SignedIn -> AccountState.SignedIn(profile)
                is AccountState.Expired -> AccountState.Expired(profile)
                AccountState.Anonymous -> AccountState.Anonymous
            }
        }
    }

    /** 401：清凭证、留快照，UI 据此显示「登录已失效」横幅。 */
    fun markExpired() {
        if (_state.value is AccountState.Expired) return
        store.clearCredentials()
        val snapshot = _state.value.profileOrNull
        _state.value = if (snapshot != null) AccountState.Expired(snapshot) else AccountState.Anonymous
    }

    /** 退出登录：凭证 + 快照都清。 */
    suspend fun signOut() {
        store.clearAll()
        _state.value = AccountState.Anonymous
    }
}
