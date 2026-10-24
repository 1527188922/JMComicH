# 项目规范

> 这里记的是**跨功能的硬约束**：写新代码时要遵守，评审时照着核对。
> 阶段计划见 `docs/android-app-plan.md`，改动流水见 `docs/PROGRESS.md`。

## 1. jm漫画封面加载：只允许一个入口 `Cover`

### 规则（必须遵守）

1. **显示 JM 封面的唯一入口是 `Cover(albumId, contentDescription, modifier, tag)`**
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
    albumId = albumId,              // 只传本子号
    contentDescription = title,
    tag = item.categoryTag,          // 右上角主 tag；没有分类就传 null（不画）
    modifier = Modifier.size(width = 72.dp, height = 100.dp).clip(RoundedCornerShape(8.dp)),
)
```

尺寸、裁切、点击、占位背景都由调用点决定；**不要**改 `Cover` 里的 `size` /
`memoryCacheKey`，也不要为了"更清晰"再建第二个 ImageLoader。

`tag` 传的是**接口的 `category.title`**（用户 2026-10-22 明确：主 tag 就是这个字段）。
取不到就传 `null`，**不要**用 `category_sub.title` 或 `/album` 的 `tags` 去凑。
因此只有**在线列表（搜索 / 分类）**与**账号收藏里的漫画卡**会传；
书库 / 最近浏览 / 继续阅读（本地数据没有分类）、**详情页封面**、**收藏夹封面**
以及**下载任务页**都**不传**（后三者是用户明确要求不显示）。

tag 徽标的底色 / 文字色**必须与官方 App 一致**：`#FF7B00` + 纯白，
色值在 `ui/theme/Color.kt` 的 `CoverTagBackground` / `CoverTagText`，
改封面样式时不要去改这两个值。

### 明确不算违规的例外

- **阅读器的章节原图**不是封面，不适用本规范：它有两种来源（见 `ReaderCacheMode`）——
  旧管线落盘的解扰成品（`ImageStore.file` → `AsyncImage(page.displayFile)`），
  或新管线就地解扰出的位图（`ImageStore.displayBitmap` → `Image(page.bitmap)`）。
  **不要**把章节图塞进 `Cover`，也不要另建 ImageLoader。
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

## 3. 本机收藏下线 / 账号收藏上线：隐藏规则

> 2026-10-21 起：**收藏统一走官方在线收藏**（入口在「我的」里），本机收藏的界面下线。
> 设计见 `docs/account-design.md` §4 / §10，接口见 `docs/official-api-manga.md` §8。

### 规则（必须遵守）

1. **隐藏点必须带标记**：`// 【本地UI隐藏】…` 后面写清"放开即可恢复"，并说明恢复时要一起放开哪几处。
2. **只许注释 / 改调用，不许删代码**：
   - `Repository` 的收藏与最近浏览方法、`FavoriteDao`、`FavoritesViewModel`、`FavoritePickerSheet`、
     `FavoritePickerForSelection`、`RecentScreen`、`Repository.touchRecent()` **一行都不许删**；
   - 隐藏的方式只有两种：注释调用点，或把开关参数的默认值改掉（例如 `LibraryScreen(showCollectedChip = false)`）。
3. **恢复要成套放开**：`Tab.FAVORITES`、`Routes.FAVORITES` / `Routes.FAVORITE_ALBUMS` 两个 composable、
   详情页本机面板、书库「收藏」筛选与多选「修改收藏夹」——少放开一处就会出现"有入口没页面"。
4. **最近浏览始终是本机数据**：不走官方 `/watch_list`（那个接口的 `POST` 是**删记录**，官方也没有"上报观看"的调用点）。
5. **账号请求的前置检查只有一处**：`AccountRepository.guarded()`（未登录直接抛"请先登录"）。
   401 一律翻译成"登录已失效"并把会话标成 `Expired`，页面里不许各自弹 401 对话框。
6. **`POST` 不自动重试**：`ApiClient.postDecrypted` 刻意不套 `withTimeoutRetry`——
   `POST /favorite` 是**切换**语义，重放一次等于把刚加上的收藏取消掉。
7. **重发验证信只走网页版 `/confirm`**（`feature/api/WebConfirmClient.kt`）：请求体必须带
   `submit_action=resend` + `username` + `password`。App API 的
   `POST /confirm{username,password}` 少了 `submit_action`，服务端稳定返回
   `无效的核查身份证!您确定此ID是正确的？`——即使账号刚注册也一样；
   回退到它会重新制造"验证码过期后账号无法登录"的问题。响应是 HTML，按
   `toastr[...]` 文案判成败（成功时用的是 `toastr['error']`）。

### 验收清单（改收藏链路后照着跑）

- [ ] 底栏 4 项，点「我的 → 收藏」能进账号收藏夹列表，夹卡有「N 本 + 封面」；
- [ ] 详情页点「收藏」开的是账号面板；未登录先跳登录、登录后回到详情页（不自动收藏）；
- [ ] 未收藏点某个夹 → 加入并归入该夹；已收藏点该夹 → 换夹；点「全部收藏」→ 加入/取消；
- [ ] 非空夹的「删除」按钮不出现；空夹能删；
- [ ] 书库筛选里没有「收藏」，长按多选里没有「修改收藏夹」；
- [ ] 全局搜索 `Routes.FAVORITES` / `Routes.MORE` 的调用点都在注释里。
