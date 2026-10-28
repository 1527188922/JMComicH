package com.haruhi.jmcomic.core.di

import android.content.Context
import com.haruhi.jmcomic.BuildConfig
import com.haruhi.jmcomic.core.account.AccountSession
import com.haruhi.jmcomic.core.account.AccountStore
import com.haruhi.jmcomic.core.database.JmComicDatabase
import com.haruhi.jmcomic.core.network.NetworkClientFactory
import com.haruhi.jmcomic.core.network.NetworkMonitor
import com.haruhi.jmcomic.core.protocol.ApiLanguage
import com.haruhi.jmcomic.core.protocol.Defaults
import com.haruhi.jmcomic.core.protocol.LineCatalog
import com.haruhi.jmcomic.core.settings.SecretStore
import com.haruhi.jmcomic.core.settings.SettingDefaults
import com.haruhi.jmcomic.core.settings.SettingKeys
import com.haruhi.jmcomic.core.settings.SettingsRepository
import com.haruhi.jmcomic.core.storage.LibraryDirs
import com.haruhi.jmcomic.core.storage.OfficialZipPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.io.File

/**
 * 手写依赖注入容器（P0 决策 D-3）。
 *
 * 为什么不用 Hilt：AGP 9 的内置 Kotlin 禁止第三方 Gradle 插件用
 * `kotlin.sourceSets` 添加生成目录，Hilt 的 Gradle 插件正是这么做的，应用即失败；
 * KSP 2.3.x 已经改用 Variant API 所以可用，但 Hilt 不行。详见 `docs/p0/README.md`。
 *
 * 手写容器的代价是没有编译期注入校验；收益是**依赖关系完全显式**、构建可用、
 * 没有代码生成。本工程的依赖图很浅（设置 → 网络 → 仓库 → ViewModel），规模可控。
 *
 * 生命周期：由 `JmComicApp` 创建一次，存活到进程结束。
 * **每个模式的 OkHttp 客户端是懒加载 + 可失效的**：
 * 计划 §9.2 要求「线程数、超时、线路等对新任务或下次重试生效，不中途替换运行任务配置」，
 * 所以改设置后调 [invalidateNetworkClients] 重建客户端，已在跑的任务继续用旧实例。
 */
class AppContainer(private val appContext: Context) {

