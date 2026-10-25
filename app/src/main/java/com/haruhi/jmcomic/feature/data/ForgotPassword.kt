package com.haruhi.jmcomic.feature.data

/**
 * 「忘记密码」表单的本地规则（`docs/official-api-manga.md` §7.5）。
 *
 * 官方 App 的入口是 `POST /forgot {email}`：只填**注册邮箱**，服务端把
 * 「用户名 + 系统生成的新密码」发到该邮箱，**旧密码当场作废**
 * （2026-10-25 用真实账号实测：成功调用之后原密码登录直接 401 `無效的用戶名和/或密碼！`）。
 * 所以这个入口没有任何验证码、也没有"设置新密码"的一步——重置完全在服务端完成。
 */

/** 邮箱输入上限：与注册表单一致（官方 `maxLength` 50）。 */
const val FORGOT_EMAIL_MAX = 50

/** 忘记密码表单。 */
data class ForgotPasswordForm(val email: String = "") {
    /** 输入框硬上限（按码位截断，emoji 不会被劈成半个）。 */
    fun limited(): ForgotPasswordForm =
        copy(email = limitCodePoints(email, FORGOT_EMAIL_MAX))
}

/**
 * 本地校验：只拦"没填 / 不像邮箱"。
 *
 * 邮箱是否真的注册过一律交给服务端——它的文案更准，而且实测会区分两种情况：
 * `請輸入有效的電子EMAIL地址！`（格式不对）与 `沒有任何用戶使用此電子EMAIL！`（没这个账号）。
 * 界面**原样展示**服务端文案，不自造"账号不存在"之类的提示。
 */
fun validateForgotPassword(form: ForgotPasswordForm): String? {
    val email = form.email.trim()
    return when {
        email.isEmpty() -> "请输入注册邮箱"
        !SIGN_UP_EMAIL_PATTERN.matches(email) -> "邮箱格式不正确"
        else -> null
    }
}
