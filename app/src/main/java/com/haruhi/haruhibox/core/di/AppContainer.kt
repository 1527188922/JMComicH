package com.haruhi.haruhibox.core.di

import android.content.Context
import com.haruhi.haruhibox.BuildConfig
import com.haruhi.haruhibox.core.database.HaruhiBoxDatabase
import com.haruhi.haruhibox.core.jm.JmDefaults
import com.haruhi.haruhibox.core.network.NetworkClientFactory
import com.haruhi.haruhibox.core.network.NetworkMonitor
import com.haruhi.haruhibox.core.settings.SecretStore
import com.haruhi.haruhibox.core.settings.SettingDefaults
import com.haruhi.haruhibox.core.settings.SettingKeys
import com.haruhi.haruhibox.core.settings.SettingsRepository
import com.haruhi.haruhibox.core.storage.LibraryDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
 * 生命周期：由 `HaruhiBoxApp` 创建一次，存活到进程结束。
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
     * 天然隔离，这里不需要再靠目录名区分（原先的 `LIBRARY_DIR_NAME` 因此已移除）。
     */
    val libraryDirs: LibraryDirs = LibraryDirs(
        File(appContext.filesDir, LIBRARY_DIR_NAME),
    ).ensureAll()

    val database: HaruhiBoxDatabase by lazy { HaruhiBoxDatabase.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    // ---------------- 下载 ----------------

    /**
     * 下载引擎。**每次取用都会按当前网络设置新建**：
     * 计划 §9.2 要求"线程数、超时、线路等对新任务或下次重试生效"，
     * 所以引擎不缓存客户端，而是持有 [jmHttpClient] / [biliHttpClient] 的引用。
     */
    val fileDownloader: com.haruhi.haruhibox.core.download.FileDownloader by lazy {
        com.haruhi.haruhibox.core.download.FileDownloader(
            client = jmHttpClient,
            dirs = libraryDirs,
        )
    }

    /** 带 B站请求头的下载器（B站 CDN 对 Referer 有校验）。 */
    val biliFileDownloader: com.haruhi.haruhibox.core.download.FileDownloader by lazy {
        com.haruhi.haruhibox.core.download.FileDownloader(
            client = biliHttpClient,
            dirs = libraryDirs,
        )
    }

    /** 持久化下载任务仓库。 */
    val downloadTasks: com.haruhi.haruhibox.core.download.DownloadTaskRepository by lazy {
        com.haruhi.haruhibox.core.download.DownloadTaskRepository(
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
     * 任务必须继续跑；进程被杀后由 `HaruhiBoxApp` 的恢复流程重新拉起。
     */
    val jmDownloads: com.haruhi.haruhibox.feature.jm.data.JmDownloadManager by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmDownloadManager(
            repo = jmRepository,
            tasks = downloadTasks,
            downloader = com.haruhi.haruhibox.core.download.ParallelImageDownloader(
                downloader = fileDownloader,
            ),
            dirs = libraryDirs,
            environment = jmDownloadEnvironment,
            scope = appScope,
            onTaskStarted = {
                // 长下载必须有前台服务，否则息屏后会被系统掐掉（计划 §8）
                com.haruhi.haruhibox.core.download.DownloadForegroundService.ensureRunning(appContext)
            },
        )
    }

    /**
     * 执行下载时读取的环境参数。
     *
     * **每次调用都重新读设置**：计划 §9.2 要求"线程数、线路、网络策略对新任务或
     * 下次重试生效，不中途替换运行任务配置"。所以这里是 `suspend` 读取而不是快照。
     */
    private val jmDownloadEnvironment: com.haruhi.haruhibox.feature.jm.data.JmDownloadEnvironment =
        object : com.haruhi.haruhibox.feature.jm.data.JmDownloadEnvironment {

            override suspend fun concurrency(): Int {
                val configured = settings.jmInt(SettingKeys.JM_DOWNLOAD_THREADS, SettingDefaults.JM_DOWNLOAD_THREADS_AUTO).first()
                return if (configured > 0) {
                    configured.coerceIn(
                        SettingDefaults.JM_DOWNLOAD_THREADS_MIN,
                        SettingDefaults.JM_DOWNLOAD_THREADS_MAX,
                    )
                } else {
                    com.haruhi.haruhibox.feature.jm.data.JmDownloadManager.AUTO_CONCURRENCY
                }
            }

            override suspend fun imageDomain(): String {
                val first = settings.jmString(SettingKeys.JM_IMAGE_DOMAINS).first()
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                return first ?: JmDefaults.IMAGE_DOMAIN
            }

            override suspend fun allowsTransfer(metered: Boolean): Boolean {
                val policy = settings.resolveJmNetwork(secrets).downloadNetwork
                return com.haruhi.haruhibox.core.download.DownloadGate.of(policy, metered)
                    .allowNewTransfer()
            }

            override val networkState = networkMonitor.state
        }

    /** 阅读位置与章节清单查询（阅读器与"继续阅读"入口）。 */
    val jmReading: com.haruhi.haruhibox.feature.jm.data.JmReadingRepository by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmReadingRepository(
            positionDao = database.playbackPositionDao(),
            chapterDao = database.jmChapterDao(),
        )
    }

    // ---------------- JM ----------------

    /**
     * JM 接口客户端。
     *
     * **每次取用都重新构造**：它只持有 OkHttp 客户端与几个"读设置"的闭包，
     * 构造成本可以忽略；而这样能保证改完设置（线路、重试次数、超时）后
     * 新任务立刻用上新值（计划 §9.2 的生效时机要求）。
     */
    val jmApi: com.haruhi.haruhibox.feature.jm.api.JmApiClient
        get() = com.haruhi.haruhibox.feature.jm.api.JmApiClient(
            client = jmHttpClient,
            config = { runBlocking { settings.resolveJmNetwork(secrets) } },
            retryProvider = {
                com.haruhi.haruhibox.feature.jm.api.JmApiClient.JmRetrySettings(
                    album = runBlocking { jmRetry(SettingKeys.JM_RETRY_ALBUM) },
                    search = runBlocking { jmRetry(SettingKeys.JM_RETRY_SEARCH) },
                    chapter = runBlocking { jmRetry(SettingKeys.JM_RETRY_CHAPTER) },
                )
            },
            apiDomainProvider = { runBlocking { jmApiDomain() } },
        )

    private suspend fun jmRetry(key: String): Int = settings
        .jmInt(key, com.haruhi.haruhibox.core.settings.SettingDefaults.JM_RETRY)
        .first()

    /** 接口线路：取设置里列表的第一项；未配置时用参考实现的默认域名。 */
    private suspend fun jmApiDomain(): String = settings
        .jmString(SettingKeys.JM_API_DOMAINS)
        .first()
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: com.haruhi.haruhibox.core.jm.JmDefaults.DEFAULT_API_DOMAIN

    /** JM 仓库（在线数据 ↔ 本地库）。 */
    val jmRepository: com.haruhi.haruhibox.feature.jm.data.JmRepository by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmRepository(
            api = jmApi,
            albumDao = database.jmAlbumDao(),
            chapterDao = database.jmChapterDao(),
            favoriteDao = database.jmFavoriteDao(),
            recentDao = database.jmRecentAlbumDao(),
            searchHistoryDao = database.jmSearchHistoryDao(),
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
    val jmImageStore: com.haruhi.haruhibox.feature.jm.data.JmImageStore by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmImageStore(
            repo = jmRepository,
            downloader = fileDownloader,
            dirs = libraryDirs,
            environment = jmDownloadEnvironment,
        )
    }

    // ---------------- 导出（P4） ----------------

    /** ZIP 打包器（zip4j，支持 AES 或不加密）。 */
    val archiveWriter: com.haruhi.haruhibox.core.codec.ArchiveWriter by lazy {
        com.haruhi.haruhibox.core.codec.ArchiveWriter()
    }

    /** PDF 生成器（pdfbox-android；超长图按高度切片）。 */
    val pdfWriter: com.haruhi.haruhibox.core.codec.PdfWriter by lazy {
        com.haruhi.haruhibox.core.codec.PdfWriter()
    }

    /** 成品交付：API 29+ 走 MediaStore 的下载目录，老系统退到应用外部私有目录。 */
    val exportPublisher: com.haruhi.haruhibox.core.storage.ExportPublisher by lazy {
        com.haruhi.haruhibox.core.storage.AndroidExportPublisher(appContext)
    }

    /** 导出编排（复用下载任务表，进度进任务页）。 */
    val jmExports: com.haruhi.haruhibox.feature.jm.data.JmExportManager by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmExportManager(
            repo = jmRepository,
            tasks = downloadTasks,
            dirs = libraryDirs,
            archiveWriter = archiveWriter,
            pdfWriter = pdfWriter,
            publisher = exportPublisher,
            scope = appScope,
            onTaskStarted = {
                com.haruhi.haruhibox.core.download.DownloadForegroundService.ensureRunning(appContext)
            },
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
            com.haruhi.haruhibox.core.download.DownloadForegroundService.stop(appContext)
        }
    }

    /**
     * 封面缓存的逐条失效。
     *
     * 封面走 Coil 的磁盘缓存（`files/covers` 里是按 URL 哈希命名的文件），
     * 所以"删掉某一本的封面"只能按 key 失效，不能删文件名。
     * 内存缓存也一并清掉，否则刚删完还会从内存里显示出来。
     */
    private val coverCache: com.haruhi.haruhibox.feature.jm.data.CoverCache =
        object : com.haruhi.haruhibox.feature.jm.data.CoverCache {
            override fun evict(albumId: Long) {
                val url = JmDefaults.buildCoverUrl(albumId)
                runCatching {
                    imageLoader.diskCache?.remove(url)
                    imageLoader.memoryCache?.remove(coil3.memory.MemoryCache.Key(url))
                }.onFailure { Timber.tag("jm").w(it, "清理封面缓存失败：%d", albumId) }
            }
        }

    /** "删除本地文件 / 从书库移除"的统一编排（记录 + 图片 + 成品 + 封面）。 */
    val jmAlbumCleaner: com.haruhi.haruhibox.feature.jm.data.JmAlbumCleaner by lazy {
        com.haruhi.haruhibox.feature.jm.data.JmAlbumCleaner(
            repo = jmRepository,
            exports = jmExports,
            tasks = downloadTasks,
            coverCache = coverCache,
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
     */
    val imageLoader: coil3.ImageLoader by lazy {
        coil3.ImageLoader.Builder(appContext)
            .components {
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { jmHttpClient },
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

    @Volatile
    private var jmClient: OkHttpClient? = null

    @Volatile
    private var biliClient: OkHttpClient? = null

    /** JM 模式的网络客户端。首次访问时按当前设置构建。 */
    val jmHttpClient: OkHttpClient
        get() = jmClient ?: synchronized(this) {
            jmClient ?: buildClient(NetworkProfile.JM).also { jmClient = it }
        }

    /** B站模式的网络客户端。 */
    val biliHttpClient: OkHttpClient
        get() = biliClient ?: synchronized(this) {
            biliClient ?: buildClient(NetworkProfile.BILI).also { biliClient = it }
        }

    /**
     * 设置变更后调用：丢掉缓存的客户端，让新任务用新配置。
     * 已在执行的任务持有旧实例的引用，不受影响（符合 §9.2 的生效时机要求）。
     */
    fun invalidateNetworkClients() {
        synchronized(this) {
            jmClient = null
            biliClient = null
        }
    }

    private fun buildClient(profile: NetworkProfile): OkHttpClient {
        val config = runBlocking {
            when (profile) {
                NetworkProfile.JM -> settings.resolveJmNetwork(secrets)
                NetworkProfile.BILI -> settings.resolveBiliNetwork(secrets)
            }
        }
        return NetworkClientFactory.create(
            config = config,
            userAgent = when (profile) {
                NetworkProfile.JM -> JmDefaults.USER_AGENT
                NetworkProfile.BILI -> BILI_USER_AGENT
            },
            extraHeaders = when (profile) {
                NetworkProfile.JM -> emptyMap()
                // 参考实现 BilibiliService 第 62～67 行的固定请求头
                NetworkProfile.BILI -> mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                )
            },
            enableLogging = BuildConfig.DEBUG,
        )
    }

    private enum class NetworkProfile { JM, BILI }

    companion object {
        /** 参考实现 `BilibiliService.HEADERS` 的 User-Agent。 */
        const val BILI_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0"

        /**
         * 私有库目录名。
         *
         * debug 与 release 的 `applicationId` 不同（`.debug` 后缀），各自的 `filesDir`
         * 本来就隔离，所以这里两种构建用同一个名字即可。
         */
        const val LIBRARY_DIR_NAME = "library"

        /** 封面磁盘缓存上限：256MB 足够放下几万张缩略封面。 */
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024

        /** 封面内存缓存上限（占可用内存比例）。 */
        const val MEMORY_CACHE_PERCENT = 0.15
    }
}
