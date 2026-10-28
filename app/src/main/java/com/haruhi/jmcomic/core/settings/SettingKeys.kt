package com.haruhi.jmcomic.core.settings

/**
 * 设置项的 key。
 *
 * **这些字符串只用于内部持久化，绝不出现在 UI 上**（计划 §9：所有设置页只展示
 * 易懂的名称、说明、当前值和控件，不展示内部 key）。
 *
 * 命名与隔离：全部偏好都在 [Jm] 里，写进唯一的 DataStore 文件（`settings_jm`）。
 * 历史上还有一层「全局作用域」（另一个文件 `settings_global`，key 直接放在本对象的顶层）——
 * 那是「JM / B站 双模式」时期的遗留：当时各模式设置要"跟随全局"。现在只有漫画一种内容，
 * 全局这一层已经取消，所有设置都属于漫画模式，`SettingKeys.Jm.X` 就是唯一入口。
 * 旧的全局文件只保留一个一次性迁移读口，见 [SettingsRepository]。
 */
internal object SettingKeys {

    /** 漫画模式（也是唯一的模式）的偏好。 */
    internal object Jm {
        /** 0=跟随系统 1=浅色 2=深色 */
        const val THEME = "theme"

        /** 书库布局 0=三列 1=两列 2=列表 */
        const val LIBRARY_LAYOUT = "library_layout"

        /** 0=纵向连续 1=横向分页 */
        const val READING_MODE = "reading_mode"

        /** 0=左到右 1=右到左（仅横向分页有效） */
        const val READING_DIRECTION = "reading_direction"

        /** 0=跟随主题 1=浅色 2=深色 */
        const val READING_BACKGROUND = "reading_background"

        /** 0=跟随系统 1=自定义 */
        const val BRIGHTNESS_MODE = "brightness_mode"
        const val BRIGHTNESS_VALUE = "brightness_value"

        const val KEEP_SCREEN_ON = "keep_screen_on"

        /**
         * 隐私模式：所有漫画封面模糊显示。
         *
         * 设置页的 Switch 与全应用的双指手势写的是**同一个 key**：
         * 手势只是这个设置的另一个入口，所以状态会跨重启保留。
         */
        const val PRIVACY_MODE = "privacy_mode"

        /**
         * 文字隐私：隐私模式开启时才生效的「隐藏书名」开关。
         *
         * 开启后漫画名称一律换成 JM 号、详情页 tags 隐藏、收藏夹名称只保留首字符。
         */
        const val TEXT_PRIVACY = "text_privacy"

        /**
         * 导出时解扰后的编码方式：0=无损，1=有损（默认）。
         *
         * 只影响 ZIP 包内图片与 PDF 内嵌图片；`library/` 里下载的永远是站点原始字节。
         * 见 [ImageEncoding]。
         */
        const val IMAGE_ENCODING = "image_encoding"

        /**
         * 同时下载图片数（1～12，默认 4；**没有"自动"档**，2026-10-15 取消）。
         *
         * 老版本存过 `0`（当时那一档"自动"）：读取时按 `normalizeDownloadThreads` 折成 4。
         */
        const val DOWNLOAD_THREADS = "download_threads"

        /** 同时下载几本官方 ZIP（1～4，默认 2）。 */
        const val OFFICIAL_ZIP_CONCURRENCY = "official_zip_concurrency"

        /** 是否允许同时下载多本漫画 */
        const val PARALLEL_ALBUMS = "parallel_albums"

        /** 下载网络：0=仅 Wi-Fi 1=允许移动网络 */
        const val DOWNLOAD_NETWORK = "download_network"

        /** 文件夹名称长度上限（字节） */
        const val ALBUM_NAME_MAX_BYTES = "album_name_max_bytes"

        /** 是否保存搜索历史 */
        const val SAVE_SEARCH_HISTORY = "save_search_history"
        const val SEARCH_HISTORY_LIMIT = "search_history_limit"

        /**
         * 选中的接口线路（**只存一个域名**，例如 `www.cdngwc.cc`）。
         *
         * 可选值来自硬编码清单 `core/protocol/LineCatalog.kt`（设置页只让用户从清单里选，
         * 选中即生效，没有「保存」按钮）。空值 = 用工程当前的默认线路
         * （`Defaults.DEFAULT_API_DOMAIN`），「恢复默认设置」清掉偏好后就是这一种。
         *
         * **兼容老格式**：这个 key 以前存的是「换行分隔的域名列表，第一行生效」，
         * 升级用户的历史值仍按"取第一行"解析（见 `LineCatalog.apiLineOf`）。
         */
        const val API_DOMAINS = "api_domains"

