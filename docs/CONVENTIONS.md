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

## 2. 底部面板里的可滚动列表：必须挂 `sheetSwipeGuard`

### 规则（必须遵守）

1. 面板里只要有 `LazyColumn` 这类**能滚**的列表（线路、章节、收藏夹……），就给面板挂
   ```kotlin
   val listState = rememberLazyListState()
   ModalBottomSheet(
       onDismissRequest = onDismiss,
       sheetState = sheetState,
       modifier = Modifier.sheetSwipeGuard(listState),   // 见 feature/ui/SheetGestures.kt
   ) {
       LazyColumn(state = listState) { … }               // 同一个 listState 也要给列表
   }
   ```
2. 面板里**至少留一条"不靠猜"的关闭出口**：把手/标题区可下滑，或一颗「关闭」/「取消」按钮。
   列表区被守门人保护之后，用户不能再靠"在列表上下滑"关面板了。
3. **别用 `sheetGesturesEnabled = false` 图省事**：那会把把手的下滑一起禁掉，
   面板只剩点遮罩/返回键能关（material3 1.4.0 起有这个参数，但不是本项目的做法）。

### 为什么（真实踩过的坑）

material3 1.4.0 的 `ModalBottomSheet` 内容上同时挂了两套手势：

- `draggable`：按住**非滚动区域**（标题 / 提示 / 按钮行）上下滑 = 直接拖面板；
- `nestedScroll(ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection)`：
  `pre-up / post-down` —— 向上先给面板（面板已全展开时它只吞 0，余量落到列表），
  **向下则是列表消费完之后的余量直接拿去拖面板**。

也就是"列表一到顶，之后每一像素向下位移都变成面板位移"，而关闭判定只有
`BottomSheetDefaults.PositionalThreshold = 56.dp` / `VelocityThreshold = 125.dp/s`
（两个常量都读自 aar）。结果就是"往上翻列表时，手指往回收一点，面板关了"。
2026-10-19 处理设置页线路面板误关时确认过，完整分析见 `docs/PROGRESS.md` 的同名小节。

### 验收清单（改动面板手势后照着跑）

- [ ] 列表中部上下滑：只滚列表，面板不动；
- [ ] 列表滑到顶后继续向下拉 100dp 再松手：**不关**；
- [ ] 在列表上快速向下甩一下：**不关**；
- [ ] 按把手 / 标题区向下拖、点遮罩、点「关闭」、返回键：四条都还能关。