    init {
        // pdfbox-android 需要在生成 PDF 之前用真实 Context 初始化一次资源加载器
        // （字体/ICC 等从 APK 资源里读）。放在容器构造时做，保证早于任何导出。
        runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(appContext)
        }.onFailure { Timber.tag("pdf").w(it, "PDFBoxResourceLoader 初始化失败") }
    }

    /**
     * 应用级协程作用域（进程存活期）。
     *
     * 下载任务必须活过任何页面：用户提交下载后可以退出详情页、切到别的 tab，
     * 任务不能跟着 ViewModel 一起被取消。所以执行器挂在这个作用域上，
     * 由前台服务负责"让进程别被杀"。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------- 存储 ----------------

    /**
     * 本地目录规划。
     *
     * debug 与 release 的 `applicationId` 不同（`.debug` 后缀），所以两者的 `filesDir`
     * 天然隔离，这里不需要再靠目录名区分。
     */
    val libraryDirs: LibraryDirs = LibraryDirs(
        File(appContext.filesDir, LibraryDirs.LIBRARY_DIR_NAME),
    ).ensureAll()

    val database: JmComicDatabase by lazy { JmComicDatabase.create(appContext) }

    val settings: SettingsRepository by lazy {
        SettingsRepository.create(appContext).also { repository ->
            // 旧「全局作用域」的值一次性搬进漫画作用域（全新安装是空操作）。
            // **同步跑完再交出仓库**：网络客户端、下载环境都在后面按同一份偏好读取，
            // 异步迁移会和它们抢跑，让"升级后第一眼看到的还是旧值"。
            runBlocking {
                repository.migrateLegacyGlobalScope(secrets)
                // 合并前的四个「按接口分开的重试次数」→ 一个「请求重试次数」
                repository.migrateLegacyRetryKeys()
            }
        }
    }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    // ---------------- 账号（JM 会员） ----------------

    /**
     * 账号凭证与资料快照的落盘。
     *
     * 凭证（`jwttoken` / `s`）走 [SecretStore]（Keystore AES-GCM），资料快照走 DataStore；
     * **不保存密码**（官方把账号密码明文存 localStorage 用来静默重登，这里不这么做）。
     */
    val accountStore: AccountStore by lazy { AccountStore(settings, secrets) }

    /**
     * 账号会话（进程内唯一真相源）。
     *
     * `ApiClient.authProvider`、「我的」页、账号页都读它；首页`by lazy` 触发时
     * 会同步读一次资料快照（一个很小的字符串），保证第一帧就能显示昵称/头像。
     */
    val accountSession: AccountSession by lazy { AccountSession(accountStore) }

    /** 账号数据入口（登录/退出/收藏/资料）；UI 不直接碰 `ApiClient`。 */
    val accountRepository: com.haruhi.jmcomic.feature.data.AccountRepository by lazy {
        com.haruhi.jmcomic.feature.data.AccountRepository(
            api = { jmApi },
            session = accountSession,
            // 退出登录挂在应用级作用域：个人资料页点完就弹回上一页，不能被页面级作用域带走
            scope = appScope,
            // 收藏页/资料页进页面先用上次的快照渲染，避免每次重建 ViewModel 都重新联网而"闪一下"
            cache = accountCache,
            // 重发验证信走网页版（App API 的 /confirm 重发分支是坏的，见 WebConfirmClient）
            webConfirm = webConfirmClient,
        )
    }

    /**
     * 网页版「重发验证信」客户端。
     *
     * 与接口请求**分开一个派生客户端**：
     * - 网页版 `/confirm` 返回的是带广告的 HTML（实测 ~180KB），可能比接口慢；
     *   而接口默认超时只有 3 秒，直接用 `httpClient` 会频繁超时；
     * - 仍从 `httpClient` 派生：代理 / UA / 拦截器与 App 保持一致，只放宽超时。
     */
    val webConfirmClient: com.haruhi.jmcomic.feature.api.WebConfirmClient by lazy {
        com.haruhi.jmcomic.feature.api.WebConfirmClient(
            client = com.haruhi.jmcomic.core.network.NetworkClientFactory.deriveWithTimeout(
                base = httpClient,
                timeoutSeconds = WEB_CONFIRM_TIMEOUT_SECONDS,
                callTimeoutSeconds = WEB_CONFIRM_CALL_TIMEOUT_SECONDS,
            ),
        )
    }

    /**
     * 账号数据的本地缓存（挂在容器上，跟着进程走）。
     *
     * 只缓存"列表快照"这类展示数据：登录/退出时清空，进程结束自然消失；
     * 收藏夹名单另外落盘到 DataStore（见 `feature/data/AccountCache.kt` 的注释）。
     */
    val accountCache: com.haruhi.jmcomic.feature.data.AccountCache by lazy {
        com.haruhi.jmcomic.feature.data.AccountCache(settings)
    }

    /**
     * 当前接口语言（请求里的 `lang`）的**热流**。
     *
     * 直接由偏好存储派生：设置页写一次，这里就自动更新——
     * - 接口请求（[jmApi]）与线路测速（[apiLineProbe]）随时取 `.value`，不用每次读存储；
     * - 分类页订阅它，语言一变就按新语言重拉分类树与列表
     *   （还留在导航栈里的分类页不会自己重建，不清就会继续显示上一门语言的分类名）。
     *
     * 初始值用同步读到的真实值（不是写死的默认值）：这样 `drop(1)` 的订阅方
     * 收到的第一项一定是"当前语言"，不会把启动时的初始值误判成一次切换。
     */
    val apiLanguage: StateFlow<ApiLanguage> by lazy {
        settings.jmString(SettingKeys.Jm.API_LANGUAGE, ApiLanguage.DEFAULT.code)
            .map { ApiLanguage.fromCode(it) }
            .stateIn(
                scope = appScope,
                started = SharingStarted.Eagerly,
                initialValue = runBlocking { settings.resolveJmApiLanguage() },
            )
    }

    // ---------------- 下载 ----------------

    /**
     * 下载引擎。**每次取用都会按当前网络设置新建**：
     * 计划 §9.2 要求"线程数、超时、线路等对新任务或下次重试生效"，
     * 所以引擎不缓存客户端，而是持有 [imageHttpClient] 的引用。
     *
     * 「失败后再试几次」也做成现读的闭包（设置页「高级 → 图片加载/下载失败重试次数」）：
     * 引擎要的是总尝试次数，所以 `+1`（0 次重试 = 只尝试一次）。
     */
    val fileDownloader: com.haruhi.jmcomic.core.download.FileDownloader by lazy {
        com.haruhi.jmcomic.core.download.FileDownloader(
            client = imageHttpClient,
            dirs = libraryDirs,
            attemptsProvider = { settings.resolveJmImageRetry() + 1 },
        )
    }

    /** 持久化下载任务仓库。 */
    val downloadTasks: com.haruhi.jmcomic.core.download.DownloadTaskRepository by lazy {
        com.haruhi.jmcomic.core.download.DownloadTaskRepository(
            dao = database.downloadTaskDao(),
            dirs = libraryDirs,
        )
    }

    /** 网络状况监视器（下载闸门与"等待网络"状态的数据源）。 */
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    /**
     * JM 章节图片下载的编排器。
     *
     * 挂在 [appScope] 上而不是任何 ViewModel 上：用户提交下载后可以离开页面，
     * 任务必须继续跑；进程被杀后由 `JmComicApp` 的恢复流程重新拉起。
     */
    val jmDownloads: com.haruhi.jmcomic.feature.data.DownloadManager by lazy {
        com.haruhi.jmcomic.feature.data.DownloadManager(
            repo = jmRepository,
            tasks = downloadTasks,
            downloader = com.haruhi.jmcomic.core.download.ParallelImageDownloader(
                downloader = fileDownloader,
            ),
            dirs = libraryDirs,
            environment = jmDownloadEnvironment,
            scope = appScope,
            fileDownloader = fileDownloader,
            officialZipApi = { chapterId -> jmApi.albumDownload(chapterId) },
            officialZipPublisher = officialZipPublisher,
            onTaskStarted = {
                // 长下载必须有前台服务，否则息屏后会被系统掐掉（计划 §8）
                com.haruhi.jmcomic.core.download.DownloadForegroundService.ensureRunning(appContext)
            },
        )
    }

    /**
     * 执行下载时读取的环境参数。
     *
     * **每次调用都重新读设置**：计划 §9.2 要求"线程数、线路、网络策略对新任务或
     * 下次重试生效，不中途替换运行任务配置"。所以这里是 `suspend` 读取而不是快照。
     */
    private val jmDownloadEnvironment: com.haruhi.jmcomic.feature.data.DownloadEnvironment =
        object : com.haruhi.jmcomic.feature.data.DownloadEnvironment {

            override suspend fun concurrency(): Int {
                // 「自动」这一档已取消（用户 2026-10-15）：滑杆就是 1～12 的数值，默认 4。
                // 老版本存下的 0 由 normalizeDownloadThreads 按默认值 4 处理，不会掉成 1。
                val configured = settings.jmInt(SettingKeys.Jm.DOWNLOAD_THREADS, SettingDefaults.Jm.DOWNLOAD_THREADS).first()
                return SettingDefaults.Jm.normalizeDownloadThreads(configured)
            }

            override suspend fun imageDomain(): String =
                LineCatalog.imageLineOf(settings.jmString(SettingKeys.Jm.IMAGE_DOMAINS).first()).host

            override suspend fun officialZipConcurrency(): Int {
                val configured = settings.jmInt(
                    SettingKeys.Jm.OFFICIAL_ZIP_CONCURRENCY,
                    SettingDefaults.Jm.OFFICIAL_ZIP_CONCURRENCY,
                ).first()
                return SettingDefaults.Jm.normalizeOfficialZipConcurrency(configured)
            }

            override suspend fun allowsTransfer(metered: Boolean): Boolean {
                val policy = settings.resolveJmNetwork(secrets).downloadNetwork
                return com.haruhi.jmcomic.core.download.DownloadGate.of(policy, metered)
                    .allowNewTransfer()
            }

            override val networkState = networkMonitor.state

            // 「等待网络」期间的兜底重读：漏掉系统回调时由下载执行器周期调用
            override fun refreshNetwork() = networkMonitor.refresh()
        }

    /** 阅读位置与章节清单查询（阅读器与"继续阅读"入口）。 */
    val jmReading: com.haruhi.jmcomic.feature.data.ReadingRepository by lazy {
        com.haruhi.jmcomic.feature.data.ReadingRepository(
            positionDao = database.playbackPositionDao(),
            chapterDao = database.chapterDao(),
        )
    }

    /**
     * 详情页评论的**内存缓存**（按 jm 号区分，用户要求）。
     *
     * 放在容器里而不是 ViewModel 里：详情页退出再进来会换一个 ViewModel，
     * 缓存挂在容器上才能跨"进出页面"存活；而容器跟着进程走，所以退出 App 自然清空，
     * [handleAppExit] 里还会再显式清一次（进程留在后台的情况）。
     */
    val jmCommentCache: com.haruhi.jmcomic.feature.data.CommentCache by lazy {
        com.haruhi.jmcomic.feature.data.CommentCache()
    }

    /**
     * 分类页的**内存缓存**：分类树 + 每个（主分类 / 二级分类 / 排序）已加载的列表。
     *
     * 用户要求：分类树**只在打开 App 后第一次进分类页时加载一次**；漫画列表也要缓存，
     * 切到别的页面再切回来还得是上次那批。挂在容器上（而不是 ViewModel 上）才能跨
     * "分类页被移出导航栈 / 页面重建"存活；容器跟进程走，退出 App 自然清空，
     * [handleAppExit] 里还会再显式清一次。
     */
    val jmCategoryCache: com.haruhi.jmcomic.feature.data.CategoryCache by lazy {
        com.haruhi.jmcomic.feature.data.CategoryCache()
    }

    // ---------------- JM ----------------

    /**
     * JM 接口客户端。
     *
     * **每次取用都重新构造**：它只持有 OkHttp 客户端与几个"读设置"的闭包，
     * 构造成本可以忽略；而这样能保证改完设置（线路、重试次数、超时）后
     * 新任务立刻用上新值（计划 §9.2 的生效时机要求）。
     */
    val jmApi: com.haruhi.jmcomic.feature.api.ApiClient
        get() = com.haruhi.jmcomic.feature.api.ApiClient(
            client = httpClient,
            config = { runBlocking { settings.resolveJmNetwork(secrets) } },
            // 所有接口共用同一项重试次数（设置页「高级 → 请求重试次数」）
            retryProvider = { runBlocking { settings.resolveJmRequestRetry() } },
            // 账号只读接口是否复用服务端缓存（设置页「高级 → 账号接口缓存」，默认开启）
            accountCacheProvider = { runBlocking { settings.resolveJmAccountApiCache() } },
            apiDomainProvider = { runBlocking { jmApiDomain() } },
            // 接口语言（`lang`）：取容器的热流，切完语言新请求立刻生效
            languageProvider = { apiLanguage.value },
            // 登录态：登录/退出后新请求立刻带上/去掉 Authorization 与 Cookie
            authProvider = { accountSession.authHeaders() },
        )

    /**
     * 接口线路：取设置里选中的那一条；没设置过（或刚「恢复默认」）时用工程当前的默认线路。
     *
     * 线路清单是硬编码的（`core/protocol/LineCatalog.kt`），设置页只能从清单里选，
     * 所以这里拿到的永远是清单里的域名；老版本手填的历史值也原样沿用
     * （`LineCatalog.apiLineOf` 会把清单外的域名当成"当前线路"）。
     */
    private suspend fun jmApiDomain(): String = LineCatalog
        .apiLineOf(settings.jmString(SettingKeys.Jm.API_DOMAINS).first())
        .host

    /**
     * 线路测速（设置页「线路 → 全部测速」用）。
     *
     * 客户端取 [httpClient] 的派生：代理 / UA / 拦截器与其它请求一致，只有超时是测速
     * 自己的固定值（见 `ApiLineProbe.DEFAULT_TIMEOUT_SECONDS`）。**每次取用重新派生**，
     * 所以改完超时 / 代理后重启应用即生效，和接口请求的生效时机一致。
     *
     * 探针里的 `lang` 跟当前接口语言走（官方测速也是带 `localStorage.lang` 打的）。
     */
    val apiLineProbe: com.haruhi.jmcomic.feature.api.ApiLineProbe
        get() = com.haruhi.jmcomic.feature.api.ApiLineProbe(
            clientProvider = { httpClient },
            languageProvider = { apiLanguage.value },
        )

    /**
     * 图片线路测速（设置页「线路 → 图片线路 → 全部测速」用）。
     *
     * 与 [apiLineProbe] 同理，只是派生自 [imageHttpClient]（图片请求的超时 / 拦截器那一条），
     * 并且**每条线路现派生一次**：探针会换上全新的空连接池，让 13 条线路都是冷连接
     * （见 `ImageLineProbe.probeClient`）。
     */
    val imageLineProbe: com.haruhi.jmcomic.feature.api.ImageLineProbe
        get() = com.haruhi.jmcomic.feature.api.ImageLineProbe(clientProvider = { imageHttpClient })

    /** JM 仓库（在线数据 ↔ 本地库）。 */
    val jmRepository: com.haruhi.jmcomic.feature.data.Repository by lazy {
        com.haruhi.jmcomic.feature.data.Repository(
            api = jmApi,
            albumDao = database.albumDao(),
            chapterDao = database.chapterDao(),
            favoriteDao = database.favoriteDao(),
            recentDao = database.recentAlbumDao(),
            searchHistoryDao = database.searchHistoryDao(),
            dirs = libraryDirs,
            positionDao = database.playbackPositionDao(),
        )
    }

    /**
     * 章节图片的本地优先取图器（**阅读器与下载任务共用**）。
     *
     * 阅读器遇到磁盘上没有的图会直接联网取回来并落盘，用户不必先下载再看；
     * 下载任务每张图也走同一个"文件在不在"的判断，所以"一边看一边下"不冲突。
     */
    val jmImageStore: com.haruhi.jmcomic.feature.data.ImageStore by lazy {
        com.haruhi.jmcomic.feature.data.ImageStore(
            repo = jmRepository,
            downloader = fileDownloader,
            dirs = libraryDirs,
            environment = jmDownloadEnvironment,
        )
    }

    // ---------------- 导出（P4） ----------------

    /** ZIP 打包器（zip4j，支持 AES 或不加密）。 */
    val archiveWriter: com.haruhi.jmcomic.core.codec.ArchiveWriter by lazy {
        com.haruhi.jmcomic.core.codec.ArchiveWriter()
    }

    /** PDF 生成器（pdfbox-android；超长图按高度切片）。 */
    val pdfWriter: com.haruhi.jmcomic.core.codec.PdfWriter by lazy {
        // scratchDir 指向 libraryDirs.tmp：PDFBox 的临时文件（PDFBox*.tmp）落在既有的
        // "可随时整体清理"临时目录里，能被 clearCache() 等半成品清理逻辑一并覆盖。
        // 图片流写磁盘而不是攒在堆内存里，避免"图片太多 → OOM 闪退"。见 PdfWriter.memoryUsage()。
        com.haruhi.jmcomic.core.codec.PdfWriter(scratchDir = libraryDirs.tmp)
    }

    /** 成品交付：API 29+ 走 MediaStore 的下载目录，老系统退到应用外部私有目录。 */
    val exportPublisher: com.haruhi.jmcomic.core.storage.ExportPublisher by lazy {
        com.haruhi.jmcomic.core.storage.AndroidExportPublisher(appContext)
    }

    /** 官方 ZIP 落盘：`Download/JMComicH/官方zip/<本子>/<第几话>.zip`。 */
    val officialZipPublisher: OfficialZipPublisher by lazy {
        OfficialZipPublisher(appContext)
    }

    /** 导出编排（复用下载任务表，进度进任务页）。 */
    val jmExports: com.haruhi.jmcomic.feature.data.ExportManager by lazy {
        com.haruhi.jmcomic.feature.data.ExportManager(
            repo = jmRepository,
            tasks = downloadTasks,
            dirs = libraryDirs,
            archiveWriter = archiveWriter,
            pdfWriter = pdfWriter,
            publisher = exportPublisher,
            scope = appScope,
            // 导出时解扰后的编码方式（设置页「导出 → 导出图片压缩」，默认有损）
            imageEncodingProvider = { settings.resolveJmImageEncoding().toEncodeMode() },
            onTaskStarted = {
                com.haruhi.jmcomic.core.download.DownloadForegroundService.ensureRunning(appContext)
            },
        )
    }

    // ---------------- 关于页（版本更新 / 存储统计） ----------------

    /** 系统下载（DownloadManager）的唯一收口：enqueue 打用途标记、records 按标记找回。 */
    val appDownloadGateway: com.haruhi.jmcomic.core.download.AppDownloadGateway by lazy {
        com.haruhi.jmcomic.core.download.AppDownloadGateway(appContext)
    }

    /**
     * Gitee 最新发行版检查（「关于」页）。从 [httpClient] 派生：代理 / 拦截器与
     * 其它请求一致，只放宽超时——Gitee 的响应不比 JM 接口快，默认 3 秒太紧
     * （与 [webConfirmClient] 同一模式）。
     */
    val releaseChecker: com.haruhi.jmcomic.feature.api.ReleaseChecker by lazy {
        com.haruhi.jmcomic.feature.api.ReleaseChecker(
            com.haruhi.jmcomic.core.network.NetworkClientFactory.deriveWithTimeout(
                base = httpClient,
                timeoutSeconds = RELEASE_CHECK_TIMEOUT_SECONDS,
                callTimeoutSeconds = RELEASE_CHECK_CALL_TIMEOUT_SECONDS,
            ),
        )
    }

    /** 「关于」页的本地存储统计：六类占用（图片库 / 封面 / 导出 zip/pdf / 官方 ZIP / 阅读缓存）。 */
    val storageStatsCollector: com.haruhi.jmcomic.feature.data.StorageStatsCollector by lazy {
        com.haruhi.jmcomic.feature.data.StorageStatsCollector(
            repo = jmRepository,
            dirs = libraryDirs,
            exports = exportPublisher,
            officialZips = officialZipPublisher,
        )
    }

    // ---------------- 退出收尾 ----------------

    /**
     * 退出应用时的统一收尾（用户拍板的行为）。
     *
     * - **漫画图片下载 → 暂停**：退出后不继续跑，再进入 App 时任务显示"已暂停"，
     *   用户点"继续"从断点续传；
     * - **zip/pdf 导出 → 直接删除**：导出不能续做，任务行与未完成的中转文件一起清掉，
     *   用户回来重新从详情页点一次导出即可（也顺带解决了"旧任务占着
     *   同 `(type, bizKey)` 的去重名额、再点导出被挡住"的问题）。
     *
     * 两个退出触发点共用这里：`DownloadForegroundService.onTaskRemoved`（划掉最近任务）
     * 与 `MainActivity.onDestroy`（返回键退出）。两者可能同时被调用，所以**必须幂等**：
     * 暂停/删除都只对"还活跃的任务"动手，重复执行没有副作用。
     *
     * 顺序：先把任务状态写进数据库，再停前台服务撤通知——服务里的通知订阅的就是任务表，
     * 服务先停的话通知可能停在"下载中"的旧快照上。进程若在写库之前就被杀死，
     * 下次启动的 `DownloadTaskRepository.recoverInterrupted` 兜底会落到同一结果。
     */
    fun handleAppExit() {
        appScope.launch {
            runCatching { jmExports.abortInProgressForExit() }
                .onFailure { Timber.tag("app").w(it, "退出时清理导出任务失败") }
            runCatching { jmDownloads.pauseAllForExit() }
                .onFailure { Timber.tag("app").w(it, "退出时暂停下载任务失败") }
            // 新版本 APK 的更新下载：退出即取消（半成品文件由系统连记录一起清掉），
            // **已完成的不动**——成品要留给"下次检查显示安装按钮"。
            // 与漫画下载的"暂停"不同：DownloadManager 没有暂停/恢复，取消即从零重来。
            runCatching {
                appDownloadGateway.cancelIncomplete(
                    com.haruhi.jmcomic.core.download.AppDownloadGateway.MARKER_APP_UPDATE,
                )
            }.onFailure { Timber.tag("app").w(it, "退出时取消更新下载失败") }
            // 评论缓存是"退出 App 即清空"的（用户要求）：正常退出时顺手清掉，
            // 免得进程还留在后台（下载/导出把进程续着）再被点开时又看到上次的评论。
            runCatching { jmCommentCache.clearAll() }
                .onFailure { Timber.tag("app").w(it, "退出时清理评论缓存失败") }
            // 分类页的分类树与列表同样是"退出 App 即清空"（用户要求）：
            // 下次进来重新拉一次分类树，不会看到上次的分类/排序选择。
            runCatching { jmCategoryCache.clear() }
                .onFailure { Timber.tag("app").w(it, "退出时清理分类缓存失败") }
            com.haruhi.jmcomic.core.download.DownloadForegroundService.stop(appContext)
        }
    }

    /**
     * 当前图片线路（图片 CDN 域名）的**热流**。
     *
     * 设置页「线路 → 图片线路」选中的那条，封面等图片 URL 都按它拼接。
     * 做成热流是为了让已经在界面上的封面跟着设置立刻换线路（见 `ModeRoot` 的
     * `LocalImageDomain`），而不是等应用重启。
     */
    val imageDomain: StateFlow<String> by lazy {
        settings.jmString(SettingKeys.Jm.IMAGE_DOMAINS)
            .map { LineCatalog.imageLineOf(it).host }
            .stateIn(
                scope = appScope,
                started = SharingStarted.Eagerly,
                initialValue = LineCatalog.imageLineOf(
                    runBlocking { settings.jmString(SettingKeys.Jm.IMAGE_DOMAINS).first() },
                ).host,
            )
    }

    /**
     * 隐私模式（所有漫画封面模糊显示）的**热流**。
     *
     * 设置页的 Switch 与全应用双指手势写的是同一个 key，所以做成热流：
     * 任意一个入口改完，已经在界面上的封面立刻跟着变。
     *
     * 初始值**同步**读一次（与 [imageDomain] 同样的理由）：如果上次退出时是开启状态，
     * 异步读会让第一帧先画出清晰封面，隐私模式下这是不可接受的。
     */
    val privacyMode: StateFlow<Boolean> by lazy {
        val flow = settings.jmBoolean(SettingKeys.Jm.PRIVACY_MODE, SettingDefaults.Jm.PRIVACY_MODE)
        flow.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = runBlocking { flow.first() },
        )
    }

    /**
     * 写入隐私模式：设置页 Switch 与全局双指手势共用这一个入口。
     *
     * 写盘挂在 [appScope]：手势可能发生在任何页面，不能跟着某个页面的作用域被取消。
     */
    fun setPrivacyMode(enabled: Boolean) {
        // 手势会在任何页面反复触发：状态没变就不写盘，避免无意义的 DataStore 写入。
        if (privacyMode.value == enabled) return
        appScope.launch {
            settings.putJmBoolean(SettingKeys.Jm.PRIVACY_MODE, enabled)
        }
    }

    /**
     * 文字隐私（隐藏书名）的**热流**。
     *
     * 独立于 [privacyMode] 存储：设置页里只有隐私模式开启时才显示这一项，
     * 但值本身要跨重启保留。实际是否生效由根节点按 `privacyMode && textPrivacy` 计算。
     */
    val textPrivacy: StateFlow<Boolean> by lazy {
        val flow = settings.jmBoolean(SettingKeys.Jm.TEXT_PRIVACY, SettingDefaults.Jm.TEXT_PRIVACY)
        flow.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = runBlocking { flow.first() },
        )
    }

    /** 写入文字隐私；默认开启，与「隐私模式」分开保存。 */
    fun setTextPrivacy(enabled: Boolean) {
        if (textPrivacy.value == enabled) return
        appScope.launch {
            settings.putJmBoolean(SettingKeys.Jm.TEXT_PRIVACY, enabled)
        }
    }

    /**
     * 封面缓存的逐条失效。
     *
     * 封面走 Coil 的磁盘缓存（`files/covers` 里是按 URL 哈希命名的文件），
     * 所以"删掉某一本的封面"只能按 key 失效，不能删文件名。
     * 内存缓存也一并清掉，否则刚删完还会从内存里显示出来。
     */
    private val coverCache: com.haruhi.jmcomic.feature.data.CoverCache =
        object : com.haruhi.jmcomic.feature.data.CoverCache {
            override fun evict(albumId: Long) {
                // 与 `Cover` 用同一条图片线路拼 URL，否则磁盘缓存里的那份删不到
                val url = Defaults.buildCoverUrl(albumId, imageDomain.value)
                runCatching {
                    imageLoader.diskCache?.remove(url)
                    imageLoader.memoryCache?.remove(coil3.memory.MemoryCache.Key(url))
                }.onFailure { Timber.tag("jm").w(it, "清理封面缓存失败：%d", albumId) }
            }
        }

    /** "删除本地文件 / 从书库移除"的统一编排（记录 + 图片 + 成品 + 封面 + 旧版阅读缓存）。 */
    val jmAlbumCleaner: com.haruhi.jmcomic.feature.data.AlbumCleaner by lazy {
        com.haruhi.jmcomic.feature.data.AlbumCleaner(
            repo = jmRepository,
            exports = jmExports,
            tasks = downloadTasks,
            coverCache = coverCache,
            // 旧版阅读缓存（reader-cache/<aid>）跟着这本一起删：删本地文件就是"别占我空间了"
            dirs = libraryDirs,
        )
    }

    /**
     * 封面图加载器。
     *
     * **磁盘缓存显式指向 [LibraryDirs.covers]（`files/covers`）**：
     * "加载过的封面优先读本地、没有才请求网络"就是 Coil 磁盘缓存的默认行为，
     * 不需要自己写一套文件缓存。缓存文件可被设置里的"清理缓存"整体清空。
     *
     * 网络层复用 JM 的 OkHttp（带正确的 UA 与代理设置）——
     * JM 的封面域名对 UA 有校验，用 Coil 默认的 OkHttp 会被拒。
     *
     * 用 [imageHttpClient]：封面属于"图片下载"，超时跟设置里的「图片下载超时」走。
     */
    val imageLoader: coil3.ImageLoader by lazy {
        coil3.ImageLoader.Builder(appContext)
            .components {
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { imageHttpClient },
                    ),
                )
            }
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(appContext, MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    // Coil 3 的 DiskCache 用 okio 的 Path，不是 java.io.File
                    .directory(libraryDirs.covers.toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    // ---------------- 网络 ----------------

    /** 一对客户端：超时值不同，其余（ConnectionPool / Dispatcher / 拦截器）共享。 */
    private class Clients(val api: OkHttpClient, val image: OkHttpClient)

    @Volatile
    private var clients: Clients? = null

    private val resolvedClients: Clients
        get() = clients ?: synchronized(this) {
            clients ?: buildClients().also { clients = it }
        }

    /**
     * 接口客户端：漫画详情 / 章节 / 搜索。超时取设置里的「请求超时」。
     */
    val httpClient: OkHttpClient
        get() = resolvedClients.api

    /**
     * 图片下载客户端：章节图片与封面。超时取设置里的「图片下载超时」。
     *
     * 由 [httpClient] 经 `newBuilder()` 派生，所以两个客户端共享连接池与线程池，
     * 构建逻辑也仍在 `NetworkClientFactory` 一处。
     */
    val imageHttpClient: OkHttpClient
        get() = resolvedClients.image

    /**
     * 设置变更后调用：丢掉缓存的客户端，让新任务用新配置。
     * 已在执行的任务持有旧实例的引用，不受影响（符合 §9.2 的生效时机要求）。
     *
     * **当前没有任何调用点**：超时与代理的生效时机是"重启应用"（设置页有对应提示），
     * 冷启动时按存储里的值构建即可。留着是为了将来若要改成即时生效时有落点。
     */
    fun invalidateNetworkClients() {
        synchronized(this) { clients = null }
    }

    /**
     * 按**同一份配置快照**构建两个客户端：代理 / UA / 日志配置必须一致，
     * 只有三个超时不同（接口一份、图片一份）。
     */
    private fun buildClients(): Clients {
        val config = runBlocking { settings.resolveJmNetwork(secrets) }
        val api = NetworkClientFactory.create(
            config = config,
            userAgent = Defaults.USER_AGENT,
            extraHeaders = emptyMap(),
            enableLogging = BuildConfig.DEBUG,
        )
        val image = NetworkClientFactory.deriveWithTimeout(api, config.imageTimeoutSeconds)
        return Clients(api, image)
    }

    companion object {
        /** 封面磁盘缓存上限：256MB 足够放下几万张缩略封面。 */
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024

        /** 封面内存缓存上限（占可用内存比例）。 */
        const val MEMORY_CACHE_PERCENT = 0.15

        /**
         * 网页版重发验证信的单次请求超时。
         *
         * 网页版 `/confirm` 会返回一整页带广告的 HTML（实测 ~180KB），比接口慢；
         * 接口默认 3 秒超时不够用。单条 host 最多 12 秒，整次重发由
         * `WebConfirmClient` 自己再封顶 30 秒。
         */
        const val WEB_CONFIRM_TIMEOUT_SECONDS = 12
        const val WEB_CONFIRM_CALL_TIMEOUT_SECONDS = 15L

        /** Gitee 版本检查的超时：响应是一份完整 JSON（含 assets 列表），比 JM 接口慢。 */
        const val RELEASE_CHECK_TIMEOUT_SECONDS = 15
        const val RELEASE_CHECK_CALL_TIMEOUT_SECONDS = 20L
    }
}
