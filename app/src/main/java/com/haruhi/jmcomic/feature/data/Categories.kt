package com.haruhi.jmcomic.feature.data

import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.feature.api.CategoryGroupDto
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 分类页的数据模型、纯映射规则与内存缓存。
 *
 * 接口细节见 `docs/official-api-2.1.11.md` §4：
 * - `GET /categories` 拿分类树（9 个主分类，每个带 0～6 个二级分类）；
 * - `GET /categories/filter?c=&o=&page=` 拿某一分类的漫画列表（每页 80 条）。
 *
 * 这个文件只放"不依赖 Android / 网络"的纯逻辑（映射、参数拼接、缓存），
 * 所以能直接写单元测试（`CategoryCatalogTest`）。网络往返在
 * [Repository.categoryCatalog] / [Repository.categoryAlbums]。
 */

/**
 * 抽屉第一层：一个主分类。
 *
 * @property name 展示名（接口给，例如 `同人`）。
 * @property slug 分类标识；**空串**代表「最新A漫」（接口里 id=0 那一项）。
 * @property subs 抽屉第二层。**至少有一项**：没有真正的二级分类时只有一个虚拟的
 *   [CATEGORY_SUB_ALL]，保证抽屉永远是"双层"。
 * @property totalAlbums 该主分类下的漫画总数（接口 `total_albums`）。`0` 表示
 *   "接口没给 / 这一类本来就没有内容"（最新A漫 等无二级分类的是 0），界面据此**不显示**数字。
 */
data class CategoryGroup(
    val name: String,
    val slug: String,
    val subs: List<CategorySub>,
    val totalAlbums: Long = 0L,
) {
    /**
     * 除虚拟的「全部」之外，还有没有真正的二级分类。
     *
     * 界面据此决定"点这个主分类之后抽屉要不要停住"：只有「全部」的话没有再选的余地，
     * 直接收起抽屉；有二级分类则停在第二层让用户挑。
     */
    val hasRealSubs: Boolean get() = subs.any { it.slug.isNotBlank() }

    /** 抽屉里显示在分类名右侧的总数（`1.0k` / `1.0w`）；没有总数时为 null。 */
    val countLabel: String? get() = if (totalAlbums > 0L) formatCompactCount(totalAlbums) else null

    fun subOf(slug: String): CategorySub? = subs.firstOrNull { it.slug == slug }
}

/**
 * 抽屉第二层：一个二级分类。
 *
 * @property slug 二级分类标识；**空串**专门留给虚拟的「全部」，表示"这个主分类不带子分类"
 *   （拼接口参数时只传主分类的 slug）。
 */
data class CategorySub(val name: String, val slug: String)

/**
 * 虚拟的「全部」二级分类（用户要求）。
 *
 * 官方 App 的「最新A漫 / Cosplay / 3D / 禁漫漢化組」没有二级分类，抽屉第二层会是空的；
 * 用户要求这时用「全部」虚拟一个，并且在选中该主分类时**默认选中「全部」**。
 *
 * 本工程对所有主分类都垫这一项（不只是没二级分类的那几个）：
 * 官方 App 里主分类那一栏本身就有"整个主分类"的含义，抽屉只有二级分类一层可选，
 * 垫上「全部」才能表达"看这个主分类的全部内容"。
 */
val CATEGORY_SUB_ALL: CategorySub = CategorySub(name = "全部", slug = "")

/** 「最新A漫」的展示名：接口没返回它时用来兜底（它是默认选中项，必须在位）。 */
const val CATEGORY_LATEST_NAME: String = "最新A漫"

/**
 * 把接口的分类树映射成界面能直接用的结构。
 *
 * 规则（都能单测）：
 * 1. 丢掉名字为空、以及 `slug` 重复的条目（重复会让抽屉里出现两行同样的 key）；
 * 2. 每个主分类的 `subs` **第一项固定是「全部」**，后面接接口给的二级分类（去重、
 *    丢掉空名字/空 slug 的）；
 * 3. 「最新A漫」（`slug == ""`）**永远排第一**：它是抽屉的默认选中项。
 *    接口没返回它时补一个；返回了就用接口给的名字。
 * 4. 接口返回空列表时也至少给出「最新A漫」一项——分类页照样能看默认列表。
 * 5. `total_albums` 解析成数字（数字/字符串两种形态都吃），负数按 0 处理。
 */
