package com.haruhi.jmcomic.feature.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haruhi.jmcomic.core.settings.DownloadNetwork
import com.haruhi.jmcomic.core.settings.ProxyType
import com.haruhi.jmcomic.core.settings.ResolvedNetworkConfig
import com.haruhi.jmcomic.core.settings.SecretStore
import com.haruhi.jmcomic.core.settings.SettingDefaults
import com.haruhi.jmcomic.core.settings.SettingKeys
import com.haruhi.jmcomic.core.settings.SettingLimits
import com.haruhi.jmcomic.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 */
data class SettingsUiState(
    val loading: Boolean = true,

    // 阅读与显示
    //
    // 「阅读背景」「亮度」**不放这一页**：它们随每次阅读的环境变，用户要改也是进了阅读器再改
    // （阅读器控制栏里的设置面板保留着这几项，和这里共用同一份存储）。
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val readingDirection: ReadingDirection = ReadingDirection.LTR,
    val keepScreenOn: Boolean = true,

    // 下载
    /** 0 = 自动，1～8 = 固定图片并发数。 */
    val downloadThreads: Int = SettingDefaults.Jm.DOWNLOAD_THREADS_AUTO,

    // 导出默认密码：原文存进加密凭据存储，页面上默认以圆点显示，点"显示"才看得到
    val zipPassword: String = "",
    val pdfPassword: String = "",

    // 网络与线路
    /** 请求超时（秒）。 */
    val timeoutSeconds: Int = SettingDefaults.Jm.REQUEST_TIMEOUT_SECONDS,
    /** 代理设置（密码存在加密凭据存储里，读出来只用于这一页的显示）。 */
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    /** 代理密码原文（默认圆点显示；留空 = 不走认证）。 */
    val proxyPassword: String = "",
    /** 接口线路 / 图片线路：换行分隔的域名列表，第一项是当前线路。 */
    val apiDomains: String = "",
    val imageDomains: String = "",
    /** 当前生效的网络配置：下载网络那一行的"当前生效"文案用它。 */
    val resolved: ResolvedNetworkConfig? = null,

    // 高级
    val retryAlbum: Int = 0,
    val retrySearch: Int = 0,
    val retryChapter: Int = 0,

    val message: String? = null,
)

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
 * - **代理字段、密码与两条线路**是自由文本，保留「保存」按钮，代理还要先校验，失败保留原值；
 * - 密码三个（代理 / ZIP / PDF）原文仍然存在加密凭据存储里，页面读出来预填，
 *   默认用圆点遮住、点「显示」才看原文——省掉"已设置 / 修改 / 清除"那一套流程。
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
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
     */
    private suspend fun reload() {
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
            .jmInt(SettingKeys.Jm.DOWNLOAD_THREADS, SettingDefaults.Jm.DOWNLOAD_THREADS_AUTO)
            .first()
        val proxyType = ResolvedNetworkConfig.proxyTypeOf(
            settings.jmInt(SettingKeys.Jm.PROXY_TYPE, SettingDefaults.Jm.PROXY_NONE).first(),
        )
        val retryAlbum = settings.jmInt(SettingKeys.Jm.RETRY_ALBUM, SettingDefaults.Jm.RETRY).first()
        val retrySearch = settings.jmInt(SettingKeys.Jm.RETRY_SEARCH, SettingDefaults.Jm.RETRY).first()
        val retryChapter = settings.jmInt(SettingKeys.Jm.RETRY_CHAPTER, SettingDefaults.Jm.RETRY).first()
        val timeout = settings
            .jmInt(SettingKeys.Jm.REQUEST_TIMEOUT_SECONDS, SettingDefaults.Jm.REQUEST_TIMEOUT_SECONDS)
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
                loading = false,
                readingMode = mode,
                readingDirection = direction,
                keepScreenOn = keepScreenOn,
                downloadThreads = threads,
                zipPassword = zipPassword,
                pdfPassword = pdfPassword,
                timeoutSeconds = timeout,
                proxyType = proxyType,
                proxyHost = proxyHost,
                proxyPort = proxyPort.takeIf { it > 0 }?.toString().orEmpty(),
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
                apiDomains = apiDomains,
                imageDomains = imageDomains,
                resolved = resolved,
                retryAlbum = retryAlbum,
                retrySearch = retrySearch,
                retryChapter = retryChapter,
            )
        }
    }

    /** 写完网络相关的项之后刷新一次"有效值"，让"当前生效"那行立刻跟上。 */
    private suspend fun refreshResolved() {
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

    /** [threads] 为 0 表示自动；1～8 是固定的图片并发数（业务侧按 [SettingLimits.DOWNLOAD_THREADS] 收敛）。 */
    fun setDownloadThreads(threads: Int) {
        val value = if (threads <= 0) {
            SettingDefaults.Jm.DOWNLOAD_THREADS_AUTO
        } else {
            threads.coerceIn(SettingLimits.DOWNLOAD_THREADS.first, SettingLimits.DOWNLOAD_THREADS.last)
        }
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
        // 存的和显示的是同一个值（都去过首尾空白），避免"输入框显示 A、实际存的是 B"
        val value = password.trim()
        secrets.put(SettingKeys.SECRET_JM_ZIP_PASSWORD, value)
        _state.update { it.copy(zipPassword = value) }
        notify(if (value.isEmpty()) "已清空压缩包默认密码" else "压缩包默认密码已保存")
    }

    fun savePdfPassword(password: String) {
        val value = password.trim()
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

    fun saveApiDomains(raw: String) {
        val normalized = normalizeDomains(raw)
        _state.update { it.copy(apiDomains = normalized) }
        viewModelScope.launch {
            settings.putJmString(SettingKeys.Jm.API_DOMAINS, normalized)
            notify(if (normalized.isEmpty()) "已清空接口线路，将使用默认域名" else "接口线路已保存")
        }
    }

    fun saveImageDomains(raw: String) {
        val normalized = normalizeDomains(raw)
        _state.update { it.copy(imageDomains = normalized) }
        viewModelScope.launch {
            settings.putJmString(SettingKeys.Jm.IMAGE_DOMAINS, normalized)
            notify(if (normalized.isEmpty()) "已清空图片线路，将使用默认域名" else "图片线路已保存")
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
            notify("已恢复默认设置")
        }
    }

    private companion object {
        /**
         * 线路文本规整：逐行去空白、丢空行，再用换行拼回去。
         *
         * 空串是合法结果——业务侧读到空列表会回落到默认域名（AppContainer 里那一层兜底），
         * 所以这里不需要报错，也不需要禁止清空。
         */
        fun normalizeDomains(raw: String): String = raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
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
    onSaveProxy: (ProxyDraft) -> Unit,
    onSaveApiDomains: (String) -> Unit,
    onSaveImageDomains: (String) -> Unit,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.loading) {
                LoadingBox()
            } else {
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
                        onSaveProxy = onSaveProxy,
                        onSaveApiDomains = onSaveApiDomains,
                        onSaveImageDomains = onSaveImageDomains,
                    )
                    AdvancedSection(state = state, onSetRetry = onSetRetry)
                    ResetSection(onReset = { confirmReset = true })
                    Spacer(Modifier.height(24.dp))
                }
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
            hint = "自动值会参考设备内存；调大不一定更快",
            value = state.downloadThreads.toFloat(),
            range = 0f..8f,
            steps = 7,
            label = { if (it.roundToInt() == 0) "自动" else "${it.roundToInt()}" },
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
        PasswordEditor(
            title = "压缩包默认密码",
            hint = "导出 ZIP 时的预填值，留空表示不加密（每次导出仍会弹框确认）",
            value = state.zipPassword,
            onSave = onSaveZipPassword,
        )
        CardDivider()
        PasswordEditor(
            title = "PDF 默认密码",
            hint = "与压缩包密码互相独立，留空表示不加密",
            value = state.pdfPassword,
            onSave = onSavePdfPassword,
        )
    }
}

