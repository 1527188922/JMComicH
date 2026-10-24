package com.haruhi.jmcomic.feature.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关于」页检查更新的**纯逻辑**测试（不联网）。
 *
 * 覆盖三件事：
 * 1. 版本比较 [ReleaseChecker.isNewer]——tag（`v1.2.0`）对当前版本名
 *    （`1.1.1` / debug 的 `1.1.1-debug`），不符合 `v*.*.*` 时必须判"无法识别"
 *    而不是误报新版本；
 * 2. 下载标题 ↔ tag 的互转（找回 DownloadManager 记录后反推版本号的依据）；
 * 3. **真实响应样本**能被 [ReleaseChecker.parse] 正确解析（字段名 / 未知字段忽略 /
 *    从 assets 里取出固定名字的 APK 地址）。
 */
class ReleaseCheckTest {

    // ---------------- 版本比较 ----------------

    @Test
    fun `tag 比当前版本大就是新版本`() {
        assertTrue(ReleaseChecker.isNewer("v1.2.0", "1.1.1") == true)
        assertTrue(ReleaseChecker.isNewer("v2.0.0", "1.9.9") == true)
        assertTrue(ReleaseChecker.isNewer("v1.1.2", "1.1.1") == true)
    }

    @Test
    fun `tag 等于当前版本不算新`() {
        assertFalse(ReleaseChecker.isNewer("v1.1.1", "1.1.1")!!)
    }

    @Test
    fun `tag 小于当前版本不算新`() {
        assertFalse(ReleaseChecker.isNewer("v1.1.0", "1.1.1")!!)
        assertFalse(ReleaseChecker.isNewer("v1.0.9", "1.1.0")!!)
    }

    @Test
    fun `debug 版本名去掉后缀再比`() {
        assertTrue(ReleaseChecker.isNewer("v1.2.0", "1.1.1-debug") == true)
        assertFalse(ReleaseChecker.isNewer("v1.1.1", "1.1.1-debug")!!)
    }

    @Test
    fun `段数不足按零补齐`() {
        assertTrue(ReleaseChecker.isNewer("v1.2", "1.1.1") == true)
        assertFalse(ReleaseChecker.isNewer("v1.1", "1.1.0")!!)
        assertFalse(ReleaseChecker.isNewer("v1.1", "1.1")!!)
    }

    @Test
    fun `tag 不符合 v 星点格式时无法判断`() {
        assertNull(ReleaseChecker.isNewer("master", "1.1.1"))
        assertNull(ReleaseChecker.isNewer("v1.x.0", "1.1.1"))
        assertNull(ReleaseChecker.isNewer("v", "1.1.1"))
        assertNull(ReleaseChecker.isNewer("", "1.1.1"))
    }

    // ---------------- 下载标题 ↔ tag ----------------

    @Test
    fun `下载标题能反解出 tag`() {
        assertEquals("v1.2.0", ReleaseChecker.tagFromTitle("JMComicH 新版本 v1.2.0"))
        assertEquals("v1.1.10", ReleaseChecker.tagFromTitle("JMComicH 新版本 v1.1.10"))
    }

    @Test
    fun `标题里没有版本号时反解为空`() {
        assertNull(ReleaseChecker.tagFromTitle("JMComicH 新版本"))
        assertNull(ReleaseChecker.tagFromTitle(""))
    }

    @Test
    fun `标题与 tag 能互转`() {
        val tag = "v2.3.4"
        assertEquals(tag, ReleaseChecker.tagFromTitle(ReleaseChecker.downloadTitleOf(tag)))
    }

    // ---------------- 真实响应样本 ----------------

    @Test
    fun `真实响应样本能被解析且取出 apk 地址`() {
        val release = ReleaseChecker.parse(SAMPLE_RESPONSE) ?: error("样本解析失败")
        assertEquals("v1.1.0", release.tagName)
        assertEquals("Release 1.1.0", release.name)
        assertFalse(release.prerelease)
        // body 里的 \r\n 转义应被还原成真实换行
        assertEquals("1. 支持登录\r\n2. 支持在线收藏夹\r\n3. 性能优化", release.body)
        assertEquals(3, release.assets.size)
        assertEquals(
            "https://gitee.com/Lelouch-cc/JMComicH/releases/download/v1.1.0/app-release.apk",
            ReleaseChecker.apkUrlOf(release),
        )
    }

    @Test
    fun `预览版字段能被解析`() {
        val release = ReleaseChecker.parse("""{"tag_name":"v2.0.0","prerelease":true}""")
            ?: error("解析失败")
        assertEquals(true, release.prerelease)
    }

    @Test
    fun `没有 apk 资产时下载地址为空`() {
        val release = GiteeRelease(
            tagName = "v2.0.0",
            assets = listOf(GiteeAsset(name = "v2.0.0.zip", browserDownloadUrl = "https://example.com/x.zip")),
        )
        assertNull(ReleaseChecker.apkUrlOf(release))
    }

    @Test
    fun `坏 json 解析为空不抛`() {
        assertNull(ReleaseChecker.parse("这不是 JSON"))
        assertNull(ReleaseChecker.parse("{\"tag_name\": 123}"))
    }

    private companion object {
        /** 2026-10 实测响应（删减了 author 的长字段，保留未知字段验证 ignoreUnknownKeys）。 */
        val SAMPLE_RESPONSE = """
            {
              "id": 1227194,
              "tag_name": "v1.1.0",
              "target_commitish": "fea4fdbd41f6fbac6e8dc77db0c61405f72f9193",
              "prerelease": false,
              "name": "Release 1.1.0",
              "body": "1. 支持登录\r\n2. 支持在线收藏夹\r\n3. 性能优化",
              "author": {"id": 5721721, "login": "Lelouch-cc", "type": "User"},
              "created_at": "2026-10-22T00:42:55+08:00",
              "assets": [
                {
                  "browser_download_url": "https://gitee.com/Lelouch-cc/JMComicH/releases/download/v1.1.0/app-release.apk",
                  "name": "app-release.apk"
                },
                {
                  "browser_download_url": "https://gitee.com/Lelouch-cc/JMComicH/archive/refs/tags/v1.1.0.zip",
                  "name": "v1.1.0.zip"
                },
                {
                  "browser_download_url": "https://gitee.com/Lelouch-cc/JMComicH/archive/refs/tags/v1.1.0.tar.gz",
                  "name": "v1.1.0.tar.gz"
                }
              ]
            }
        """.trimIndent()
    }
}
