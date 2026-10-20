package com.haruhi.jmcomic.feature.data

import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.feature.api.ApiJson
import com.haruhi.jmcomic.feature.api.CategoriesRespDto
import com.haruhi.jmcomic.feature.api.CategoryFilterRespDto
import com.haruhi.jmcomic.feature.api.CategoryGroupDto
import com.haruhi.jmcomic.feature.api.CategorySubDto
import com.haruhi.jmcomic.feature.api.SearchItemDto
import org.junit.Test

/**
 * 分类页的纯逻辑：分类树映射、`c` 参数拼接、选择修正、列表合并与内存缓存。
 *
 * 这些规则直接决定用户看到什么（抽屉里有哪些行、默认选中谁、翻页会不会崩），
 * 而且都不依赖网络 / Android，所以放在单元测试里锁死。
 */
class CategoryCatalogTest {

    // ------------------------------------------------------------------
    // 分类树映射
    // ------------------------------------------------------------------

    private fun group(
        name: String,
        slug: String,
        subs: List<Pair<String, String>> = emptyList(),
        total: String? = null,
    ) = CategoryGroupDto(
        name = name,
        slug = slug,
        totalAlbums = total,
        subCategories = subs.map { (subName, subSlug) ->
            CategorySubDto(name = subName, slug = subSlug)
        },
    )

    @Test
    fun `latest group is always first and is synthesized when the api omits it`() {
        val catalog = buildCategoryCatalog(
            listOf(
                group("同人", "doujin", listOf("漢化" to "chinese")),
                group("單本", "single"),
            ),
        )

        assertThat(catalog.map { it.name }).containsExactly("最新A漫", "同人", "單本").inOrder()
        assertThat(catalog.first().slug).isEmpty()
    }

    @Test
    fun `latest group keeps the api name when it is present`() {
        val catalog = buildCategoryCatalog(
            listOf(
                group("同人", "doujin"),
                group("最新A漫 (API)", ""),
            ),
        )

        assertThat(catalog.first().name).isEqualTo("最新A漫 (API)")
        assertThat(catalog.first().slug).isEmpty()
    }

    @Test
    fun `every main group starts with the virtual all sub`() {
        val catalog = buildCategoryCatalog(
            listOf(
                group("同人", "doujin", listOf("漢化" to "chinese", "日語" to "japanese")),
                group("Cosplay", "another_cosplay"),
            ),
        )
        val doujin = catalog.first { it.slug == "doujin" }
        val cosplay = catalog.first { it.slug == "another_cosplay" }

        assertThat(doujin.subs.map { it.name }).containsExactly("全部", "漢化", "日語").inOrder()
        assertThat(doujin.subs.first().slug).isEmpty()
        // 没有二级分类的主分类：用「全部」虚拟一个（用户要求）
        assertThat(cosplay.subs).containsExactly(CATEGORY_SUB_ALL)
        assertThat(cosplay.hasRealSubs).isFalse()
        assertThat(doujin.hasRealSubs).isTrue()
    }

    @Test
    fun `blank or duplicated entries are dropped`() {
        val catalog = buildCategoryCatalog(
            listOf(
                group("  ", "blank", listOf("空的" to "")),
                group("同人", "doujin", listOf("" to "x", "漢化" to "chinese", "漢化副本" to "chinese")),
                group("同人（重复 slug）", "doujin"),
            ),
        )

        assertThat(catalog.map { it.slug }).containsExactly("", "doujin").inOrder()
        assertThat(catalog.first { it.slug == "doujin" }.subs.map { it.name })
            .containsExactly("全部", "漢化").inOrder()
    }

    @Test
    fun `an empty response still yields the latest group`() {
        assertThat(buildCategoryCatalog(emptyList()))
            .containsExactly(
                CategoryGroup(CATEGORY_LATEST_NAME, "", listOf(CATEGORY_SUB_ALL)),
            )
    }

    @Test
    fun `main group total is parsed from number or string`() {
        val catalog = buildCategoryCatalog(
            listOf(
                group("最新A漫", "", total = "0"),
                group("同人", "doujin", total = "354792"),
                group("單本", "single"),
            ),
        )

        assertThat(catalog.first { it.slug == "doujin" }.totalAlbums).isEqualTo(354_792L)
        // 「最新A漫」的 0 当"没有总数"：界面上不显示数字
        assertThat(catalog.first { it.slug.isEmpty() }.countLabel).isNull()
        // 接口没给 total_albums 也是一样
        assertThat(catalog.first { it.slug == "single" }.countLabel).isNull()
        assertThat(catalog.first { it.slug == "doujin" }.countLabel).isEqualTo("35.5w")
    }

