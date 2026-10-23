package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.feature.api.EditResultDto

/**
 * 账号写操作（注册 / 验证码 / 收藏 / 收藏夹）返回值的统一解读。
 *
 * 这些接口的共同点是：**成败不看 HTTP**。实测失败也是 `HTTP 200 + 信封 code 200`，
 * 真正的结果在解密后的 `data` 里：成功 `{status:"ok", msg}`，失败
 * `{status:"fail", msg, errors:[…]}`（注册类接口尤其会把所有问题列在 `errors`）。
 *
 * 注意：注册**成功**时实测 `errors` 是空字符串（不是空数组），所以 DTO 用
 * `LooseStringListSerializer` 做宽容解析（见 `AccountModels.kt`）。
 */
fun EditResultDto.displayMessage(): String? {
    // `errors` 里是**全部**问题；`msg` 只是第一条。界面优先展示完整的一份，
    // 否则用户改完第一条又会被第二条挡住（实测注册会一次返回邮箱 + 用户名 + 密码三类错误）。
    val allErrors = errors
        .mapNotNull { cleanServerMessage(it) }
        .distinct()
    if (allErrors.isNotEmpty()) return allErrors.joinToString("；")
    return cleanServerMessage(msg)
}

/**
 * 判定业务成功并取出可展示的文案；失败抛 [NetworkError.Business]（`message` 是服务端原文）。
 *
 * @param fallback 服务端没给文案时的失败提示。
 * @param successFallback 成功但服务端没给文案时的提示。
 */
fun EditResultDto.requireSuccess(
    fallback: String,
    successFallback: String,
): String {
    val message = displayMessage()
    if (!isOk) throw NetworkError.Business(message ?: fallback)
    return message ?: successFallback
}

/**
 * 接口 `msg`/`errors` → 一行可展示的文案。
 *
 * 注册/验证码的文案里会带 `<br>` 之类的标签（与评论的 `msg` 同一个来源），
 * 直接弹出来会露出半截 HTML，所以复用评论正文那套清洗规则，再把换行压成空格。
 */
private val MULTI_WHITESPACE = Regex("\\s{2,}")

fun cleanServerMessage(raw: String?): String? {
    val cleaned = cleanCommentHtml(raw)
        .replace('\n', ' ')
        .replace(MULTI_WHITESPACE, " ")
        .trim()
    return cleaned.takeIf { it.isNotEmpty() }
}
