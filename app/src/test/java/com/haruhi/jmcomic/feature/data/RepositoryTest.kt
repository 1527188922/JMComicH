package com.haruhi.jmcomic.feature.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.database.entity.AlbumBaseEntity
import com.haruhi.jmcomic.core.database.entity.ChapterImageEntity
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.feature.api.AlbumDto
import com.haruhi.jmcomic.feature.api.ApiClient
import com.haruhi.jmcomic.feature.api.ChapterDto
import com.haruhi.jmcomic.feature.api.SeriesDto
import com.haruhi.jmcomic.feature.api.ApiJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JM 仓库的入库与查询语义验收（计划 §10 P2）：
 * 「与参考实现同一 aid 的入库字段一致」。
 *
 * 这里覆盖三条最容易写错、且一旦写错就会丢数据的规则
 * （逐条对应参考实现 `JmcomicSqliteServiceImpl`）：
 *
 * 1. `saveOrUpdateAlbum` 是"覆盖在线字段但**保留本地 collected**"；
 * 2. `listChapters` **优先用 `album.series`**，为空才回落到章节图片表；
 * 3. 章节图片入库是"整章替换"，`imageSort` 从 1 开始。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

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
            api = ApiClient(
                client = OkHttpClient(),
                config = { error("本测试不发起网络请求") },
            ),
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

    // ---------------- 入库字段一致性 ----------------

    @Test
    fun `saving an album maps every field and builds the folder name`() = runTest {
        val album = repo.saveAlbum(albumFixture())

        assertThat(album.id).isEqualTo(480854L)
        assertThat(album.name).isEqualTo("测试本子")
        // `<清洗后的标题>_JM<aid>`
        assertThat(album.albumFolderName).isEqualTo("测试本子_JM480854")
        assertThat(album.seriesId).isEqualTo("12345")
        assertThat(album.description).isEqualTo("简介内容")
        assertThat(album.totalViews).isEqualTo("12345")
        assertThat(album.commentTotal).isEqualTo("7")
        // 列表字段存 JSON 数组字符串；解析回来顺序不变
        assertThat(parse(album.tags)).containsExactly("标签一", "标签二").inOrder()
        assertThat(parse(album.author)).containsExactly("作者A")
        assertThat(parse(album.actors)).containsExactly("演员1")
        assertThat(album.createTime).isNotNull()
        assertThat(album.modifyTime).isNotNull()
        // 入库同时写入书库成员，排序用的入库时间落在成员表里
        assertThat(repo.findAlbum(480854L)).isNotNull()
    }

    @Test
    fun `saving an album keeps the local collected flag`() = runTest {
        repo.saveAlbum(albumFixture())
        repo.addToFavorite(480854L)

        assertThat(repo.findAlbum(480854L)!!.collected).isTrue()

        // 重新拉取在线数据必须**保留**本地收藏状态（参考实现的 delete+insert 也做同样的事）
        repo.saveAlbum(albumFixture().copy(name = "改名后的标题"))

        val updated = repo.findAlbum(480854L)!!
        assertThat(updated.name).isEqualTo("改名后的标题")
        assertThat(updated.collected).isTrue()
        // 文件夹名跟着新标题走（重新入库会重算）
        assertThat(updated.albumFolderName).isEqualTo("改名后的标题_JM480854")
    }

    @Test
    fun `saving an album twice does not duplicate the row`() = runTest {
        repo.saveAlbum(albumFixture())
        repo.saveAlbum(albumFixture())

        assertThat(db.albumDao().all()).hasSize(1)
    }

    @Test
    fun `album folder name respects the configured byte limit`() = runTest {
        val long = Repository(
            api = repoApi(),
            albumDao = db.albumDao(),
            chapterDao = db.chapterDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentAlbumDao(),
            searchHistoryDao = db.searchHistoryDao(),
            dirs = dirs,
            albumNameMaxBytes = { 40 },
        )
        val album = long.saveAlbum(albumFixture().copy(name = "这是一个非常长的本子标题用于测试截断行为"))
        // 后缀不参与截断，所以整体长度 = 基础名(<=40 字节) + "_JM480854"
        assertThat(album.albumFolderName).endsWith("_JM480854")
        val base = album.albumFolderName!!.removeSuffix("_JM480854")
        assertThat(base.toByteArray(Charsets.UTF_8).size).isAtMost(40)
    }

    // ---------------- 章节来源优先级 ----------------
    @Test
    fun `chapters come from the online series field when present`() = runTest {
        val album = repo.saveAlbum(albumFixture())

        val chapters = repo.chaptersOf(album)

        assertThat(chapters.map { it.chapterId }).containsExactly(500001L, 500002L).inOrder()
        // title 为空时回落成「第{sort}话」
        assertThat(chapters[0].title).isEqualTo("第1话")
        assertThat(chapters[1].title).isEqualTo("第2话")
    }

    @Test
    fun `chapters fall back to the local image table when series is empty`() = runTest {
        // series 为空是真实情况：有的本子接口不返回章节，只能靠已下载章节
        val album = repo.saveAlbum(albumFixture().copy(series = null))

        // 还没下过任何一话时给一个兜底的单话章节（见下一个用例）
        assertThat(repo.chaptersOf(album).map { it.chapterId }).containsExactly(480854L)

        db.chapterDao().insertAll(
            listOf(
                ChapterImageEntity(
                    albumId = 480854,
                    chapterId = 900001,
                    chapterSort = "1",
                    chapterTitle = "第1话",
                    chapterName = "name",
                    imageFile = "00001.webp",
                    imageSort = 1,
                ),
            ),
        )

        val chapters = repo.chaptersOf(repo.findAlbum(480854L)!!)
        assertThat(chapters.map { it.chapterId }).containsExactly(900001L)
        assertThat(chapters[0].title).isEqualTo("第1话")
    }

    /**
     * 单话本子的章节标题必须与磁盘目录名一致。
     *
     * 模拟器实测踩到的坑：接口返回的 `chapter.name` 往往是**本子标题**，
     * 如果把它当成 `chapterTitle`，下载用的目录名（合成的"第1话"）就和它对不上，
     * 于是图片下好了、界面却一直显示 0/1 已下载。
     */
    @Test
    fun `a single chapter album stores the synthetic title so it matches the folder name`() = runTest {
        val album = repo.saveAlbum(albumFixture().copy(series = null))
        val chapter = ChapterDto(
            id = 480854,
            images = listOf("00001.webp", "00002.webp"),
            // 接口给的名字就是本子标题
            name = "敏的秘密办公室恋情",
        )

        repo.saveChapterImages(480854, chapter)

        val images = repo.imagesOf(480854, 480854)
        assertThat(images).hasSize(2)
        assertThat(images[0].chapterTitle).isEqualTo("第1话")
        // 章节列表里的标题与章节目录名一致，界面才能正确判定"已下载"
        val summary = repo.chaptersOf(album).single()
        assertThat(summary.title).isEqualTo("第1话")
        assertThat(repo.chapterFolderName(summary)).isEqualTo("第1话")
    }

    @Test
    fun `multi chapter albums keep the api chapter name`() = runTest {
        repo.saveAlbum(albumFixture())
        val chapter = ChapterDto(id = 500009, images = listOf("00001.webp"), name = "单行本第9话")

        // 本子有 series 但这一话不在里面 -> 不合成，用接口给的 name
        repo.saveChapterImages(480854, chapter)

        assertThat(repo.imagesOf(480854, 500009)[0].chapterTitle).isEqualTo("单行本第9话")
    }

    /**
     * 历史数据自愈：单话本子的章节行里可能存着接口给的 `chapter.name`（= 本子标题），
     * 与磁盘目录名「第1话」对不上。列表与目录名都按"单话本子固定第1话"归一化。
     */
    @Test
    fun `a single chapter album normalizes the stored title to the folder name`() = runTest {
        val album = repo.saveAlbum(albumFixture().copy(series = null))
        // 直接写入"坏"数据，模拟被旧逻辑写坏的库
        db.chapterDao().insertAll(
            listOf(
                ChapterImageEntity(
                    albumId = 480854,
                    chapterId = 480854,
                    chapterSort = "1",
                    chapterTitle = "敏的秘密办公室恋情",
                    chapterName = "敏的秘密办公室恋情",
                    imageFile = "00001.webp",
                    imageSort = 1,
                ),
            ),
        )

        val summary = repo.chaptersOf(album).single()
        assertThat(summary.title).isEqualTo("第1话")
        assertThat(repo.chapterFolderName(summary)).isEqualTo("第1话")
    }

    /**
     * 单话本子：`/album` 不返回 `series` 时，JM 的约定是"章节 id 就是本子 id"。
     *
     * 参考实现 `downloadAlbumWithoutLock` 第 866～872 行就是这么兜底的。
     * 少了这条兜底，单话本子在 App 里显示"共 0 话"，**永远无法下载**——
     * 而单话本子在 JM 上非常常见（模拟器实测才发现）。
     */
    @Test
    fun `a single chapter album falls back to using the album id as the chapter id`() = runTest {
        val album = repo.saveAlbum(albumFixture().copy(series = null, name = "单话本子"))

        val chapters = repo.chaptersOf(album)

        assertThat(chapters).hasSize(1)
        assertThat(chapters[0].chapterId).isEqualTo(480854L)
        assertThat(chapters[0].title).isEqualTo("第1话")
        assertThat(chapters[0].sort).isEqualTo("1")
        // 章节目录名走 title -> "第1话"，与参考实现一致
        assertThat(repo.chapterFolderName(chapters[0])).isEqualTo("第1话")
    }

    @Test
    fun `chapter images are replaced as a whole and sorted with image sort starting at one`() = runTest {        repo.saveAlbum(albumFixture())
        val chapter = ChapterDto(
            id = 500001,
            // 故意乱序：入库前必须排序（对齐参考实现的 sortImageFiles）
            images = listOf("00003.webp", "00001.webp", "00010.webp", "00002.webp"),
            name = "章节名",
            addTime = "2024-01-01",
            seriesId = "12345",
            // `/comic_read` 会随图片清单一起给 scramble_id，入库时必须落库
            scrambleId = "999999999",
        )

        repo.saveChapterImages(480854, chapter, SeriesDto(id = "500001", sort = "1", name = "章节名"))

        val images = repo.imagesOf(480854, 500001)
        assertThat(images.map { it.imageFile })
            .containsExactly("00001.webp", "00002.webp", "00003.webp", "00010.webp").inOrder()
        // imageSort 从 1 开始（参考实现是 i + 1）
        assertThat(images.map { it.imageSort }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(images[0].chapterTitle).isEqualTo("第1话")
        assertThat(images[0].chapterSort).isEqualTo("1")
        // 同一话的每一行都带同一个 scramble_id；之后取它**先查库**，不再打接口
        assertThat(images.map { it.scrambleId }).containsExactly(999999999L, 999999999L, 999999999L, 999999999L)
        assertThat(repo.scrambleIdOf(500001)).isEqualTo(999999999L)
    }

    @Test
    fun `replacing a chapter drops images the server no longer returns`() = runTest {
        repo.saveAlbum(albumFixture())
        val full = ChapterDto(id = 500001, images = listOf("1.webp", "2.webp", "3.webp"))
        repo.saveChapterImages(480854, full, null)
        assertThat(repo.imagesOf(480854, 500001)).hasSize(3)

        repo.saveChapterImages(480854, ChapterDto(id = 500001, images = listOf("1.webp")), null)

        assertThat(repo.imagesOf(480854, 500001).map { it.imageFile }).containsExactly("1.webp")
    }

    @Test
    fun `chapter folder name follows title then name then id`() {
        val withTitle = ChapterSummary(chapterId = 1, sort = "1", name = "n", title = "第1话")
        assertThat(repo.chapterFolderName(withTitle)).isEqualTo("第1话")

        val nameOnly = withTitle.copy(title = "", name = "章节名")
        assertThat(repo.chapterFolderName(nameOnly)).isEqualTo("章节名")

        val idOnly = withTitle.copy(title = "", name = null)
        assertThat(repo.chapterFolderName(idOnly)).isEqualTo("1")
    }

    /**
     * 真机数据：jmid=1486169 的 `series` 是
     * `[{id:1486169, sort:"1", name:""}, {id:1486172, sort:"1", name:""}]`
     * —— 两话都退化成「第1话」，但它们其实是 1 和 1.2，页数、解扰块数都不同。
     *
     * 目录名必须带上 chapterId，否则两话共用一个目录：内容看起来一样、删一个等于删两个、
     * 第二话还会用别话的字节解扰（花屏）。
     */
    @Test
    fun `duplicate chapter titles get distinct folder names`() = runTest {
        repo.saveAlbum(
            albumFixture().copy(
                series = listOf(
                    SeriesDto(id = "1486169", name = "", sort = "1", title = ""),
                    SeriesDto(id = "1486172", name = "", sort = "1", title = ""),
                ),
            ),
        )

        val chapters = repo.chaptersOf(repo.findAlbum(480854L)!!)

        // 展示名照官方 App 的做法保持一致（两个「第1话」），身份靠 chapterId
        assertThat(chapters.map { it.title }).containsExactly("第1话", "第1话").inOrder()
        val folders = chapters.map { repo.chapterFolderName(it) }
        assertThat(folders).containsExactly("第1话-1486169", "第1话-1486172").inOrder()
        // 这条修复的全部意义就是这一句
        assertThat(folders.toSet()).hasSize(2)
    }

    /** 标题不冲突的章节必须保持老目录名，升级用户的已下图片不能被判成"未下载"。 */
    @Test
    fun `unique chapter titles keep the old folder names`() = runTest {
        repo.saveAlbum(albumFixture())

        val folders = repo.chaptersOf(repo.findAlbum(480854L)!!).map { repo.chapterFolderName(it) }

        assertThat(folders).containsExactly("第1话", "第2话").inOrder()
    }

    /**
     * `chapterId -> 目录名`：下载执行与启动恢复都靠它拿**当前**目录名，
     * 不能再用任务行/章节行里存的展示名（重名时会指向共享目录）。
     */
    @Test
    fun `chapter folders map exposes the unique storage key per chapter`() = runTest {
        repo.saveAlbum(
            albumFixture().copy(
                series = listOf(
                    SeriesDto(id = "1486169", name = "", sort = "1", title = ""),
                    SeriesDto(id = "1486172", name = "", sort = "1", title = ""),
                ),
            ),
        )

        val folders = repo.chapterFolders(480854L)

        assertThat(folders[1486169L]).isEqualTo("第1话-1486169")
        assertThat(folders[1486172L]).isEqualTo("第1话-1486172")
    }

    /**
     * `/comic_read` 的 `name` 是这一话**自己的**名字（`108P 1.2`、`魔女集会 1.2`），
     * 重复标题下它是唯一的区分信息。`series.name` 常常是空串，不能拿它覆盖掉。
     */
    @Test
    fun `a series chapter keeps its own name from comic read`() = runTest {
        repo.saveAlbum(albumFixture())

        repo.saveChapterImages(
            480854,
            ChapterDto(id = 500002, images = listOf("00001.webp"), name = "第2话 番外"),
            SeriesDto(id = "500002", sort = "2", name = ""),
        )
        // series.name 非空、chapter.name 为空时，退回 series.name（老行为）
        repo.saveChapterImages(
            480854,
            ChapterDto(id = 500003, images = listOf("00001.webp"), name = ""),
            SeriesDto(id = "500003", sort = "3", name = "3.5"),
        )

        assertThat(repo.imagesOf(480854, 500002)[0].chapterName).isEqualTo("第2话 番外")
        assertThat(repo.imagesOf(480854, 500003)[0].chapterName).isEqualTo("3.5")
    }

    @Test
    fun `downloaded image count only counts non empty files on disk`() = runTest {
        repo.saveAlbum(albumFixture())
        val folder = "测试本子_JM480854"
        val chapterDir = dirs.chapterDir(folder, "第1话").apply { mkdirs() }
        File(chapterDir, "00001.webp").writeBytes(ByteArray(16))
        // 0 字节残留不算已下载（与下载层的判定一致）
        File(chapterDir, "00002.webp").writeBytes(ByteArray(0))

        val have = repo.downloadedImageCount(
            folder,
            "第1话",
            listOf("00001.webp", "00002.webp", "00003.webp"),
        )

        assertThat(have).isEqualTo(1)
    }

    // ---------------- 书库筛选 ----------------

    @Test
    fun `library search matches name author and jm id`() = runTest {
        repo.saveAlbum(albumFixture())
        repo.saveAlbum(albumFixture().copy(id = 999, name = "别的本子", author = null, tags = null))

        assertThat(repo.observeLibrary(keyword = "测试").first().map { it.id })
            .containsExactly(480854L)
        assertThat(repo.observeLibrary(keyword = "作者A").first().map { it.id })
            .containsExactly(480854L)
        assertThat(repo.observeLibrary(keyword = "999").first().map { it.id })
            .containsExactly(999L)
    }

    @Test
    fun `tag filter uses substring match on the stored json array`() = runTest {
        repo.saveAlbum(albumFixture())
        repo.saveAlbum(albumFixture().copy(id = 999, name = "别的本子", tags = listOf("标签三")))

        assertThat(repo.observeLibrary(tags = listOf("标签一")).first().map { it.id })
            .containsExactly(480854L)
        // 多选是 OR 语义
        assertThat(repo.observeLibrary(tags = listOf("标签一", "标签三")).first().map { it.id })
            .containsExactly(480854L, 999L)
        assertThat(repo.observeLibrary(tags = emptyList()).first()).hasSize(2)
    }

    @Test
    fun `favorite folder filter ignores the collected flag`() = runTest {
        repo.saveAlbum(albumFixture())
        repo.saveAlbum(albumFixture().copy(id = 999, name = "别的本子"))

        val favoriteId = repo.ensureDefaultFavorite().id
        repo.addToFavorite(480854L, favoriteId)
        // 取消 collected 但保留关联，验证"指定收藏夹时不叠加 collected 条件"
        db.albumDao().setCollected(999L, true)
        repo.removeFromFavorite(999L, favoriteId)

        val inFolder = repo.observeLibrary(favoriteId = favoriteId).first()
        assertThat(inFolder.map { it.id }).containsExactly(480854L)
    }

    @Test
    fun `collect and uncollect update the flag from the favorite links`() = runTest {
        repo.saveAlbum(albumFixture())

        val first = repo.ensureDefaultFavorite()
        assertThat(repo.createFavorite("第二夹")).isEqualTo(FavoriteWriteResult.Ok)
        val second = repo.listFavorites().first { it.name == "第二夹" }

        repo.addToFavorite(480854L, first.id)
        repo.addToFavorite(480854L, second.id)
        assertThat(repo.findAlbum(480854L)!!.collected).isTrue()

        // 移出一个夹，另一个还在 -> 仍是已收藏
        repo.removeFromFavorite(480854L, first.id)
        assertThat(repo.findAlbum(480854L)!!.collected).isTrue()

        repo.removeFromFavorite(480854L, second.id)
        assertThat(repo.findAlbum(480854L)!!.collected).isFalse()

        // 取消收藏会清掉所有关联
        repo.addToFavorite(480854L, first.id)
        repo.uncollect(480854L)
        assertThat(repo.favoriteIdsOf(480854L)).isEmpty()
        assertThat(repo.findAlbum(480854L)!!.collected).isFalse()
    }

    @Test
    fun `default favorite cannot be renamed or deleted`() = runTest {
        val default = repo.ensureDefaultFavorite()

        assertThat(repo.renameFavorite(default.id, "新名字")).isEqualTo(FavoriteWriteResult.DefaultFolder)
        assertThat(repo.deleteFavorite(default.id)).isEqualTo(FavoriteWriteResult.DefaultFolder)
        assertThat(repo.findAlbum(0)).isNull()
    }

    /**
     * 用户反馈第 7 点：改名撞上已有名字时提示成了"默认收藏夹不支持重命名"。
     * 现在每种失败都有独立结果，界面才能给准确文案。
     */
    @Test
    fun `renaming to an existing name reports a conflict not a default folder error`() = runTest {
        repo.ensureDefaultFavorite()
        repo.createFavorite("第一夹")
        repo.createFavorite("第二夹")
        val first = repo.listFavorites().first { it.name == "第一夹" }

        assertThat(repo.renameFavorite(first.id, "第二夹")).isEqualTo(FavoriteWriteResult.NameTaken)
        assertThat(repo.renameFavorite(first.id, "   ")).isEqualTo(FavoriteWriteResult.NameBlank)
        assertThat(repo.renameFavorite(9999L, "随便")).isEqualTo(FavoriteWriteResult.NotFound)
        // 改成自己原来的名字是幂等的，不算冲突
        assertThat(repo.renameFavorite(first.id, "第一夹")).isEqualTo(FavoriteWriteResult.Ok)
        assertThat(repo.renameFavorite(first.id, "新名字")).isEqualTo(FavoriteWriteResult.Ok)
        assertThat(repo.listFavorites().map { it.name }).contains("新名字")
    }

    @Test
    fun `creating a duplicate favorite reports the conflict`() = runTest {
        repo.ensureDefaultFavorite()

        assertThat(repo.createFavorite("同一夹")).isEqualTo(FavoriteWriteResult.Ok)
        assertThat(repo.createFavorite("同一夹")).isEqualTo(FavoriteWriteResult.NameTaken)
        assertThat(repo.createFavorite("  ")).isEqualTo(FavoriteWriteResult.NameBlank)
    }

    @Test
    fun `deleting albums removes records and chapter lists but keeps files`() = runTest {
        repo.saveAlbum(albumFixture())
        repo.saveChapterImages(480854, ChapterDto(id = 500001, images = listOf("1.webp")), null)
        val chapterDir = dirs.chapterDir("测试本子_JM480854", "第1话").apply { mkdirs() }
        File(chapterDir, "1.webp").writeBytes(ByteArray(8))

        repo.deleteAlbums(listOf(480854L))

        assertThat(repo.findAlbum(480854L)).isNull()
        assertThat(repo.imagesOf(480854, 500001)).isEmpty()
        // 基础数据缓存**保留**（用户要求：缓存表不因移出书库而删除）
        assertThat(db.albumDao().findById(480854L)?.name).isEqualTo("测试本子")
        // 本地文件不受"删除记录"影响（计划 §9.1 的删除语义区分）
        assertThat(File(chapterDir, "1.webp").exists()).isTrue()
    }

    @Test
    fun `deleting album files keeps the record`() = runTest {
        repo.saveAlbum(albumFixture())
        val chapterDir = dirs.chapterDir("测试本子_JM480854", "第1话").apply { mkdirs() }
        File(chapterDir, "1.webp").writeBytes(ByteArray(8))

        val freed = repo.deleteAlbumFiles("测试本子_JM480854")

        assertThat(freed).isGreaterThan(0L)
        assertThat(dirs.albumDir("测试本子_JM480854").exists()).isFalse()
        assertThat(repo.findAlbum(480854L)).isNotNull()
    }

    // ---------------- 搜索结果「加入书库」（用户反馈第 1 点） ----------------

    @Test
    fun `adding to library from a search result does not hit the network when local data exists`() = runTest {
        // 精确匹配那一条：搜索时已经请求过 /album 并写进**基础数据表**（模拟：直接写 dao）
        db.albumDao().insertIgnore(
            AlbumBaseEntity(
                id = 480854L,
                name = "测试本子",
                albumFolderName = "测试本子_JM480854",
            ),
        )

        // 本测试的 api 客户端**一被调用就抛异常**，所以能跑通就说明没有联网
        val album = repo.addToLibraryLocalFirst(480854L)

        assertThat(album.id).isEqualTo(480854L)
        assertThat(repo.findAlbum(480854L)).isNotNull()
        // **不是最近预览**：缓存详情不等于加入最近预览
        assertThat(repo.findRecent(480854L)).isNull()
    }

    @Test
    fun `adding to library from a search result is instant when the album is already in the library`() = runTest {
        repo.saveAlbum(albumFixture())

        val album = repo.addToLibraryLocalFirst(480854L)

        assertThat(album.id).isEqualTo(480854L)
        // 幂等：不会多出一条
        assertThat(repo.findAlbum(480854L)?.id).isEqualTo(480854L)
    }

    @Test
    fun `adding to library falls back to the network when nothing is cached locally`() = runTest {
        // 普通搜索结果：本地既没有书库成员也没有基础数据缓存
        val result = runCatching { repo.addToLibraryLocalFirst(111111L) }

        // 回落到了网络，而本测试的客户端禁止联网，所以必然是失败
        assertThat(result.isFailure).isTrue()
        assertThat(repo.findAlbum(111111L)).isNull()
    }

    // ------------------------------------------------------------------

    private fun repoApi() = ApiClient(
        client = OkHttpClient(),
        config = { error("本测试不发起网络请求") },
    )

    private fun parse(json: String?): List<String> =
        json?.let { ApiJson.decodeFromString(ListSerializer(String.serializer()), it) }.orEmpty()

    /** 与参考实现同 aid 的一份固定响应样本（字段名逐条对齐 `Album.java`）。 */
    private fun albumFixture() = AlbumDto(
        id = 480854,
        name = "测试本子",
        addTime = "2024-01-01 00:00:00",
        description = "简介内容",
        totalViews = "12345",
        likes = "678",
        series = listOf(
            SeriesDto(id = "500001", name = "第1话", sort = "1", title = ""),
            SeriesDto(id = "500002", name = "第2话", sort = "2", title = ""),
        ),
        seriesId = "12345",
        commentTotal = "7",
        author = listOf("作者A"),
        tags = listOf("标签一", "标签二"),
        works = listOf("作品1"),
        actors = listOf("演员1"),
        liked = true,
        isFavorite = false,
        price = "0",
        purchased = "0",
    )

    @Test
    fun `jm defaults expose the reference api domain and image domain`() {
        // 防止有人"顺手"改掉这两个域名而没意识到会影响所有请求
        assertThat(Defaults.DEFAULT_API_DOMAIN).isEqualTo("www.cdnbea.net")
        assertThat(Defaults.IMAGE_DOMAIN).isEqualTo("cdn-msp2.jmapiproxy2.cc")
        assertThat(Defaults.SEARCH_PAGE_SIZE).isEqualTo(80)
    }
}
