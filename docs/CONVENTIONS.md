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
- **评论者头像**（详情页「评论」tab）不是封面：地址是 `{img_host}/media/users/{photo}`，
  按**文件名**取图，根本没有本子号可以传给 `Cover`。这种图直接 `AsyncImage` +
  **同一个应用级 ImageLoader**（`currentImageLoader()`，带正确 UA / 代理、缓存同一处），
  并且自己垫一层"昵称首字"的圆底占位（加载失败时它就是头像）。
  **不要**为了头像去改 `Cover` 的 `memoryCacheKey` / 解码尺寸，也不要另建 ImageLoader。
- **Preview / UI 测试**：`LocalImageLoader` 默认值为 `null`，此时 Coil 退回单例加载器，
  仅用于在没有完整容器的情况下把界面画出来。

### 验收清单（改动封面链路后照着跑）

- [ ] 任意列表（书库 / 最近 / 发现 / 收藏 / 任务）看到封面后点进详情页，
      `adb logcat -s http:*` 里**没有**封面请求（只有 `/album` 等接口请求）。
- [ ] 「从书库移除」「清空最近浏览」之后，对应封面按 `CoverCache.evict` 失效；
      "只删除本地文件"、以及仍能在书库/最近里看到的，封面**保留**。

## 2. 底部面板一律用 `HandleDragOnlyBottomSheet`

### 规则（必须遵守）

1. **别直接写 `ModalBottomSheet { … }`**，一律用 `feature/ui/SheetGestures.kt` 里的外壳：
   ```kotlin
   HandleDragOnlyBottomSheet(
       onDismissRequest = onDismiss,
       sheetState = sheetState,        // 需要时自己 rememberModalBottomSheetState(...)
   ) {
       Column(Modifier.fillMaxWidth()) {
           …                          // 标题、按钮行、LazyColumn 照旧
       }
   }
   ```
   它保证**内容区（标题 / 按钮 / 列表）上的上下滑动完全到不了面板**（两个方向都一样），
   只有顶部的把手能拖着关。
2. 面板里**至少留一条"不靠猜"的关闭出口**：把手可下滑、或一颗「关闭」/「取消」按钮。
3. **别在调用点自己开面板手势**：`HandleDragOnlyBottomSheet` 里之所以传
   `sheetGesturesEnabled = false`（material3 1.4.0 起有这个参数），是为了把面板自带的那套手势
   **整套摘掉**——那套东西（Surface 上的 `draggable` + `nestedScroll`）就是会跟列表抢上下滑的元凶。
   把手由外壳自己用 `Modifier.draggable` 接管（拖过 56dp 或甩过 125dp/s 就收起）。
4. 列表状态（`rememberLazyListState()`）该提到面板外面还是照旧提——面板一关就整个移出组合，
   提上去才能在"关掉再打开"之后回到上次看到的那几条。
5. **面板里能滚的列表面板必须挂滚动条**：`VerticalScrollbar(state = listState, modifier = Modifier.matchParentSize())`
   放进 `Box` 里，名单用 `Modifier.heightIn(max = …)` 封顶（`Scrollbar.kt`，不溢出时自动不画）。
6. **别指望面板整体能滚**：material 的 sheet 内容不是滚动容器，放不下就被裁掉。
   `HandleDragOnlyBottomSheet` 外面包了一层兜底滚动（`weight(1f, fill = false) + verticalScroll`），
   只在"整块内容比窗口还高"时才会滚；平时观感不变，所以内容还是该有自己的上限，
   否则小屏 / 大字号下按钮会被推得很靠下。

### 为什么（真实踩过的坑）

material3 1.4.0 的 `ModalBottomSheet` 把面板拖动挂在**整块内容**上，而且有**两条**通道：

- `draggable`（指针手势）：手指落在**没有被下面消费掉**的地方——标题 / 提示 / 按钮行，
  以及"一屏放得下、滚不动"的短列表；
- `nestedScroll(ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection)`：
  `pre-up / post-down` —— 向上先给面板（面板已全展开时它只吞 0，余量落到列表），
  **向下则是列表消费完之后的余量直接拿去拖面板**。

关闭判定只有 `BottomSheetDefaults.PositionalThreshold = 56.dp` / `VelocityThreshold = 125.dp/s`
（两个常量都读自 aar）。结果就是"想上下滑着找线路 / 翻章节，手指一动面板就跟着走、一不小心就滑没了"。

2026-10-19 先在列表区挡了一道（只挡"长列表 + 已在顶部"）；用户随后要求**彻底取消内容区的上下滑手势**，
于是改成先"消费事件"的拦截版——真机验出来**向下不抢了、向上还抢**。
最后定稿：不给面板挂手势（`sheetGesturesEnabled = false`），把手自己接管。
完整分析见 `docs/PROGRESS.md` 的「面板内容区不再响应上下滑动手势」与之前那节「线路面板不再被"下滑即关"误伤」。

### 验收清单（改动面板手势后照着跑）

- [ ] 在标题 / 提示 / 按钮行上下滑：面板纹丝不动；
- [ ] 列表中部上下滑：只滚列表，面板不动（**上下两个方向都要试**）；
- [ ] 列表滑到顶后继续向下拉 100dp 再松手：**不关**；
- [ ] 在列表上快速向下甩一下：**不关**；
- [ ] 一屏放得下的短列表（接口线路 6 条）上同样滑不动面板；
- [ ] 在**把手**上向下拖、松手：面板滑下去关掉；
- [ ] 点遮罩、点「关闭」、返回键：都还能关；
- [ ] 内容溢出的列表面板：右边缘出现那根 3dp 胶囊（静止淡、滑动加深），内容不溢出时不出现；
- [ ] 把系统字号调到最大 / 用小屏设备：面板底部按钮仍够得到（外层兜底滚动生效）。
