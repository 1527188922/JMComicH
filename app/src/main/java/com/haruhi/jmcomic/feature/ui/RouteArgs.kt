package com.haruhi.jmcomic.feature.ui

import java.util.Base64

/**
 * 把"用户输入的任意文本"塞进路由参数时的编解码。
 *
 * ## 为什么不用 `URLEncoder` / `URLDecoder`（这是用户报的一个 bug）
 *
 * 关键字原来是这样走的：`URLEncoder.encode(keyword)` 拼进路由 → 页面里
 * `URLDecoder.decode(rawKeyword)` 解回来。两处都会咬人：
 *
 * 1. `URLEncoder` 是**表单编码（application/x-www-form-urlencoded）**，空格会变成 `+`；
 *    而 Navigation 解析路由参数走的是 `Uri.decode` 那一套——**只做百分号解码，
 *    不会把 `+` 还原成空格**。所以 `+` 会一路活到界面（用户看到输入框里全是 `+`）；
 * 2. 我们再解一次的那次 `URLDecoder.decode` 是**第二次解码**，而且它碰到用户关键字里
 *    的 `%`（例如 `100%`）会抛 `IllegalArgumentException`（`%` 后面不是合法转义）。
 *    异常被 `runCatching` 兜住后退回"没解码的原始串"，于是上面那些 `+` 就留在了界面上。
 *    用户原话的现象（`[Hoshikawa+Ranga]+120cm...100%...`）正是这两条叠出来的。
 *
 * ## 现在的做法：Base64（URL-safe、无填充）+ 前缀
 *
 * - 字母表只有 `A-Za-z0-9-_`，**不含 `%`、`+`、`&`、`=`、空格**，
 *   所以无论 URI 那一层解码几次、怎么解，值都原样到达——这是它比"换个转义函数"更稳的地方；
 * - 加一个 [ROUTE_ARG_PREFIX] 前缀是为了**可判别**：不带前缀的值按明文处理。
 *   没有前缀的话，`Base64.decode("abcd")` 会"成功"解出一堆乱码，
 *   用户搜 `abcd` 这种恰好是合法 Base64 的词就会被改坏；
 * - 代价是路由不再可读，但路由是内部细节，不需要人读。
 *
 * 用 `java.util.Base64`（API 26 起可用，与本工程 `minSdk 26` 一致）而不是
 * `android.util.Base64`，这样编解码是**纯 JVM 逻辑**，单测不需要 Robolectric。
 */
internal const val ROUTE_ARG_PREFIX = "b64."

/** 编码一个路由参数值（用户输入的任意文本）。 */
internal fun encodeRouteArg(value: String): String =
    ROUTE_ARG_PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

/**
 * 解码一个路由参数值。
 *
 * 不带 [ROUTE_ARG_PREFIX] 的一律当明文返回（兼容旧路由 / 手写的深链）；
 * 带前缀但内容不是合法 Base64 时也退回原始串，绝不抛异常。
 */
internal fun decodeRouteArg(raw: String): String {
    if (!raw.startsWith(ROUTE_ARG_PREFIX)) return raw
    val payload = raw.removePrefix(ROUTE_ARG_PREFIX)
    return runCatching {
        String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
    }.getOrDefault(raw)
}
