package com.haruhi.jmcomic.feature.api

import com.haruhi.jmcomic.core.network.NetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException

/** 网页版重发验证信的结果。 */
sealed interface WebConfirmResult {
    data class Success(val message: String) : WebConfirmResult
    data class Failure(val message: String) : WebConfirmResult

    /** 响应里没有任何可识别的重发结果（例如 Cloudflare 验证页 / 页面改版）。 */
    data object Unknown : WebConfirmResult
}

/**
 * 网页版「重发验证信」客户端。
 *
 * ## 为什么不用 App 的 `/confirm`
 *
 * 2026-10-23 实测（随机账号 + 网页版表单）：
 * - App 的 `POST /confirm{username,password}` **永远**返回
 *   `无效的核查身份证!您确定此ID是正确的？`，即使账号刚注册、验证码还没过期也一样；
 * - 网页版 `https://18comic.vip/confirm` 的重发表单多一个隐藏字段
 *   **`submit_action=resend`**，由另一套 web 控制器处理，能正常重发；
 * - 所以重发**只能走网页版**，不能回退到 App API（回退只会把用户引到同一个错误）。
 *
 * ## 请求形态（复刻网页表单，2026-10-23 实测通过）
 *
 * ```
 * POST https://{web_host}/confirm
 * Content-Type: application/x-www-form-urlencoded
 *
 * submit_action=resend
 * username=...
 * password=...
 * submit_confirm=发送邮件   ← 网页表单的提交按钮名，服务端不校验值，但保持与网页一致
 * ```
 *
 * 响应是 **HTML**（不是 JM 的 JSON 信封），结果写在页面里的
 * `toastr['error']("信件已发出，请十分钟后再试 !")` 这类调用中；
 * 同一个页面里还可能夹着无关提示（例如 `請勿重複簽到`），所以解析时要按内容筛选。
 *
 * 不发送 `Token` / `Tokenparam`（那是 App API 的签名），也不发登录态 header：
 * 网页版重发是给"还没登录、账号也没验证"的用户用的。
 */
class WebConfirmClient(
    private val client: OkHttpClient,
    /**
     * 依次尝试的网页版 host。
     *
     * `18comic.vip` 是官方 `/setting` 的 `main_web_host`（账号在主站，优先走它）；
     * `comic18j-guta.quest` 是官方 APK 下载页所在的镜像，2026-10-23 实测它的 `/confirm`
     * 也能重发 API 注册的账号，用来在主站被墙 / Cloudflare 拦截时兜底。
     * 不要在这里加入非官方域名——重发请求会带上账号密码。
     */
    private val hosts: List<String> = DEFAULT_HOSTS,
) {

    suspend fun resend(username: String, password: String): String = withContext(Dispatchers.IO) {
        val form = buildResendForm(username, password)
        var lastError: NetworkError? = null
        // 多条 host 依次尝试，但整体封顶 30 秒：网页版响应带整页广告，不能让用户干等太久
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        for (host in hosts) {
            if (System.currentTimeMillis() >= deadline) break
            val url = "https://$host/confirm"
            val result = try {
                attempt(url, form)
            } catch (e: NetworkError) {
                // 这条 host 连不上 / 超时：换下一条；最终把最后一次原因抛给界面
                lastError = e
                continue
            }
            when (result) {
                is WebConfirmResult.Success -> return@withContext result.message
                // 网页版明确给出了失败文案（账号/密码不对、服务端拒绝）：这是权威结果，不再换 host 重试
                is WebConfirmResult.Failure -> throw NetworkError.Business(result.message)

                WebConfirmResult.Unknown -> {
                    Timber.tag("jm").d("网页版重发：%s 响应无法识别，尝试下一条线路", host)
                    lastError = NetworkError.Business("网页版重发验证信没有返回结果，请稍后重试")
                }
            }
        }
        throw lastError ?: NetworkError.Business("网页版重发验证信超时，请稍后重试或到网页版操作")
    }

    private fun attempt(url: String, form: FormBody): WebConfirmResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-TW,zh;q=0.9")
            // 与浏览器提交表单时的 Referer/Origin 一致；主站的 WAF 可能校验
            .header("Referer", url)
            .header("Origin", url.removeSuffix("/confirm"))
            .post(form)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("jm").d("网页版重发：%s -> HTTP %d", url, response.code)
                    return@use WebConfirmResult.Unknown
                }
                val html = response.body?.string().orEmpty()
                val parsed = parseWebConfirmResponse(html)
                Timber.tag("jm").d(
                    "网页版重发：%s -> %s",
                    url,
                    when (parsed) {
                        is WebConfirmResult.Success -> "success"
                        is WebConfirmResult.Failure -> "failure"
                        WebConfirmResult.Unknown -> "unknown"
                    },
                )
                parsed
            }
        } catch (e: IOException) {
            Timber.tag("jm").d(e, "网页版重发：%s 请求失败", url)
            // 网络层的失败换下一条 host；但最终要把原因抛给界面
            throw NetworkError.from(url, e)
        }
    }

    companion object {
        /**
         * 网页版 host，按顺序尝试。主站优先，官方镜像兜底。
         *
         * 不要在这里加入非官方域名——重发请求会带上账号密码。
         */
        val DEFAULT_HOSTS: List<String> = listOf(
            "18comic.vip",
            "comic18j-guta.quest",
            "www.18comic.vip",
            "18comic.org",
        )

        /** 整次重发（含逐 host 重试）的总预算。 */
        private const val TOTAL_BUDGET_MS = 30_000L
    }
}

