package com.haruhi.jmcomic.core.storage

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「打开文件夹」的目录 URI（用户反馈第 2 点）。
 *
 * 这条路能不能走通，取决于成品落在哪儿：
 * - **API 29+** 成品在公共下载目录里，可以给出文档提供者的目录 URI，
 *   系统文件管理器直接定位过去（我们不需要任何存储权限）；
 * - **API 26～28** 成品在应用外部私有目录里，别的应用根本看不见，所以必须给 null，
 *   界面据此不显示那颗按钮。
 *
 * 用 Robolectric 按 API 级别分别断言，避免"在老系统上给了一个打不开的 URI"。
 */
@RunWith(RobolectricTestRunner::class)
class ExportPublisherFolderTest {

    private fun publisher() = AndroidExportPublisher(ApplicationProvider.getApplicationContext())

    @Test
    @Config(sdk = [34])
    fun `on modern android the folder uri points at the public downloads subfolder`() {
        val uri = publisher().folderUri()

        assertThat(uri).isNotNull()
        assertThat(uri!!.authority).isEqualTo(AndroidExportPublisher.EXTERNAL_STORAGE_PROVIDER)
        // 卷名前缀不能少：少了 DocumentsUI 会退回"下载"根目录，等于没定位到文件夹
        assertThat(DocumentsContract.getDocumentId(uri))
            .isEqualTo("${AndroidExportPublisher.PRIMARY_VOLUME}:Download/${AndroidExportPublisher.SUBFOLDER}")
    }

    @Test
    @Config(sdk = [34])
    fun `the folder uri is a directory document, not a file`() {
        val uri = publisher().folderUri()!!

        // 目录文档 URI 的形状是 content://<provider>/document/<docId>。
        // 这里不去问系统"这是不是目录"（DocumentsContract.isDocumentUri 会去查提供者，
        // Robolectric 里没有真实 DocumentsUI），只断言 URI 的形状。
        assertThat(uri.pathSegments.first()).isEqualTo("document")
        assertThat(DocumentsContract.getDocumentId(uri)).endsWith(AndroidExportPublisher.SUBFOLDER)
    }

    @Test
    @Config(sdk = [28])
    fun `on old android the app private folder cannot be browsed so there is no uri`() {
        // 老系统这条路上成品在 Android/data/<pkg>/files/Download，
        // 别的应用无权浏览 —— 给 URI 只会让用户点开一个打不开的界面
        assertThat(Build.VERSION.SDK_INT).isEqualTo(28)
        assertThat(publisher().folderUri()).isNull()
    }

    @Test
    @Config(sdk = [34])
    fun `the location label matches where the folder uri points`() {
        assertThat(publisher().locationLabel)
            .isEqualTo("下载/${AndroidExportPublisher.SUBFOLDER}")
    }

    @Test
    @Config(sdk = [34])
    fun `the folder uri is stable across calls`() {
        val first = publisher().folderUri()
        val second = publisher().folderUri()

        assertThat(first).isEqualTo(second)
        assertThat(Uri.parse(first.toString())).isEqualTo(second)
    }
}
