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
         * 同时下载图片数（1～12，默认 4；**没有"自动"档**，2026-10-15 取消）。
         *
         * 老版本存过 `0`（当时那一档"自动"）：读取时按 `normalizeDownloadThreads` 折成 4。
         */
        const val DOWNLOAD_THREADS = "download_threads"

        /** 是否允许同时下载多本漫画 */
        const val PARALLEL_ALBUMS = "parallel_albums"

        /** 下载网络：0=仅 Wi-Fi 1=允许移动网络 */
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

        /** 请求超时（秒）：接口（详情 / 章节 / 搜索）用。 */
        const val REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"

        /** 图片下载超时（秒）：章节图片、封面用。 */
        const val IMAGE_REQUEST_TIMEOUT_SECONDS = "image_request_timeout_seconds"

        /** 代理类型 0=不使用 1=HTTP 2=SOCKS */
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
    const val SECRET_JM_PROXY_PASSWORD = "secret_jm_proxy_password"

    /**
     * 历史遗留：旧「全局作用域」的代理密码。
     *
     * 与 [SettingsRepository] 的旧全局 DataStore 一样，只用于**一次性迁移**到
     * [SECRET_JM_PROXY_PASSWORD]，迁移时读走就删（`SecretStore.clear`）。
     */
    const val SECRET_LEGACY_GLOBAL_PROXY_PASSWORD = "secret_g_proxy_password"
}
