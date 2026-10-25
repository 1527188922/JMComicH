package com.haruhi.jmcomic.feature.api

import com.haruhi.jmcomic.core.protocol.ApiLanguage
import com.haruhi.jmcomic.core.protocol.CategorySort
import com.haruhi.jmcomic.core.protocol.ChapterSource
import com.haruhi.jmcomic.core.protocol.Crypto
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.protocol.SearchSort
import com.haruhi.jmcomic.core.protocol.SignatureScheme
import com.haruhi.jmcomic.core.network.NetworkError
import com.haruhi.jmcomic.core.network.isNetworkTimeout
import com.haruhi.jmcomic.core.network.isRetryableApiError
import com.haruhi.jmcomic.core.network.withTimeoutRetry
import com.haruhi.jmcomic.core.settings.ResolvedNetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder

/**
 * JM 接口客户端。
 *
 * 覆盖计划 §10 P2 要求的四个接口 + 一个模板接口：
 * `/search`、`/album`、`/chapter`、`/chapter_view_template`；
 * 详情页「评论」tab 用的 `/forum`，以及分类页用的 `/categories` + `/categories/filter`
 * 也在这里。
 *
 * 2026-10-22 补上**评论的写接口**：`/comment`（发留言 / 回复）与 `/comment_delete`（删留言），
 * 以及「我的留言」用的 `/forum?uid=`（见 [sendComment] / [deleteComment] / [forumByUser]）。
 * 它们的成功/失败都写在 `data.status` 里（HTTP 永远 200），由 `AccountRepository` 判定。
 *
 * **账号接口**（`/login`、`/favorite`、`/favorite_folder`、`/useredit`）在 2026-10 加入：
 * 它们都是 `multipart/form-data` 的 **POST**，响应仍是同一个 `{code,data(密文)}` 信封，
 * 解密密钥同样由 `ts` 派生（见 [postRaw] / [postDecrypted]）。
 * 登录态由构造时的 [authProvider] 提供，**本类不保存任何账号状态**（保持无状态，
 * 401 只按 `NetworkError.HttpStatus(401)` 往上抛，由 `AccountRepository` 翻译）。
 *
 * 官方 2.1.11 的两套协议面（`/comic_read` + 新签名）在这里按 [ChapterSource] /
 * [SignatureScheme] 分派，默认走新的、旧的一并保留（见 `core/protocol/ApiScheme.kt`）。
 *
 * 契约细节全部对齐参考实现 `JmcomicService`：
 * - 每次请求新建签名（`ts` 为秒级时间戳）；**重试时沿用第一次的 `ts`**，
 *   否则响应无法用同一个 key 解密（参考实现 `executeGet` 的注释明确写了这一点）；
 * - 只有**可重试的异常**才重试（网络超时，或响应空体 / 非 JSON 信封这类**内容异常**），
 *   次数只有一个（设置页「高级 → 请求重试次数」，**所有接口共用**）；
 * - 响应 JSON 的 `data` 字段是密文，用 `ts` 派生 AES-256 密钥解密；
 * - **每个 GET 请求都带 `lang`**（设置页「语言 → 接口语言」，默认简中）：官方
 *   `fetchGet` 就是这么做的，`/categories` 的分类名、`/album` 的标签与简介、
 *   `/search` 的简介等中文文案都跟着它变（见 [ApiLanguage]）。
 *
 * `ts` 的作用域刻意放在**每次请求**（而不是每次尝试）里：
 * 这样重试复用的是同一个 `ts`，满足上面第 1 条契约。
 */
