package com.haruhi.jmcomic.feature.data

/** 用户名上限：**服务端实测** 15 个字符（`用户名只能包含15个字符!`），不是官方前端的 50。 */
const val SIGN_UP_USERNAME_MAX = 15

/** 密码上限：官方注册表单 `maxLength={50}`。 */
const val SIGN_UP_PASSWORD_MAX = 50

/** 验证码输入上限（官方 `maxLength={10}`）。 */
const val SIGN_UP_CODE_MAX = 10

/**
 * 邮箱格式：逐字照抄官方注册表单的 `pattern`
 * （`MemberModal.tsx`：`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`）。
 *
 * **它只是第一道门槛**：服务端还会做更严的校验（实测 `probe-only@example.com`
 * 会被判 `电子邮件是不是有效的电子邮件地址!`，而官方前端正则本身是放行的）。
 * 所以本地拦格式，服务端的原文照样要展示。
 */
val SIGN_UP_EMAIL_PATTERN = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")

/** 注册表单。`gender` 取 `Male` / `Female`（官方提交值）。 */
data class SignUpForm(
    val username: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val email: String = "",
    val gender: String = "",
    /** 18+ 勾选：**纯前端门槛**，官方也不提交它。 */
    val adultConfirmed: Boolean = false,
    /** 用户协议勾选：同上，不提交。 */
    val termsAccepted: Boolean = false,
) {
    /** 输入框的硬上限（按码位截断，emoji 不会被劈成半个）。 */
    fun limited(): SignUpForm = copy(
        username = limitCodePoints(username, SIGN_UP_USERNAME_MAX),
        password = limitCodePoints(password, SIGN_UP_PASSWORD_MAX),
        passwordConfirm = limitCodePoints(passwordConfirm, SIGN_UP_PASSWORD_MAX),
    )
}

/**
 * 注册表单的本地校验结果：通过时所有字段都是 null。
 *
 * 只做"填没填 / 像不像 / 一不一致"这类**确定**的判断；邮箱是否可用、用户名是否被占用
 * 一律交给服务端（它的错误更准，而且可能一次给多条）。
 */
data class SignUpValidation(
    val username: String? = null,
    val password: String? = null,
    val passwordConfirm: String? = null,
    val email: String? = null,
    val gender: String? = null,
    val confirmation: String? = null,
) {
    val hasError: Boolean
        get() = listOf(username, password, passwordConfirm, email, gender, confirmation)
            .any { it != null }
}

/** 注册 / 验证码界面上能填的两种性别。 */
val SIGN_UP_GENDERS: List<Pair<String, String>> = listOf(
    "Male" to "男",
    "Female" to "女",
)

fun validateSignUp(form: SignUpForm): SignUpValidation {
    val username = form.username.trim()
    val email = form.email.trim()
    val password = form.password
    val confirm = form.passwordConfirm
    return SignUpValidation(
        username = when {
            username.isEmpty() -> "请输入用户名"
            username.codePointCount(0, username.length) > SIGN_UP_USERNAME_MAX ->
                "用户名最多 $SIGN_UP_USERNAME_MAX 个字符"

            else -> null
        },
        password = when {
            password.isEmpty() -> "请输入密码"
            // 密码按用户输入原样提交（服务端还可能带自己的策略）；这里只拦空
            else -> null
        },
        passwordConfirm = when {
            confirm.isEmpty() -> "请再次输入密码"
            confirm != password -> "两次输入的密码不一致"
            else -> null
        },
        email = when {
            email.isEmpty() -> "请输入邮箱"
            !SIGN_UP_EMAIL_PATTERN.matches(email) -> "邮箱格式不正确"
            else -> null
        },
        gender = if (form.gender.isBlank()) "请选择性别" else null,
        confirmation = when {
            !form.adultConfirmed -> "请确认已年满 18 岁"
            !form.termsAccepted -> "请阅读并同意用户协议"
            else -> null
        },
    )
}

/** 验证码的本地校验：只拦"没填 / 明显不是数字"。 */
fun validateSignUpCode(raw: String): String? {
    val code = raw.trim()
    return when {
        code.isEmpty() -> "请输入邮箱验证码"
        code.length > SIGN_UP_CODE_MAX -> "验证码最多 $SIGN_UP_CODE_MAX 位"
        // 官方字段叫 `numcode`，实测界面输入的是数字；非数字交给服务端回话会更准
        else -> null
    }
}

/**
 * 把一段文本截到 [max] 个**码位**。
 *
 * 不能直接用 `take()`：它按 UTF-16 char 截，正好切在 emoji 的代理对中间会得到半个字符
 * （界面上是一个 `?`，服务端还可能判成非法内容）。与 `limitCommentDraft` 同一套做法。
 */
fun limitCodePoints(raw: String, max: Int): String {
    if (max <= 0) return ""
    if (raw.codePointCount(0, raw.length) <= max) return raw
    return raw.substring(0, raw.offsetByCodePoints(0, max))
}
