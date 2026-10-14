package com.haruhi.jmcomic.core.network

import com.haruhi.jmcomic.core.settings.ProxyType
import com.haruhi.jmcomic.core.settings.ResolvedNetworkConfig
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 统一的 OkHttp 客户端工厂。
 *
 * 计划 §8「网络」一行的要求：统一 UA / Referer、可配置代理、超时重试
 * （照抄参考实现的指数退避）、HTTPS 证书异常要有可理解提示。
 *
 * 这里只负责"按已解析的网络配置造出客户端"。整个应用**只有一个客户端实例**
 * （见 `AppContainer.httpClient`）：漫画作用域可以覆盖全局的代理与超时（§9.2）。
 * 运行中的任务不会被中途换配置：换配置后新建的客户端只对新任务生效（§9.2）。
 */
object NetworkClientFactory {

    /**
     * @param config 已把"跟随全局"合并完的有效配置
     * @param userAgent 统一使用的 UA
     * @param extraHeaders 调用方额外附加的固定 header（JM 接口不需要）
     * @param enableLogging 是否记录请求日志；**凭据相关的 header 必须被脱敏**，见 [redactHeaders]
     */
    fun create(
        config: ResolvedNetworkConfig,
        userAgent: String,
        extraHeaders: Map<String, String> = emptyMap(),
        enableLogging: Boolean = false,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // 不设总时长上限：大文件下载可以很久
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()
                chain.proceed(request)
            }

        config.proxyUrlOrNull()?.let { builder.proxy(parseProxy(config, it)) }
        proxyAuthenticator(config)?.let { builder.proxyAuthenticator(it) }

        if (enableLogging) {
            builder.addInterceptor(HttpLoggingInterceptor(REDACTED_HEADERS))
        }
        return builder.build()
    }

    private fun parseProxy(config: ResolvedNetworkConfig, proxyUrl: String): Proxy {
        val type = when (config.proxyType) {
            ProxyType.SOCKS -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        return Proxy(type, InetSocketAddress.createUnresolved(config.proxyHost, config.proxyPort))
    }

    /**
     * 需要脱敏的 header：Cookie、token、JM 的时间戳签名都属于凭据，
     * **绝不能进日志**（计划 §9.2、§D.2 第 6 条）。
     */
    val REDACTED_HEADERS = setOf("cookie", "set-cookie", "authorization", "token", "tokenparam", "x-csrf-token")

    /** 代理认证：OkHttp 的 `proxyAuthenticator` 用 Basic；密码来自 Keystore，不进日志。 */
    fun proxyAuthenticator(config: ResolvedNetworkConfig): okhttp3.Authenticator? {
        if (!config.usesProxy || config.proxyUsername.isBlank()) return null
        val password = config.proxyPassword ?: return null
        return okhttp3.Authenticator { _, response ->
            val credential = Credentials.basic(config.proxyUsername, password)
            response.request.newBuilder().header("Proxy-Authorization", credential).build()
        }
    }
}

/**
 * 极简日志拦截器。
 *
 * 没有直接引 OkHttp 官方的 `HttpLoggingInterceptor` 的 `BODY` 级别：
 * JM 的响应体是密文且体积大，记录它没有收益、只有泄漏风险。
 * 这里只记录方法、URL（去掉 query）、状态码和耗时。
 */
class HttpLoggingInterceptor(
    @Suppress("unused") private val redactHeaders: Set<String>,
    private val logger: (String) -> Unit = ::timberHttpLogger,
) : okhttp3.Interceptor {

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            logger("HTTP ${request.method} ${safeUrl(request)} -> ${e.javaClass.simpleName}")
            throw e
        }
        val costMs = (System.nanoTime() - started) / 1_000_000
        logger("HTTP ${request.method} ${safeUrl(request)} -> ${response.code} ${costMs}ms")
        return response
    }

    /** 去掉 query：JM 的 query 里有 search_query 与时间戳签名，都不该进日志。 */
    private fun safeUrl(request: okhttp3.Request): String {
        val url = request.url
        return "${url.scheme}://${url.host}${url.encodedPath}"
    }
}

private fun timberHttpLogger(message: String) {
    timber.log.Timber.tag("http").d(message)
}