@Composable
private fun NetworkSection(
    state: SettingsUiState,
    onSetTimeoutSeconds: (Int) -> Unit,
    onSaveProxy: (ProxyDraft) -> Unit,
    onSaveApiDomains: (String) -> Unit,
    onSaveImageDomains: (String) -> Unit,
) {
    SettingsSection("网络与线路") {
        SliderRow(
            title = "请求超时",
            hint = "连接与单次读写的等待上限，不是整个下载的总时长",
            value = state.timeoutSeconds.toFloat(),
            range = SettingLimits.REQUEST_TIMEOUT_SECONDS.first.toFloat()..
                SettingLimits.REQUEST_TIMEOUT_SECONDS.last.toFloat(),
            label = { "${it.roundToInt()} 秒" },
            onCommit = { onSetTimeoutSeconds(it.roundToInt()) },
        )
        CardDivider()
        ProxyEditor(state = state, onSaveProxy = onSaveProxy)
    }

    SettingsSection("线路") {
        DomainsEditor(
            title = "接口线路",
            hint = "漫画详情、搜索、图片清单将使用该域名（留空使用默认域名）",
            value = state.apiDomains,
            onSave = onSaveApiDomains,
        )
        CardDivider()
        DomainsEditor(
            title = "图片线路",
            hint = "漫画图片、封面下载使用该域名（留空使用默认域名）",
            value = state.imageDomains,
            onSave = onSaveImageDomains,
        )
    }
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
// 代理与线路的编辑块
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

/**
 * 线路编辑块。
 *
 * 换行分隔的域名列表：也是自由文本，所以同样用「保存」提交。
 * 空值不禁止——业务侧读到空列表会用默认域名兜底。
 */
@Composable
private fun DomainsEditor(
    title: String,
    hint: String,
    value: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            minLines = 2,
            label = { Text("域名列表，一行一个（放在第一行的域名将生效）") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = { onSave(draft) }) { Text("保存") }
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

/** 开关行：名称 + 说明 +（可展开的自定义区）+ 开关。 */
@Composable
private fun SwitchRow(
    title: String,
    hint: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (innerPadding) 16.dp else 0.dp,
                vertical = 8.dp,
            ),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onCommit: (Float) -> Unit,
    hint: String? = null,
    steps: Int = 0,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = label(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onCommit(draft) },
        )
    }
}

/**
 * 密码输入框：原文直接预填，默认用圆点遮住，右边的「显示 / 隐藏」用来核对。
 *
 * 没查 `material-icons-core` 里有没有眼睛图标——那一套里没有 Visibility 系列，
 * 所以这里跟 [AlbumDetailScreen] 的导出密码框保持一致，用文字按钮切换。
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

/** 密码 + 「保存」：导出那两条用它（自由文本，按计划 §9.2 用保存提交）。 */
@Composable
private fun PasswordEditor(
    title: String,
    hint: String,
    value: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PasswordField(
            title = title,
            hint = hint,
            value = draft,
            onValueChange = { draft = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = { onSave(draft) }) { Text("保存") }
        }
    }
}