fun buildCategoryCatalog(groups: List<CategoryGroupDto>): List<CategoryGroup> {
    val seenSlugs = HashSet<String>(groups.size + 1)
    val result = ArrayList<CategoryGroup>(groups.size + 1)

    for (dto in groups) {
        val name = dto.name?.trim().orEmpty()
        val slug = dto.slug?.trim().orEmpty()
        if (name.isEmpty() || !seenSlugs.add(slug)) continue

        val subs = arrayListOf(CATEGORY_SUB_ALL)
        for (sub in dto.subCategories.orEmpty()) {
            val subName = sub.name?.trim().orEmpty()
            val subSlug = sub.slug?.trim().orEmpty()
            if (subName.isEmpty() || subSlug.isEmpty()) continue
            if (subs.any { it.slug == subSlug }) continue
            subs += CategorySub(subName, subSlug)
        }
        result += CategoryGroup(
            name = name,
            slug = slug,
            subs = subs,
            totalAlbums = dto.totalAlbums?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        )
    }

    // 「最新A漫」置顶：接口里它本来就是第一项，但这里不依赖返回顺序
    val latestIndex = result.indexOfFirst { it.slug.isEmpty() }
    val latest = if (latestIndex >= 0) {
        result.removeAt(latestIndex)
    } else {
        CategoryGroup(name = CATEGORY_LATEST_NAME, slug = "", subs = listOf(CATEGORY_SUB_ALL))
    }
    result.add(0, latest)
    return result
}

/**
 * **紧凑数字文案**：上千用 `k`、上万用 `w`，都保留一位小数。
 *
 * 用户要求（2026-10-19）先用在分类抽屉右侧的总数上，2026-10-20 起
 * **详情页的点赞数 / 观看数也用同一套**（用户要求"跟分类的数量格式化方式保持一致"）——
 * 所以名字改成中性的 [formatCompactCount]，两个调用点共用这一个实现。
 *
 * | 输入 | 输出 |
 * |---|---|
 * | `0` / `999` | `0` / `999`（原样） |
 * | `1000` | `1.0k` |
 * | `3547` | `3.5k` |
 * | `10_000` | `1.0w` |
 * | `354_792` | `35.5w` |
 *
 * 四舍五入交给 `String.format`（`Locale.ROOT`，避免某些系统语言把小数点变成逗号）。
 */
fun formatCompactCount(total: Long): String = when {
    total < 1_000L -> total.toString()
    total < 10_000L -> String.format(Locale.ROOT, "%.1fk", total / 1_000.0)
    else -> String.format(Locale.ROOT, "%.1fw", total / 10_000.0)
}

/**
 * 列表条目的"更新"文案：**优先用更新时间，没有才用上架日期**（用户要求）。
 *
 * @param updateAt 接口的 `update_at`（10 位秒级时间戳）。0 / null 视为没有。
 * @param addDate 接口的 `adddate`（`2026-10-19` 这种字符串）。
 * @return `2026-10-19` 这样的日期文案；两者都没有时返回 null（界面不展示）。
 */
fun updateLabel(updateAt: Long?, addDate: String?): String? {
    val seconds = updateAt ?: 0L
    if (seconds > 0L) {
        val date = runCatching {
            Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
        if (date != null) return date.toString()
    }
    return addDate
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "0000-00-00" && !it.equals("null", ignoreCase = true) }
}

/**
 * **详情页头部的"上架时间"文案**（与 JM 号同一行显示，用户 2026-10-20 要求）。
 *
 * `/album` 只返回 `addtime`（**上架 / 创建时间**，10 位秒级时间戳），响应里没有单独的
 * "更新时间"字段——所以详情页展示的就是这个上架时间，标签写「上架」而不是「更新」。
 *
 * 取值规则复用 [updateLabel]：
 * - 纯数字（秒级时间戳）→ 本地时区的 `YYYY-MM-DD`；
 * - 非数字（万一接口某天换成 `2022-06-15` 这种字符串）→ 原样返回；
 * - 空 / `"0"` / `"null"` → null（这一行不拼这段）；
 * - 拿不到可识别的日期 → null。
 */
fun albumAddTimeLabel(addTime: String?): String? {
    val raw = addTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = raw.toLongOrNull()
    // 数字但不是有效时间戳（0 / 负数）：当"没有时间"，不要显示"上架 0"
    if (seconds != null) return if (seconds > 0L) updateLabel(seconds, null) else null
    return updateLabel(null, raw)
}

