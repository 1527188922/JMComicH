package com.haruhi.jmcomic.core.network

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 网络错误分类。
 *
 * UI 需要给出**可理解**的原因（计划 §9.1「失败重试不清空已有数据」、
 * §9.3「设置页错误用可理解文案」），所以错误在数据层就要分好类，
 * 不能把 `IOException` 直接抛到界面。
 */
sealed class NetworkError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 连接/读/写超时。**只有这一类才按参考实现的语义重试**（`withTimeoutRetry`）。 */
    class Timeout(val url: String, cause: Throwable) : NetworkError("网络超时：$url", cause)

    /** 连不上（DNS 失败、拒绝连接、无网络）。 */
    class Unreachable(val url: String, cause: Throwable) : NetworkError("无法连接到服务器：$url", cause)

    /** HTTP 状态码非 2xx。 */
    class HttpStatus(val url: String, val code: Int) : NetworkError("服务器返回 $code：$url")

    /** 证书/HTTPS 握手问题——注意与"没网"区分，提示文案不同。 */
    class Tls(val url: String, cause: Throwable) : NetworkError("HTTPS 证书校验失败：$url", cause)

    /**
     * 响应体为空或结构不符合预期。
     *
     * [retryable] = true 表示"**响应内容不可用**"属于可重试的一类（空体 / 非 JSON 信封）——
     * 多半是线路抖动时服务端回了个 HTML 错误页，重发一次有机会拿到正确响应。
     * 默认 false：解密后的字段解析失败等**语义**问题重试拿不到不同结果。
     */
    class Malformed(
        val url: String,
        val detail: String,
        val retryable: Boolean = false,
    ) : NetworkError("响应内容异常：$detail")

    /** 响应能拿到但业务层判定失败（例如 JM 的 `data` 字段为空）。 */
    class Business(val detail: String) : NetworkError(detail)

    /** 调用方主动取消。 */
    class Cancelled : NetworkError("已取消")

    companion object {
        /**
         * 把 OkHttp/IO 抛出的异常翻译成 [NetworkError]。
         * 判定顺序有讲究：`SocketTimeoutException` 必须先于通用 `IOException` 判断。
         */
        fun from(url: String, throwable: Throwable): NetworkError = when (throwable) {
            is NetworkError -> throwable
            is SocketTimeoutException -> Timeout(url, throwable)
            is javax.net.ssl.SSLException -> Tls(url, throwable)
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            -> Unreachable(url, throwable)
            is IOException -> Unreachable(url, throwable)
            else -> Malformed(url, throwable.message ?: throwable.javaClass.simpleName)
        }
    }
}

/**
 * 判断异常链上是否存在网络超时。
 *
 * 移植自参考实现 `JmcomicService.isNetworkTimeout`（第 164～174 行）：
 * Hutool 会把 `IOException` 包成 `HttpException`，所以沿 cause 链找
 * `SocketTimeoutException`；参考实现限制遍历深度为 10，这里保留同样的保护。
 */
fun Throwable.isNetworkTimeout(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        if (current is SocketTimeoutException) return true
        current = current.cause
        depth++
    }
    return false
}

/**
 * 接口请求可重试的异常判定 = **网络超时**，或**内容不可用的响应异常**
 * （[NetworkError.Malformed.retryable]）。
 *
 * 与 [isNetworkTimeout] 的区别：后者是参考实现的原始语义（只有超时才重试），这里在其之上
 * 补了"响应非 JSON / 空体"这一类——JM 线路抖动时可能返回空体或 HTML 错误页，重发一次
 * 比直接把错误抛给用户划算。**解密失败、字段解析失败等语义错误不在其列**（重试无意义）。
 */
fun Throwable.isRetryableApiError(): Boolean =
    isNetworkTimeout() || (this is NetworkError.Malformed && retryable)

/** 调用方的取消异常：与"失败"区分，取消不重试、不记录为失败。 */
class OperationCancelledException(message: String = "操作已取消") : Exception(message)
