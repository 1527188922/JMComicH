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

        /** 请求超时 */
        const val REQUEST_TIMEOUT_SECONDS = 5

        /** 图片下载超时：**6 秒**（与接口超时分开配置，默认值同步改成 6 秒）。 */
        const val IMAGE_REQUEST_TIMEOUT_SECONDS = 6

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
         * 同时下载图片数：**默认 4**。
         *
         * [参考 `jm.download.threads=0` → CPU 核数；Android 侧改成"自动"]——
         * **2026-10-15 用户把"自动"这一档取消了**：滑杆就是 1～12 的纯数值，没有"自动"档，
         * 默认值 4 就是滑杆的初始位置（用户原话"默认 4 表示下载数进度条控件默认在 4 这个值的位置"）。
         * 所以这里没有什么"自动值算法"：不读 CPU 核数，也不读内存。
         *
         * 老版本存过 `0`（当时那一档"自动"）的机器由 [normalizeDownloadThreads] 按 4 处理。
         */
        const val DOWNLOAD_THREADS = 4

        /**
         * 把存储里读出来的线程数收敛成可用值。
         *
         * - 正常值：落在 [SettingLimits.DOWNLOAD_THREADS] 里就原样用；
         * - 其它（**老版本存下的 `0`、越界值、脏数据**）：一律按默认 [DOWNLOAD_THREADS]。
         *
         * 为什么不是 `coerceIn`：`coerceIn` 会把老版本的 `0`（当时的"自动"）**静默变成 1**，
         * 那是从 4 张并发掉到 1 张、明显变慢；按默认值处理才是"取消自动档"应有的语义。
         * 读设置的两处（设置页显示、下载时取并发）都走这一个函数，不会两边不一致。
         */
        fun normalizeDownloadThreads(value: Int): Int =
            if (value in SettingLimits.DOWNLOAD_THREADS) value else DOWNLOAD_THREADS

        /** [参考 `jm.operation.parallel.enabled=false`] */
        const val PARALLEL_ALBUMS = false

        /** [参考 `jm.album.name_max_length=150`] */
        const val ALBUM_NAME_MAX_BYTES = 150

        /** [计划 §9.3] 保存搜索历史默认开启。 */
        const val SAVE_SEARCH_HISTORY = true

        /** [参考 `jm.search.history.limit=20`；计划 §9.3 范围 1～100] */
        const val SEARCH_HISTORY_LIMIT = 20

        /**
         * 各接口「请求失败后自动重试」的默认次数：**1 次**。
         *
         * 参考实现 `jm.request.*.retry=0`、计划 §9.3 的范围是 0～5；
         * 2026-10-20 用户要求把默认值从 0（不重试）改成 1（失败后再试一次），
         * 漫画详情 / 搜索 / 章节图片 / 获取分类四个接口共用这一个默认值。
         */
        const val RETRY = 1
    }
}

/**
 * 通用边界与校验常量。UI 层做输入校验时用这些值，不要在页面里各写一份。
 */
object SettingLimits {
    /** [计划 §9.5] 请求超时范围（秒）。 */
    val REQUEST_TIMEOUT_SECONDS = 1..30

    /** 图片下载超时范围（秒）。与接口超时分开配置，范围与它相同。 */
    val IMAGE_REQUEST_TIMEOUT_SECONDS = 1..30

    /** [计划 §9.3] 搜索历史条数范围。 */
    val SEARCH_HISTORY_LIMIT = 1..100

    /** [计划 §9.3] 接口重试次数范围（0 = 不自动重试）。 */
    val JM_RETRY = 0..5

    /** 端口范围。 */
    val PORT = 1..65535

    /** [计划 §9.3] 文件夹名称长度范围。 */
    val ALBUM_NAME_MAX_BYTES = 32..240

    /**
     * 同时下载图片数范围。
     *
     * **没有"自动"档**（用户 2026-10-15 取消）：最小值 1、最大值 **12**（"最大支持 12 线程"），
     * 默认值见 [SettingDefaults.Jm.DOWNLOAD_THREADS]（4）。
     * 滑杆能拉到的最大值与业务侧的收敛都用这一个范围，改它就两处一起生效。
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