    // ------------------------------------------------------------------
    // 数量缩写（用户要求：上千 1.0k、上万 1.0w）
    // ------------------------------------------------------------------

    @Test
    fun `count is abbreviated with k below ten thousand and with w above`() {
        assertThat(formatCompactCount(0)).isEqualTo("0")
        assertThat(formatCompactCount(999)).isEqualTo("999")
        assertThat(formatCompactCount(1_000)).isEqualTo("1.0k")
        assertThat(formatCompactCount(1_499)).isEqualTo("1.5k")
        assertThat(formatCompactCount(3_547)).isEqualTo("3.5k")
        assertThat(formatCompactCount(9_999)).isEqualTo("10.0k")
        assertThat(formatCompactCount(10_000)).isEqualTo("1.0w")
        assertThat(formatCompactCount(43_357)).isEqualTo("4.3w")
        assertThat(formatCompactCount(354_792)).isEqualTo("35.5w")
        assertThat(formatCompactCount(397_296)).isEqualTo("39.7w")
    }

    // ------------------------------------------------------------------
    // 更新时间（用户要求：有 update_at 用它，没有就用 adddate）
    // ------------------------------------------------------------------

    @Test
    fun `update label prefers the timestamp and falls back to the add date`() {
        // 1792418849 = 2026-10-19（UTC+8）
        assertThat(updateLabel(1792418849L, "2020-01-01")).isEqualTo("2026-10-19")
        // update_at 缺失 / 为 0 / 负数 → 用 adddate
        assertThat(updateLabel(null, "2026-10-18")).isEqualTo("2026-10-18")
        assertThat(updateLabel(0L, "2026-10-18")).isEqualTo("2026-10-18")
        assertThat(updateLabel(-5L, "2026-10-18")).isEqualTo("2026-10-18")
        // 两者都没有 / 脏值 → 不展示
        assertThat(updateLabel(null, null)).isNull()
        assertThat(updateLabel(null, "  ")).isNull()
        assertThat(updateLabel(null, "0000-00-00")).isNull()
        assertThat(updateLabel(null, "null")).isNull()
    }

    @Test
    fun `view mode defaults to card and toggles between both`() {
        // 用户 2026-10-20 要求：分类页默认卡片展示
        assertThat(OnlineViewMode.DEFAULT).isEqualTo(OnlineViewMode.CARD)
        assertThat(OnlineViewMode.LIST.toggled()).isEqualTo(OnlineViewMode.CARD)
        assertThat(OnlineViewMode.CARD.toggled()).isEqualTo(OnlineViewMode.LIST)
    }

    // ------------------------------------------------------------------
    // 接口响应的反序列化（字段类型按 2026-10-19 实测的响应写）
    // ------------------------------------------------------------------

    /**
     * `/categories` 实测样本（截取）：`id` 与 `total_albums` **数字与字符串混排**，
     * `id` 不声明，`total_albums` 用宽松标量读。
     */
    @Test
    fun `categories response with mixed scalar types decodes`() {
        val json = """
            {"categories":[
              {"id":0,"name":"最新A漫","slug":"","total_albums":0},
              {"id":"1","name":"同人","slug":"doujin","total_albums":"354792",
               "sub_categories":[{"CID":"1","name":"漢化","slug":"chinese"},{"CID":"2","name":"日語","slug":"japanese"}]}
            ],"blocks":[{"title":"主題A漫","content":["透視"]}]}
        """.trimIndent()

        val dto = ApiJson.decodeFromString(CategoriesRespDto.serializer(), json)
        val catalog = buildCategoryCatalog(dto.categories.orEmpty())

        assertThat(catalog.map { it.name }).containsExactly("最新A漫", "同人").inOrder()
        assertThat(catalog.last().subs.map { it.slug })
            .containsExactly("", "chinese", "japanese").inOrder()
        // 数字 0 与字符串 "354792" 都要读得出来
        assertThat(catalog.first().totalAlbums).isEqualTo(0L)
        assertThat(catalog.last().totalAlbums).isEqualTo(354_792L)
    }

    /**
     * `/categories/filter` 实测样本（截取）：`total` 是**数字** `10000`（不是字符串），
     * 列表项与 `/search` 同构。宽松标量解析器要能把它读成 `"10000"`。
     */
    @Test
    fun `filter response with numeric total decodes`() {
        val json = """
            {"search_query":"","total":10000,"tags":["透視"],"content":[
              {"id":"1484011","name":"某本","author":"","image":"",
               "category":{"id":"1","title":"同人"},"category_sub":{"id":"3","title":"短篇"},
               "liked":false,"is_favorite":false,"update_at":1792100000,"adddate":"2026-10-16"}
            ]}
        """.trimIndent()

        val dto = ApiJson.decodeFromString(CategoryFilterRespDto.serializer(), json)

        // 数字也能当字符串读：否则整条响应都会解析失败
        assertThat(dto.total).isEqualTo("10000")
        assertThat(dto.content).hasSize(1)
        assertThat(dto.content!!.first().id).isEqualTo("1484011")
        assertThat(dto.content!!.first().categorySub?.title).isEqualTo("短篇")
    }

