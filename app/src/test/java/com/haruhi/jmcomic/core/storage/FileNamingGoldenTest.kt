package com.haruhi.jmcomic.core.storage

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.testing.GoldenData
import org.junit.Test

/**
 * 文件名清洗与排序的黄金用例（G4 / G4B / G5）。
 *
 * 期望值来自参考实现 `shared/util/FileUtil.sanitizeFileName` 与
 * `JmcomicService.sortFolders/sortFiles` 的真实输出，要求**逐字节一致**。
 */
class FileNamingGoldenTest {

    @Test
    fun `sanitize matches reference implementation byte for byte`() {
        val rows = GoldenData.of("G4")
        assertThat(rows).isNotEmpty()

        for (row in rows) {
            val name = row.text(0)
            val fallback = row.text(1)
            val maxBytes = row.int(2)
            val expected = GoldenData.percentDecode(row[3])

            assertThat(FileNaming.sanitize(name, fallback, maxBytes)).isEqualTo(expected)
        }
    }

    @Test
    fun `album folder name matches reference implementation`() {
        val rows = GoldenData.of("G4B")
        assertThat(rows).isNotEmpty()

        for (row in rows) {
            val name = row.text(0)
            val aid = row[1]
            val maxBytes = row.int(2)
            val expected = GoldenData.percentDecode(row[3])

            assertThat(FileNaming.albumFolderName(name, aid, maxBytes)).isEqualTo(expected)
        }
    }

    @Test
    fun `illegal characters are replaced with dash`() {
        assertThat(FileNaming.sanitize("a\\b/c:d*e?f\"g<h>i|j", "480854", 150))
            .isEqualTo("a-b-c-d-e-f-g-h-i-j")
    }

    @Test
    fun `control characters are removed`() {
        assertThat(FileNaming.sanitize("a\u0000b\u0001c", "480854", 150)).isEqualTo("abc")
    }

    @Test
    fun `windows reserved device names get a trailing underscore`() {
        for (name in listOf("CON", "con", "com1", "NUL")) {
            assertThat(FileNaming.sanitize(name, "480854", 150)).isEqualTo("${name}_")
        }
    }

    @Test
    fun `fallback is used when nothing meaningful remains`() {
        assertThat(FileNaming.sanitize("", "480854", 150)).isEqualTo("480854")
        assertThat(FileNaming.sanitize("   ", "480854", 150)).isEqualTo("480854")
    }

    @Test
    fun `trailing dots and blanks are stripped`() {
        assertThat(FileNaming.sanitize("标题.", "480854", 150)).isEqualTo("标题")
        assertThat(FileNaming.sanitize("标题 ", "480854", 150)).isEqualTo("标题")
        assertThat(FileNaming.sanitize("  前后空白  ", "480854", 150)).isEqualTo("前后空白")
    }

    /**
     * 参考实现的已知行为：只有点组成的名字在 `trimEndDotsAndBlank` 之后会变成空串，
     * 不会再走一次兜底。这个用例是**有意锁定该行为**，保证与服务器端落盘名称一致。
     */
    @Test
    fun `name made only of dots becomes an empty string like the reference`() {
        assertThat(FileNaming.sanitize("...", "480854", 150)).isEmpty()
    }

    @Test
    fun `truncation respects utf8 byte budget and character boundaries`() {
        val chinese = "这是一个非常长的中文标题用于测试按字节截断的行为以及字符边界处理是否正确"
        val truncated = FileNaming.sanitize(chinese, "480854", 30)
        assertThat(truncated.toByteArray(Charsets.UTF_8).size).isAtMost(30)
        assertThat(chinese).startsWith(truncated)

        // 4 字节 emoji 不能被截成半个字符
        val emoji = FileNaming.sanitize("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", "480854", 10)
        assertThat(emoji).isEqualTo("\uD83D\uDE00\uD83D\uDE00")
    }

    @Test
    fun `maxBytes of zero means no truncation`() {
        val chinese = "这是一个非常长的中文标题用于测试按字节截断的行为以及字符边界处理是否正确"
        assertThat(FileNaming.sanitize(chinese, "480854", 0)).isEqualTo(chinese)
    }

    @Test
    fun `base name and extension follow the reference helpers`() {
        assertThat(FileNaming.baseName("00001.webp")).isEqualTo("00001")
        assertThat(FileNaming.baseName("noext")).isEqualTo("noext")
        assertThat(FileNaming.baseName("a.b.c")).isEqualTo("a.b")

        assertThat(FileNaming.extension("00001.WEBP")).isEqualTo("webp")
        assertThat(FileNaming.extension("noext")).isNull()
        assertThat(FileNaming.extension("trailing.")).isNull()
    }

    @Test
    fun `chapter folder sorting matches the reference implementation`() {
        val rows = GoldenData.of("G5F")
        assertThat(rows).hasSize(1)
        val expected = rows[0][0].split(',')

        val input = listOf("第10话", "第2话", "第1话", "番外", "第1话_extra", "封面")
        assertThat(FileNaming.sortChapterFolders(input)).isEqualTo(expected)
        // 没有"第N话"的目录排在前面（序号视为 0），与参考实现一致
        assertThat(FileNaming.sortChapterFolders(input).take(2)).containsExactly("番外", "封面")
    }

    @Test
    fun `image file sorting matches the reference implementation`() {
        val rows = GoldenData.of("G5I")
        assertThat(rows).hasSize(1)
        val expected = rows[0][0].split(',')

        val input = listOf("10", "2", "1", "003", "cover", "1a", "")
        assertThat(FileNaming.sortImageFiles(input)).isEqualTo(expected)
        // 非数字名与空串都按 0 处理，空串会被过滤掉
        assertThat(FileNaming.sortImageFiles(input)).doesNotContain("")
    }

    @Test
    fun `imageFile sorting strips the extension before extracting the number`() {
        val input = listOf("00010.webp", "00002.webp", "00001.webp")
        assertThat(FileNaming.sortImageFiles(input))
            .containsExactly("00001.webp", "00002.webp", "00010.webp")
            .inOrder()
    }
}
