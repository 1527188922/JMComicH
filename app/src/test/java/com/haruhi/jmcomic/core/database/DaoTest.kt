package com.haruhi.jmcomic.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteAlbumEntity
import com.haruhi.jmcomic.core.database.entity.FavoriteEntity
import com.haruhi.jmcomic.core.database.entity.LibraryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JM 相关 Dao 的语义测试：分页、排序、唯一索引冲突、删除语义、标签筛选。
 *
 * 这些语义直接决定书库页/详情页/收藏页的数据正确性，属于 P1/P2 的验收项
 * （计划 §11.2「数据库：Room 迁移测试 + Dao 语义测试（分页/排序/唯一索引冲突）」）。
 *
 * 注意标签参数是 `tagsCsv`（逗号分隔）不是单个标签：多选标签是 **OR** 语义，
 * 对应参考实现 `applyTagFilter` 的动态 OR 拼接。
 *
 * v3 起"在书库"= 基础表有一行 + `t_jm_library` 有一行成员记录；
 * 书库列表/详情/计数全部按这两张表拼接，所以下面凡是测书库语义的用例
 * 都用 [seed library album][seedLibraryAlbum] 一次写两行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DaoTest {

    private lateinit var db: JmComicDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JmComicDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** 基础数据 + 书库成员各写一行："这本在书库里"的完整形态。 */
    private suspend fun seedLibraryAlbum(
        id: Long,
        name: String,
        createTime: String? = null,
        collected: Boolean? = null,
        author: String? = null,
        tags: String? = null,
        description: String? = null,
    ) {
        db.albumDao().insertIgnore(
            AlbumBaseEntity(
                id = id,
                name = name,
                author = author,
                tags = tags,
                description = description,
            ),
        )
        db.albumDao().insertLibraryRow(
            LibraryEntity(albumId = id, collected = collected, createTime = createTime),
        )
    }

    /**
     * 书库分页：**按入库时间倒序**（用户要求第 2 点）。
     *
     * `t_jm_library.create_time` 才是"入库时间"——刷新详情只会改基础数据，
     * 所以排序不看收藏标记，也不会被刷新动作影响。
     */
    @Test
    fun `library pagination returns stable slices ordered by added time`() = runTest {
        val dao = db.albumDao()
        // 5 本：id 1..5，入库时间递增（id 5 最新）
        (1L..5L).forEach { id ->
            seedLibraryAlbum(id = id, name = "本子$id", createTime = "2026-01-0$id", collected = id % 2 == 0L)
        }

        val firstPage = dao.observeLibrary(null, null, null, "", null, limit = 2, offset = 0).first()
        val secondPage = dao.observeLibrary(null, null, null, "", null, limit = 2, offset = 2).first()
        val thirdPage = dao.observeLibrary(null, null, null, "", null, limit = 2, offset = 4).first()

        assertThat(firstPage.map { it.id }).containsExactly(5L, 4L).inOrder()
        assertThat(secondPage.map { it.id }).containsExactly(3L, 2L).inOrder()
        assertThat(thirdPage.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `refreshing an album does not change its place in the library`() = runTest {
        val dao = db.albumDao()
        seedLibraryAlbum(id = 1, name = "旧的", createTime = "2026-01-01")
        seedLibraryAlbum(id = 2, name = "新的", createTime = "2026-01-02")

        // 刷新旧的那本：基础数据整行替换（create_time 保留），入库时间在成员表里不受影响
        dao.replaceAlbum(AlbumBaseEntity(id = 1, name = "旧的（刷新后）"))

        val page = dao.observeLibrary(null, null, null, "", null, limit = 10, offset = 0).first()
        assertThat(page.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(page.first { it.id == 1L }.name).isEqualTo("旧的（刷新后）")
    }

    @Test
    fun `library count matches the same filters`() = runTest {
        seedLibraryAlbum(id = 1, name = "海贼王", author = "尾田", collected = true)
        seedLibraryAlbum(id = 2, name = "火影", author = "岸本", collected = false)
        seedLibraryAlbum(id = 3, name = "海贼王外传", author = "尾田", collected = false)
        val dao = db.albumDao()

        assertThat(dao.observeLibraryCount(null, null, null, "", null).first()).isEqualTo(3)
        assertThat(dao.observeLibraryCount("海贼", null, null, "", null).first()).isEqualTo(2)
        assertThat(dao.observeLibraryCount(null, true, null, "", null).first()).isEqualTo(1)
        assertThat(dao.observeLibraryCount(null, null, "尾田", "", null).first()).isEqualTo(2)
    }

    @Test
    fun `keyword search also matches the jm id`() = runTest {
        val dao = db.albumDao()
        seedLibraryAlbum(id = 480854, name = "标题")
        seedLibraryAlbum(id = 123, name = "别的")

        val hit = dao.observeLibrary("480854", null, null, "", null, 10, 0).first()
        assertThat(hit.map { it.id }).containsExactly(480854L)
    }

    @Test
    fun `tag filter matches any of the selected tags`() = runTest {
        val dao = db.albumDao()
        // tags 列存的是 JSON 数组字符串（与参考实现一致）
        seedLibraryAlbum(id = 1, name = "A", tags = """["标签一","标签二"]""")
        seedLibraryAlbum(id = 2, name = "B", tags = """["标签三"]""")
        seedLibraryAlbum(id = 3, name = "C", tags = null)

        // 单个标签
        assertThat(dao.observeLibrary(null, null, null, "标签一", null, 10, 0).first().map { it.id })
            .containsExactly(1L)
        // 多选是 OR
        assertThat(dao.observeLibrary(null, null, null, "标签一,标签三", null, 10, 0).first().map { it.id })
            .containsExactly(1L, 2L)
        // 空串表示不筛选
        assertThat(dao.observeLibrary(null, null, null, "", null, 10, 0).first()).hasSize(3)
        // 子串不能误命中（"标签" 不等于 "标签一"）
        assertThat(dao.observeLibrary(null, null, null, "标签", null, 10, 0).first()).isEmpty()
    }

    @Test
    fun `inserting the same album id twice keeps the first row`() = runTest {
        val dao = db.albumDao()
        dao.insertIgnore(AlbumBaseEntity(id = 1, name = "原始"))
        // IGNORE 策略：基础数据已存在时不覆盖（刷新走事务整行替换，不经过这里）
        dao.insertIgnore(AlbumBaseEntity(id = 1, name = "覆盖"))

        assertThat(dao.findById(1)!!.name).isEqualTo("原始")
    }

    /** `collected` 在成员表里：只改它一个字段，基础数据不受影响。 */
    @Test
    fun `setCollected only touches the library row`() = runTest {
        val dao = db.albumDao()
        seedLibraryAlbum(id = 1, name = "标题", description = "简介", collected = false)

        dao.setCollected(1, true)

        assertThat(dao.findLibraryAlbum(1)!!.collected).isTrue()
        // 基础数据原样不动
        assertThat(dao.findById(1)!!.name).isEqualTo("标题")
        assertThat(dao.findById(1)!!.description).isEqualTo("简介")
    }

    /**
     * "移出书库"只删成员行：基础数据（永久缓存）必须保留，
     * 这样详情页/阅读器离线还能打开（用户要求：缓存表不因任何操作删除）。
     */
    @Test
    fun `moving an album out of the library keeps its base data`() = runTest {
        val dao = db.albumDao()
        seedLibraryAlbum(id = 1, name = "本子", createTime = "2026-01-01")

        dao.deleteLibraryRows(listOf(1))

        assertThat(dao.findLibraryAlbum(1)).isNull()
        assertThat(dao.findById(1)?.name).isEqualTo("本子")
        assertThat(dao.existingIds(listOf(1))).isEmpty()
    }

    @Test
    fun `chapter list groups images by chapter and orders by chapter sort then id`() = runTest {
        val dao = db.chapterDao()
        for (sort in listOf("2", "1", "10")) {
            val chapterId = sort.toLong()
            dao.insertAll(
                (1..3).map { index ->
                    ChapterImageEntity(
                        albumId = 100,
                        chapterId = chapterId,
                        chapterSort = sort,
                        chapterTitle = "第${sort}话",
                        imageFile = "%05d.webp".format(index),
                        imageSort = index,
                    )
                },
            )
        }

        val chapters = dao.chapterList(100)
        // ORDER BY CAST(chapter_sort AS INTEGER)：1, 2, 10（不是字符串序）
        assertThat(chapters.map { it.chapterSort }).containsExactly("1", "2", "10").inOrder()
        assertThat(chapters).hasSize(3)
    }

    @Test
    fun `images are returned in image sort order`() = runTest {
        val dao = db.chapterDao()
        dao.insertAll(
            listOf(3, 1, 2).map { index ->
                ChapterImageEntity(
                    albumId = 100,
                    chapterId = 1,
                    imageFile = "%05d.webp".format(index),
                    imageSort = index,
                )
            },
        )

        val images = dao.images(100, 1)
        assertThat(images.map { it.imageFile })
            .containsExactly("00001.webp", "00002.webp", "00003.webp").inOrder()
    }

    @Test
    fun `replacing a chapter drops images the server no longer returns`() = runTest {
        val dao = db.chapterDao()
        dao.replaceChapter(
            100,
            1,
            listOf("00001.webp", "00002.webp", "00003.webp").map {
                ChapterImageEntity(albumId = 100, chapterId = 1, imageFile = it, imageSort = 0)
            },
        )
        assertThat(dao.images(100, 1)).hasSize(3)

        // 服务端这次只返回 2 张：本地清单必须跟着收缩，否则详情页会多算图片数
        dao.replaceChapter(
            100,
            1,
            listOf("00001.webp", "00002.webp").map {
                ChapterImageEntity(albumId = 100, chapterId = 1, imageFile = it, imageSort = 0)
            },
        )
        assertThat(dao.images(100, 1).map { it.imageFile })
            .containsExactly("00001.webp", "00002.webp")
    }

    @Test
    fun `favorite name is unique`() = runTest {
        val dao = db.favoriteDao()
        val first = dao.insert(FavoriteEntity(name = "默认收藏夹", sortOrder = 0))
        val second = dao.insert(FavoriteEntity(name = "默认收藏夹", sortOrder = 1))

        assertThat(first).isGreaterThan(0)
        // IGNORE 策略：重名插入被丢弃，返回 -1
        assertThat(second).isEqualTo(-1)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `linking the same album to a favorite twice is a no-op`() = runTest {
        val favoriteDao = db.favoriteDao()
        val favoriteId = favoriteDao.insert(FavoriteEntity(name = "夹子"))

        val first = favoriteDao.link(FavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1))
        val second = favoriteDao.link(FavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1))

        assertThat(first).isGreaterThan(0)
        assertThat(second).isEqualTo(-1) // 唯一索引 (favorite_id, album_id)
        assertThat(favoriteDao.observeAlbumCount(favoriteId).first()).isEqualTo(1)
        assertThat(favoriteDao.albumCount(favoriteId)).isEqualTo(1)
    }

    @Test
    fun `deleting a favorite removes links but keeps the album record`() = runTest {
        val albumDao = db.albumDao()
        val favoriteDao = db.favoriteDao()
        seedLibraryAlbum(id = 1, name = "本子")
        val favoriteId = favoriteDao.insert(FavoriteEntity(name = "夹子"))
        favoriteDao.link(FavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1))

        favoriteDao.deleteWithLinks(favoriteId)

        assertThat(favoriteDao.all()).isEmpty()
        assertThat(favoriteDao.favoriteIdsOf(1)).isEmpty()
        // 书库记录与文件不受影响（§9 第 6 条）
        assertThat(albumDao.findLibraryAlbum(1)).isNotNull()
    }

    @Test
    fun `library can be filtered by favorite folder`() = runTest {
        val albumDao = db.albumDao()
        val favoriteDao = db.favoriteDao()
        seedLibraryAlbum(id = 1, name = "A")
        seedLibraryAlbum(id = 2, name = "B")
        val favoriteId = favoriteDao.insert(FavoriteEntity(name = "夹子"))
        favoriteDao.link(FavoriteAlbumEntity(favoriteId = favoriteId, albumId = 2))

        val inFolder = albumDao.observeLibrary(null, null, null, "", favoriteId, 10, 0).first()
        assertThat(inFolder.map { it.id }).containsExactly(2L)
    }

    /**
     * 夹内排序：**按"加入这个收藏夹"的时间倒序**（用户要求第 4 点）。
     *
     * 与书库的入库顺序刻意相反：id=1 先入库但后加进夹子，所以它在 id=2 后面。
     * 书库本身仍然按 `t_jm_library.create_time` 倒序。
     */
    @Test
    fun `albums inside a favorite are ordered by the time they joined that folder`() = runTest {
        val albumDao = db.albumDao()
        val favoriteDao = db.favoriteDao()
        seedLibraryAlbum(id = 1, name = "先入库后加夹", createTime = "2026-01-01 10:00:00")
        seedLibraryAlbum(id = 2, name = "后入库先加夹", createTime = "2026-01-02 10:00:00")
        val favoriteId = favoriteDao.insert(FavoriteEntity(name = "夹子"))
        favoriteDao.link(
            FavoriteAlbumEntity(favoriteId = favoriteId, albumId = 1, createTime = "2026-01-03 10:00:00"),
        )
        favoriteDao.link(
            FavoriteAlbumEntity(favoriteId = favoriteId, albumId = 2, createTime = "2026-01-04 10:00:00"),
        )

        val inFolder = albumDao.observeLibrary(null, null, null, "", favoriteId, 10, 0).first()
        assertThat(inFolder.map { it.id }).containsExactly(2L, 1L).inOrder()
    }
}