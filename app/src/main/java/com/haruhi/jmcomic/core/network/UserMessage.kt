package com.haruhi.jmcomic.core.network

/**
 * 把异常翻译成**用户能看懂**的文案（计划 §9.1「失败重试不清空已有数据」，
 * 以及"所有用户界面只展示易懂的名称、说明、当前值及操作"）。
 *
 * 为什么放在 `core.network` 而不是 UI 层：下载执行器（`DownloadManager`）
 * 写失败原因时也需要它，而任务表里的 `message` 是要直接展示给人的。
 * 让数据层依赖 UI 包是反向依赖，所以映射本身放在这里，UI 只负责显示。
 */
fun Throwable.toUserMessage(): String = when (this) {
    is NetworkError.Timeout -> "网络超时，请检查网络后重试"
    is NetworkError.Unreachable -> "连不上服务器，请检查网络或代理设置"
    is NetworkError.Tls -> "HTTPS 证书校验失败，可能是线路被劫持"
    is NetworkError.HttpStatus -> "服务器返回 $code，稍后再试或更换线路"
    is NetworkError.Malformed -> "返回内容异常（$detail）"
    is NetworkError.Business -> message ?: "操作失败"
    is NetworkError.Cancelled -> "已取消"
    else -> message ?: javaClass.simpleName
}
