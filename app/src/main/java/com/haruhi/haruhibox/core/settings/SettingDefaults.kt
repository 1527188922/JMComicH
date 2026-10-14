package com.haruhi.haruhibox.core.settings

/**
 * 设置的**类型化默认值**。
 *
 * 来源分三类，代码里逐条标注：
 * - `[参考]`：参考实现 `jm.properties` / `bilibili.properties` 的默认值；
 * - `[计划]`：计划文档 §9.3～§9.5 明确写出的 Android 侧默认值；
 * - `[Android]`：参考实现没有、Android 侧因平台限制新定的值。
 */
object SettingDefaults {

    // ---------------- Global ----------------

    /** [计划 §9.5] 主题：跟随系统。 */
    const val THEME_FOLLOW_SYSTEM = 0

    /** [计划 §9.5] 打开应用时：每次选择。 */
    const val START_MODE_ASK = 0

    /** [计划 §9.5] 下载网络：仅 Wi-Fi（存储值 0，与 `DownloadNetwork` 的序号一致）。 */
    const val DOWNLOAD_NETWORK_WIFI_ONLY = 0

    /**
     * 下载网络：允许移动网络（存储值 1）。
     *
     * **[计划 §9.5 原定默认"仅 Wi-Fi"，2026-10-09 按用户要求把默认值改成这一项]**
     * 理由：设置模块（P6）还没做，界面上改不了；默认"仅 Wi-Fi"会让移动网络下的下载
     * **看起来没反应**（任务静默停在"等待网络"）。P6 做出全局 / JM / B站三个开关后
     * 用户可自行收紧——判定逻辑（`DownloadGate`）与"等待网络"状态机已就绪，不需要改。
     */
    const val DOWNLOAD_NETWORK_ANY = 1

    /** [计划 §9.5] 请求超时：30 秒。 */
    const val REQUEST_TIMEOUT_SECONDS = 30

    /** [计划 §9.5] 代理：默认不使用。 */
    const val PROXY_NONE = 0

    // ---------------- JM ----------------

    /** [计划 §9.3] 书库布局：三列封面。 */
    const val JM_LIBRARY_LAYOUT_GRID3 = 0

    /** [计划 §9.3] 默认阅读方式：纵向连续。 */
    const val JM_READING_MODE_VERTICAL = 0

    /** [计划 §9.3] 横向翻页方向：左到右。 */
    const val JM_READING_DIRECTION_LTR = 0

    /** [计划 §9.3] 横向翻页方向：右到左。 */
    const val JM_READING_DIRECTION_RTL = 1

    /** [计划 §9.3] 阅读背景：跟随主题。 */
    const val JM_READING_BACKGROUND_THEME = 0

    /** [计划 §9.3] 阅读背景：浅色 / 深色。 */
    const val JM_READING_BACKGROUND_LIGHT = 1
    const val JM_READING_BACKGROUND_DARK = 2

    /**
     * 历史值：护眼暖色。
     *
     * 阅读界面已经去掉这个选项（用户要求第 4 点），保留常量只为了说明编码 3 曾经存在——
     * 读到它时一律回落到"跟随主题"。
     */
    const val JM_READING_BACKGROUND_SEPIA_LEGACY = 3

    /** [计划 §9.3] 阅读方式：横向分页。 */
    const val JM_READING_MODE_HORIZONTAL = 1

    /** [计划 §9.3] 阅读亮度：跟随系统 / 自定义。 */
    const val JM_BRIGHTNESS_FOLLOW_SYSTEM = 0
    const val JM_BRIGHTNESS_CUSTOM = 1

    /** [计划 §9.3] 自定义亮度默认值（0～100，应用内相对亮度）。 */
    const val JM_BRIGHTNESS_VALUE_DEFAULT = 60

    /** [计划 §9.3] 阅读时保持亮屏：开启。 */
    const val JM_KEEP_SCREEN_ON = true

    /** [参考 `jm.download.threads=0` → CPU 核数；Android 改成"自动"] */
    const val JM_DOWNLOAD_THREADS_AUTO = 0

    /** [参考 `jm.operation.parallel.enabled=false`] */
    const val JM_PARALLEL_ALBUMS = false

    /** [计划 §9.3] 下载网络默认跟随全局。 */
    const val NETWORK_FOLLOW_GLOBAL = -1

    /** [参考 `jm.album.name_max_length=150`] */
    const val JM_ALBUM_NAME_MAX_BYTES = 150

    /** [计划 §9.3] 保存搜索历史默认开启。 */
    const val JM_SAVE_SEARCH_HISTORY = true

    /** [参考 `jm.search.history.limit=20`；计划 §9.3 范围 1～100] */
    const val JM_SEARCH_HISTORY_LIMIT = 20

    /** [参考 `jm.request.*.retry=0`，计划 §9.3 范围 0～5] */
    const val JM_RETRY = 0

    /** [计划 §9.3] 文件夹名称长度范围。 */
    const val JM_ALBUM_NAME_MAX_BYTES_MIN = 32
    const val JM_ALBUM_NAME_MAX_BYTES_MAX = 240

    /** [计划 §9.3] 同时下载图片数范围（0 = 自动）。 */
    const val JM_DOWNLOAD_THREADS_MIN = 1
    const val JM_DOWNLOAD_THREADS_MAX = 8

    // ---------------- Bili ----------------

    /** [参考 `bilibili.download_video.duration_limit=600` = 10 分钟；计划 §9.4 范围 1～1440] */
    const val B_DURATION_LIMIT_MINUTES = 10
    const val B_DURATION_LIMIT_MINUTES_MIN = 1
    const val B_DURATION_LIMIT_MINUTES_MAX = 1440

    /** [计划 §9.4] 默认播放速度 1.0×（×100 存储）。 */
    const val B_PLAYBACK_SPEED_X100 = 100

    /** [计划 §9.4] 播放时保持亮屏默认开启。 */
    const val B_KEEP_SCREEN_ON = true

    /** [计划 §9.4 / §5.2] 现阶段只支持 progressive durl，清晰度只读展示。 */
    const val B_QUALITY_LABEL = "当前接口可用清晰度（兼容下载模式）"
}

/**
 * 通用边界与校验常量。UI 层做输入校验时用这些值，不要在页面里各写一份。
 */
object SettingLimits {
    /** [计划 §9.5] 全局请求超时范围（秒）。 */
    val REQUEST_TIMEOUT_SECONDS = 5..120

    /** [计划 §9.3] 搜索历史条数范围。 */
    val SEARCH_HISTORY_LIMIT = 1..100

    /** [计划 §9.3] 接口重试次数范围（0 = 不自动重试）。 */
    val JM_RETRY = 0..5

    /** 端口范围。 */
    val PORT = 1..65535

    /** [计划 §9.4] 播放速度可选值（×100）。 */
    val PLAYBACK_SPEEDS_X100 = listOf(50, 75, 100, 125, 150, 200)
}
