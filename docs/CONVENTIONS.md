# 项目规范

> 这里记的是**跨功能的硬约束**：写新代码时要遵守，评审时照着核对。
> 阶段计划见 `docs/android-app-plan.md`，改动流水见 `docs/PROGRESS.md`。

## 1. jm漫画封面加载：只允许一个入口 `Cover`

### 规则（必须遵守）

1. **显示 JM 封面的唯一入口是 `Cover(albumId, contentDescription, modifier)`**
   （`feature/ui/Screens.kt`）。书库网格、继续阅读卡片、最近浏览、
   发现页搜索结果、详情页头图、任务页、收藏夹封面——全部用它。
2. **禁止**自己写 `AsyncImage(model = <封面 URL>)`（或自己造 `ImageRequest`、自己造
   `ImageLoader`）去加载封面。要封面就往 `Cover` 传**本子号**，不要传 URL。
3. 封面缓存位置只有一处：`files/library/covers`，由**应用级** `AppContainer.imageLoader`
   的 Coil 磁盘缓存管理（`AppContainer.imageLoader` 里 `.diskCache` 指向
   `LibraryDirs.covers`）。不要另建一份封面缓存目录。
   > 备注：`LibraryDirs.jmCoverFile()`（`covers/jm/<aid>.jpg`）目前只是目录规划里的
   > **声明**，生产代码里没有任何调用点——封面在 `covers/` 下是 Coil 按 key 哈希命名的文件。
   > 别以为磁盘上会存在 `<aid>.jpg`。
4. 内存缓存 key 固定 `jm-cover-<aid>`（`coverMemoryCacheKey`），解码尺寸下限固定
   `400×540`（`COVER_MIN_WIDTH_PX/HEIGHT`）。同一本在列表与详情之间切换必须是内存命中，
   不重新解码、不闪。
5. 删除/失效封面只有一条路：`CoverCache.evict(albumId)`（实现见 `AppContainer`）。

### 为什么（真实踩过的坑）

Coil 的 `AsyncImage` **不传 `imageLoader` 时取的是进程单例**（`SingletonImageLoader.get`）。
发现页搜索结果原来就是裸 `AsyncImage(item.coverUrl)`：它走单例加载器，内存缓存与磁盘缓存
（`cacheDir/coil3_disk_cache`）和应用级加载器（`files/library/covers`）**完全不共享**，
而且单例没有 JM 的 UA / 代理设置。后果就是：搜索结果里封面明明已经显示出来了，
点进详情页还会为同一张封面**再发一次网络请求**（详情页查的是应用级加载器那两层缓存，全是 miss）。

`Cover` 还顺手统一了内存缓存 key 与解码尺寸下限，所以走它才能拿到
"列表 → 详情不重新解码、不闪白"的体验（见 `PROGRESS.md` 的 L1）。

### 新增一个显示封面的地方，只需要

```kotlin
Cover(
    albumId = albumId,                  // 只传本子号
    contentDescription = title,
    modifier = Modifier.size(width = 72.dp, height = 100.dp).clip(RoundedCornerShape(8.dp)),
)
```

尺寸、裁切、点击、占位背景都由调用点决定；**不要**改 `Cover` 里的 `size` /
`memoryCacheKey`，也不要为了"更清晰"再建第二个 ImageLoader。

### 明确不算违规的例外

- **阅读器的章节原图**是本地文件（`ImageStore` 负责落盘，`AsyncImage(page.file)` 渲染），
  不是封面，不适用本规范。
- **Preview / UI 测试**：`LocalImageLoader` 默认值为 `null`，此时 Coil 退回单例加载器，
  仅用于在没有完整容器的情况下把界面画出来。

### 验收清单（改动封面链路后照着跑）

- [ ] 任意列表（书库 / 最近 / 发现 / 收藏 / 任务）看到封面后点进详情页，
      `adb logcat -s http:*` 里**没有**封面请求（只有 `/album` 等接口请求）。
- [ ] 「从书库移除」「清空最近浏览」之后，对应封面按 `CoverCache.evict` 失效；
      "只删除本地文件"、以及仍能在书库/最近里看到的，封面**保留**。
