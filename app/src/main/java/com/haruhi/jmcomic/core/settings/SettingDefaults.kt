package com.haruhi.jmcomic.core.settings

/**
 * 设置的**类型化默认值**。
 *
 * 来源分两类，代码里逐条标注：
 * - `[参考]`：参考实现（haruhibot-server）`jm.properties` 的默认值；
 * - `[计划]`：计划文档 §9.3～§9.5 明确写出的 Android 侧默认值；
 * - `[Android]`：参考实现没有、Android 侧因平台限制新定的值。
 *
 * 组织方式与 [SettingKeys] 对齐：默认值全在 [Jm] 里（历史上顶层是「全局作用域」的
 * 默认值，随全局作用域一起取消，见 [SettingKeys]）。范围类常量（UI 校验用）在 [SettingLimits]。
 */
object SettingDefaults {

    object Jm {

        /** [计划 §9.5] 主题：跟随系统（编码，与 [AppTheme] 的序号一致）。 */
        const val THEME_FOLLOW_SYSTEM = 0

        /**
         * 下载网络：允许移动网络（存储值 1）。
         *
         * **[计划 §9.5 原定默认"仅 Wi-Fi"，2026-10-09 按用户要求把默认值改成这一项]**
         * 理由：设置模块（P6）还没做，界面上改不了；默认"仅 Wi-Fi"会让移动网络下的下载
         * **看起来没反应**（任务静默停在"等待网络"）。做出开关后用户可自行收紧——
         * 判定逻辑（`DownloadGate`）与"等待网络"状态机已就绪，不需要改。
         */
        const val DOWNLOAD_NETWORK_ANY = 1

        /** [计划 §9.5] 请求超时：30 秒。 */
        const val REQUEST_TIMEOUT_SECONDS = 30

        /** [计划 §9.5] 代理：默认不使用。 */
        const val PROXY_NONE = 0

        /** [计划 §9.3] 书库布局：三列封面。 */
        const val LIBRARY_LAYOUT_GRID3 = 0

        /** [计划 §9.3] 默认阅读方式：纵向连续。 */
        const val READING_MODE_VERTICAL = 0

        /** [计划 §9.3] 阅读方式：横向分页。 */
        const val READING_MODE_HORIZONTAL = 1

        /** [计划 §9.3] 横向翻页方向：左到右。 */
        const val READING_DIRECTION_LTR = 0

        /** [计划 §9.3] 横向翻页方向：右到左。 */
        const val READING_DIRECTION_RTL = 1

        /** [计划 §9.3] 阅读背景：跟随主题。 */
        const val READING_BACKGROUND_THEME = 0

        /** [计划 §9.3] 阅读背景：浅色 / 深色。 */
        const val READING_BACKGROUND_LIGHT = 1
        const val READING_BACKGROUND_DARK = 2

        /**
         * 历史值：护眼暖色。
         *
         * 阅读界面已经去掉这个选项（用户要求第 4 点），保留常量只为了说明编码 3 曾经存在——
         * 读到它时一律回落到"跟随主题"。
         */
        const val READING_BACKGROUND_SEPIA_LEGACY = 3

        /** [计划 §9.3] 阅读亮度：跟随系统 / 自定义。 */
        const val BRIGHTNESS_FOLLOW_SYSTEM = 0
        const val BRIGHTNESS_CUSTOM = 1

        /** [计划 §9.3] 自定义亮度默认值（0～100，应用内相对亮度）。 */
        const val BRIGHTNESS_VALUE_DEFAULT = 60

        /** [计划 §9.3] 阅读时保持亮屏：开启。 */
        const val KEEP_SCREEN_ON = true

        /**
         * 同时下载图片数：**自动**（存储值 0）。
         *
         * [参考 `jm.download.threads=0` → CPU 核数；Android 侧改成"自动"]。
         * Android 的"自动"**不读设备参数**——就是一个固定的 4
         * （`DownloadManager.AUTO_CONCURRENCY`，用户 2026-10-15 确认"默认 4 即可"），
         * 设置页那句说明必须照实写，不要再写"参考设备内存"。
         */
        const val DOWNLOAD_THREADS_AUTO = 0

        /** [参考 `jm.operation.parallel.enabled=false`] */
        const val PARALLEL_ALBUMS = false

        /** [参考 `jm.album.name_max_length=150`] */
        const val ALBUM_NAME_MAX_BYTES = 150

        /** [计划 §9.3] 保存搜索历史默认开启。 */
        const val SAVE_SEARCH_HISTORY = true

        /** [参考 `jm.search.history.limit=20`；计划 §9.3 范围 1～100] */
        const val SEARCH_HISTORY_LIMIT = 20

        /** [参考 `jm.request.*.retry=0`，计划 §9.3 范围 0～5] */
        const val RETRY = 0
    }
}

/**
 * 通用边界与校验常量。UI 层做输入校验时用这些值，不要在页面里各写一份。
 */
object SettingLimits {
    /** [计划 §9.5] 请求超时范围（秒）。 */
    val REQUEST_TIMEOUT_SECONDS = 5..120

    /** [计划 §9.3] 搜索历史条数范围。 */
    val SEARCH_HISTORY_LIMIT = 1..100

    /** [计划 §9.3] 接口重试次数范围（0 = 不自动重试）。 */
    val JM_RETRY = 0..5

    /** 端口范围。 */
    val PORT = 1..65535

    /** [计划 §9.3] 文件夹名称长度范围。 */
    val ALBUM_NAME_MAX_BYTES = 32..240

    /**
     * 同时下载图片数范围（0 = 自动）。
     *
     * 上限**从 8 提到 12**（用户 2026-10-15 要求"最大支持 12 线程"）：滑杆能拉到的最大值就是这里，
     * 业务侧 `AppContainer.concurrency()` 也按同一个范围收敛，所以改这一个值两处一起生效。
     */
    val DOWNLOAD_THREADS = 1..12

    /**
     * [用户要求] 导出默认密码（ZIP / PDF）的**最大字符数**。
     *
     * 设置页那两个密码框按这个值限长，粘贴进来的多行文本也会被净化
     * （见 `SettingsScreen.sanitizeExportPassword`），所以存下去的密码不会超长。
     */
    const val EXPORT_PASSWORD_MAX_CHARS = 30
}
