package com.haruhi.jmcomic.feature.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader

/**
 * 供 Composable 取到应用级 `ImageLoader`。
 *
 * 为什么需要它：JM 的封面必须走**带正确 UA 与代理设置**的 OkHttp
 * （封面域名对 UA 有校验），而且磁盘缓存要落在 `files/covers`
 * （用户要求第 8 点：封面本地缓存）。这些都在 `AppContainer.imageLoader` 里配好，
 * 通过这个 CompositionLocal 下发，避免每个页面各自造一个 ImageLoader。
 *
 * 默认值是 `null`（而不是抛异常）是为了让 **Preview 与 UI 测试**不必先构造完整容器：
 * 此时 [currentImageLoader] 返回 null，Coil 退回它自己的单例加载器，依然能渲染，
 * 只是没有落到 `files/covers` 的磁盘缓存。生产路径由 `ModeRoot` 显式覆盖。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：它在整棵树的生命周期内
 * 不变，static 版本没有读取追踪开销。
 */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** 取当前生效的应用级 ImageLoader；没安装时返回 null（此时用 Coil 的单例）。 */
@Composable
fun currentImageLoader(): ImageLoader? = LocalImageLoader.current
