package com.haruhi.haruhibox.testing

import java.io.File
import java.net.URLDecoder

/**
 * 读取由参考实现（haruhibot-server）生成的黄金用例数据。
 *
 * 生成方式见 `docs/p0/README.md`：用反射调用参考实现的编译产物，
 * **不修改**参考工程源码。数据文件位于 `app/src/test/resources/golden/golden.txt`。
 *
 * 文本字段使用 **percent-encoding（UTF-8，空格为 `%20`）**，`-` 表示 null。
 * 用 percent 而不是 base64 是为了让空串、中文和填充都不产生歧义。
 */
object GoldenData {

    private val rows: List<Row> by lazy { load() }

    data class Row(val kind: String, val fields: List<String>) {
        operator fun get(index: Int): String = fields[index]
        fun int(index: Int): Int = fields[index].toInt()
        fun long(index: Int): Long = fields[index].toLong()
        /** 字段是 percent-encoded 的 UTF-8 字符串时用它还原；`-` 表示 null。 */
        fun text(index: Int): String? = fields[index].takeIf { it != "-" }?.let(::percentDecode)
    }

    private fun load(): List<Row> {
        val stream = GoldenData::class.java.classLoader
            ?.getResourceAsStream("golden/golden.txt")
            ?: error("golden/golden.txt not found in test resources; see docs/p0/README.md")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trimEnd('\r', '\n') }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    // split 的第二参数必须给 limit，否则行尾的空字段（如空的 filename）
                    // 会被 Kotlin 丢掉，导致字段错位。
                    val parts = line.split('|', limit = 6)
                    Row(parts[0], parts.drop(1))
                }
                .toList()
        }
    }

    fun of(kind: String): List<Row> = rows.filter { it.kind == kind }

    /** percent-encoding 解码（UTF-8）。`+` 按字面量处理，不当空格。 */
    fun percentDecode(text: String): String =
        URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)

    /**
     * 黄金 PNG 文件。文件名形如 `descramble_dst_16x43_b8.png`：
     * 宽 x 高、以及解扰块数。
     */
    fun goldenImage(name: String): File {
        val url = GoldenData::class.java.classLoader?.getResource("golden/descramble/$name")
            ?: error("golden image not found: $name")
        return File(url.toURI())
    }
}
