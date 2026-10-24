package com.haruhi.jmcomic.feature.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Gitee 最新发行版（`/repos/Lelouch-cc/JMComicH/releases/latest` 的响应）。 */
@Serializable
data class GiteeRelease(
    /** 形如 `v1.2.0`（建发行版时 tag 固定 `v*.*.*` 格式）。 */
    @SerialName("tag_name") val tagName: String = "",
    /** 发行版标题，弹框内容的标题行。 */
    val name: String = "",
    /** 更新说明，弹框正文（多行纯文本）。 */
    val body: String = "",
    /** 是否预览版，弹框标题行旁边的徽标。 */
    val prerelease: Boolean = false,
    val assets: List<GiteeAsset> = emptyList(),
)

@Serializable
data class GiteeAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

sealed interface ReleaseCheckResult {
    data class Ok(val release: GiteeRelease) : ReleaseCheckResult

    /** 网络失败 / 非 2xx / 解析失败，一律归为"暂时查不到"，界面提示重试。 */
    data object Unavailable : ReleaseCheckResult
}

/**
 * 「关于」页的版本更新检查（Gitee API v5）。
 *
 * 与 JM 接口无关，所以不走 `ApiClient`：这是一个独立的小客户端
 * （与 [WebConfirmClient] 同一模式），只复用 OkHttp 的代理 / 拦截器配置，
 * 超时单独放宽——Gitee 的响应不比 JM 接口快，接口默认 3 秒太紧。
 */
class ReleaseChecker(private val client: OkHttpClient) {

    suspend fun latest(): ReleaseCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val body = client.newCall(Request.Builder().url(LATEST_RELEASE_URL).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string() ?: error("响应体为空")
                }
            parse(body) ?: error("响应解析失败")
        }
            .onFailure { Timber.tag("update").w(it, "检查更新失败") }
            .map { ReleaseCheckResult.Ok(it) }
            .getOrDefault(ReleaseCheckResult.Unavailable)
    }

    companion object {
        /** 只解析自己认识的字段，响应里的 id / author / created_at 等全部忽略。 */
        private val json = Json { ignoreUnknownKeys = true }

        const val LATEST_RELEASE_URL =
            "https://gitee.com/api/v5/repos/Lelouch-cc/JMComicH/releases/latest"

        /** 发行版没带 APK 资产时的兜底入口（浏览器打开）。 */
        const val RELEASES_PAGE_URL = "https://gitee.com/Lelouch-cc/JMComicH/releases"

        /** Gitee 建发行版时固定上传的 APK 资产名。 */
        const val APK_ASSET_NAME = "app-release.apk"

        /** APK 下载地址；发行版没带 APK 资产时为 null（界面退回「打开发布页」）。 */
        fun apkUrlOf(release: GiteeRelease): String? =
            release.assets.firstOrNull { it.name == APK_ASSET_NAME }
                ?.browserDownloadUrl
                ?.takeIf { it.isNotBlank() }

        /** 解析发行版 JSON；解析失败返回 null（不抛）。抽出来是给单测喂真实样本用。 */
        internal fun parse(body: String): GiteeRelease? =
            runCatching { json.decodeFromString<GiteeRelease>(body) }.getOrNull()

        /**
         * `tag_name`（`v1.2.0`）与当前版本名（`1.1.1`，debug 是 `1.1.1-debug`）比较。
         *
         * 逐段按数值比较、长度不足补 0（`1.2` 等价 `1.2.0`）。
         *
         * @return true = 有新版本；false = 不新于当前；null = tag 不符合 `v*.*.*`
         *         （按"无法判断"处理，**绝不误报新版本**）。
         */
        fun isNewer(tagName: String, currentVersionName: String): Boolean? {
            val latest = parseVersionParts(tagName.trim().removePrefix("v")) ?: return null
            val current = parseVersionParts(
                currentVersionName.trim().substringBefore('-').removePrefix("v"),
            ) ?: return null
            val size = maxOf(latest.size, current.size)
            for (i in 0 until size) {
                val l = latest.getOrElse(i) { 0 }
                val c = current.getOrElse(i) { 0 }
                if (l != c) return l > c
            }
            return false
        }

        /** `1.2.0` → `[1, 2, 0]`；空串或含非数字段返回 null。 */
        private fun parseVersionParts(raw: String): List<Int>? {
            if (raw.isEmpty()) return null
            val parts = raw.split('.')
            if (parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return null
            return parts.map { it.toInt() }
        }

        /**
         * 下载任务的系统通知标题：`JMComicH 新版本 v1.2.0`。
         *
         * 它同时是"找回下载记录后反推版本号"的依据（见 [tagFromTitle]）——
         * 记录里没有我们的业务字段，标题是我们唯一可控、跨进程仍可读的载体。
         */
        fun downloadTitleOf(tag: String): String = "JMComicH 新版本 $tag"

        /** 从下载标题反推 tag（`v1.2.0`）；解析不出返回 null（记录视为失效）。 */
        fun tagFromTitle(title: String): String? = TITLE_TAG_REGEX.find(title)?.value

        private val TITLE_TAG_REGEX = Regex("""v\d+(\.\d+)*""")
    }
}
