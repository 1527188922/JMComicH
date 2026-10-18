package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.protocol.ApiLine
import com.haruhi.jmcomic.core.protocol.ImageLine
import com.haruhi.jmcomic.core.protocol.LineCatalog
import com.haruhi.jmcomic.core.settings.DownloadNetwork
import com.haruhi.jmcomic.core.settings.ProxyType
import com.haruhi.jmcomic.core.settings.ResolvedNetworkConfig
import com.haruhi.jmcomic.core.settings.SecretStore
import com.haruhi.jmcomic.core.settings.SettingDefaults
import com.haruhi.jmcomic.core.settings.SettingKeys
import com.haruhi.jmcomic.core.settings.SettingLimits
import com.haruhi.jmcomic.core.settings.SettingsRepository
import com.haruhi.jmcomic.feature.api.ApiLineProbe
import com.haruhi.jmcomic.feature.api.LineProbeResult
import com.haruhi.jmcomic.feature.api.LineSpeedLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 三处「请求失败后重试」的区分（详情 / 搜索 / 章节）。 */
enum class RetryField(val label: String, val key: String) {
    ALBUM("漫画详情", SettingKeys.Jm.RETRY_ALBUM),
    SEARCH("搜索", SettingKeys.Jm.RETRY_SEARCH),
    CHAPTER("章节图片", SettingKeys.Jm.RETRY_CHAPTER),
}

/** 代理编辑草稿：端口先按文本收，由 ViewModel 校验后再落存储。 */
data class ProxyDraft(
    val type: ProxyType,
    val host: String,
    val port: String,
    val username: String,
    val password: String,
)

/**
 * 应用设置页（「更多」→「应用设置」）的状态快照。
 *
 * 只放**本轮范围**内的项（计划 §9.3 里"有 UI 就生效"的那批）：
 * 阅读与显示、下载、导出、网络与线路、高级。
 * 书库布局 / 并行下载 / 搜索历史 / 文件夹名长度 / 存储与缓存这几组
 * 要么还没接线、要么没有实现，按计划 §9.5「未实现的功能不展示空页面或占位开关」不做。
 *
 * **没有 `loading` 字段**（修进入掉帧时去掉的）：以前初始值是"加载中"，等存储读回来
 * 再整页换成内容——那次"占位页 → 内容页"的结构切换正好落在页面进入动画中间，
 * 第 2~3 帧要一边跑动画、一边首次组合并测量整页，掉的就是那一帧。
 * 现在初始值就是各字段的默认值，首帧直接画出整页结构，真实值回来只是若干文本/选中态
 * 变化，不再重建整页。代价是默认值（线程数、超时秒数之类）可能在头一两帧先露一下
 * ——这段时间远短于一帧的观感阈值，看不到。
 */
data class SettingsUiState(
    // 阅读与显示
    //
    // 「阅读背景」「亮度」**不放这一页**：它们随每次阅读的环境变，用户要改也是进了阅读器再改
    // （阅读器控制栏里的设置面板保留着这几项，和这里共用同一份存储）。
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val readingDirection: ReadingDirection = ReadingDirection.LTR,
    val keepScreenOn: Boolean = true,

    // 下载
    /** 同时下载图片数（**没有"自动"档**）；默认 4 就是滑杆的初始位置。 */
    val downloadThreads: Int = SettingDefaults.Jm.DOWNLOAD_THREADS,

    // 导出默认密码：原文存进加密凭据存储，页面上默认以圆点显示，点"显示"才看得到
    val zipPassword: String = "",
    val pdfPassword: String = "",

    // 网络与线路
    /** 接口请求超时（秒）。 */
    val timeoutSeconds: Int = SettingDefaults.Jm.REQUEST_TIMEOUT_SECONDS,
    /** 图片下载超时（秒）。 */
    val imageTimeoutSeconds: Int = SettingDefaults.Jm.IMAGE_REQUEST_TIMEOUT_SECONDS,
    /** 代理设置（密码存在加密凭据存储里，读出来只用于这一页的显示）。 */
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    /** 代理密码原文（默认圆点显示；留空 = 不走认证）。 */
    val proxyPassword: String = "",
    /**
     * 当前接口线路 / 图片线路。
     *
     * **选中即生效**（没有「保存」按钮）：写存储后，`AppContainer.jmApi` 每次取用都会重新读
     * 线路，所以下一次请求就走新线路，不需要重启。可选项是硬编码的
     * [LineCatalog.apiLines] / [LineCatalog.imageLines]；老版本手填的清单外域名
     * 会以 `builtIn = false` 的形式留在这里，不会凭空消失。
     */
    val apiLine: ApiLine = LineCatalog.apiLines.first(),
    val imageLine: ImageLine = LineCatalog.imageLines.first(),
    /** 接口线路的测速结果（key = 域名，见 [ApiLineProbe]）；空 = 这一页还没测过。 */
    val speedResults: Map<String, LineProbeResult> = emptyMap(),
    /** 正在「全部测速」：六条线路并发跑，界面上按钮转圈、每条线路各自出灯号。 */
    val speedTesting: Boolean = false,
    /** 当前生效的网络配置：下载网络那一行的"当前生效"文案用它。 */
    val resolved: ResolvedNetworkConfig? = null,

    // 高级
    val retryAlbum: Int = 0,
    val retrySearch: Int = 0,
    val retryChapter: Int = 0,

    val message: String? = null,
)

/**
 * 导出默认密码**输入框**允许的文本：去掉回车换行（粘贴多行文本时很常见）并截断到上限。
 *
 * 这里**不动首尾空白**：边打字边 trim 会让"中间的空格"永远打不出来
 * （"a" + 空格 → 被 trim 掉 → 再打 "b" 就成了 "ab"）。首尾空白留到保存那一步去掉
 * （见 [sanitizeExportPassword]），反正存进凭据存储的一定是净化后的值。
 */
private fun filterExportPasswordInput(raw: String): String =
    raw.filterNot { it == '\n' || it == '\r' }.take(SettingLimits.EXPORT_PASSWORD_MAX_CHARS)

/**
 * 真正要存下来的导出默认密码：在 [filterExportPasswordInput] 的基础上**去掉首尾空白**。
 *
 * 输入框按「保存」时用它、ViewModel 落存储前也用它，两边同一份规则，
 * 避免"框里显示的和实际存的不是一个值"。
 */
private fun sanitizeExportPassword(raw: String): String = filterExportPasswordInput(raw).trim()

