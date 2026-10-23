package com.haruhi.jmcomic.feature.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.haruhi.jmcomic.core.protocol.Defaults

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

/**
 * 非封面图（**头像**那类）的加载入口：与 `Cover` 同一个应用级 ImageLoader（正确 UA / 代理、
 * 同一份磁盘缓存），但 `imageLoader` 参数是**非空**的 Coil API，所以这里替调用方分一次流。
 *
 * 与 `CONVENTIONS.md` 第 1 条一致：头像不属于封面，不传 albumId、不改 `Cover` 的缓存 key。
 */
@Composable
fun AvatarImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val loader = currentImageLoader()
    if (loader != null) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            imageLoader = loader,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

/**
 * 当前选中的图片线路（图片 CDN 域名）。
 *
 * 封面 URL 由 [Defaults.buildCoverUrl] 按它拼接；设置页切线路后这个值跟着变，
 * 画面上的封面随即改用新线路（见 `ModeRoot`）。
 *
 * 默认值取 [Defaults.IMAGE_DOMAIN]，让 Preview / UI 测试不依赖容器也能画出封面。
 * 用 `compositionLocalOf` 而不是 `staticCompositionLocalOf`：设置变化时要触发重组。
 */
val LocalImageDomain = compositionLocalOf { Defaults.IMAGE_DOMAIN }

/**
 * 隐私模式：所有漫画封面模糊/遮蔽。
 *
 * 目前只有 `Cover` 读取它。用 `compositionLocalOf` 而不是 static 版本：
 * 开关切换时需要触发封面重组，static 会让 Provider 下面的整棵子树一起重组。
 * 默认 `false`，Preview / UI 测试不需要额外配置。
 */
val LocalPrivacyMode = compositionLocalOf { false }
