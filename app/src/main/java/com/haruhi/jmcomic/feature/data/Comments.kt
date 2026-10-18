package com.haruhi.jmcomic.feature.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 详情页「评论」tab 的数据模型、正文清洗与内存缓存。
 *
 * 接口细节（实测 2026-10，aid=114514）见 `docs/official-api-2.1.11.md` §6 与
 * `feature/api/JmModels.kt` 的 `ForumRespDto`：每页 **10** 条、`total` 上限 5000、
 * 匿名可读、正文是 **HTML 片段**、每条主楼还自带 `replys` 内嵌回复。
 */

/**
 * 一条评论（已从接口 DTO 归一化成"能直接画"的形态）。
 *
 * @property cid 评论 id，**列表 key 与去重都用它**（同一页重复拉取时不能出现两个 key，
 *   LazyColumn 会直接抛 "Key was already used" 而白屏）。
 * @property author 展示名：`nickname` → `username` → "匿名用户"。
 * @property avatar 头像**文件名**（不含域名），null 表示接口没给。
 * @property text 已经过 [cleanCommentHtml] 清洗的纯文本正文（可能为空）。
 * @property likes 点赞数原文；`"0"` / 空 / `"null"` 一律折成 null（界面据此不显示）。
 * @property timeLabel 已归一化的日期文案（见 [commentTimeLabel]）。
 * @property levelName 等级名（`搖曳的金星` 这种），可能为 null。
 * @property replies 内嵌回复（结构相同，但**不会再嵌一层**）。
 */
data class CommentRow(
    val cid: String,
    val author: String,
    val avatar: String? = null,
    val text: String = "",
    val likes: String? = null,
    val timeLabel: String? = null,
    val levelName: String? = null,
    val replies: List<CommentRow> = emptyList(),
) {
    /** 头像占位圈里显示的那个字（优先昵称首字）。 */
    val initial: String
        get() = author.firstOrNull { !it.isWhitespace() }?.toString() ?: "?"
}

/**
 * 一页评论。
 *
 * @property page 这一页的页码（**从 1 开始**，与接口一致）。
 * @property total 服务端给的总条数（上限 5000）；拿不到时退化成这一页的条数。
 */
data class CommentPage(
    val page: Int,
    val total: Int,
    val comments: List<CommentRow>,
)

/**
 * 把新一页评论并到已有列表后面，**按 `cid` 去重**。
 *
 * 为什么必须去重：接口的 `total` 在翻页期间会变（有人发新评论时，下一页的内容会整体后移），
 * 同一个 `cid` 就可能出现两次，而列表拿 `cid` 当 key —— 重复 key 会直接崩。
 * 去重时**保留先出现的那一条**（顺序即接口顺序，不做二次排序）。
 */
fun mergeComments(existing: List<CommentRow>, incoming: List<CommentRow>): List<CommentRow> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + incoming.size)
    existing.forEach { seen.add(it.cid) }
    return existing + incoming.filter { seen.add(it.cid) }
}

/**
 * 评论正文的 HTML 清洗：**渲染成纯文本**。
 *
 * 实测正文形如：
 *
 * ```html
 * <div style='flex-direction:row;flex-wrap:wrap;'>喂，你看得很開心嘛</div>
 * <div style='…'>…共用一個評論區<img style="width:18px;height:18px" src="…" alt="笑哭" /></div>
 * ```
 *
 * 规则（顺序有意义）：
 * 1. `<img … alt="笑哭" …>` → 取 `alt` 的文字（正文里的 `<img>` 只有表情这一种，
 *    丢掉它会让"…共用一個評論區"后面少一块）；没有 `alt` 就整条丢掉；
 * 2. `<br>` / `</div>` / `</p>` / `</li>` → 真正的换行（`<div>` 包裹意味着行内可能有多段）；
 * 3. 其余标签全部剥掉；
 * 4. HTML 实体还原（`&nbsp;` `&amp;` `&#39;` `&#x1F600;` 等）；
 * 5. 逐行 trim、连续空行压成一行、首尾空行去掉。
 *
 * 清洗后可能是空串（正文只有表情 / 只有标签），调用方据此决定要不要画正文。
 */
fun cleanCommentHtml(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var text = IMG_TAG_REGEX.replace(raw) { match ->
        val alt = ALT_ATTR_REGEX.find(match.value)
            ?.groupValues
            ?.let { it[1].ifBlank { it.getOrElse(2) { "" } } }
            .orEmpty()
        if (alt.isBlank()) "" else alt
    }
    text = LINE_BREAK_TAG_REGEX.replace(text, "\n")
    text = ANY_TAG_REGEX.replace(text, "")
    text = decodeHtmlEntities(text)
    return collapseBlankLines(text)
}