class ApiClient(
    private val client: OkHttpClient,
    private val config: () -> ResolvedNetworkConfig,
    /**
     * 接口请求「失败后自动重试」的次数（0 = 不自动重试）。
     *
     * **所有接口共用这一项**（设置页「高级 → 请求重试次数」）：以前按接口分成
     * 详情 / 搜索 / 章节 / 分类四项，2026-10-20 按用户要求合并成一个。
     * 只管"重发几次"，**不影响什么错误值得重发**（那由 `isRetryableApiError()` 决定）。
     */
    private val retryProvider: () -> Int = { Defaults.RETRY_DEFAULT },
    /**
     * 账号类只读接口是否复用服务端缓存（设置页「高级 → 账号接口缓存」，默认 true）。
     *
     * false 时给 `/favorite`、`/useredit`、`/album` 的 URL 补一个 `t=<秒级时间戳>`：
     * 服务端按「线路 + URL」缓存这些 GET 的明文响应，1app 的写入不会失效另一条线路的
     * 缓存——双开 / 多线路同时登录同一账号时，纯下拉刷新会一直拿到旧数据。
     * URL 变了缓存键就变，强制回源；代价是这些请求不再命中服务端缓存。
     */
    private val accountCacheProvider: () -> Boolean = { true },
    /** 接口线路；默认取 [Defaults.DEFAULT_API_DOMAIN]。 */
    private val apiDomainProvider: () -> String = { Defaults.DEFAULT_API_DOMAIN },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * 请求签名用哪一套；默认 [SignatureScheme.DEFAULT]（官方 2.1.11）。
     *
     * 做成"每次取用"的闭包而不是构造时的快照：将来若要允许用户切换，
     * 改值就能对新请求生效，不用重建客户端（与 `apiDomainProvider` 一致）。
     */
    private val signatureSchemeProvider: () -> SignatureScheme = { SignatureScheme.DEFAULT },
    /**
     * 章节图片清单 + `scramble_id` 用哪一套接口；
     * 默认 [ChapterSource.DEFAULT]（`/comic_read`，一次拿两样）。
     */
    private val chapterSourceProvider: () -> ChapterSource = { ChapterSource.DEFAULT },
    /**
     * 接口语言（请求里的 `lang`），默认 [ApiLanguage.DEFAULT]（简中）。
     *
     * 对应官方 App 的 `localStorage.lang`：`HttpUtil.fetchGet` 会给**每个 GET 请求**
     * 自动补 `lang`（见文档 §2.4 / §2.6），所以这里也统一在 [getDecrypted] 里补一次，
     * 各接口调用点不再自己拼这个参数。
     *
     * 与"每次取用"的其它 provider 一致：改设置后新请求立刻生效，不用重建客户端。
     */
    private val languageProvider: () -> ApiLanguage = { ApiLanguage.DEFAULT },
    /**
     * 登录态 header（`Authorization: Bearer …` + `Cookie: AVS=…`）。
     *
     * 默认 null = 匿名请求，一个 header 都不加。官方 App 匿名时发的是**空串**，
     * 在 OkHttp 里发空 header 没有意义，所以这里干脆不发。
     * 同样做成"每次取用"的闭包：登录/退出后新请求立刻生效，不用重建客户端。
     */
    private val authProvider: () -> AuthHeaders? = { null },
) {

    private val apiDomain: String get() = apiDomainProvider().ifBlank { Defaults.DEFAULT_API_DOMAIN }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** `/search`。`page` 从 1 开始，接口每页固定 80 条。 */
    suspend fun search(
        query: String,
        sort: SearchSort = SearchSort.DEFAULT,
        page: Int = 1,
    ): SearchRespDto {
        val keyword = query.trim()
        require(keyword.isNotEmpty()) { "请输入搜索关键字" }
        val safePage = if (page < 1) 1 else page

        return getDecrypted(
            path = "/search",
            params = listOf(
                "main_tag" to "0",
                "search_query" to keyword,
                "page" to safePage.toString(),
                "o" to sort.code,
            ),
            retry = retryProvider(),
            decode = { json -> ApiJson.decodeFromString(SearchRespDto.serializer(), json) },
        )
    }

    /**
     * `/album`：本子详情，含章节列表（`series`）。
     *
     * 响应里有账号态字段（`liked` / `is_favorite`），同样受「接口缓存」开关影响。
     */
    suspend fun album(aid: Long): AlbumDto = getDecrypted(
        path = "/album",
        params = withAccountCacheBuster(listOf("id" to aid.toString())),
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(AlbumDto.serializer(), json) },
    )

    /**
     * `/categories`：分类树（主分类 + 二级分类）。
     *
     * 官方文档 §4.2：9 个主分类，其中「最新A漫」的 `slug` 是**空串**、没有二级分类；
     * 其余每个主分类带 2～6 个二级分类。真实响应里 `id` / `total_albums` 有数字与字符串
     * 混排，所以 [CategoriesRespDto] 干脆不声明它们（见该类的注释）。
     *
     * 分类的**展示名**（`同人` / `漢化` / `最新A漫`）就是接口直接给的，会跟着
     * `lang` 变（简中 `汉化` / 繁中 `漢化`）；`lang` 由 [getDecrypted] 统一补，
     * 取值来自设置页「语言 → 接口语言」。
     *
     * 重试次数用「请求重试次数」那一项（设置页「高级」，所有接口共用）。
     */
    suspend fun categories(): CategoriesRespDto = getDecrypted(
        path = "/categories",
        params = emptyList(),
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(CategoriesRespDto.serializer(), json) },
    )

    /**
     * `/categories/filter`：分类内容列表。
     *
     * | 参数 | 说明 |
     * |---|---|
     * | `page` | **从 1 开始**，每页固定 80 条 |
     * | `c` | `slug` 或 `slug + "_" + 二级slug`；「最新A漫」传空串 → **不传** |
     * | `o` | 排序码（[CategorySort]）；「最新」是空串 → **不传** |
     * | `lang` | 由 [getDecrypted] 自动补（设置页「语言 → 接口语言」） |
     *
     * 空值参数按官方 `fetchGet` 的规则**直接丢掉**（文档 §2.4）：
     * 传 `o=` / `c=` 空串与不传在服务端语义相同，但少一个参数少一次歧义。
     */
    suspend fun categoryFilter(
        category: String,
        sort: CategorySort = CategorySort.DEFAULT,
        page: Int = 1,
    ): CategoryFilterRespDto = getDecrypted(
        path = "/categories/filter",
        params = buildList {
            add("page" to (if (page < 1) 1 else page).toString())
            if (sort.code.isNotBlank()) add("o" to sort.code)
            if (category.isNotBlank()) add("c" to category)
        },
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(CategoryFilterRespDto.serializer(), json) },
    )

    /**
     * `/forum`：漫画评论列表（详情页「评论」tab）。
     *
     * | 参数 | 说明 |
     * |---|---|
     * | `mode` | 固定 `all`（详情页看全部；`manhua` / `chat` 是官方 App 的其它场景） |
     * | `page` | **从 1 开始**，每页固定 **10** 条 |
     * | `aid` | 漫画 id |
     *
     * `total` 被服务端截断到 5000，所以最多 500 页。**匿名可读**（实测无登录态也返回 200），
     * 与本工程"不做 JM 账号"的定位一致。
     *
     * 重试次数用统一的「请求重试次数」那一项（所有接口共用，评论不再单开设置项）。
     */
    suspend fun forum(aid: Long, page: Int = 1, mode: String = "all"): ForumRespDto = getDecrypted(
        path = "/forum",
        params = listOf(
            "mode" to mode,
            "page" to (if (page < 1) 1 else page).toString(),
            "aid" to aid.toString(),
        ),
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(ForumRespDto.serializer(), json) },
    )

    /**
     * `/forum?uid=`：某个用户发过的留言（「我的 → 留言记录」）。
     *
     * 与 [forum] 的差别（逐字对齐官方 `FETCH_FORUM_THUNK`）：
     * - **不传 `aid`**：这是"这个人发过的所有留言"，跨本子；
     * - **不传 `mode`**：官方 `Member/CommentList.tsx` 只发 `{uid, page}`
     *   （2026-10-22 实测带 `mode=all` 结果一样，但少一个参数少一处歧义）；
     * - 每条都带 `AID` / `name`，界面靠它们显示「来自 JMxxxx」并跳详情页。
     *
     * 每页同样是 10 条、`page` 从 1 开始、`total` 上限 5000。**匿名也能读**。
     */
    suspend fun forumByUser(uid: String, page: Int = 1): ForumRespDto = getDecrypted(
        path = "/forum",
        params = listOf(
            "page" to (if (page < 1) 1 else page).toString(),
            "uid" to uid,
        ),
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(ForumRespDto.serializer(), json) },
    )

    /**
     * `POST /comment`：发留言；带 [parentCid] 就是**回复**那条留言。
     *
     * 官方 `FETCH_FORUM_SEND_THUNK` 的字段就是 `comment` / `aid` / `comment_id`
     * （`spoilers` 那个勾选只存在前端，**不往上传**）。
     *
     * 成败**不在 HTTP 状态里**：实测业务失败仍是 `HTTP 200 + 信封 code 200`，
     * `data` 里是 `{status:"fail", msg:"…"}`。所以这里原样把 DTO 交回去，
     * 由 `AccountRepository` 按 `status` 判定（[CommentEditResultDto.isOk]）。
     *
     * 走 [postDecrypted]：**不自动重试**——发留言不是幂等操作（重发会多一条）。
     */
    suspend fun sendComment(
        aid: Long,
        content: String,
        parentCid: String? = null,
    ): CommentEditResultDto = postDecrypted(
        path = "/comment",
        fields = buildList {
            add("comment" to content)
            add("aid" to aid.toString())
            if (!parentCid.isNullOrBlank()) add("comment_id" to parentCid)
        },
        decode = { json -> ApiJson.decodeFromString(CommentEditResultDto.serializer(), json) },
    )

    /**
     * `POST /comment_delete`：删除一条留言（只有自己的能删）。
     *
     * 实测响应与发留言同构：成功 `{status:"ok", msg:"評論成功刪除！", cid, aid, …}`，
     * 删别人的/不存在的是 `{status:"fail", msg:"無法刪除評論！ ？"}`（HTTP 200）。
     *
     * `aid` 官方会一起传（`ForumList.tsx` 的 `handleDeteleComment` 传了 aid/bid/nid），
     * 所以知道是哪本时就带上；拿不到（理论上不会有）也能只传 `comment_id`。
     */
    suspend fun deleteComment(commentId: String, aid: Long? = null): CommentEditResultDto =
        postDecrypted(
            path = "/comment_delete",
            fields = buildList {
                add("comment_id" to commentId)
                if (aid != null && aid > 0L) add("aid" to aid.toString())
            },
            decode = { json -> ApiJson.decodeFromString(CommentEditResultDto.serializer(), json) },
        )

    /**
     * 章节详情（图片文件名清单）——按 [ChapterSource] 分派到两代接口之一，
     * 两条路径都返回**同一个** [ChapterDto]，上层只认这一个模型。
     *
     * - [ChapterSource.COMIC_READ]（默认）：`/comic_read`，一次拿到清单 + `scramble_id`；
     * - [ChapterSource.LEGACY_CHAPTER]：`/chapter`（`scramble_id` 另走 [scrambleId]）。
     */
    suspend fun chapter(chapterId: Long): ChapterDto = when (chapterSourceProvider()) {
        ChapterSource.COMIC_READ -> comicRead(chapterId)
        ChapterSource.LEGACY_CHAPTER -> getDecrypted(
            path = "/chapter",
            params = listOf("id" to chapterId.toString()),
            retry = retryProvider(),
            decode = { json -> ApiJson.decodeFromString(ChapterDto.serializer(), json) },
        )
    }

    /**
     * `/comic_read`：官方 2.1.11 的阅读接口，一次返回图片清单与 `scramble_id`。
     *
     * 响应里 `images[].image` 是**完整 URL**（指向接口当时给的 CDN），本工程只用它取文件名：
     * 图片实际按**用户选中的图片线路**拼接（`Defaults.buildImgUrl`，见 `ImageStore` /
     * `DownloadManager`），这样"图片线路"这个设置项才有意义，也不受接口临时换 CDN 影响
     * （实测两处 CDN 取到的是同一张图）。
     *
     * 参数只带 `id`（章节 id）。文档 §2.4 说官方 `fetchGet` 会自动追加 `lang`，
     * 与现有 `/chapter` 一样这里不带它——加上不影响结果，少一个参数少一次歧义。
     */
    private suspend fun comicRead(chapterId: Long): ChapterDto = getDecrypted(
        path = "/comic_read",
        params = listOf("id" to chapterId.toString()),
        retry = retryProvider(),
        decode = { json ->
            ApiJson.decodeFromString(ComicReadDto.serializer(), json).toChapterDto(chapterId)
        },
    )

    /**
     * 该章节的 `scramble_id`（解扰块数的输入），按 [ChapterSource] 分派。
     *
     * - 默认（[ChapterSource.COMIC_READ]）走 `/comic_read`：它顺带返回图片清单，
     *   所以**不必再调** `/chapter_view_template`；
     * - [ChapterSource.LEGACY_CHAPTER] 走 `/chapter_view_template`（旧机制，原样保留）。
     *
     * 两条路径取不到时都回落到兜底值 [Defaults.FALLBACK_SCRAMBLE_ID]，
     * 与参考实现 `getScrambleId` 的 catch 分支一致。
     */
    suspend fun scrambleId(chapterId: Long): Long = try {
        when (chapterSourceProvider()) {
            ChapterSource.COMIC_READ -> comicRead(chapterId).scrambleId?.toLongOrNull()
                ?: throw NetworkError.Malformed("/comic_read", "响应里没有 scramble_id")
            ChapterSource.LEGACY_CHAPTER -> requestScrambleIdFromTemplate(chapterId)
        }
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Timber.tag("jm").w(e, "取 scramble_id 失败，使用兜底值 chapterId=%d", chapterId)
        Defaults.FALLBACK_SCRAMBLE_ID
    }

    /**
     * `/chapter_view_template`：旧机制里取 `scramble_id` 的接口。
     *
     * 它用的是**另一个 secret**（`18comicAPPContent`），且响应是 HTML 不是 JSON，
     * 所以不走 [getDecrypted]。
     *
     * **重试**：与其它接口共用同一个「请求重试次数」——它同样是"打开章节就要打"的
     * 前置调用，可重试的异常（超时 / 空体 / 响应不含标记）与章节接口一致。
     *
     * **不带 `lang`**：它不是 `fetchGet` 的 JSON 接口，而是旧版阅读页的 HTML 模板地址，
     * 官方那一版的 URL 里就没有语言参数（文档 §7.5 也只给 `id`），这里保持原样。
     */
    private suspend fun requestScrambleIdFromTemplate(chapterId: Long): Long {
        val ts = nowSeconds()
        val headers = Crypto.contentHeaderParams(ts)
        val url = buildUrl(
            Defaults.chapterViewTemplateUrl(apiDomain),
            listOf(
                "id" to chapterId.toString(),
                "v" to ts.toString(),
                "mode" to "vertical",
                "page" to "0",
                "app_img_shunt" to "1",
                "express" to "off",
            ),
        )
        return withTimeoutRetry(
            maxRetry = retryProvider(),
            retryOn = { it.isRetryableApiError() },
            onRetry = { attempt, total, cause ->
                Timber.tag("jm").d(
                    "第 %d/%d 次重试 /chapter_view_template（%s）",
                    attempt,
                    total,
                    cause.javaClass.simpleName,
                )
            },
        ) {
            parseScrambleId(url, execute(url, headers.token, headers.tokenParam))
        }
    }

    /** `/comic_read` 响应 → 统一的 [ChapterDto]：完整 URL 只取文件名，其余字段原样带过去。 */
    private fun ComicReadDto.toChapterDto(requestedChapterId: Long) = ChapterDto(
        // `id` 文档是字符串、实测是数字，统一按文本读；实在拿不到就用请求里的章节号
        id = id?.trim()?.toLongOrNull() ?: requestedChapterId,
        name = name,
        images = images.orEmpty().mapNotNull { item ->
            item.image?.let(::imageFileName)?.takeIf { it.isNotEmpty() }
        },
        addTime = addTime,
        seriesId = seriesId,
        isFavorite = isFavorite,
        liked = liked,
        scrambleId = scrambleId?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** `https://{cdn}/media/photos/{chapterId}/00001.webp?t=…` → `00001.webp`。 */
    private fun imageFileName(url: String): String = url
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .trim()

    // ------------------------------------------------------------------
    // 账号（JM 会员）—— 契约见 docs/official-api-manga.md §7 / §8
    // ------------------------------------------------------------------

    /**
     * `POST /login`。
     *
     * 失败形态（实测）：**HTTP 401 + 顶层 `errorMsg`**（明文，`data` 为空），
     * 例如 `无效的用户名和/或密码!` / `用户名和密码字段不能留空!`。
     * 所以这里不能走 [postDecrypted]（它只认 `data`），必须自己读 `errorMsg`。
     */
    suspend fun login(username: String, password: String): LoginDto {
        val raw = postRaw(
            path = "/login",
            fields = listOf("username" to username.trim(), "password" to password),
        )
        val env = runCatching { ApiJson.decodeFromString(AccountEnvelopeDto.serializer(), raw.body) }
            .getOrNull()
        val message = env?.errorMsg?.takeIf { it.isNotBlank() }
            ?: env?.msg?.takeIf { it.isNotBlank() }
        if (raw.http !in 200..299 || env?.code != 200 || env.data.isNullOrBlank()) {
            throw NetworkError.Business(message ?: "登录失败（HTTP ${raw.http}）")
        }
        val plain = runCatching { Crypto.decryptData(raw.ts, env.data, signatureSchemeProvider()) }
            .getOrElse { throw NetworkError.Business("登录响应解密失败，接口版本可能已变更") }
        val dto = runCatching { ApiJson.decodeFromString(LoginDto.serializer(), plain) }
            .getOrElse { throw NetworkError.Malformed("/login", "登录响应字段与预期不符：${it.message}") }
        if (dto.jwtToken.isNullOrBlank() || dto.avs.isNullOrBlank()) {
            throw NetworkError.Business("登录响应缺少凭证，请稍后重试")
        }
        return dto
    }

    /** `POST /logout`。**失败不抛**：退出登录在本地必须成功（调用方照旧清凭证）。 */
    suspend fun logout() {
        runCatching { postRaw(path = "/logout", fields = emptyList()) }
            .onFailure { Timber.tag("jm").d(it, "logout 失败（已忽略）") }
    }

    /**
     * `GET /favorite`：账号收藏列表。
     *
     * @param folderId 收藏夹 `FID`；null/空 = **全部收藏**（未归类的也在里面）。
     * @param order `mr` 收藏时间（默认，与不传一致）/ `mp` 更新时间。
     */
    suspend fun favoritePage(
        page: Int = 1,
        folderId: String? = null,
        order: String = "mr",
    ): FavoritePageDto = getDecrypted(
        path = "/favorite",
        params = withAccountCacheBuster(buildList {
            add("page" to (if (page < 1) 1 else page).toString())
            if (!folderId.isNullOrBlank()) add("folder_id" to folderId)
            if (order.isNotBlank()) add("o" to order)
        }),
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(FavoritePageDto.serializer(), json) },
    )

    /**
     * `POST /favorite`：**切换**收藏（未收藏 → 加入；已收藏 → 移除）。
     *
     * 实测 `folder_id` 参数会被忽略，所以"加进某个夹"必须再调一次 [favoriteFolder]。
     * 也**不要**把多个 aid 用逗号拼在一起（实测返回的 `type` 与实际状态不符）。
     */
    suspend fun favoriteToggle(aid: Long): EditResultDto = postDecrypted(
        path = "/favorite",
        fields = listOf("aid" to aid.toString()),
        decode = { json -> ApiJson.decodeFromString(EditResultDto.serializer(), json) },
    )

    /**
     * `POST /favorite_folder`：收藏夹增删改移。
     *
     * @param type `add`（新建，需 folderName）/ `edit`（改名）/ `del`（删除，只能删空夹）/
     *   `move`（把 [aid] 搬进 [folderId]）。
     *
     * 业务失败**仍是 HTTP 200**，看返回的 `status`/`code`（见 [EditResultDto.isOk]）。
     */
    suspend fun favoriteFolder(
        type: String,
        folderId: String? = null,
        folderName: String? = null,
        aid: Long? = null,
    ): EditResultDto = postDecrypted(
        path = "/favorite_folder",
        fields = buildList {
            add("type" to type)
            if (!folderId.isNullOrBlank()) add("folder_id" to folderId)
            if (!folderName.isNullOrBlank()) add("folder_name" to folderName)
            if (aid != null) add("aid" to aid.toString())
        },
        decode = { json -> ApiJson.decodeFromString(EditResultDto.serializer(), json) },
    )

    /** `GET /useredit/{uid}`：个人资料。 */
    suspend fun userEditGet(uid: String): UserEditDto = getDecrypted(
        path = "/useredit/$uid",
        params = withAccountCacheBuster(emptyList()),
        retry = retryProvider(),
        decode = { json -> ApiJson.decodeFromString(UserEditDto.serializer(), json) },
    )

    /**
     * `POST /useredit/{uid}`：写个人资料。
     *
     * **整条覆盖**（实测）：没提交的字段会被写成默认值，所以调用方必须把
     * [userEditGet] 拿到的 24 个字段原样回传（`password`/`password_confirm` 留空 = 不改密码）。
     */
    suspend fun userEditPost(uid: String, fields: List<Pair<String, String>>): UserEditDto =
        postDecrypted(
            path = "/useredit/$uid",
            fields = fields,
            decode = { json -> ApiJson.decodeFromString(UserEditDto.serializer(), json) },
        )

    /**
     * `POST /register`：注册（`docs/official-api-manga.md` §7.3）。
     *
     * 2026-10-23 实测（**只测失败形态**，没有创建账号）：
     * - 业务失败仍是 **HTTP 200 + 信封 `code` 200**，`data` 解密后是
     *   `{status:"fail", msg:"…", errors:[…]}`；顶层还带一个明文 `errorMsg`（= `errors` 的第一条）。
     * - `errors` 会把**所有**问题列出来（邮箱格式、用户名已存在 / 超长、两次密码不一致……），
     *   所以界面要展示 `errors` 而不是只看 `msg`。
     * - 用户名上限实测 **15 个字符**（`用户名只能包含15个字符!`），与官方前端 `maxLength=50` 不一致。
     *
     * 注册成功**不代表能登录**：还要走 [confirmSignUp] 过邮箱验证码。
     * 走 [postDecrypted]（**不重试**）：注册不是幂等操作。
     */
    suspend fun register(
        username: String,
        password: String,
        passwordConfirm: String,
        email: String,
        gender: String,
    ): EditResultDto = postDecrypted(
        path = "/register",
        fields = listOf(
            "username" to username.trim(),
            "password" to password,
            "password_confirm" to passwordConfirm,
            "email" to email.trim(),
            "gender" to gender,
        ),
        decode = { json -> ApiJson.decodeFromString(EditResultDto.serializer(), json) },
    )

    /**
     * `POST /confirm`：提交邮箱验证码（`docs/official-api-manga.md` §7.4）。
     *
     * 参数按官方 `FETCH_SIGN_UP_CONFIRM_THUNK`：`username` + `numcode`。
     * 响应形态与 [register] 同一套（`status` / `msg` / `errors`），同样不重试。
     */
    suspend fun confirmSignUp(username: String, numCode: String): EditResultDto = postDecrypted(
        path = "/confirm",
        fields = listOf(
            "username" to username.trim(),
            "numcode" to numCode.trim(),
        ),
        decode = { json -> ApiJson.decodeFromString(EditResultDto.serializer(), json) },
    )

    /**
     * `POST /confirm`：**App API 的重新发送验证码**。
     *
     * ⚠ **实测不可用（2026-10-23）**：App API 的重发分支稳定返回
     * `无效的核查身份证!您确定此ID是正确的？`，即使账号刚注册、验证码还没过期也一样。
     * 把网页版的参数（`submit_action=resend` / `submit_confirm` / `email` 等）
     * 原样打到这个接口上**同样失败**，所以不是缺参数的问题，而是服务端分支坏了。
     *
     * **保留此方法**（用户明确要求保留 App `/confirm` 接口），但生产路径**不要调用**；
     * 重发验证信走 [WebConfirmClient]（网页版 `/confirm` + `submit_action=resend`）。
     */
    suspend fun resendSignUpCode(username: String, password: String): EditResultDto =
        postDecrypted(
            path = "/confirm",
            fields = listOf(
                "username" to username.trim(),
                "password" to password,
            ),
            decode = { json -> ApiJson.decodeFromString(EditResultDto.serializer(), json) },
        )

    /**
     * `POST /forgot`：忘记密码（`docs/official-api-manga.md` §7.5，2026-10-25 实测）。
     *
     * 参数只有 `email`（**注册邮箱**，不是用户名——传用户名会回
     * `請輸入有效的電子EMAIL地址！`）。成功时服务端把「用户名 + 系统生成的新密码」
     * 发到该邮箱，并**当场作废旧密码**（实测：成功调用后原密码登录返回 401
     * `無效的用戶名和/或密碼！`）。
     *
     * 两条硬约束：
     * - **绝不重试**：每成功一次就再生成一个新密码，重放会把用户刚收到的那个也作废；
     * - 业务失败与 `/register` 同构：HTTP 200 + 信封 200，`data` 里是
     *   `{status:"fail", msg, errors:[…]}`，由 `AccountRepository` 按 `status` 判定。
     *
     * 成功形态（实测）：`{"type":"","msg":"用戶名和新密碼已通過電子EMAIL發送給您！","status":"ok","errors":""}`。
     */
    suspend fun forgotPassword(email: String): EditResultDto = postDecrypted(
        path = "/forgot",
        fields = listOf("email" to email.trim()),
        decode = { json -> ApiJson.decodeFromString(EditResultDto.serializer(), json) },
    )

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 请求 → 取 `data` → AES 解密 → 反序列化。
     *
     * `ts` 在**进入重试之前**确定一次，重试闭包复用它 —— 这是能解密的前提。
     *
     * `lang` 也在这里统一补（[withLanguage]）：官方 App 的 `fetchGet` 会给每个 GET
     * 请求自动追加语言参数（文档 §2.4 / §2.6），所以本工程的所有接口都带它，
     * 各接口方法只写自己真正的业务参数。
     *
     * 「发请求 + 解析 JSON 信封」都在重试动作里：线路抖动时接口可能返回**空体**或
     * **非 JSON**（HTML 错误页），重发一次有机会拿到正确响应（见 [parseEnvelope]）。
     * 解密与字段解析留在循环外——那两类失败重试拿不到不同结果。
     */
    private suspend fun <T> getDecrypted(
        path: String,
        params: List<Pair<String, String>>,
        retry: Int,
        decode: (String) -> T,
    ): T {
        val ts = nowSeconds()
        // 签名方案与解密方案必须同步取一次：两者都由 ts 派生，取两次可能拿到不同方案
        val scheme = signatureSchemeProvider()
        val headers = Crypto.headerParams(ts, scheme)
        val url = buildUrl("https://$apiDomain$path", withLanguage(params))

        val encrypted = withTimeoutRetry(
            maxRetry = retry,
            retryOn = { it.isRetryableApiError() },
            onRetry = { attempt, total, cause ->
                Timber.tag("jm").d("第 %d/%d 次重试 %s（%s）", attempt, total, path, cause.javaClass.simpleName)
            },
        ) {
            parseEnvelope(url, execute(url, headers.token, headers.tokenParam))
        }

        if (encrypted.isNullOrBlank()) {
            throw NetworkError.Business("接口返回的 data 字段为空")
        }

        val plain = runCatching { Crypto.decryptData(ts, encrypted, scheme) }.getOrElse { e ->
            // 解密失败通常意味着 ts/密钥/接口版本不匹配，重试没有意义
            throw NetworkError.Business("响应解密失败，可能是接口版本已变更：${e.message}")
        }
        return runCatching { decode(plain) }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应字段与预期不符：${e.message}")
        }
    }

    /**
     * 给请求参数补上 `lang`（设置页「语言 → 接口语言」）。
     *
     * 逐字对齐官方 `HttpUtil.fetchGet` 的两条规则：
     * 1. 调用方**自己传了** `lang` 就不动它（"若没有才补"）；
     * 2. 补的值是当前语言（官方是 `localStorage.lang`，本工程是设置项，默认简中）。
     *
     * `lang` 排在参数表**最后**，也与官方一致（`searchParams.set` 追加在末尾）。
     * 服务端对参数顺序不敏感，这里只是让抓包对比官方请求时少一处差异。
     */
    private fun withLanguage(params: List<Pair<String, String>>): List<Pair<String, String>> =
        if (params.any { it.first == "lang" }) params
        else params + ("lang" to languageProvider().code)

    /**
     * 账号类只读接口的防缓存参数（设置页「高级 → 账号接口缓存」）。
     *
     * 开启（默认）时原样返回；关闭时补 `t=<秒级时间戳>`，让服务端按 URL 缓存的键每次都不同。
     * 只给带账号态的接口用（`/favorite`、`/useredit`、`/album`），
     * 其它公开接口（搜索、章节、分类等）保持官方形态。
     */
    private fun withAccountCacheBuster(
        params: List<Pair<String, String>>,
    ): List<Pair<String, String>> =
        if (accountCacheProvider()) params else params + ("t" to nowSeconds().toString())

    /**
     * 响应体 → JSON 信封的 `data` 字段。
     *
     * 空体与非法 JSON 一律按**可重试**的内容异常抛（[NetworkError.Malformed.retryable]）：
     * 这两类多半是线路抖动时服务端回了个非预期响应，重发一次比直接把错误抛给用户划算。
     */
    private fun parseEnvelope(url: String, body: String): String? {
        if (body.isBlank()) {
            throw NetworkError.Malformed(url, "响应为空", retryable = true)
        }
        return runCatching {
            ApiJson.decodeFromString(ApiEnvelope.serializer(), body).data
        }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应不是合法 JSON：${e.message}", retryable = true)
        }
    }

    /** 发一次 GET 并读响应体。超时抛 [NetworkError.Timeout]，供 `withTimeoutRetry` 判定。 */
    private suspend fun execute(url: String, token: String, tokenParam: String): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("token", token)
                .header("tokenparam", tokenParam)
                // 参考实现第 91 行的固定 UA，必须带；OkHttp 的默认 UA 会被拒
                .header("user-agent", Defaults.USER_AGENT)
            // 登录后才有：`/album` 的 liked/is_favorite、`/favorite` 等都靠它
            authProvider()?.let { auth ->
                builder.header("Authorization", "Bearer ${auth.jwt}")
                builder.header("Cookie", "AVS=${auth.avs}")
            }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw NetworkError.HttpStatus(url, response.code)
                    }
                    response.body?.string() ?: throw NetworkError.Malformed(url, "响应体为空")
                }
            } catch (e: IOException) {
                // 先按"是否超时"分类：只有超时才允许重试（参考实现语义）
                throw if (e.isNetworkTimeout()) NetworkError.Timeout(url, e)
                else NetworkError.from(url, e)
            }
        }

    /** 一次 POST 的原始结果：HTTP 状态 + 这次请求用的 `ts` + 响应体（解密要用 `ts`）。 */
    private class RawApiResponse(val http: Int, val ts: Long, val body: String)

    /**
     * 发一次 `multipart/form-data` POST，**原样**返回响应体（不解密、不判 `code`）。
     *
     * **故意不重试**：登录、收藏、发留言都不是幂等操作；`POST /favorite` 更是切换语义，
     * 重放一次等于把刚加上的收藏又取消。失败一律抛给 UI，由用户自己点重试。
     */
    private suspend fun postRaw(path: String, fields: List<Pair<String, String>>): RawApiResponse =
        withContext(Dispatchers.IO) {
            val ts = nowSeconds()
            val headers = Crypto.headerParams(ts, signatureSchemeProvider())
            val url = "https://$apiDomain$path"
            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply { fields.forEach { (k, v) -> addFormDataPart(k, v) } }
                .build()
            val builder = Request.Builder()
                .url(url)
                .header("token", headers.token)
                .header("tokenparam", headers.tokenParam)
                .header("user-agent", Defaults.USER_AGENT)
                .post(form)
            authProvider()?.let { auth ->
                builder.header("Authorization", "Bearer ${auth.jwt}")
                builder.header("Cookie", "AVS=${auth.avs}")
            }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        throw NetworkError.Malformed(url, "响应为空", retryable = true)
                    }
                    RawApiResponse(response.code, ts, body)
                }
            } catch (e: IOException) {
                throw if (e.isNetworkTimeout()) NetworkError.Timeout(url, e)
                else NetworkError.from(url, e)
            }
        }

    /**
     * POST + 信封校验 + AES 解密 + 反序列化（与 [getDecrypted] 同一套解密规则，但没有重试）。
     *
     * 三类失败分开抛：
     * - 401 → [NetworkError.HttpStatus]（由账号层翻译成"登录已失效"）；
     * - 信封 `code != 200` → [NetworkError.Business]（`errorMsg`/`msg` 原文）；
     * - 字段解析失败 → [NetworkError.Malformed]。
     */
    private suspend fun <T> postDecrypted(
        path: String,
        fields: List<Pair<String, String>>,
        decode: (String) -> T,
    ): T {
        val raw = postRaw(path, fields)
        val url = "https://$apiDomain$path"
        val env = runCatching { ApiJson.decodeFromString(AccountEnvelopeDto.serializer(), raw.body) }
            .getOrNull()
            ?: throw NetworkError.Malformed(url, "响应不是合法 JSON 信封", retryable = true)
        if (raw.http == 401 || env.code == 401) throw NetworkError.HttpStatus(url, 401)
        if (raw.http !in 200..299) throw NetworkError.HttpStatus(url, raw.http)
        val message = env.errorMsg?.takeIf { it.isNotBlank() } ?: env.msg?.takeIf { it.isNotBlank() }
        if (env.code != 200) {
            throw NetworkError.Business(message ?: "接口返回错误（code=${env.code}）")
        }
        val encrypted = env.data
        if (encrypted.isNullOrBlank()) {
            throw NetworkError.Business(message ?: "接口返回的 data 为空")
        }
        val plain = runCatching { Crypto.decryptData(raw.ts, encrypted, signatureSchemeProvider()) }
            .getOrElse { throw NetworkError.Business("响应解密失败，可能是接口版本已变更：${it.message}") }
        return runCatching { decode(plain) }.getOrElse { e ->
            throw NetworkError.Malformed(url, "响应字段与预期不符：${e.message}")
        }
    }

    /**
     * `var scramble_id = 220980;` → 220980。
     *
     * 拿不到时抛**可重试**的内容异常（[NetworkError.Malformed.retryable]）——多半是线路
     * 抖动回了错误页；重试次数由调用方 [scrambleId] 控制，耗尽后由它兜底。
     */
    internal fun parseScrambleId(url: String, html: String): Long {
        val marker = "var scramble_id = "
        val start = html.indexOf(marker)
        if (start < 0) {
            throw NetworkError.Malformed(url, "响应里没有 $marker", retryable = true)
        }
        val value = html.substring(start + marker.length)
            .substringBefore(';')
            .trim()
        return value.toLongOrNull()
            ?: throw NetworkError.Malformed(url, "scramble_id 不是数字：$value", retryable = true)
    }

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        if (params.isEmpty()) return base
        val query = params.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return "$base?$query"
    }

    /**
     * 与 Hutool `HttpUtil.urlWithForm(..., false)` 一致的查询串编码：
     * 空格用 `%20`（不是 `+`）。JM 的搜索关键字里可能有空格与中文，必须一致。
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** 供 UI 展示的线路信息（设置页的"接口线路"）。 */
    fun currentApiDomain(): String = apiDomain
}
