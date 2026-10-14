package com.haruhi.jmcomic.feature.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.RecentAlbumEntity
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.AlbumDto
import com.haruhi.jmcomic.feature.api.ApiClient
import com.haruhi.jmcomic.feature.api.SeriesDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 最近预览 + "点详情不入库" + 收藏夹数量刷新的验收测试。
 *
 * 对应用户提出的改动：
 * - 第 1 点：收藏之后收藏页数量要更新（[favorite count flow emits after collecting]）；
 * - 第 2 点：点详情不等于加入书库（[touching recent does not add to the library]）；
 * - 第 3、4 点：最近预览按浏览时间倒序、刷新不改浏览时间。
 *
 * 本次修复的核心：**请求 `/album` ≠ 加入最近预览**。
 * 详情数据落在永久缓存（基础数据表 `t_jm_album`）里，最近预览（`t_jm_recent_album`）
 * 只在进入详情页/阅读页时写入，两张表通过 id 拼接。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentAndFavoriteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: JmComicDatabase
    private lateinit var dirs: LibraryDirs
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JmComicDatabase::class.java,
        ).allowMainThreadQueries().build()
        dirs = LibraryDirs(temp.newFolder("library")).ensureAll()
        repo = Repository(
            api = ApiClient(client = OkHttpClient(), config = { error("本测试不发起网络请求") }),
            albumDao = db.albumDao(),
            chapterDao = db.chapterDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentAlbumDao(),
            searchHistoryDao = db.searchHistoryDao(),
            dirs = dirs,
        )
    }

    @After
    fun tearDown() = db.close()

    // ---------------- 第 2 点：点详情不入库 ----------------

    @Test
    fun `touching recent does not add to the library`() = runTest {
        repo.touchRecent(480854L)

        // 书库成员与基础数据都必须还是空的
        assertThat(repo.findAlbum(480854L)).isNull()
        assertThat(db.albumDao().all()).isEmpty()
        // 但最近预览里已经有了
        assertThat(repo.findRecent(480854L)).isNotNull()
    }

    /**
     * 本次修复的核心用例：**缓存详情数据 ≠ 加入最近预览**。
     *
     * 搜索精确匹配、详情页内部加载都可能把 `/album` 结果写进基础数据表（永久缓存），
     * 但这不该让这本出现在"最近"列表——只有真正进过详情页/阅读页才算看过。
     */
    @Test
    fun `caching the detail does not add to the recent list`() = runTest {
        // 模拟搜索等路径把在线详情落进基础数据表（直接写 dao）
        db.albumDao().insertIgnore(albumEntity(480854, "只看不入库"))

        assertThat(repo.findRecent(480854L)).isNull()
        assertThat(repo.observeRecentAlbums().first()).isEmpty()
        // 缓存存在：点进详情页可以离线秒开；但没有入库
        assertThat(repo.localAlbumDetail(480854L)!!.fromLibrary).isFalse()
        assertThat(repo.findAlbum(480854L)).isNull()
    }

    @Test
    fun `addToLibrary writes the library but not the recent list`() = runTest {
        // addToLibrary 会先请求网络，这里直接验证 saveAlbum 的语义：只入库，不写最近
        repo.saveAlbum(albumFixture(480854, "本子"))

        assertThat(repo.findAlbum(480854L)).isNotNull()
        assertThat(repo.findRecent(480854L)).isNull()
    }

    // ---------------- 第 3、4 点：最近预览 ----------------

    /** 最近列表的内容直接来自基础数据表（不复制、不依赖"回填"）。 */
    @Test
    fun `recent list renders the cached base data`() = runTest {
        repo.saveAlbum(albumFixture(480854, "在书库的本子"))
        val library = repo.findAlbum(480854L)!!

        repo.touchRecent(480854L)

        val recent = repo.observeRecentAlbums().first().single()
        assertThat(recent.name).isEqualTo(library.name)
        assertThat(recent.albumFolderName).isEqualTo(library.albumFolderName)
        assertThat(recent.series).isEqualTo(library.series)
    }

    @Test
    fun `recent list is ordered by viewed time descending`() = runTest {
        // 内容来自基础数据表；浏览时间来自最近表
        db.albumDao().insertIgnore(albumEntity(1L, "第一个"))
        db.albumDao().insertIgnore(albumEntity(2L, "第二个"))
        db.albumDao().insertIgnore(albumEntity(3L, "第三个"))
        db.recentAlbumDao().upsert(RecentAlbumEntity(1L, 1_000L))
        db.recentAlbumDao().upsert(RecentAlbumEntity(2L, 2_000L))
        db.recentAlbumDao().upsert(RecentAlbumEntity(3L, 3_000L))

        val recent = repo.observeRecentAlbums().first()
        assertThat(recent.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(recent.first().name).isEqualTo("第三个")
    }

    @Test
    fun `re-entering updates the viewed time and keeps a single row`() = runTest {
        repo.touchRecent(480854L)
        db.recentAlbumDao().touch(480854L, 1_000L)
        assertThat(repo.findRecent(480854L)!!.viewedTime).isEqualTo(1_000L)

        // 再次进入：只更新时间，不新增行
        repo.touchRecent(480854L)

        assertThat(repo.observeRecentAlbums().first()).hasSize(1)
        assertThat(repo.findRecent(480854L)!!.viewedTime).isGreaterThan(1_000L)
    }

    /**
     * "只进过页面、还没缓存到在线数据"（例如首次进入时离线）的条目仍要显示，
     * 界面回落成 "JM<id>"；稍后基础数据缓存到位后，同一行自动显示完整内容。
     */
    @Test
    fun `a recent row without cached data still shows and picks up content later`() = runTest {
        repo.touchRecent(480854L)

        val before = repo.observeRecentAlbums().first().single()
        assertThat(before.id).isEqualTo(480854L)
        assertThat(before.name).isNull()

        // 之后详情页联网成功：内容写进基础表（不是最近表）
        db.albumDao().insertIgnore(albumEntity(480854, "拉到数据之后"))

        val after = repo.observeRecentAlbums().first().single()
        assertThat(after.name).isEqualTo("拉到数据之后")
    }

    /**
     * 进入详情页记下的浏览时间，不会被随后"详情联网成功写内容"覆盖——
     * 现在两条写入路径彻底分离（最近表只写时间、基础表只写内容），不存在互相覆盖。
     */
    @Test
    fun `caching the detail later keeps the viewed time`() = runTest {
        repo.touchRecent(480854L)
        val touchedAt = repo.findRecent(480854L)!!.viewedTime

        // 模拟详情页联网成功：写基础数据
        db.albumDao().insertIgnore(albumEntity(480854, "联网结果"))

        assertThat(repo.findRecent(480854L)!!.viewedTime).isEqualTo(touchedAt)
        assertThat(repo.observeRecentAlbums().first().single().name).isEqualTo("联网结果")
    }

    @Test
    fun `refresh updates cached content but keeps the viewed time`() = runTest {
        db.albumDao().insertIgnore(albumEntity(480854, "旧标题"))
        repo.touchRecent(480854L)
        val viewedAt = repo.findRecent(480854L)!!.viewedTime

        // 模拟点刷新：拿到新的在线数据（整行刷新基础数据）
        db.albumDao().replaceAlbum(albumEntity(480854, "新标题"))

        assertThat(repo.observeRecentAlbums().first().single().name).isEqualTo("新标题")
        // **浏览时间不变**（用户要求第 4 点：浏览时间只在进详情页时更新）
        assertThat(repo.findRecent(480854L)!!.viewedTime).isEqualTo(viewedAt)
    }

    @Test
    fun `refreshing does not create a library row for an album that is not in the library`() = runTest {
        // 刷新只更新基础数据缓存，不产生书库成员
        db.albumDao().insertIgnore(albumEntity(480854, "只看不入库"))

        assertThat(repo.findAlbum(480854L)).isNull()
    }

    @Test
    fun `local detail prefers the library over the cache`() = runTest {
        repo.saveAlbum(albumFixture(480854, "书库版本"))

        val local = repo.localAlbumDetail(480854L)!!
        assertThat(local.fromLibrary).isTrue()
        assertThat(local.album.name).isEqualTo("书库版本")

        // 移出书库后：缓存还在（永久保留），但从"已入库"变成"只在缓存里"
        repo.deleteAlbums(listOf(480854L))
        val cached = repo.localAlbumDetail(480854L)!!
        assertThat(cached.fromLibrary).isFalse()
        assertThat(cached.album.name).isEqualTo("书库版本")
    }

    @Test
    fun `local detail falls back to the cache when not in the library`() = runTest {
        db.albumDao().insertIgnore(albumEntity(480854, "只在缓存里"))

        val local = repo.localAlbumDetail(480854L)!!
        assertThat(local.fromLibrary).isFalse()
        assertThat(local.album.name).isEqualTo("只在缓存里")
        // 未入库、未收藏：没有收藏语义
        assertThat(local.album.collected).isNull()
    }

    /**
     * 只有最近预览的"骨架记录"（没缓存到在线数据）不能当成"本地详情数据"——
     * 详情页必须去联网，否则会显示一个只有 JM 号、"共 0 话"的空壳且永远不联网。
     */
    @Test
    fun `a recent placeholder does not count as local detail data`() = runTest {
        repo.touchRecent(480854L)

        assertThat(repo.findRecent(480854L)).isNotNull()
        assertThat(repo.localAlbumDetail(480854L)).isNull()
        assertThat(repo.localAlbumDetail(999L)).isNull()
    }

    @Test
    fun `recent can be cleared without touching the library`() = runTest {
        repo.saveAlbum(albumFixture(480854, "在书库"))
        repo.touchRecent(480854L)

        val cleared = repo.clearRecent()

        assertThat(cleared).isEqualTo(1)
        assertThat(repo.findRecent(480854L)).isNull()
        assertThat(repo.findAlbum(480854L)).isNotNull()
    }

    // ---------------- 第 1 点：收藏夹数量实时更新 ----------------

    @Test
    fun `favorite count flow emits after collecting`() = runTest {
        repo.ensureDefaultFavorite()
        val defaultId = repo.listFavorites().first().id

        // 首次读取：0 本
        assertThat(repo.observeFavoritesWithCount().first().first().albumCount).isEqualTo(0)

        repo.saveAlbum(albumFixture(480854, "本子"))
        repo.addToFavorite(480854L, defaultId)

        // 收藏之后同一个 Flow 必须给出新的数量（之前用一次性查询，这里是 0 导致不刷新）
        assertThat(repo.observeFavoritesWithCount().first().first().albumCount).isEqualTo(1)
    }

    @Test
    fun `favorite count flow emits after removing and deleting`() = runTest {
        repo.saveAlbum(albumFixture(480854, "本子"))
        val defaultId = repo.ensureDefaultFavorite().id
        repo.createFavorite("第二夹")
        val secondId = repo.listFavorites().first { it.name == "第二夹" }.id

        repo.addToFavorite(480854L, defaultId)
        repo.addToFavorite(480854L, secondId)
        var counts = repo.observeFavoritesWithCount().first().associate { it.id to it.albumCount }
        assertThat(counts[defaultId]).isEqualTo(1)
        assertThat(counts[secondId]).isEqualTo(1)

        // 移出后数量下降
        repo.removeFromFavorite(480854L, secondId)
        counts = repo.observeFavoritesWithCount().first().associate { it.id to it.albumCount }
        assertThat(counts[secondId]).isEqualTo(0)
        assertThat(counts[defaultId]).isEqualTo(1)

        // 删除收藏夹后它本身消失，书库记录仍在
        repo.deleteFavorite(secondId)
        val remaining = repo.observeFavoritesWithCount().first()
        assertThat(remaining.map { it.id }).containsExactly(defaultId)
        assertThat(repo.findAlbum(480854L)).isNotNull()
    }

    // ---------------- 收藏夹封面与排序用的字段 ----------------

    /**
     * 收藏页要展示"夹内最近加入的那本"的封面，并按"最近加入时间"排序，
     * 所以汇总查询必须把这两个字段带出来（用户要求第 1 点）。
     */
    @Test
    fun `favorite summary exposes the latest album and its added time`() = runTest {
        repo.saveAlbum(albumFixture(111, "早加的"))
        repo.saveAlbum(albumFixture(222, "晚加的"))
        val folderId = repo.ensureDefaultFavorite().id

        repo.addToFavorite(111L, folderId)
        // 关联表的时间戳精确到秒，测试里连续写入会落在同一秒，所以显式隔开
        Thread.sleep(1100)
        repo.addToFavorite(222L, folderId)

        val summary = repo.observeFavoritesWithCount().first().first { it.id == folderId }
        assertThat(summary.albumCount).isEqualTo(2)
        assertThat(summary.latestAlbumId).isEqualTo(222L)
        assertThat(summary.lastAddedTime).isNotNull()
        assertThat(summary.createTime).isNotNull()
    }

    @Test
    fun `an empty favorite has no cover source`() = runTest {
        // 默认收藏夹由收藏页的 ViewModel 保证存在，测试里显式建一次
        val folderId = repo.ensureDefaultFavorite().id

        val summary = repo.observeFavoritesWithCount().first().first { it.id == folderId }
        assertThat(summary.albumCount).isEqualTo(0)
        assertThat(summary.latestAlbumId).isNull()
        assertThat(summary.lastAddedTime).isNull()
    }

    @Test
    fun `favorite summary marks the default folder`() = runTest {
        repo.ensureDefaultFavorite()
        repo.createFavorite("普通夹")

        val summaries = repo.observeFavoritesWithCount().first()
        assertThat(summaries.first { it.name == "默认收藏夹" }.isDefault).isTrue()
        assertThat(summaries.first { it.name == "普通夹" }.isDefault).isFalse()
    }

    // ------------------------------------------------------------------

    /** 基础数据表里的一行（模拟"已经缓存到在线详情"的状态）。 */
    private fun albumEntity(id: Long, name: String) = AlbumBaseEntity(id = id, name = name)

    private fun albumFixture(id: Long, name: String) = AlbumDto(
        id = id,
        name = name,
        description = "简介",
        series = listOf(SeriesDto(id = "${id}01", name = "第1话", sort = "1", title = "")),
        seriesId = "12345",
        author = listOf("作者A"),
        tags = listOf("标签一"),
    )
}