    // ------------------------------------------------------------------
    // c 参数 / 选择器文案
    // ------------------------------------------------------------------

    @Test
    fun `category param follows the official slug rule`() {
        assertThat(categoryParam("doujin", "chinese")).isEqualTo("doujin_chinese")
        assertThat(categoryParam("doujin", "")).isEqualTo("doujin")
        // 最新A漫：c 整个不传
        assertThat(categoryParam("", "")).isEmpty()
        // 带空格的脏数据也不该拼出 `_` 开头的串
        assertThat(categoryParam(" 禁漫漢化組 ", " ")).isEqualTo("禁漫漢化組")
    }

    @Test
    fun `selector label reads main dot sub`() {
        val catalog = buildCategoryCatalog(listOf(group("同人", "doujin", listOf("漢化" to "chinese"))))
        val doujin = catalog.first { it.slug == "doujin" }

        assertThat(categorySelectorLabel(doujin, doujin.subOf("chinese"))).isEqualTo("同人 · 漢化")
        assertThat(categorySelectorLabel(doujin, doujin.subs.first())).isEqualTo("同人 · 全部")
        // 分类树还没加载出来时的占位文案
        assertThat(categorySelectorLabel(null, null)).isEqualTo("最新A漫 · 全部")
    }

    // ------------------------------------------------------------------
    // 排序
    // ------------------------------------------------------------------

    @Test
    fun `weekly rank is only offered for the latest group`() {
        assertThat(CategorySort.optionsFor("")).containsExactly(
            CategorySort.LATEST,
            CategorySort.LIKE,
            CategorySort.RANK_TOTAL,
            CategorySort.RANK_MONTH,
            CategorySort.RANK_WEEK,
        ).inOrder()
        assertThat(CategorySort.optionsFor("doujin")).doesNotContain(CategorySort.RANK_WEEK)
    }

    @Test
    fun `sort codes match the official api`() {
        assertThat(CategorySort.entries.map { it.code })
            .containsExactly("", "tf", "mv", "mv_m", "mp_w").inOrder()
        assertThat(CategorySort.fromCode(null)).isEqualTo(CategorySort.LATEST)
        assertThat(CategorySort.fromCode("mv_m")).isEqualTo(CategorySort.RANK_MONTH)
    }

    // ------------------------------------------------------------------
    // 选择修正
    // ------------------------------------------------------------------

    @Test
    fun `selection falls back to the latest group and its all sub`() {
        val catalog = buildCategoryCatalog(listOf(group("同人", "doujin", listOf("漢化" to "chinese"))))
        val fixed = fixCategorySelection(CategorySelection("不存在", "也不存在"), catalog)

        assertThat(fixed).isEqualTo(CategorySelection("", "", CategorySort.LATEST))
    }

    @Test
    fun `selection keeps a valid choice and drops an unsupported sort`() {
        val catalog = buildCategoryCatalog(listOf(group("同人", "doujin", listOf("漢化" to "chinese"))))

        val kept = fixCategorySelection(
            CategorySelection(mainSlug = "doujin", subSlug = "chinese", sort = CategorySort.RANK_MONTH),
            catalog,
        )
        assertThat(kept).isEqualTo(
            CategorySelection(mainSlug = "doujin", subSlug = "chinese", sort = CategorySort.RANK_MONTH),
        )

        // 週排行只在「最新A漫」下可用：换到「同人」要退回「最新」，否则界面选中的芯片
        // 和列表内容会对不上
        val dropped = fixCategorySelection(
            CategorySelection(mainSlug = "doujin", subSlug = "chinese", sort = CategorySort.RANK_WEEK),
            catalog,
        )
        assertThat(dropped.sort).isEqualTo(CategorySort.LATEST)
    }

    @Test
    fun `missing sub falls back to all`() {
        val catalog = buildCategoryCatalog(listOf(group("同人", "doujin", listOf("漢化" to "chinese"))))
        val fixed = fixCategorySelection(CategorySelection(mainSlug = "doujin", subSlug = "gone"), catalog)

        assertThat(fixed.subSlug).isEmpty()
    }

