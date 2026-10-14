package com.haruhi.jmcomic.core.network

import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * 网络超时重试。
 *
 * 完全对齐参考实现 `JmcomicService.withTimeoutRetry` + `RetryUtil.retry`
 * （`JmcomicService` 第 150～157 行、`RetryUtil` 第 25～70 行）的语义：
 *
 * 1. `maxRetry <= 0` 时**不包装**、直接执行一次（`jm.request.*.retry` 默认 0）；
 * 2. `maxRetry` 是"重试次数"，总尝试次数 = `maxRetry + 1`；
 * 3. **只有网络超时**才重试，其它异常（404、解密失败等）原样抛出，不做无意义重试；
 * 4. 指数退避：基础 1 秒，每轮翻倍（1s、2s、4s…）；
 * 5. 全部尝试耗尽后抛出的是**上一次的异常**。
 *
 * 注意「签名时间戳沿用第一次请求」这条约束不在这里实现：
 * 它属于调用方职责——JM 的 `ts` 在进入 [withTimeoutRetry] 之前就已经确定并用于构造请求，
 * 重试时闭包复用同一个 `ts`，所以响应能用同一个 key 解密。
 */
suspend fun <T> withTimeoutRetry(
    maxRetry: Int,
    baseDelayMs: Long = 1_000L,
    enableBackOff: Boolean = true,
    onRetry: ((attempt: Int, total: Int, cause: Throwable) -> Unit)? = null,
    action: suspend () -> T,
): T {
    if (maxRetry <= 0) return action()

    val maxAttempt = maxRetry + 1
    var currentDelay = baseDelayMs
    var lastError: Throwable? = null

    for (attempt in 1..maxAttempt) {
        try {
            return action()
        } catch (e: OperationCancelledException) {
            // 取消不是失败，直接向上抛，不重试
            throw e
        } catch (e: Throwable) {
            lastError = e
            if (!e.isNetworkTimeout()) {
                // 不允许重试的异常：原样抛出
                throw e
            }
            if (attempt >= maxAttempt) break
            onRetry?.invoke(attempt, maxAttempt, e)
            Timber.tag("net").d("第 %d/%d 次尝试超时，%dms 后重试", attempt, maxAttempt, currentDelay)
            delay(currentDelay)
            if (enableBackOff) currentDelay *= 2
        }
    }
    throw lastError ?: IllegalStateException("withTimeoutRetry 未执行任何尝试")
}