/**
 * 评论时间文案。
 *
 * 接口给的 `addtime` 实测是英文月日年（`Oct 11, 2026`，与 `lang` 无关），
 * 直接摆进中文界面很出戏，所以能解析就归一成 `2026-10-11`；
 * 解析不了（接口换了格式）就**原样返回**，宁可显示原串也不显示空白。
 */
fun commentTimeLabel(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == "0" || value.equals("null", ignoreCase = true)) return null
    return try {
        LocalDate.parse(value, ENGLISH_DATE).toString()
    } catch (_: DateTimeParseException) {
        value
    }
}

/** 评论喜欢数的展示值：`"0"` / 空 / `"null"` 都不显示。 */
fun commentLikeCount(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != "0" && !it.equals("null", ignoreCase = true) }

// ----------------------------------------------------------------------
// 内存缓存
// ----------------------------------------------------------------------

/**
 * 评论的**进程内缓存**（按 jm 号区分，用户要求）。
 *
 * 存在的理由：评论是分页拉的，用户"看几条 → 返回 → 再进来"时不该从第一页重新拉一遍，
 * 也不该丢掉已经滑出来的那几十条。缓存只活在内存里 —— **退出 App（进程结束）即清空**，
 * 磁盘上不留任何痕迹；`AppContainer.handleAppExit()` 里还会显式 [clearAll] 一次，
 * 让"返回键退出、进程还在后台跑下载"的情况也符合用户要求的语义。
 */
class CommentCache {

    /**
     * 一份缓存快照。
     *
     * @property nextPage 下一次要请求的页码（已经加载到 `nextPage - 1`）。
     * @property reachedEnd 已经到底了（再问也是空页），界面显示"没有更多评论了"。
     */
    data class Snapshot(
        val comments: List<CommentRow>,
        val total: Int,
        val nextPage: Int,
        val reachedEnd: Boolean,
    )

    private val lock = Any()
    private val entries = HashMap<Long, Snapshot>()

    fun get(albumId: Long): Snapshot? = synchronized(lock) { entries[albumId] }

    fun put(albumId: Long, snapshot: Snapshot) {
        synchronized(lock) { entries[albumId] = snapshot }
    }

    fun clear(albumId: Long) {
        synchronized(lock) { entries.remove(albumId) }
    }

    fun clearAll() {
        synchronized(lock) { entries.clear() }
    }
}

// ----------------------------------------------------------------------

private val IMG_TAG_REGEX = Regex("(?is)<img\\b[^>]*>")
private val ALT_ATTR_REGEX = Regex("""(?i)\balt\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/**
 * 会"占一行"的标签：`<br>`（含 `</br>` 与自闭合写法）以及 `</div>` `</p>` `</li>` `</hN>`。
 *
 * 写成两条并行的分支（`br` 与 `/` + 块标签名），而不是"可选斜杠 + 标签名"：
 * 那样可选的斜杠会先把 `</div>` 里的斜杠吃掉，后面的 `/div` 分支就永远匹配不到（踩过）。
 *
 * ⚠ 这段注释里**不要**出现星号紧跟斜杠的两个字符（本工程踩过：Kotlin 的块注释支持嵌套，
 * 那两个字符会直接把注释提前结束掉，后面的代码全变成语法错误）。
 */
private val LINE_BREAK_TAG_REGEX =
    Regex("(?i)<\\s*(?:br|/\\s*(?:br|div|p|li|h[1-6]))\\s*/?\\s*>")
private val ANY_TAG_REGEX = Regex("(?s)<[^>]*>")
private val ENTITY_REGEX = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,10});")

private val ENGLISH_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

private val NAMED_ENTITIES = mapOf(
    "nbsp" to " ",
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "middot" to "·",
    "times" to "×",
    "copy" to "©",
    "reg" to "®",
)

private fun decodeHtmlEntities(text: String): String =
    ENTITY_REGEX.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value

            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value

            else -> NAMED_ENTITIES[body.lowercase(Locale.ROOT)] ?: match.value
        }
    }

/** 码位 → 字符串；非法码位（代理区、超出 Unicode）原样返回原实体文本。 */
private fun codePointToString(codePoint: Int): String? =
    if (Character.isValidCodePoint(codePoint) && codePoint !in 0..0x1F) {
        String(Character.toChars(codePoint))
    } else {
        null
    }

/** 逐行 trim、连续空行压成一行、去掉首尾空行。 */
private fun collapseBlankLines(text: String): String {
    val lines = text.split('\n').map { it.trim() }
    val out = StringBuilder(text.length)
    var lastBlank = true
    lines.forEach { line ->
        if (line.isEmpty()) {
            if (!lastBlank) out.append('\n')
            lastBlank = true
        } else {
            out.append(line).append('\n')
            lastBlank = false
        }
    }
    return out.toString().trim()
}