    // ------------------------------------------------------------------
    // 列表合并
    // ------------------------------------------------------------------

    private fun item(id: Long) = OnlineSearchItem(
        id = id,
        name = "本子 $id",
        author = null,
        description = null,
        coverUrl = "",
        tags = emptyList(),
        updateAt = null,
        existsLocal = false,
        raw = SearchItemDto(id = id.toString()),
    )

    @Test
    fun `next page is appended and duplicates are dropped`() {
        val merged = mergeCategoryItems(
            existing = listOf(item(1), item(2), item(3)),
            incoming = listOf(item(3), item(4)),
        )

        // 翻页期间有新本子上架会让后面的条目整体后移一位：重复 key 会让 LazyColumn 崩
        assertThat(merged.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `an empty page keeps the existing list untouched`() {
        val existing = listOf(item(1))

        assertThat(mergeCategoryItems(existing, emptyList())).isSameInstanceAs(existing)
    }

    // ------------------------------------------------------------------
    // 内存缓存
    // ------------------------------------------------------------------

    private fun snapshot(page: Int, totalPages: Int = 3) = CategoryListSnapshot(
        items = listOf(item(page.toLong() * 10)),
        total = 160L,
        page = page,
        totalPages = totalPages,
    )

    @Test
    fun `cache keeps the catalog and the last selection`() {
        val cache = CategoryCache()
        val catalog = buildCategoryCatalog(listOf(group("同人", "doujin")))
        val selection = CategorySelection("doujin", "", CategorySort.RANK_TOTAL)

        assertThat(cache.catalog).isNull()
        cache.saveCatalog(catalog)
        cache.saveSelection(selection)

        assertThat(cache.catalog).isEqualTo(catalog)
        assertThat(cache.selection).isEqualTo(selection)
    }

    @Test
    fun `cache separates lists by category and sort`() {
        val cache = CategoryCache()
        val latest = CategorySelection("", "", CategorySort.LATEST)
        val doujin = CategorySelection("doujin", "", CategorySort.LATEST)
        val doujinRanked = CategorySelection("doujin", "", CategorySort.RANK_TOTAL)

        cache.putSnapshot(latest, snapshot(page = 1))
        cache.putSnapshot(doujin, snapshot(page = 2))

        assertThat(cache.snapshot(latest)?.page).isEqualTo(1)
        assertThat(cache.snapshot(doujin)?.page).isEqualTo(2)
        // 换了排序就是另一份列表，不能命中旧的
        assertThat(cache.snapshot(doujinRanked)).isNull()
    }

    @Test
    fun `cache bounds the number of lists and drops the least recently written`() {
        val cache = CategoryCache()
        val selections = (0 until CategoryCache.MAX_LISTS + 1).map { index ->
            CategorySelection(mainSlug = "group$index", subSlug = "", sort = CategorySort.LATEST)
        }

        selections.forEach { cache.putSnapshot(it, snapshot(page = 1)) }

        assertThat(cache.snapshot(selections.first())).isNull()
        assertThat(cache.snapshot(selections.last())).isNotNull()

        // 再写一次老 key：它会被挪到队尾，下一份被淘汰的是别人
        cache.putSnapshot(selections[1], snapshot(page = 2))
        cache.putSnapshot(
            CategorySelection(mainSlug = "extra", subSlug = "", sort = CategorySort.LATEST),
            snapshot(page = 1),
        )
        assertThat(cache.snapshot(selections[1])?.page).isEqualTo(2)
        assertThat(cache.snapshot(selections[2])).isNull()
    }

    @Test
    fun `clear empties everything`() {
        val cache = CategoryCache()
        val selection = CategorySelection("doujin", "", CategorySort.LIKE)
        cache.saveCatalog(buildCategoryCatalog(listOf(group("同人", "doujin"))))
        cache.saveSelection(selection)
        cache.saveViewMode(OnlineViewMode.CARD)
        cache.putSnapshot(selection, snapshot(page = 1))

        cache.clear()

        assertThat(cache.catalog).isNull()
        assertThat(cache.selection).isEqualTo(CategorySelection.DEFAULT)
        assertThat(cache.viewMode).isEqualTo(OnlineViewMode.DEFAULT)
        assertThat(cache.snapshot(selection)).isNull()
    }

    @Test
    fun `cache remembers the chosen view mode`() {
        val cache = CategoryCache()
        // 默认卡片（用户 2026-10-20 要求）
        assertThat(cache.viewMode).isEqualTo(OnlineViewMode.CARD)

        cache.saveViewMode(OnlineViewMode.LIST)

        assertThat(cache.viewMode).isEqualTo(OnlineViewMode.LIST)
    }
}