/** 重发表单：字段名 / 顺序与网页版 `<form name="confirmEmailForm">` 一致。 */
internal fun buildResendForm(username: String, password: String): FormBody =
    FormBody.Builder()
        .add("submit_action", "resend")
        .add("username", username.trim())
        .add("password", password)
        .add("submit_confirm", "发送邮件")
        .build()

/** 页面里 `toastr['error']("…")` / `toastr["success"]("…")` 的调用。 */
private val TOASTR_REGEX = Regex(
    """toastr\[['"]([^'"]+)['"]\]\(\s*["'](.*?)["']\s*\)""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

/**
 * 解析网页版 `/confirm` 的 HTML。
 *
 * 只看 `toastr[...]` 调用，并按**内容**判断成败：
 * 网页版成功时用的是 `toastr['error']`（红色提示），所以不能看 level。
 * 同一页还可能夹带无关提示（`請勿重複簽到`），先按"是不是重发相关"筛一遍。
 */
fun parseWebConfirmResponse(html: String): WebConfirmResult {
    if (html.isBlank()) return WebConfirmResult.Unknown
    val messages = TOASTR_REGEX.findAll(html)
        .mapNotNull { cleanWebMessage(it.groupValues[2]) }
        .toList()
    if (messages.isEmpty()) return WebConfirmResult.Unknown

    val relevant = messages.filter(::isResendRelated)
    relevant.firstOrNull(::isSuccessMessage)?.let { return WebConfirmResult.Success(it) }
    val failure = relevant.firstOrNull(::isFailureMessage) ?: relevant.firstOrNull()
    return failure?.let { WebConfirmResult.Failure(it) } ?: WebConfirmResult.Unknown
}

/** 重发相关提示的关键词：避免把页面上无关的 `請勿重複簽到` 当成本次结果。 */
private fun isResendRelated(message: String): Boolean =
    listOf(
        "信件", "郵件", "邮件", "信箱", "email", "Email", "EMAIL",
        "驗證", "验证", "認證", "认证", "帳號", "账号", "用戶名", "用户名", "用戶", "用户",
        "密碼", "密码", "重發", "重发", "核查", "身份證", "身份证", "ID",
        "十分鐘", "十分钟",
    ).any(message::contains)

private fun isSuccessMessage(message: String): Boolean =
    listOf(
        "已发出", "已發出", "已发送", "已發送", "发送成功", "發送成功",
        "信件已", "郵件已", "邮件已", "请查收", "請查收", "已寄出", "已寄",
        "十分钟", "十分鐘",
    ).any(message::contains)

private fun isFailureMessage(message: String): Boolean =
    listOf(
        "无效", "無效", "失败", "失敗", "错误", "錯誤", "不存在",
        "不正确", "不正確", "超时", "超時", "未验证", "未驗證",
    ).any(message::contains)

/**
 * 提示文案清洗：HTML 实体 / JS 转义 / 首尾空白。
 *
 * 网页版的 `toastr` 文案是服务端直接拼进 `<script>` 的，可能带 `&nbsp;`、
 * `&#39;` 或 `\"`；原样塞进弹窗/提示行会露出转义字符。
 */
internal fun cleanWebMessage(raw: String): String? {
    val decoded = raw
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\n", " ")
        .replace("\\r", " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""&#(?:x([0-9a-fA-F]+)|(\d+));""")) { match ->
            val code = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
                ?: match.groupValues[2].toIntOrNull()
            code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }.orEmpty()
        }
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
    return decoded.takeIf { it.isNotEmpty() }
}
