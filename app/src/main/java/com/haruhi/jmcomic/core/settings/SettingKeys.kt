package com.haruhi.jmcomic.core.settings

/**
 * 两个设置作用域（全局 / 漫画）的 key。
 *
 * **这些字符串只用于内部持久化，绝不出现在 UI 上**（计划 §9：所有设置页只展示
 * 易懂的名称、说明、当前值和控件，不展示内部 key）。
 *
 * 命名与隔离：顶层常量属于**全局作用域**，[Jm] 里的常量属于**漫画作用域**。
 * 两个作用域各写各的 DataStore 文件（`settings_global` / `settings_jm`），
 * 所以 key 不再需要以前的 `g_` / `jm_` 前缀——同名 key 在各自文件里天然隔离，
 * "两个作用域互不覆盖"由文件划分保证。`SettingKeys.Jm.X` 这个写法在调用处
 * 一眼能看出作用域，比 `JM_X` 后缀更难写错。
 */
internal object SettingKeys {

    // ---------------- 全局作用域 ----------------

    /** 0=跟随系统 1=浅色 2=深色 */
    const val THEME = "theme"

    /** 0=仅 Wi-Fi 1=允许移动网络 */
    const val DOWNLOAD_NETWORK = "download_network"

    /** 全局请求超时（秒） */
    const val REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"

    /** 代理类型 0=不使用 1=HTTP 2=SOCKS */
    const val PROXY_TYPE = "proxy_type"
    const val PROXY_HOST = "proxy_host"
    const val PROXY_PORT = "proxy_port"
    const val PROXY_USERNAME = "proxy_username"

    // ---------------- 漫画作用域 ----------------

    internal object Jm {
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

        /** 同时下载图片数；0 = 自动 */
        const val DOWNLOAD_THREADS = "download_threads"

        /** 是否允许同时下载多本漫画 */
        const val PARALLEL_ALBUMS = "parallel_albums"

        /** 下载网络：-1=跟随全局 0=仅 Wi-Fi 1=允许移动网络 */
        const val DOWNLOAD_NETWORK = "download_network"

        /** 文件夹名称长度上限（字节） */
        const val ALBUM_NAME_MAX_BYTES = "album_name_max_bytes"

        /** 是否保存搜索历史 */
        const val SAVE_SEARCH_HISTORY = "save_search_history"
        const val SEARCH_HISTORY_LIMIT = "search_history_limit"

        /** 接口线路（换行分隔的域名列表，第一项为当前线路） */
        const val API_DOMAINS = "api_domains"

        /** 图片线路（换行分隔的域名列表，第一项为当前线路） */
        const val IMAGE_DOMAINS = "image_domains"

        /** 网络覆盖：0=跟随全局 1=本模式自定义 */
        const val NETWORK_OVERRIDE = "network_override"
        const val REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"
        const val PROXY_TYPE = "proxy_type"
        const val PROXY_HOST = "proxy_host"
        const val PROXY_PORT = "proxy_port"
        const val PROXY_USERNAME = "proxy_username"

        /** 各接口重试次数（0 = 不自动重试） */
        const val RETRY_ALBUM = "retry_album"
        const val RETRY_SEARCH = "retry_search"
        const val RETRY_CHAPTER = "retry_chapter"
    }

    // ---------------- 敏感凭据（单独的安全存储，键名同样不外露） ----------------
    const val SECRET_JM_ZIP_PASSWORD = "secret_jm_zip_password"
    const val SECRET_JM_PDF_PASSWORD = "secret_jm_pdf_password"
    const val SECRET_GLOBAL_PROXY_PASSWORD = "secret_g_proxy_password"
    const val SECRET_JM_PROXY_PASSWORD = "secret_jm_proxy_password"
}