        /** 选中的图片线路（只存一个域名）。规则与 [API_DOMAINS] 相同。 */
        const val IMAGE_DOMAINS = "image_domains"

        /** 请求超时（秒）：接口（详情 / 章节 / 搜索）用。 */
        const val REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"

        /** 图片下载超时（秒）：章节图片、封面用。 */
        const val IMAGE_REQUEST_TIMEOUT_SECONDS = "image_request_timeout_seconds"

        /** 代理类型 0=不使用 1=HTTP 2=SOCKS */
        const val PROXY_TYPE = "proxy_type"
        const val PROXY_HOST = "proxy_host"
        const val PROXY_PORT = "proxy_port"
        const val PROXY_USERNAME = "proxy_username"

        /**
         * 所有接口请求「失败后自动重试」的次数（0 = 不自动重试）。
         *
         * 2026-10-20 按用户要求合并：以前按接口分四项（`retry_album` / `retry_search` /
         * `retry_chapter` / `retry_category`），现在**凡是接口请求都用这一项**。
         * 老版本那四个 key 只剩一次性迁移读口（见 `SettingsRepository.migrateLegacyRetryKeys`）。
         */
        const val RETRY_REQUEST = "retry_request"

        /**
         * 图片加载 / 下载「失败后自动重试」的次数（0 = 不自动重试）。
         *
         * 只管**章节图片**：阅读器按需取图与下载任务共用同一个下载引擎（`FileDownloader`），
         * 所以两项一起生效。**只有网络超时才会重试**——连不上 / 5xx / 内容异常一律不重试。
         */
        const val RETRY_IMAGE = "retry_image"

        /**
         * 带账号态的只读接口（`/favorite`、`/useredit`、`/album`）是否复用服务端缓存。
         *
         * **默认 true**（不额外加参数，保持官方请求形态）：服务端会按「线路 + URL」缓存
         * 这些 GET 的明文响应，1app 的写入不会失效另一条线路的缓存——双开 / 多线路同时
         * 登录同一账号时，纯下拉刷新可能一直拿到旧数据。
         *
         * 关掉后每次请求都补一个 `t=<秒级时间戳>`，URL 变化 → 缓存必然 miss → 强制回源。
         * 对应设置页「高级 → 账号接口缓存」。
         */
        const val ACCOUNT_API_CACHE = "account_api_cache"

        /**
         * 接口语言：`"CN"` 简中 / `"TW"` 繁中（官方 App 的 `localStorage.lang`）。
         *
         * 存的是协议里的**取值**而不是枚举序号（见 `core/protocol/ApiScheme.kt` 的
         * `ApiLanguage.fromCode`）：脏数据 / 认不出的值一律回落到默认的简中。
         * 空值（没设置过、或刚「恢复默认」）= 简中。
         */
        const val API_LANGUAGE = "api_language"

        /**
         * 账号资料快照（JSON 一行）。
         *
         * 只用于「我的」页首屏（离线也能显示上次的昵称/头像/收藏数），
         * **不是账号数据的真相**；凭证在 [SecretStore] 里，见 `core/account/AccountStore`。
         * 退出登录时清空。
         */
        const val ACCOUNT_PROFILE = "account_profile"

        /**
         * 账号收藏夹列表快照（JSON 数组，只有 `{FID, name}`）。
         *
         * 「我的」页的「· N 个收藏夹」、详情页的收藏面板都要在第一帧就拿到夹列表，
         * 所以除了进程内缓存还要落盘；任何一次 `/favorite` 请求或夹增删改都会覆盖它。
         * **不是真相**：下次联网就纠正。退出登录时清空。
         */
        const val ACCOUNT_FOLDERS = "account_folders"
    }

    // ---------------- 敏感凭据（单独的安全存储，键名同样不外露） ----------------
    const val SECRET_JM_ZIP_PASSWORD = "secret_jm_zip_password"
    const val SECRET_JM_PDF_PASSWORD = "secret_jm_pdf_password"
    const val SECRET_JM_PROXY_PASSWORD = "secret_jm_proxy_password"

    /** 账号登录凭证：官方接口的 `jwttoken`（`Authorization: Bearer …`）。 */
    const val SECRET_JWT = "secret_jwt"

    /** 账号登录凭证：官方接口的 `s`（`Cookie: AVS=…`）。 */
    const val SECRET_AVS = "secret_avs"

    /**
     * 历史遗留：旧「全局作用域」的代理密码。
     *
     * 与 [SettingsRepository] 的旧全局 DataStore 一样，只用于**一次性迁移**到
     * [SECRET_JM_PROXY_PASSWORD]，迁移时读走就删（`SecretStore.clear`）。
     */
    const val SECRET_LEGACY_GLOBAL_PROXY_PASSWORD = "secret_g_proxy_password"
}
