package com.haruhi.jmcomic.core.account

/**
 * 账号会话状态。
 *
 * 只有三种，UI 不该自己再拼"有没有 token"这类判断（见 `docs/account-design.md` §9）：
 *
 * ```
 * ANONYMOUS ──登录成功──▶ SIGNED_IN ──任一账号接口 401──▶ EXPIRED
 *     ▲                        │                            │
 *     └────── 退出 / 清凭证 ────┘◀──── 重新登录成功 ───────────┘
 * ```
 *
 * [Expired] **保留资料快照**：横幅只提示"登录已失效"，页面里已经显示出来的
 * 昵称/收藏数不清空（清空会让人以为数据丢了）。
 */
sealed interface AccountState {

    /** 没登录（或刚退出）。 */
    data object Anonymous : AccountState

    /** 已登录且 token 可用。 */
    data class SignedIn(val profile: MemberProfile) : AccountState

    /** 曾经登录过，但 token 已失效（401）。凭证已清掉，资料快照还在。 */
    data class Expired(val profile: MemberProfile) : AccountState

    /** 当前资料快照（三种状态都可能为 null）。 */
    val profileOrNull: MemberProfile?
        get() = when (this) {
            is SignedIn -> profile
            is Expired -> profile
            Anonymous -> null
        }

    val isSignedIn: Boolean get() = this is SignedIn
}