/**
 * 应用设置页的 ViewModel。
 *
 * 保留 **A 类设置项**：key 已定义、且业务代码已经在消费——所以这一页上任何一处改动
 * 都是"有 UI 就生效"，不存在改了没反应的开关。
 *
 * 保存策略（计划 §9.2 说"开关与单选即时保存，文本/数值用取消保存"）：
 * - 开关、单选、**受范围约束的滑杆**（线程数、超时、重试）**即时保存**——
 *   它们的取值构造上不可能越界（滑杆就是范围本身），不需要草稿态和校验，
 *   这也符合"少一层状态、少一处能写错的地方"的取舍；
 * - **两条线路也是即时保存**（用户 2026-10-18 要求）：线路清单是硬编码的，
 *   用户只能从清单里选一条，选中就写存储、下一次请求立刻生效，不再有「保存」按钮；
 * - **代理字段与密码**是自由文本，保留「保存」按钮，代理还要先校验，失败保留原值；
 * - 密码三个（代理 / ZIP / PDF）原文仍然存在加密凭据存储里，页面读出来预填；
 *   代理密码默认用圆点遮住、点「显示」才看原文，
 *   **导出那两个（ZIP / PDF）按用户要求明文显示，输入框右边的按钮就是「保存」**。
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    /** 线路测速（「线路」面板里的「全部测速」）。 */
    private val lineProbe: ApiLineProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun notify(message: String) = _state.update { it.copy(message = message) }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 一次性把这一页要用的项读出来。
     *
     * 逐项 `first()` 会走多次 DataStore 读取，但只发生在进页面那一次，
     * 换来的是"读到的每一项都独立、不需要构造一个临时 Preferences 快照"的简单实现。
     *
     * **整段跑在 IO 上**（`withContext(Dispatchers.IO)`）：这里除了 DataStore 读取，
     * 还有 4 次凭据解密——[SecretStore.get] 每次都要 `KeyStore.load(null)` + `Cipher.init`
     * + `doFinal`，是跨进程、走 TEE 的调用，慢的时候十几到几十毫秒。以前这段落在
     * `viewModelScope` 默认的 Main 上，正好压在页面进入动画的那几帧里；
     * 现在主线程在动画期间只负责组合与绘制。
     */
    private suspend fun reload() = withContext(Dispatchers.IO) {
        val mode = ReadingMode.fromCode(
            settings.jmInt(SettingKeys.Jm.READING_MODE, SettingDefaults.Jm.READING_MODE_VERTICAL).first(),
        )
        val direction = ReadingDirection.fromCode(
            settings.jmInt(SettingKeys.Jm.READING_DIRECTION, SettingDefaults.Jm.READING_DIRECTION_LTR).first(),
        )
        val keepScreenOn = settings
            .jmBoolean(SettingKeys.Jm.KEEP_SCREEN_ON, SettingDefaults.Jm.KEEP_SCREEN_ON)
            .first()
        val threads = settings
            .jmInt(SettingKeys.Jm.DOWNLOAD_THREADS, SettingDefaults.Jm.DOWNLOAD_THREADS)
            .first()
            // 老版本存过的 0（当时那一档"自动"）在这里就折成默认值 4，
            // 否则滑杆最小值是 1、state 却是 0，拇指位置和右侧数字会对不上
            .let { SettingDefaults.Jm.normalizeDownloadThreads(it) }
        val proxyType = ResolvedNetworkConfig.proxyTypeOf(
            settings.jmInt(SettingKeys.Jm.PROXY_TYPE, SettingDefaults.Jm.PROXY_NONE).first(),
        )
        val retryAlbum = settings.jmInt(SettingKeys.Jm.RETRY_ALBUM, SettingDefaults.Jm.RETRY).first()
        val retrySearch = settings.jmInt(SettingKeys.Jm.RETRY_SEARCH, SettingDefaults.Jm.RETRY).first()
        val retryChapter = settings.jmInt(SettingKeys.Jm.RETRY_CHAPTER, SettingDefaults.Jm.RETRY).first()
        val timeout = settings
            .jmInt(SettingKeys.Jm.REQUEST_TIMEOUT_SECONDS, SettingDefaults.Jm.REQUEST_TIMEOUT_SECONDS)
            .first()
        val imageTimeout = settings
            .jmInt(
                SettingKeys.Jm.IMAGE_REQUEST_TIMEOUT_SECONDS,
                SettingDefaults.Jm.IMAGE_REQUEST_TIMEOUT_SECONDS,
            )
            .first()
        val proxyHost = settings.jmString(SettingKeys.Jm.PROXY_HOST).first()
        val proxyPort = settings.jmInt(SettingKeys.Jm.PROXY_PORT, 0).first()
        val proxyUsername = settings.jmString(SettingKeys.Jm.PROXY_USERNAME).first()
        val proxyPassword = secrets.get(SettingKeys.SECRET_JM_PROXY_PASSWORD).orEmpty()
        val zipPassword = secrets.get(SettingKeys.SECRET_JM_ZIP_PASSWORD).orEmpty()
        val pdfPassword = secrets.get(SettingKeys.SECRET_JM_PDF_PASSWORD).orEmpty()
        val apiDomains = settings.jmString(SettingKeys.Jm.API_DOMAINS).first()
        val imageDomains = settings.jmString(SettingKeys.Jm.IMAGE_DOMAINS).first()

        // 有效值要放在 _state.update 之前算好：update 的 lambda 不是挂起函数，
        // 里面不能直接 await DataStore / Keystore 读取。
        val resolved = settings.resolveJmNetwork(secrets)

        _state.update {
            it.copy(
                readingMode = mode,
                readingDirection = direction,
                keepScreenOn = keepScreenOn,
                downloadThreads = threads,
                zipPassword = zipPassword,
                pdfPassword = pdfPassword,
                timeoutSeconds = timeout,
                imageTimeoutSeconds = imageTimeout,
                proxyType = proxyType,
                proxyHost = proxyHost,
                proxyPort = proxyPort.takeIf { it > 0 }?.toString().orEmpty(),
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
                // 存储里的域名 → 清单里的可选项（空值 = 默认线路；清单外的历史手填值原样保留）
                apiLine = LineCatalog.apiLineOf(apiDomains),
                imageLine = LineCatalog.imageLineOf(imageDomains),
                resolved = resolved,
                retryAlbum = retryAlbum,
                retrySearch = retrySearch,
                retryChapter = retryChapter,
            )
        }
    }

    /**
     * 写完网络相关的项之后刷新一次"有效值"，让"当前生效"那行立刻跟上。
     *
     * 和 [reload] 一样整段放 IO：这里的 [SettingsRepository.resolveJmNetwork] 内部还会再读一次
     * 代理密码（Keystore 解密），不该占主线程。
     */
    private suspend fun refreshResolved() = withContext(Dispatchers.IO) {
        val resolved = settings.resolveJmNetwork(secrets)
        _state.update { it.copy(resolved = resolved) }
    }

    // ------------------------------------------------------------------
    // 阅读与显示（与阅读器控制栏共用同一批 key，两处改都即时生效）
    // ------------------------------------------------------------------

    fun setReadingMode(mode: ReadingMode) {
        _state.update { it.copy(readingMode = mode) }
        viewModelScope.launch { settings.putJmInt(SettingKeys.Jm.READING_MODE, mode.toCode()) }
    }

    fun setReadingDirection(direction: ReadingDirection) {
        _state.update { it.copy(readingDirection = direction) }
        viewModelScope.launch { settings.putJmInt(SettingKeys.Jm.READING_DIRECTION, direction.toCode()) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _state.update { it.copy(keepScreenOn = enabled) }
        viewModelScope.launch { settings.putJmBoolean(SettingKeys.Jm.KEEP_SCREEN_ON, enabled) }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 同时下载图片数：**只有 1～12 这几个数值，没有"自动"档**（用户 2026-10-15）。
     *
     * 走 [SettingDefaults.Jm.normalizeDownloadThreads]：越界值（含老版本存下的 0）都按默认值 4，
     * 不静默变成 1。
     */
    fun setDownloadThreads(threads: Int) {
        val value = SettingDefaults.Jm.normalizeDownloadThreads(threads)
        _state.update { it.copy(downloadThreads = value) }
        viewModelScope.launch { settings.putJmInt(SettingKeys.Jm.DOWNLOAD_THREADS, value) }
    }

    /** [index] 是 `DownloadNetwork.entries` 的下标：0 = 仅 Wi-Fi，1 = 允许移动网络。 */
    fun setDownloadNetwork(index: Int) {
        val policy = DownloadNetwork.entries.getOrElse(index) { DownloadNetwork.ANY }
        viewModelScope.launch {
            settings.putJmInt(SettingKeys.Jm.DOWNLOAD_NETWORK, policy.ordinal)
            refreshResolved()
        }
    }

    // ------------------------------------------------------------------
    // 导出默认密码（原文写加密凭据存储，空串 = 清除）
    // ------------------------------------------------------------------

    fun saveZipPassword(password: String) {
        // 存的和显示的是同一个值（都按同一份规则净化过），避免"输入框显示 A、实际存的是 B"
        val value = sanitizeExportPassword(password)
        secrets.put(SettingKeys.SECRET_JM_ZIP_PASSWORD, value)
        _state.update { it.copy(zipPassword = value) }
        notify(if (value.isEmpty()) "已清空压缩包默认密码" else "压缩包默认密码已保存")
    }

    fun savePdfPassword(password: String) {
        val value = sanitizeExportPassword(password)
        secrets.put(SettingKeys.SECRET_JM_PDF_PASSWORD, value)
        _state.update { it.copy(pdfPassword = value) }
        notify(if (value.isEmpty()) "已清空 PDF 默认密码" else "PDF 默认密码已保存")
    }

    // ------------------------------------------------------------------
    // 网络与线路
    // ------------------------------------------------------------------

    fun setTimeoutSeconds(seconds: Int) {
        val value = seconds.coerceIn(SettingLimits.REQUEST_TIMEOUT_SECONDS.first, SettingLimits.REQUEST_TIMEOUT_SECONDS.last)
        _state.update { it.copy(timeoutSeconds = value) }
        viewModelScope.launch {
            settings.putJmInt(SettingKeys.Jm.REQUEST_TIMEOUT_SECONDS, value)
            refreshResolved()
        }
    }

    /**
     * 图片下载超时：与接口超时同属"冷启动才生效"（两个客户端都在首次访问时构建）。
     * 滑杆即时落存储，重启应用后生效（UI 上有橙色提示）。
     */
    fun setImageTimeoutSeconds(seconds: Int) {
        val value = seconds.coerceIn(
            SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS.first,
            SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS.last,
        )
        _state.update { it.copy(imageTimeoutSeconds = value) }
        viewModelScope.launch {
            settings.putJmInt(SettingKeys.Jm.IMAGE_REQUEST_TIMEOUT_SECONDS, value)
            refreshResolved()
        }
    }

    /**
     * 保存代理设置（含密码）。
     *
     * 校验复用 [ResolvedNetworkConfig.validate]：它给出的是**用户能看懂的中文文案**
     * （"代理地址只能包含字母、数字、点、横线和冒号"这类），页面不自己写一份判断。
     * 校验不通过就**不写存储**，页面上的草稿原样保留（计划 §9.2：失败保留原值）。
     */
    fun saveProxy(draft: ProxyDraft) {
        val host = draft.host.trim()
        val username = draft.username.trim()
        val password = draft.password.trim()
        val portValue = draft.port.trim().toIntOrNull()

        if (draft.type != ProxyType.NONE && portValue == null) {
            notify("代理端口需要填 1～65535 的数字")
            return
        }
        val probe = ResolvedNetworkConfig(
            timeoutSeconds = _state.value.timeoutSeconds,
            imageTimeoutSeconds = _state.value.imageTimeoutSeconds,
            proxyType = draft.type,
            proxyHost = ResolvedNetworkConfig.normalizedHost(host),
            proxyPort = portValue ?: 0,
            proxyUsername = username,
            proxyPassword = null,
            downloadNetwork = DownloadNetwork.ANY,
        )
        probe.validate().firstOrNull()?.let { error ->
            notify(error.message)
            return
        }

        _state.update {
            it.copy(
                proxyType = draft.type,
                proxyHost = host,
                proxyPort = portValue?.toString().orEmpty(),
                proxyUsername = username,
                proxyPassword = password,
            )
        }
        viewModelScope.launch {
            settings.putJmInt(SettingKeys.Jm.PROXY_TYPE, draft.type.ordinal)
            settings.putJmString(SettingKeys.Jm.PROXY_HOST, host)
            settings.putJmInt(SettingKeys.Jm.PROXY_PORT, portValue ?: 0)
            settings.putJmString(SettingKeys.Jm.PROXY_USERNAME, username)
            // 空串等于清掉（SecretStore 的约定：null / 空串 = 删除）
            secrets.put(SettingKeys.SECRET_JM_PROXY_PASSWORD, password)
            refreshResolved()
            notify("代理设置已保存")
        }
    }

    /**
     * 选一条接口线路：**即时生效**，没有「保存」按钮。
     *
     * 为什么写入后立刻就能用：`AppContainer.jmApi` 每次取用都重新构造，线路通过
     * `apiDomainProvider` 现读存储，所以下一次请求就走新线路（进行中的请求不受影响）。
     * 只写一个域名（不再写"换行列表"），读侧见 `LineCatalog.apiLineOf`。
     *
     * **不弹提示**（用户 2026-10-18 要求）：选择面板点完不关，用户可以接着试下一条，
     * 每点一次弹一条 Snackbar 既吵又会被面板挡住；反馈由面板里的「使用中 + ✓」和
     * 分组里那行域名负责（两者都是即时更新的）。
     */
    fun selectApiLine(host: String) = applyApiLine(host)

    /** 选一条图片线路：即时生效（章节图片与"边看边下"的取图都按它走），同样不弹提示。 */
    fun selectImageLine(host: String) = applyImageLine(host)

    /** 接口线路「恢复默认」：回到工程当前在用的那条（[LineCatalog.DEFAULT_API_HOST]）。 */
    fun resetApiLine() = applyApiLine(LineCatalog.DEFAULT_API_HOST)

    /** 图片线路「恢复默认」：回到工程当前在用的那条（[LineCatalog.DEFAULT_IMAGE_HOST]）。 */
    fun resetImageLine() = applyImageLine(LineCatalog.DEFAULT_IMAGE_HOST)

    /**
     * 立即生效：先改界面（选中态/外部那行文字马上跟上），再落存储。
     *
     * 写失败（DataStore 写异常极少见）不特殊处理——界面已经切了，
     * 下次进页面会按存储里的值显示回真实状态。
     */
    private fun applyApiLine(host: String) {
        val line = LineCatalog.apiLineOf(host)
        _state.update { it.copy(apiLine = line) }
        viewModelScope.launch { settings.putJmString(SettingKeys.Jm.API_DOMAINS, line.host) }
    }

    private fun applyImageLine(host: String) {
        val line = LineCatalog.imageLineOf(host)
        _state.update { it.copy(imageLine = line) }
        viewModelScope.launch { settings.putJmString(SettingKeys.Jm.IMAGE_DOMAINS, line.host) }
    }

    /**
     * 全部线路同时测速。
     *
     * **并发**（每条线路一个 `launch`，各自独立超时），并且**逐条回填**结果：
     * 快的线路先出灯号，慢的还在转，不用等最慢的那条。已经在测时重复点直接忽略。
     */
    fun testAllApiLines() {
        if (_state.value.speedTesting) return
        val hosts = LineCatalog.apiLines.map { it.host }
        _state.update { it.copy(speedTesting = true, speedResults = emptyMap()) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    hosts.forEach { host ->
                        launch {
                            val result = lineProbe.probe(host)
                            _state.update { current ->
                                current.copy(speedResults = current.speedResults + (host to result))
                            }
                        }
                    }
                }
            } finally {
                _state.update { it.copy(speedTesting = false) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 高级
    // ------------------------------------------------------------------

    fun setRetry(field: RetryField, value: Int) {
        val clamped = value.coerceIn(SettingLimits.JM_RETRY.first, SettingLimits.JM_RETRY.last)
        _state.update {
            when (field) {
                RetryField.ALBUM -> it.copy(retryAlbum = clamped)
                RetryField.SEARCH -> it.copy(retrySearch = clamped)
                RetryField.CHAPTER -> it.copy(retryChapter = clamped)
            }
        }
        viewModelScope.launch { settings.putJmInt(field.key, clamped) }
    }

    // ------------------------------------------------------------------
    // 恢复默认
    // ------------------------------------------------------------------

    /**
     * 恢复默认：清空全部偏好（应用只有漫画一种模式，也就只有这一份）。
     * 不动书库与已下载文件、**不动凭据**（zip/pdf/代理密码单独清除）。
     */
    fun resetSettings() {
        viewModelScope.launch {
            settings.resetJm()
            reload()
            // 线路偏好被清掉后 reload 会回落到清单里的默认线路（工程当前在用的那两条），
            // 所以「恢复默认」之后线路就是默认线路，不需要额外写一次。
            notify("已恢复默认设置")
        }
    }
}

/**
 * 应用设置页（「更多」→「应用设置」）。
 *
 * 页面没有自己的 Snackbar 宿主：一次性提示由 [ModeRoot] 的 [MessageEffect] 统一弹出，
 * 所以这里不需要 message 相关参数。
 *
 * 这里是**唯一的设置页**：网络与线路直接编辑生效值，没有「跟随全局 / 本模式自定义」
 * 那一层（全局作用域已取消，见 `SettingsRepository`）。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    /** 返回上一级（本页是「更多」下面的二级页，底栏在这一层隐藏，所以需要可见的返回按钮）。 */
    onBack: () -> Unit,
    // 阅读与显示（「阅读背景」「亮度」不在这里——它们在阅读器的设置面板里）
    onSetReadingMode: (ReadingMode) -> Unit,
    onSetReadingDirection: (ReadingDirection) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    // 下载
    onSetDownloadThreads: (Int) -> Unit,
    /** 下标对应 `DownloadNetwork.entries`：0 = 仅 Wi-Fi，1 = 允许移动网络。 */
    onSetDownloadNetwork: (Int) -> Unit,
    // 导出
    onSaveZipPassword: (String) -> Unit,
    onSavePdfPassword: (String) -> Unit,
    // 网络与线路
    onSetTimeoutSeconds: (Int) -> Unit,
    onSetImageTimeoutSeconds: (Int) -> Unit,
    onSaveProxy: (ProxyDraft) -> Unit,
    /** 从清单里选中一条接口线路（**选中即生效**，没有「保存」）。 */
    onSelectApiLine: (String) -> Unit,
    /** 从清单里选中一条图片线路（同上）。 */
    onSelectImageLine: (String) -> Unit,
    /** 接口线路「恢复默认」：回到工程当前在用的那条。 */
    onResetApiLine: () -> Unit,
    /** 图片线路「恢复默认」。 */
    onResetImageLine: () -> Unit,
    /** 「全部测速」：所有接口线路并发测一轮。 */
    onTestApiLines: () -> Unit,
    // 高级
    onSetRetry: (RetryField, Int) -> Unit,
    // 恢复默认
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 左边留 8dp：IconButton 自带 48dp 触控区，再叠 16dp 会让标题明显右移
                .padding(start = 8.dp, end = 8.dp, top = 12.dp)
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "应用设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        // 正文区铺灰色底；头部「应用设置」那一行留白。
        //
        // **这里没有"加载中 → 内容"的占位切换**（修进入掉帧时去掉的，见 [SettingsUiState]）：
        // 首帧就直接组合这一整页，存储里的真实值回来只是若干文本/选中态变化，
        // 不会再把整页结构重建一遍——那次重建正好落在页面进入动画中间，掉的就是那一帧。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                ReadingSection(
                    state = state,
                    onSetReadingMode = onSetReadingMode,
                    onSetReadingDirection = onSetReadingDirection,
                    onSetKeepScreenOn = onSetKeepScreenOn,
                )
                DownloadSection(
                    state = state,
                    onSetDownloadThreads = onSetDownloadThreads,
                    onSetDownloadNetwork = onSetDownloadNetwork,
                )
                ExportSection(
                    state = state,
                    onSaveZipPassword = onSaveZipPassword,
                    onSavePdfPassword = onSavePdfPassword,
                )
                NetworkSection(
                    state = state,
                    onSetTimeoutSeconds = onSetTimeoutSeconds,
                    onSetImageTimeoutSeconds = onSetImageTimeoutSeconds,
                    onSaveProxy = onSaveProxy,
                )
                LineSection(
                    state = state,
                    onSelectApiLine = onSelectApiLine,
                    onSelectImageLine = onSelectImageLine,
                    onResetApiLine = onResetApiLine,
                    onResetImageLine = onResetImageLine,
                    onTestApiLines = onTestApiLines,
                )
                AdvancedSection(state = state, onSetRetry = onSetRetry)
                ResetSection(onReset = { confirmReset = true })
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "恢复默认设置？",
            text = "只重置偏好（阅读方式、下载、导出、网络与线路、高级，还有阅读器里的背景与亮度）。" +
                "不会删除书库记录、已下载的文件，也不会清空已保存的密码。",
            confirmText = "恢复默认",
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onConfirm = {
                confirmReset = false
                onResetSettings()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

// ======================================================================
// 分组
// ======================================================================

@Composable
private fun ReadingSection(
    state: SettingsUiState,
    onSetReadingMode: (ReadingMode) -> Unit,
    onSetReadingDirection: (ReadingDirection) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
) {
    SettingsSection("阅读与显示") {
        ChoiceRow(
            title = "默认阅读方式",
            hint = "打开漫画时的默认排版，阅读器里也能随时切换",
            options = ReadingMode.entries.map { it.label },
            selectedIndex = ReadingMode.entries.indexOf(state.readingMode),
            onSelect = { onSetReadingMode(ReadingMode.entries[it]) },
        )
        // 只有横向分页才有"翻页方向"这回事，纵向连续时这一行没有意义（与阅读器控制栏一致）
        if (state.readingMode == ReadingMode.HORIZONTAL) {
            CardDivider()
            ChoiceRow(
                title = "翻页方向",
                options = ReadingDirection.entries.map { it.label },
                selectedIndex = ReadingDirection.entries.indexOf(state.readingDirection),
                onSelect = { onSetReadingDirection(ReadingDirection.entries[it]) },
            )
        }
        // 阅读背景与亮度**故意不在这里**：它们跟着每次阅读的环境走，
        // 要改也是进阅读器再改（阅读器控制栏的小齿轮里都有），这里放了也是白放。
        CardDivider()
        SwitchRow(
            title = "阅读时保持亮屏",
            hint = "只在阅读器前台时生效",
            checked = state.keepScreenOn,
            onCheckedChange = onSetKeepScreenOn,
        )
    }
}

@Composable
private fun DownloadSection(
    state: SettingsUiState,
    onSetDownloadThreads: (Int) -> Unit,
    onSetDownloadNetwork: (Int) -> Unit,
) {
    SettingsSection("下载") {
        SliderRow(
            title = "同时下载图片数",
            // 没有"自动"档（用户 2026-10-15 取消）：滑杆就是 1～12 的数值，默认停在 4。
            // 也**没有任何"按内存/CPU 核数算"的逻辑**——以前那句"自动值会参考设备内存"
            // 是照计划 §9.3 的意图写的、代码里从没实现过，所以整档一起去掉了。
            hint = "可同时下载图片张数",
            help = "拉高配置可一定程度提升总体下载速度的下限，但上限取决于网速与手机CPU线程数。",
            value = state.downloadThreads.toFloat(),
            range = SettingLimits.DOWNLOAD_THREADS.first.toFloat()..
                SettingLimits.DOWNLOAD_THREADS.last.toFloat(),
            // Compose 的 steps 是"两端之间"的分段数：1～12 有 12 个整数档位 → 10 段
            steps = SettingLimits.DOWNLOAD_THREADS.last - SettingLimits.DOWNLOAD_THREADS.first - 1,
            label = { "${it.roundToInt()}" },
            onCommit = { onSetDownloadThreads(it.roundToInt()) },
        )
        CardDivider()
        ChoiceRow(
            title = "下载网络",
            hint = "仅 Wi-Fi 时移动网络下不下载，任务会停在「等待网络」",
            options = DownloadNetwork.entries.map { it.label },
            selectedIndex = DownloadNetwork.entries.indexOf(state.resolved?.downloadNetwork ?: DownloadNetwork.ANY),
            onSelect = onSetDownloadNetwork,
        )
    }
}

@Composable
private fun ExportSection(
    state: SettingsUiState,
    onSaveZipPassword: (String) -> Unit,
    onSavePdfPassword: (String) -> Unit,
) {
    SettingsSection("导出") {
        ExportPasswordEditor(
            title = "压缩包默认密码",
            hint = "导出 ZIP 时的密码预填值，留空表示不加密（最多 ${SettingLimits.EXPORT_PASSWORD_MAX_CHARS} 个字符）",
            value = state.zipPassword,
            onSave = onSaveZipPassword,
        )
        CardDivider()
        ExportPasswordEditor(
            title = "PDF 默认密码",
            hint = "导出 PDF 时的密码预填值，留空表示不加密（最多 ${SettingLimits.EXPORT_PASSWORD_MAX_CHARS} 个字符）",
            value = state.pdfPassword,
            onSave = onSavePdfPassword,
        )
        // 输入框是硬描边、没有文字行高那层留白，编辑器自带的 6dp 底边距看着会贴住卡片。
        // 这里只在**卡内容末尾**补一点，和上面的 6dp 凑成 12dp；行与行之间的间距不动。
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun NetworkSection(
    state: SettingsUiState,
    onSetTimeoutSeconds: (Int) -> Unit,
    onSetImageTimeoutSeconds: (Int) -> Unit,
    onSaveProxy: (ProxyDraft) -> Unit,
) {
    // 两条线路挪到了下面的「线路」分组（选中即生效），这里只剩超时与代理
    SettingsSection("网络") {
        SliderRow(
            title = "请求超时",
            hint = "连接与单次读写的等待上限",
            help = "指 connect（建立连接）、read（读取）、write（发送）各自的等待上限，" +
                "不是整次请求或整个下载的总时长。修改后需重启应用才生效。",
            needsRestart = true,
            value = state.timeoutSeconds.toFloat(),
            range = SettingLimits.REQUEST_TIMEOUT_SECONDS.first.toFloat()..
                SettingLimits.REQUEST_TIMEOUT_SECONDS.last.toFloat(),
            label = { "${it.roundToInt()} 秒" },
            onCommit = { onSetTimeoutSeconds(it.roundToInt()) },
        )
        CardDivider()
        SliderRow(
            title = "图片下载超时",
            hint = "单张图片每次读写的等待上限",
            help = "指下载章节图片与封面时，connect / read / write 各自的等待上限，" +
                "不是整张图的总下载时长（大图慢慢下不会被它掐断）。" +
                "修改后需重启应用才生效。",
            needsRestart = true,
            value = state.imageTimeoutSeconds.toFloat(),
            range = SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS.first.toFloat()..
                SettingLimits.IMAGE_REQUEST_TIMEOUT_SECONDS.last.toFloat(),
            label = { "${it.roundToInt()} 秒" },
            onCommit = { onSetImageTimeoutSeconds(it.roundToInt()) },
        )
        CardDivider()
        ProxyEditor(state = state, onSaveProxy = onSaveProxy)
    }
}

/**
 * 「线路」分组：接口线路 + 图片线路，两行都是**点开选择面板 → 选中即生效**。
 *
 * 四条设计上的约定（对应用户要求）：
 * 1. **当前线路直接显示在分组里**，不用点开面板就知道自己在用哪一条（每行的第二行就是域名）；
 * 2. **没有「保存」按钮**：面板里点一条就写存储、下一次请求立刻走新线路；
 * 3. **点完不自动关面板**（用户 2026-10-18 要求）：可以接着点下一条比较，
 *    什么时候退出由用户自己决定（**拖把手下滑**、点遮罩，或面板里的「关闭」）；
 * 4. **可选值全部来自硬编码清单**（`LineCatalog`），用户不能再手填域名；
 *    面板里另给「恢复默认」（回到工程当前在用的那条）和接口线路的「全部测速」。
 */
@Composable
private fun LineSection(
    state: SettingsUiState,
    onSelectApiLine: (String) -> Unit,
    onSelectImageLine: (String) -> Unit,
    onResetApiLine: () -> Unit,
    onResetImageLine: () -> Unit,
    onTestApiLines: () -> Unit,
) {
    var showApiPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }

    // 两个面板的列表状态提到这里（而不是在 LinePickerSheet 里 remember）：
    // 面板一关就整个移出组合，滚动位置会丢；提到这一层才能在"关掉再打开"之后
    // 回到上次看到的那几条。
    val apiListState = rememberLazyListState()
    val imageListState = rememberLazyListState()

    SettingsSection("线路") {
        LineSummaryRow(
            title = "接口线路",
            hint = "获取漫画详情、图片清单与搜索都走这条线路",
            host = state.apiLine.host,
            label = state.apiLine.label,
            isDefault = LineCatalog.isDefaultApi(state.apiLine.host),
            onClick = { showApiPicker = true },
        )
        CardDivider()
        LineSummaryRow(
            title = "图片线路",
            hint = "下载章节图片、边看边下取图都走这条线路",
            host = state.imageLine.host,
            label = null,
            isDefault = LineCatalog.isDefaultImage(state.imageLine.host),
            onClick = { showImagePicker = true },
        )
    }

    if (showApiPicker) {
        LinePickerSheet(
            title = "接口线路",
            hint = "不同网络下,同一条线路的延迟可能不同哦！",
            options = remember(state.apiLine) { apiPickerOptions(state.apiLine) },
            selectedHost = state.apiLine.host,
            defaultHost = LineCatalog.DEFAULT_API_HOST,
            speedResults = state.speedResults,
            testing = state.speedTesting,
            listState = apiListState,
            // 选中即生效，**面板不关**（用户要求）：可以接着点下一条比较，关不关由用户自己决定
            onSelect = { onSelectApiLine(it) },
            onReset = { onResetApiLine() },
            onTest = onTestApiLines,
            onDismiss = { showApiPicker = false },
            autoTest = true,
        )
    }

    if (showImagePicker) {
        LinePickerSheet(
            title = "图片线路",
//            hint = null,
            options = remember(state.imageLine) { imagePickerOptions(state.imageLine) },
            selectedHost = state.imageLine.host,
            defaultHost = LineCatalog.DEFAULT_IMAGE_HOST,
            // 图片线路暂不测速（用户明确不做），整列灯号都不显示
            speedResults = emptyMap(),
            testing = false,
            listState = imageListState,
            onSelect = { onSelectImageLine(it) },
            onReset = { onResetImageLine() },
            onTest = null,
            onDismiss = { showImagePicker = false },
            autoTest = false,
        )
    }
}

/**
 * 面板里的一行可选线路。
 *
 * @property title 主文字：接口线路是清单里的名字（`线路 2`），图片线路直接是域名。
 * @property subtitle 次文字：接口线路显示域名；图片线路不重复显示。
 */
private data class LineOption(
    val host: String,
    val title: String,
    val subtitle: String?,
)

/**
 * 接口线路的可选项：清单 + （若当前是清单外的历史手填值）把它自己加在最前面。
 *
 * **为什么要把当前项补进去**：老版本允许手填域名，升级用户的线路可能不在清单里。
 * 不补进去的话面板里没有任何一行是「使用中」，看起来像坏了；补进去还能让他随时切回清单里的线路。
 */
private fun apiPickerOptions(current: ApiLine): List<LineOption> = buildList {
    fun option(line: ApiLine) = LineOption(host = line.host, title = line.label, subtitle = line.host)
    if (!current.builtIn) add(option(current))
    LineCatalog.apiLines.forEach { add(option(it)) }
}.distinctBy { it.host }

/** 图片线路的可选项，规则同上（清单外的手填值排第一）。 */
private fun imagePickerOptions(current: ImageLine): List<LineOption> = buildList {
    if (!current.builtIn) add(LineOption(host = current.host, title = current.host, subtitle = null))
    LineCatalog.imageLines.forEach { add(LineOption(host = it.host, title = it.host, subtitle = null)) }
}.distinctBy { it.host }

/**
 * 分组里的「当前线路」行：左边名称 + 当前域名，右边一颗「更换」。
 *
 * 整行可点（不是只有「更换」两个字可点）：手机上点一整行比点一个小按钮容易得多，
 * 而且这一行的唯一动作就是"换线路"，不存在误触后果。
 */
@Composable
private fun LineSummaryRow(
    title: String,
    hint: String,
    host: String,
    label: String?,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                // 名字（线路 2）跟在标题后面，当"这是清单里的第几条"的线索
                if (label != null && label != host) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (isDefault) MiniTag(text = "默认", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ★ 当前线路在分组里直接可读，不用点开面板
            Text(
                text = host,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "更换",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 线路选择面板（底部弹出的列表）。
 *
 * 交互约定：
 * - **点一行 = 选中即生效，但面板保持打开**（用户 2026-10-18 要求）：灯号就摆在旁边，
 *   用户往往想"点一条、看有没有变快、再点下一条"，自动关掉会逼他反复重开面板。
 * - **内容区不响应上下滑动**（用户 2026-10-19 要求）：标题、按钮行、线路列表上的纵向手势
 *   只归列表（或什么也不做），面板纹丝不动——以前"上下滑着找线路"很容易把面板滑没了。
 *   实现见 `SheetGestures.kt` 的 `HandleDragOnlyBottomSheet`：两条通道（指针拖动 +
 *   列表滚到头后的嵌套滚动余量）一起掐掉，**把手**照旧能拖。
 * - **退出靠**：**拖顶部把手下滑**、点遮罩、返回键，或面板底部的「关闭」。
 *   （「关闭」是 2026-10-19 从注释里放回来的：内容区既然不能再靠下滑关闭，
 *   就得给一个不用猜的出口，否则用户会觉得"面板关不掉了"。）
 * - **不弹 Snackbar**：提示会被面板挡住、连点几次还会排队；反馈就是这一行的
 *   「使用中 + ✓」和分组里那行域名，两者都即时更新。
 * - 「全部测速」并发测所有接口线路，结果逐条回填到每行右边的灯号上；
 * - 面板**第一次打开会自动测一轮**（[autoTest]）：用户点开的目的就是找"哪条快"，
 *   让他先看一堆"未测速"再点一次按钮是多余的一步；同一页之后的打开复用已有结果。
 *
 * 图片线路的面板传 `onTest = null` 且 `autoTest = false`：图片线路暂不做测速。
 *
 * @param listState 列表状态**由调用方持有**（`LineSection`）：它交给 `LazyColumn`，
 *   并且因为活在面板外面，面板关掉再打开时滚动位置还在。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinePickerSheet(
    title: String,
    hint: String? = null,
    options: List<LineOption>,
    selectedHost: String,
    defaultHost: String,
    speedResults: Map<String, LineProbeResult>,
    testing: Boolean,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    onTest: (() -> Unit)?,
    onDismiss: () -> Unit,
    autoTest: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 第一次打开且还没有任何结果时，自动测一轮（见上面 KDoc）
    if (autoTest && onTest != null) {
        LaunchedEffect(Unit) {
            if (speedResults.isEmpty() && !testing) onTest()
        }
    }

    // 内容区（标题、按钮、线路列表）不响应上下滑动，只有把手能拖着关（详见 SheetGestures.kt）
    HandleDragOnlyBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            hint?.let {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                if (onTest != null) {
                    TextButton(onClick = onTest, enabled = !testing) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(if (testing) "测速中…" else "全部测速")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset, enabled = selectedHost != defaultHost) {
                    Text("恢复默认")
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            ) {
                items(options, key = { it.host }) { option ->
                    LineOptionRow(
                        option = option,
                        selected = option.host == selectedHost,
                        isDefault = option.host == defaultHost,
                        speed = speedResults[option.host],
                        speedEnabled = onTest != null,
                        testing = testing,
                        onClick = { onSelect(option.host) },
                    )
                }
            }
            // 显式出口：列表区不再能下滑关面板之后，这颗按钮是"不用猜"的那条路
            // （见 KDoc 第 3 条）。2026-10-18 曾按用户要求注释掉，2026-10-19 恢复。
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
//            ) {
//                Spacer(Modifier.weight(1f))
//                TextButton(onClick = onDismiss) { Text("关闭") }
//            }
        }
    }
}

/** 面板里的一行：标题 + 域名 + （可选）测速灯号 + 选中标记。 */
@Composable
private fun LineOptionRow(
    option: LineOption,
    selected: Boolean,
    isDefault: Boolean,
    speed: LineProbeResult?,
    speedEnabled: Boolean,
    /** 正在测速：还没有结果的行显示「测速中…」而不是「未测速」。 */
    testing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 选中行铺一层很淡的主色：一眼能看出"在用哪条"，又不至于像标签一样抢眼
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (selected) {
                    MiniTag(text = "使用中", color = MaterialTheme.colorScheme.primary)
                } else if (isDefault) {
                    MiniTag(text = "默认", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            option.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (speedEnabled) {
            SpeedBadge(result = speed, pending = testing)
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
    }
}

/**
 * 测速灯号：一个圆点 + 一句短文案。
 *
 * 三档阈值与官方 App 一致（`feature/api/speedLevelOf`）；
 * 失败只显示「超时 / 不可用」这种短词——完整原因（"连不上服务器，请检查网络或代理设置"）
 * 放不进这一行，也没必要：用户要的是"这条能不能用"。
 *
 * @param pending 这一轮测速还没轮到自己（结果还没回填）时显示「测速中…」，
 *   免得用户看到一整列「未测速」以为按钮没反应。
 */
@Composable
private fun SpeedBadge(result: LineProbeResult?, pending: Boolean) {
    val level = result?.level ?: LineSpeedLevel.UNKNOWN
    val color = when (level) {
        LineSpeedLevel.SMOOTH -> SpeedSmoothColor
        LineSpeedLevel.NORMAL -> RestartHintColor
        LineSpeedLevel.LAGGY, LineSpeedLevel.FAILED -> SpeedSlowColor
        LineSpeedLevel.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val text = when (level) {
        LineSpeedLevel.UNKNOWN -> if (pending) "测速中…" else "未测速"
        LineSpeedLevel.SMOOTH, LineSpeedLevel.NORMAL, LineSpeedLevel.LAGGY -> "${result?.latencyMs} ms"
        LineSpeedLevel.FAILED -> when {
            result?.error?.contains("超时") == true -> "超时"
            result?.error?.contains("证书") == true -> "证书错误"
            else -> "不可用"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (level == LineSpeedLevel.UNKNOWN) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                color
            },
        )
    }
}

/** 小号标签（「使用中」「默认」）：细描边 + 主色/次要色文字，不抢标题的注意力。 */
@Composable
private fun MiniTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun AdvancedSection(
    state: SettingsUiState,
    onSetRetry: (RetryField, Int) -> Unit,
) {
    SettingsSection("高级") {
        Text(
            text = "请求失败后自动重试的次数，0 表示不自动重试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        RetrySlider(RetryField.ALBUM, state.retryAlbum, onSetRetry)
        CardDivider()
        RetrySlider(RetryField.SEARCH, state.retrySearch, onSetRetry)
        CardDivider()
        RetrySlider(RetryField.CHAPTER, state.retryChapter, onSetRetry)
    }
}

@Composable
private fun RetrySlider(
    field: RetryField,
    value: Int,
    onSetRetry: (RetryField, Int) -> Unit,
) {
    SliderRow(
        title = field.label,
        value = value.toFloat(),
        range = SettingLimits.JM_RETRY.first.toFloat()..SettingLimits.JM_RETRY.last.toFloat(),
        steps = SettingLimits.JM_RETRY.last - SettingLimits.JM_RETRY.first - 1,
        label = { if (it.roundToInt() == 0) "不自动重试" else "${it.roundToInt()} 次" },
        onCommit = { onSetRetry(field, it.roundToInt()) },
    )
}

@Composable
private fun ResetSection(onReset: () -> Unit) {
    SettingsSection("恢复默认") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("恢复默认设置", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "重置成默认设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onReset,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("重置")
            }
        }
    }
}

// ======================================================================
// 代理编辑块
// ======================================================================

/**
 * 代理编辑块。
 *
 * 地址 / 端口 / 用户名 / 密码四个字段一起用「保存代理设置」提交（自由文本要先校验），
 * 校验失败时草稿留在页面上，提示由根部的 Snackbar 弹。
 */
@Composable
private fun ProxyEditor(
    state: SettingsUiState,
    onSaveProxy: (ProxyDraft) -> Unit,
) {
    // 草稿：以存储值为初始值；用户在字段里改的东西不写进 state，点保存才提交
    var type by remember(state.proxyType) { mutableStateOf(state.proxyType) }
    var host by remember(state.proxyHost) { mutableStateOf(state.proxyHost) }
    var port by remember(state.proxyPort) { mutableStateOf(state.proxyPort) }
    var username by remember(state.proxyUsername) { mutableStateOf(state.proxyUsername) }
    var password by remember(state.proxyPassword) { mutableStateOf(state.proxyPassword) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ChoiceRow(
            title = "代理",
            hint = "不使用 / HTTP / SOCKS",
            help = "代理地址、端口、用户名与密码都保存在本地；" +
                "用户名留空表示不走认证。修改后需重启应用才生效。",
            needsRestart = true,
            options = listOf("不使用", "HTTP", "SOCKS"),
            selectedIndex = type.ordinal,
            onSelect = { type = ProxyType.entries[it] },
            innerPadding = false,
        )
        if (type != ProxyType.NONE) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                label = { Text("代理地址") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                singleLine = true,
                label = { Text("代理端口") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text("代理用户名（可选）") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            // 密码只在选了代理时才出现：不用代理就不需要认证密码
            PasswordField(
                title = "代理密码",
                hint = "可选；留空表示代理不需要认证",
                value = password,
                onValueChange = { password = it },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { onSaveProxy(ProxyDraft(type, host, port, username, password)) },
            ) {
                Text("保存代理设置")
            }
        }
    }
}

// ======================================================================
// 基础行
// ======================================================================

/**
 * 分组标题 + 一张白卡。
 *
 * 主题是「灰底白卡」（见 ui/theme/Theme.kt）：正文区铺 `background` 灰，
 * **内容一律放在纯白卡上**——任务页的任务卡、详情页的各信息块都是这么做的，
 * 分隔线也只出现在卡内，不直接画在灰底上。
 * 标题留在卡外的灰底上（主色小字），是卡片的标签而不是卡里的第一行。
 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
        )
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** 卡内的行分隔线：与主题的描边色一致（`outlineVariant`），不缩进到卡边之外。 */
@Composable
private fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 「需要重启应用才生效」的提示色。
 *
 * `MaterialTheme` 调色板里没有橙色（primary / error 都不合适），所以自定义一个常量。
 * 值偏深，浅色底上可读；深浅主题共用同一个值。
 */
private val RestartHintColor = Color(0xFFE8590C)

/**
 * 线路测速灯号的两个固定色值。
 *
 * 与 [RestartHintColor] 同理：`MaterialTheme` 调色板里没有"绿 / 红"这种语义槽位
 * （`primary` 是品牌蓝、`error` 只表示错误），而测速灯号需要和官方 App 一致的三档观感。
 * 橙色直接复用 [RestartHintColor]。三个值都取偏深的色值，浅色底上可读，
 * 深色主题下也只是偏暗一点，不刺眼。
 */
private val SpeedSmoothColor = Color(0xFF2E7D32)
private val SpeedSlowColor = Color(0xFFC62828)

/**
 * 行标题：标题 +（可选）问号图标。
 *
 * 说明太长时，hint 只留一句简要描述、完整说明放 [help]，点问号弹框看，
 * 免得把设置行一行行撑高。
 */
@Composable
private fun SettingsTitleRow(
    title: String,
    help: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (help != null) {
            HelpBadge(onClick = { showHelp = true })
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
    if (showHelp && help != null) {
        HelpDialog(title = title, text = help, onDismiss = { showHelp = false })
    }
}

/**
 * 问号图标：一个描边圆圈 + 圈里的「?」。
 *
 * 不走图标资源：`material-icons-core` 里没有问号 / 帮助系列，
 * 而工程明确不引 `material-icons-extended`（见 app/build.gradle.kts）。
 * 32dp 是触控区，视觉上的圆圈只有 16dp。
 */
@Composable
private fun HelpBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 说明弹框：只给一颗「知道了」（这里没有可取消的动作）。 */
@Composable
private fun HelpDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/**
 * 行的说明文字：普通部分用次要色；[needsRestart] 为真时在末尾追一句橙色的
 * 「· 重启应用后生效」，提示这类配置不是即时生效的。
 */
@Composable
private fun HintText(hint: String, needsRestart: Boolean) {
    val text = if (needsRestart) {
        buildAnnotatedString {
            append(hint)
            append("  ")
            withStyle(SpanStyle(color = RestartHintColor, fontWeight = FontWeight.Medium)) {
                append("· 重启应用后生效")
            }
        }
    } else {
        AnnotatedString(hint)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 开关行：名称 + 说明 +（可展开的自定义区）+ 开关。 */
@Composable
private fun SwitchRow(
    title: String,
    hint: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String? = null,
    needsRestart: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SettingsTitleRow(title = title, help = help)
            hint?.let { HintText(hint = it, needsRestart = needsRestart) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 单选行：名称 + 说明 + 一排芯片，选中即时生效。 */
@Composable
private fun ChoiceRow(
    title: String,
    hint: String? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    innerPadding: Boolean = true,
    help: String? = null,
    needsRestart: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (innerPadding) 16.dp else 0.dp,
                vertical = 8.dp,
            ),
    ) {
        SettingsTitleRow(title = title, help = help)
        hint?.let { HintText(hint = it, needsRestart = needsRestart) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, text ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    label = { Text(text) },
                )
            }
        }
    }
}

/**
 * 滑杆行：名称 + 当前值 + 滑杆。
 *
 * 拖完（`onValueChangeFinished`）才落到存储：拖动过程只改本地草稿，
 * 少写一堆中间值，也不会因为重组把手指下的位置弹回去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onCommit: (Float) -> Unit,
    hint: String? = null,
    steps: Int = 0,
    help: String? = null,
    needsRestart: Boolean = false,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingsTitleRow(title = title, help = help) {
            Text(
                text = label(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        hint?.let { HintText(hint = it, needsRestart = needsRestart) }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onCommit(draft) },
            thumb = { SliderDotThumb() },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    // 末端收成直角（8dp 那种圆角是朝滑块「鼓」出来的，方向相反），
                    // 并让轨道一直伸到滑块边缘；露在外面的那截由滑块外围的背景色圆遮成凹弧。
                    trackInsideCornerSize = 0.dp,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}

/**
 * 圆形滑块（圆点），替代 Material3 默认的 4dp × 44dp 竖条。
 *
 * 尺寸自己定：20dp 接近旧版 Material 的观感；太小会显得"点不准"，
 * 但触控区域是 Slider 自己管的，不受这里尺寸影响，所以 18～24dp 都安全。
 */
@Composable
private fun SliderDotThumb() {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .size(25.dp)
            // 在圆点外围再铺一圈背景色：盖住轨道末端，让轨道以「向内凹的弧」包住滑块（( O ) 那种观感）。
            // 半径 = 拇指半径 12.5dp + 留白 2.5dp，想改留白就改这个数值；但必须 > 14.9dp，
            // 否则盖不住轨道端的直角，轨道两侧会露出小凸角。
            .drawBehind { drawCircle(color = surface, radius = 15.dp.toPx()) }
            .background(primary, CircleShape),
        contentAlignment = Alignment.Center,   // 让白圈居中
    ) {
        Box(modifier = Modifier.size(12.dp)          // 白圈外径
                .border(width = 14.dp,               // 圈线粗细
                    color = surface,
                    shape = CircleShape)
        )
    }
}


/**
 * 密码输入框（代理密码用它）：原文直接预填，默认用圆点遮住，右边的「显示 / 隐藏」用来核对。
 *
 * 没查 `material-icons-core` 里有没有眼睛图标——那一套里没有 Visibility 系列，
 * 所以这里跟 [AlbumDetailScreen] 的导出密码框保持一致，用文字按钮切换。
 *
 * **导出那两个密码不用这个**：按用户要求，它们是明文显示、右边那颗按钮直接就是「保存」，
 * 见 [ExportPasswordEditor]。
 */
@Composable
private fun PasswordField(
    title: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text("密码") },
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/**
 * 导出默认密码（ZIP / PDF）那一行：**明文显示 + 输入框里那颗按钮就是「保存」**。
 *
 * 按用户要求定的三条规则：
 * 1. **明文显示**：不再套 `PasswordVisualTransformation`，也没有「显示 / 隐藏」——
 *    这一页是设置页，看到的应当就是导出时会被预填的那个值；
 * 2. **「显示 / 隐藏」按钮改成「保存」**，同时**去掉下面 Row 里那颗保存按钮**
 *    （一颗按钮干一件事，不重复）；
 * 3. **最多 [SettingLimits.EXPORT_PASSWORD_MAX_CHARS] 个字符，且去掉首尾空白与换行回车**：
 *    打字/粘贴时按 [filterExportPasswordInput] 净化（去 `\n`、`\r`、截断），
 *    点「保存」时再按 [sanitizeExportPassword] 去掉首尾空白，并把净化后的值写回输入框
 *    （否则框里会留着那颗已经被 trim 掉的空格，看起来像"没保存成功"）。
 */
@Composable
private fun ExportPasswordEditor(
    title: String,
    hint: String,
    value: String,
    onSave: (String) -> Unit,
) {
    // 读出来的历史值也过一遍净化：框里显示的永远满足"≤ 30 字符、无首尾空白与换行"
    var draft by remember(value) { mutableStateOf(sanitizeExportPassword(value)) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = filterExportPasswordInput(it) },
            singleLine = true,
            label = { Text("密码") },
            trailingIcon = {
                TextButton(
                    onClick = {
                        val saved = sanitizeExportPassword(draft)
                        draft = saved
                        onSave(saved)
                    },
                ) {
                    Text("保存")
                }
            },
            // 离开输入框时就把首尾空白去掉（没点「保存」也不会一直挂着一串空格）；
            // 中间的空格保留——边打字边 trim 会让空格根本打不出来。
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .onFocusChanged { if (!it.isFocused) draft = sanitizeExportPassword(draft) },
        )
    }
}
