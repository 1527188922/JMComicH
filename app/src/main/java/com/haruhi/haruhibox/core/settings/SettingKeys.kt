package com.haruhi.haruhibox.core.settings

/**
 * 三个设置作用域的 key。
 *
 * **这些字符串只用于内部持久化，绝不出现在 UI 上**（计划 §9：所有设置页只展示
 * 易懂的名称、说明、当前值和控件，不展示内部 key）。
 *
 * 命名规则：`g_` = Global，`jm_` = JM 模式，`b_` = B站模式。
 * 前缀隔离保证"三个作用域互不覆盖"。
 */
internal object SettingKeys {

    // ---------------- Global ----------------
    /** 0=跟随系统 1=浅色 2=深色 */
    const val G_THEME = "g_theme"

    /** 0=每次选择 1=上次使用 2=固定漫画 3=固定视频 */
    const val G_START_MODE = "g_start_mode"

    /** 上次使用的模式（仅当 G_START_MODE 开启"记住上次"时有意义），0=漫画 1=视频 */
    const val G_LAST_MODE = "g_last_mode"

    /** 0=仅 Wi-Fi 1=允许移动网络 */
    const val G_DOWNLOAD_NETWORK = "g_download_network"

    /** 全局请求超时（秒） */
    const val G_REQUEST_TIMEOUT_SECONDS = "g_request_timeout_seconds"

    /** 代理类型 0=不使用 1=HTTP 2=SOCKS */
    const val G_PROXY_TYPE = "g_proxy_type"
    const val G_PROXY_HOST = "g_proxy_host"
    const val G_PROXY_PORT = "g_proxy_port"
    const val G_PROXY_USERNAME = "g_proxy_username"

    // ---------------- JM ----------------
    /** 书库布局 0=三列 1=两列 2=列表 */
    const val JM_LIBRARY_LAYOUT = "jm_library_layout"

    /** 0=纵向连续 1=横向分页 */
    const val JM_READING_MODE = "jm_reading_mode"

    /** 0=左到右 1=右到左（仅横向分页有效） */
    const val JM_READING_DIRECTION = "jm_reading_direction"

    /** 0=跟随主题 1=浅色 2=深色 3=护眼暖色 */
    const val JM_READING_BACKGROUND = "jm_reading_background"

    /** 0=跟随系统 1=自定义 */
    const val JM_BRIGHTNESS_MODE = "jm_brightness_mode"
    const val JM_BRIGHTNESS_VALUE = "jm_brightness_value"

    const val JM_KEEP_SCREEN_ON = "jm_keep_screen_on"

    /** 同时下载图片数；0 = 自动 */
    const val JM_DOWNLOAD_THREADS = "jm_download_threads"

    /** 是否允许同时下载多本漫画 */
    const val JM_PARALLEL_ALBUMS = "jm_parallel_albums"

    /** 下载网络：-1=跟随全局 0=仅 Wi-Fi 1=允许移动网络 */
    const val JM_DOWNLOAD_NETWORK = "jm_download_network"

    /** 文件夹名称长度上限（字节） */
    const val JM_ALBUM_NAME_MAX_BYTES = "jm_album_name_max_bytes"

    /** 是否保存搜索历史 */
    const val JM_SAVE_SEARCH_HISTORY = "jm_save_search_history"
    const val JM_SEARCH_HISTORY_LIMIT = "jm_search_history_limit"

    /** 接口线路（换行分隔的域名列表，第一项为当前线路） */
    const val JM_API_DOMAINS = "jm_api_domains"

    /** 图片线路（换行分隔的域名列表，第一项为当前线路） */
    const val JM_IMAGE_DOMAINS = "jm_image_domains"

    /** 网络覆盖：0=跟随全局 1=本模式自定义 */
    const val JM_NETWORK_OVERRIDE = "jm_network_override"
    const val JM_REQUEST_TIMEOUT_SECONDS = "jm_request_timeout_seconds"
    const val JM_PROXY_TYPE = "jm_proxy_type"
    const val JM_PROXY_HOST = "jm_proxy_host"
    const val JM_PROXY_PORT = "jm_proxy_port"
    const val JM_PROXY_USERNAME = "jm_proxy_username"

    /** 各接口重试次数（0 = 不自动重试） */
    const val JM_RETRY_ALBUM = "jm_retry_album"
    const val JM_RETRY_SEARCH = "jm_retry_search"
    const val JM_RETRY_CHAPTER = "jm_retry_chapter"

    // ---------------- Bili ----------------
    /** 下载时长上限（分钟） */
    const val B_DURATION_LIMIT_MINUTES = "b_duration_limit_minutes"

    /** 下载网络：-1=跟随全局 0=仅 Wi-Fi 1=允许移动网络 */
    const val B_DOWNLOAD_NETWORK = "b_download_network"

    /** 默认播放速度（存的是 ×100 的整数，避免浮点精度问题） */
    const val B_PLAYBACK_SPEED_X100 = "b_playback_speed_x100"

    const val B_KEEP_SCREEN_ON = "b_keep_screen_on"

    /** 网络覆盖：0=跟随全局 1=本模式自定义 */
    const val B_NETWORK_OVERRIDE = "b_network_override"
    const val B_REQUEST_TIMEOUT_SECONDS = "b_request_timeout_seconds"
    const val B_PROXY_TYPE = "b_proxy_type"
    const val B_PROXY_HOST = "b_proxy_host"
    const val B_PROXY_PORT = "b_proxy_port"
    const val B_PROXY_USERNAME = "b_proxy_username"

    // ---------------- 敏感凭据（单独的安全存储，键名同样不外露） ----------------
    const val SECRET_JM_ZIP_PASSWORD = "secret_jm_zip_password"
    const val SECRET_JM_PDF_PASSWORD = "secret_jm_pdf_password"
    const val SECRET_BILI_SESSDATA = "secret_bili_sessdata"
    const val SECRET_BILI_JCT = "secret_bili_jct"
    const val SECRET_BILI_TICKET = "secret_bili_ticket"
    const val SECRET_GLOBAL_PROXY_PASSWORD = "secret_g_proxy_password"
    const val SECRET_JM_PROXY_PASSWORD = "secret_jm_proxy_password"
    const val SECRET_B_PROXY_PASSWORD = "secret_b_proxy_password"
}