/**
 * 拼 `/categories/filter` 的 `c` 参数（文档 §0②）：
 *
 * ```text
 * c = slug + (sub_slug ? "_" + sub_slug : "")
 * ```
 *
 * 例：`("doujin", "chinese") → "doujin_chinese"`、`("doujin", "") → "doujin"`、
 * `("", "") → ""`（「最新A漫」不传 `c`）。
 */
fun categoryParam(mainSlug: String, subSlug: String): String {
    val main = mainSlug.trim()
    val sub = subSlug.trim()
    return when {
        sub.isEmpty() -> main
        main.isEmpty() -> sub
        else -> "${main}_$sub"
    }
}

/** 抽屉按钮上的文案：`☰ 同人 · 漢化`（图标由界面画）。 */
fun categorySelectorLabel(main: CategoryGroup?, sub: CategorySub?): String =
    "${main?.name ?: CATEGORY_LATEST_NAME} · ${sub?.name ?: CATEGORY_SUB_ALL.name}"

/**
 * 分类页当前选中的三项（主分类 / 二级分类 / 排序）。
 *
 * 也是缓存的 key 来源：换主分类、换二级、换排序都是"另一份列表"。
 */
data class CategorySelection(
    val mainSlug: String = "",
    val subSlug: String = "",
    val sort: CategorySort = CategorySort.DEFAULT,
) {
    /** 缓存 key：三项都用分隔符隔开，避免 `a|b` 与 `a_b` 之类的拼接歧义。 */
    val cacheKey: String get() = "$mainSlug\u0001$subSlug\u0001${sort.code}"

    companion object {
        /** 默认选中「最新A漫 · 全部 · 最新」——用户要求抽屉默认停在最新A漫。 */
        val DEFAULT: CategorySelection = CategorySelection()
    }
}

/**
 * 把选择修正到"当前分类树里真实存在"的组合。
 *
 * 场景：分类树刚加载完（默认 `mainSlug = ""`）、或排序项在该主分类下不可用
 * （官方只有「最新A漫」给週排行）。修正规则：
 * - 主分类不存在 → 用第一个（也就是「最新A漫」）；
 * - 二级分类不存在 → 用该主分类的第一项（「全部」）；
 * - 排序不可用 → 回到「最新」。
 */
fun fixCategorySelection(
    selection: CategorySelection,
    catalog: List<CategoryGroup>,
): CategorySelection {
    if (catalog.isEmpty()) return selection
    val main = catalog.firstOrNull { it.slug == selection.mainSlug } ?: catalog.first()
    val sub = main.subOf(selection.subSlug) ?: main.subs.first()
    val sort = if (selection.sort in CategorySort.optionsFor(main.slug)) {
        selection.sort
    } else {
        CategorySort.DEFAULT
    }
    return CategorySelection(mainSlug = main.slug, subSlug = sub.slug, sort = sort)
}

/**
 * 一页分类列表（已经归一化成界面能直接画的样子）。
 *
 * @property page 页码，**从 1 开始**。
 * @property total 服务端总数（上限 10000）。
 * @property totalPages 由 [total] 与每页条数算出来的页数，至少 1。
 */
data class CategoryAlbumPage(
    val page: Int,
    val total: Long,
    val totalPages: Int,
    val items: List<OnlineSearchItem>,
)

/** 缓存里的一份列表快照（只存内存）。 */
data class CategoryListSnapshot(
    val items: List<OnlineSearchItem>,
    val total: Long,
    val page: Int,
    val totalPages: Int,
)

/**
 * 在线列表的展示方式：**一行一本**（列表）或 **三列封面网格**（卡片）。
 *
 * **分类页与搜索结果页共用**（用户 2026-10-20 要求搜索结果也要能切），
 * 两页都**默认卡片**，切换按钮都放在「共 N 条」那一行的右端。
 *
 * 放在数据层是因为分类页的这个选择要跟着 [CategoryCache] 跨页面存活；
 * 搜索结果页不用缓存，直接放在它自己的 `OnlineSearchUiState` 里。
 */
enum class OnlineViewMode {
    /** 一行一本：封面 + 标题 + JM 号 + 更新时间 + 作者。 */
    LIST,

    /** 三列网格：封面 + 标题 + 更新时间（"区域优先"少露信息，与分类页一致）。 */
    CARD,
    ;

    fun toggled(): OnlineViewMode = if (this == LIST) CARD else LIST

    companion object {
        /** 首次进入用卡片模式（用户要求）。 */
        val DEFAULT = CARD
    }
}

/** 列表条目的"更新"文案（[updateLabel] 对 [OnlineSearchItem] 的重载）。 */
fun OnlineSearchItem.updateLabel(): String? = updateLabel(updateAt, addDate)

/**
 * 分类页的内存缓存。
 *
 * 用户要求：
 * - **分类树只在打开 App 后第一次进分类页时请求一次**，之后一直用缓存；
 * - 漫画列表也要缓存，**切到别的页面再切回来还得是上次加载的那批**；
 * - **退出 App 清空**（缓存在进程内存里，`AppContainer.handleAppExit` 里再显式清一次，
 *   因为返回键退出时进程可能还被下载任务续着）。
 *
 * 挂在 `AppContainer`（`jmCategoryCache`）上而不是 ViewModel 上：
 * ViewModel 跟着导航 entry 走，页面重建 / 栈被回收时会换一个实例，
 * 挂在容器上才能跨"进出页面"存活（与 `CommentCache` 同一套理由）。
 *
 * 线程：只在主线程（ViewModel 的协程）里读写，不加锁。
 */
class CategoryCache {

    /** `/categories` 的分类树；`null` 表示"本次进程还没加载过"。 */
    var catalog: List<CategoryGroup>? = null
        private set

    /** 上一次通过界面确认的选择，用于"切回分类页时停在原处"。 */
    var selection: CategorySelection = CategorySelection.DEFAULT
        private set

    /** 上次用的展示方式（列表 / 卡片）：切页面回来不用再切一遍。 */
    var viewMode: OnlineViewMode = OnlineViewMode.DEFAULT
        private set

    private val lists = LinkedHashMap<String, CategoryListSnapshot>()

    fun saveCatalog(catalog: List<CategoryGroup>) {
        this.catalog = catalog
    }

    fun saveSelection(selection: CategorySelection) {
        this.selection = selection
    }

    fun saveViewMode(mode: OnlineViewMode) {
        this.viewMode = mode
    }

    fun snapshot(selection: CategorySelection): CategoryListSnapshot? = lists[selection.cacheKey]

    /**
     * 写入一份列表快照。
     *
     * 超过 [MAX_LISTS] 份时丢掉**最早写入**的那份（近似 LRU，够用）：
     * 9 个主分类 × 最多 5 种排序，全点一遍也就 45 份，这个上限保证内存不会被吃穿。
     */
    fun putSnapshot(selection: CategorySelection, snapshot: CategoryListSnapshot) {
        val key = selection.cacheKey
        // 先删再放：让刚用过的 key 排到队尾，被淘汰的是真正最久没碰过的
        lists.remove(key)
        lists[key] = snapshot
        while (lists.size > MAX_LISTS) {
            val eldest = lists.keys.firstOrNull() ?: break
            lists.remove(eldest)
        }
    }

    fun clear() {
        catalog = null
        selection = CategorySelection.DEFAULT
        viewMode = OnlineViewMode.DEFAULT
        lists.clear()
    }

    /**
     * 只清"跟接口语言有关"的那两部分：分类树与已加载的列表。
     *
     * 用于**切换接口语言**：分类名、标签都是服务端按 `lang` 渲染的，切完就过期了，
     * 必须重拉；而 [viewMode]（列表 / 卡片）与 [selection]（slug）跟语言无关，
     * 没必要跟着重置——用户切个语言回来发现展示方式被重置了才是真的怪。
     */
    fun clearCatalogAndLists() {
        catalog = null
        lists.clear()
    }

    companion object {
        /** 最多缓存多少份"分类 + 排序"的列表。 */
        const val MAX_LISTS = 24
    }
}

/**
 * 把新一页分类列表并到已有列表后面，**按本子号去重**。
 *
 * 与 `mergeComments` 同一个理由：翻页期间有新本子上架时，后面的条目会整体后移一位，
 * 上一页的最后几条会在下一页再出现一次；而列表拿 `id` 当 key，
 * 重复 key 会让 `LazyColumn` 直接抛 `IllegalArgumentException`（整页白屏）。
 * 去重时保留**先出现**的那一条（顺序即接口顺序）。
 */
fun mergeCategoryItems(
    existing: List<OnlineSearchItem>,
    incoming: List<OnlineSearchItem>,
): List<OnlineSearchItem> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<Long>(existing.size + incoming.size)
    existing.forEach { seen.add(it.id) }
    return existing + incoming.filter { seen.add(it.id) }
}
