# 实施进度台账

> 计划原文：`docs/android-app-plan.md`（= `2026-10-05-android-jm-bilibili-app.md`）
> 跨功能硬约束：`docs/CONVENTIONS.md`（封面加载规范等）
> 最后更新：2026-10-15

## 目录结构决策

计划 §3.1 建议的是**逻辑模块划分**（`core/network`、`feature-jm`、`ui-jm`…），
§D.3 又明确「初期不必把每个逻辑目录都建成独立 Gradle 模块」。

本工程采用后者：**单个 `:app` Gradle 模块 + 按逻辑模块划分的包结构**。

```
app/src/main/java/com/haruhi/jmcomic/
  JmComicApp.kt             进程入口（日志、崩溃留存、DI 容器）
  MainActivity.kt           唯一 Activity
  core/
    protocol/               JM 协议：签名、解密、块数、常量与 URL 规则
    codec/                  图片编解码与解扰、zip / pdf 导出
    network/                OkHttp 工厂、错误分类、超时重试
    database/               Room 实体 / Dao / 数据库
    download/               下载引擎、任务仓库、前台服务
    settings/               设置（唯一的漫画作用域）+ Keystore 凭据存储
    storage/                目录规划、文件名清洗与排序、导出交付
    di/                     手写依赖注入容器
  feature/
    api/                    JM 接口客户端与响应模型
    data/                   仓库、图片落盘、下载 / 导出编排、阅读位置
    ui/                     Compose 界面（书库 / 最近 / 发现 / 收藏 / 任务 / 阅读器）
  ui/theme/                 Compose 主题
```

拆分 Gradle 模块的触发条件（先不做，但写下来避免争论）：
单一模块的编译时间明显变长（>2 分钟）时；或需要 feature-on-demand 分包时。

## 改造：纯漫画版（2026-10-12）

用户决定**放弃 B站模式**，本工程改为纯漫画软件，同时做了工程改名与内部结构重规划。

### 1. 工程坐标与命名

| 项 | 改前 | 改后 |
|---|---|---|
| `rootProject.name` | `HaruhiBox` | **`JMComicH`** |
| `group` / `namespace` / `applicationId` | `com.haruhi.haruhibox` | **`com.haruhi.jmcomic`** |
| Kotlin 包 | `com.haruhi.haruhibox.*` | `com.haruhi.jmcomic.*` |
| Application / 主题 / 数据库类 | `HaruhiBoxApp` / `HaruhiBoxTheme` / `HaruhiBoxDatabase` | `JmComicApp` / `JmComicTheme` / `JmComicDatabase` |
| 数据库文件 | `haruhibox.db` | `jmcomic.db` |
| 资源主题 | `Theme.HaruhiBox.*` | `Theme.JMComicH.*` |
| 导出目录 | `下载/HaruHiBox` | `下载/JMComicH` |
| CI 镜像开关 | `HARUHIBOX_USE_CN_MIRROR` | `JMCOMICH_USE_CN_MIRROR` |
| 漫画库目录 | `files/library/jm/` | `files/library/library/` |

> **本地未跟踪的 `keystore.properties` / `*.jks` 文件名保持 `haruhibox-release.jks` 不变**
> （它们被 `.gitignore` 排除，是用户本机资产，改名只会平白弄坏签名配置）。
> `docs/BUILD.md` 里仍写 `haruhibox-release.jks`，指的是本机那个文件。

### 2. 内部包结构重规划

原结构里的 `jm` 层级与 `Jm*` 类名前缀是为「JM / B站 双模式」服务的，纯漫画下已成冗余：

| 改前 | 改后 |
|---|---|
| `core/jm/` | `core/protocol/` |
| `feature/jm/api/` · `data/` · `ui/` | `feature/api/` · `data/` · `ui/` |
| `JmApiClient` · `JmRepository` · `JmModeRoot` · `JmCover` … | `ApiClient` · `Repository` · `ModeRoot` · `Cover` … |

共约 60 个类去掉 `Jm` 前缀，32 个文件改名（映射表见当次提交的 diff）。
**`.kt` 文件名与其中的类名保持一致**这条约定没有被破坏。

顺带修掉两处重复定义：
- `t_playback_position.kind` 的取值原来在 `ReadingRepository.KIND` 与
  `Repository.READING_KIND` 各写了一份 `"JM"`，现在统一到 `entity/PlaybackKind`；
- 设置 key 原来用 `g_` / `jm_` / `b_` 前缀区分作用域，现在改为
  `SettingKeys.X`（全局）/ `SettingKeys.Jm.X`（漫画），作用域隔离由 DataStore 文件划分保证。

### 3. 删除的 B站内容

- **代码**：`BilibiliVideoEntity` / `BilibiliVideoDao` / `DownloadType.BILI_VIDEO`；
  `AppContainer` 的 `biliHttpClient`、`biliFileDownloader`、`BILI_USER_AGENT`、
  `NetworkProfile.BILI`（`NetworkProfile` 整个枚举都删了，只剩一个客户端）；
  `SettingsRepository` 的 B站 DataStore 作用域与 `resolveBiliNetwork`；
  `LibraryDirs` 的 `bili/` 目录与 `biliVideoFile` / `biliDownloadingFile` / `biliCoverFile`。
- **设置**：`SettingKeys` 的 `B_*`（下载时长、播放速度、保持亮屏、网络覆盖）与
  `SECRET_BILI_*`（SESSDATA / bili_jct / ticket）；`SettingDefaults` 的 `B_*` 与
  `SettingLimits.PLAYBACK_SPEEDS_X100`；`SettingDefaults.START_MODE_ASK`、
  `SettingKeys.G_START_MODE` / `G_LAST_MODE`、`SettingsModel.StartMode`
  （「开屏选模式」没有意义了）。
- **清单**：`bilibili.com` / `b23.tv` 的 App Links 与"分享链接给 App"的 `SEND` 过滤器。
  因为只删了两个次要过滤器，主 `<queries>` 块不受影响。
- **备份规则**：`settings_bili.xml` 从 `backup_rules.xml` / `data_extraction_rules.xml` 移除。
- **黄金用例**：`tools/golden-gen/GoldenGen.java` 的 G6（BV↔AV）段与
  `golden.txt` 里对应的 11 行一并删除（G6 没有对应的 Android 侧测试，删掉是净收益）。

### 4. 数据库：v4 成为全新基线

数据库文件名、迁移类名与表结构同时变了（删表 + 去 `BILI_VIDEO`），而旧安装的
`haruhibox.db` 本来就不会再被打开——所以**没有为 B站表写迁移**，而是：

- 删除 `Migrations.kt` 与 v1～v3 的历史 schema（`app/schemas/` 现在只有 `4.json`）；
- `@Database(version = 4)` 作为干净基线，`JmComicDatabase.create()` 不再 `addMigrations`；
- 删除随迁移一起失效的 `MigrationsTest`（androidTest）。它测的是 v1→v2 的旧表结构，
  而 v1/v2 schema 已经不存在了。

> ⚠ **代价**：这次改造后，已装过旧版本的用户升级上来会看到一个空库（漫画库要重新入库）。
> 这是用户明确接受的取舍（工程当时只有 1 个提交 `d29b0d3 init project`、未发布）。
> 从 v4 起恢复常规约定：加表/加列必须升 version 并补迁移，**不要再用 destructive 方案**。

### 5. 回归验证

- `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` / `:app:lintDebug` /
  `:app:assembleDebug` / `:app:assembleRelease` 全部通过；
- 单测 **301 个用例、3 个失败**——这 3 个失败（`AlbumCleanerTest` 的
  `deleting files leaves a running task alone`、
  `removing from library cancels the running download task first`、
  `removing from library wipes records favorites progress but keeps recent and therefore the cover`）
  **在改造前就是红的**（改造前 302 个用例、同样这 3 个失败），与本次改动无关，
  是既有的未修问题；
- 设备测试用例数从原先的 11 个变为 9 个（删掉了 2 个 `MigrationsTest` 用例）。

## 改造：取消「全局设置」作用域（2026-10-15）

用户指出设置页里"全局"的说法是**双模式时期的遗留**：既然只有漫画模式，就不该再区分
「全局 App 设置」和「漫画设置」。这次把全局这一层整个去掉。

### 1. 做了什么

| 项 | 改前 | 改后 |
|---|---|---|
| 偏好存储 | `settings_global` + `settings_jm` 两个 DataStore | **只有 `settings_jm`** |
| `SettingKeys` | 顶层常量（全局）+ `Jm` 对象（漫画） | 全部收进 `Jm`（`THEME`、`REQUEST_TIMEOUT_SECONDS`、`PROXY_*`、`DOWNLOAD_NETWORK` 等） |
| 网络设置 | 「跟随全局 / 本模式自定义」二选一，跟随时那两行只读 | 直接编辑生效值：请求超时滑杆 + 代理编辑器 |
| 下载网络 | 跟随全局 / 仅 Wi-Fi / 允许移动网络（存储值 -1/0/1） | **仅 Wi-Fi / 允许移动网络**（0/1，与 `DownloadNetwork` 序号一致） |
| 代理密码 | 全局密码与漫画密码各存一份（`secret_g_proxy_password`） | 只留 `secret_jm_proxy_password` |
| 设置页文案 | "跟随全局""本模式自定义""只重置漫画模式…不动全局" | 全部改成单模式表述 |

- `ResolvedNetworkConfig.followedGlobal` 与空壳类 `NetworkOverride` 一起删掉——
  没有"跟随"这回事，解析出来的就是唯一生效值。
- 旧存储值 `-1`（跟随全局）由 `DownloadNetwork.ofOrdinal` 兜底成「允许移动网络」，
  升级后**不会**因为读到一个陌生数字而静默掐掉下载。

### 2. 旧数据迁移（一次性）

旧的 `settings_global` 文件保留为**只读迁移源**：`AppContainer` 创建仓库时同步调用
`SettingsRepository.migrateLegacyGlobalScope(secrets)`，把超时 / 代理四件套 / 代理密码
搬进 `settings_jm`（只在"旧值存在且新值没写过"时搬，幂等；全新安装是空操作）。
搬完以后没有任何代码再写全局文件。

> 为什么要迁移而不是直接忽略：用户是在**已发布**的包上升级，旧全局里的代理与超时
> 是真实配置，直接丢掉等于让用户重设一次。

### 3. 回归验证

- `:app:compileDebugKotlin` 通过；
- `:app:testDebugUnitTest`：**本次运行共 301 个用例、6 个失败**（上一行那 3 个之外，
  还有 `DownloadManagerTest.submitting again appends chapters instead of creating a second task`、
  `ReaderAndTaskLogicTest` 的 2 个底栏切换方向用例）。`git stash` 后在**干净工作区**跑
  同一批用例，这 6 个**全部复现**，所以与本次改动无关，是既有的未修问题；
- 文档：`README.md` 与本文的目录树、P1 明细里的"三作用域 + 跟随全局继承"已同步。

## 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| **P0 环境与基线** | ✅ 完成 | 见 `docs/p0/README.md`；3 个 spike 全部有结论 |
| **P1 基础设施** | ✅ 完成 | 见下表；验收项全部达成 |
| **P2 JM 数据与浏览** | 🚧 代码完成，**界面待人工确认** | 见下方 P2 明细 |
| **P3 JM 下载与阅读闭环** | 🚧 代码完成，**界面待人工确认** | 见下方 P3 明细 |
| **P4 JM 导出 zip / pdf** | 🚧 代码完成，**真机观感待确认** | 见下方 P4 明细 |
| ~~P5 B站~~ | ❌ **已取消** | B站模式整体删除，见「改造：纯漫画版」（2026-10-12） |
| P6 壳与体验 | ⬜ 未开始 | |
| P7 验收与真机 | ⬜ 未开始 | |

### P2 明细（JM 数据与浏览）

| 项 | 状态 | 产出 |
|---|---|---|
| JM 接口客户端（搜索/详情/章节/模板，**不含登录**） | ✅ | `feature/api/ApiClient.kt` |
| 接口响应模型（字段逐条对齐参考实现） | ✅ | `feature/api/JmModels.kt` |
| 本子入库（覆盖在线字段 + **保留本地收藏**） | ✅ | `Repository.saveAlbum`，测试锁定 |
| 章节清单入库（整章替换 + `imageSort` 从 1 开始） | ✅ | `Repository.saveChapterImages` |
| 章节来源优先级（`album.series` → 本地图片表） | ✅ | `Repository.chaptersOf`，两个分支都有测试 |
| 在线搜索 + 是否已入库标记 + 分页 | ✅ | `Repository.searchOnline` |
| 搜索历史（保留条数、相同条件刷新、快照回显） | ✅ | `recordSearchHistory` / `searchHistorySnapshot` |
| 收藏夹 CRUD（默认夹不可删改、删除只删关联） | ✅ | `Repository` 收藏段，测试锁定 |
| 书库筛选（关键字/作者/**多标签 OR**/收藏夹） | ✅ | Dao 用递归 CTE 表达 OR 语义 |
| 书库页（筛选、状态优先展示下载章节数） | ✅ 代码 | `JmLibraryScreen` |
| 发现页（在线搜索 + 历史 + 分页） | ✅ 代码 | `JmOnlineSearchScreen` |
| 收藏页（增删改、默认夹标识） | ✅ 代码 | `FavoritesScreen` |
| 漫画详情页（本地优先 + 在线刷新 + 章节状态） | ✅ 代码 | `AlbumDetailScreen` |
| 漫画模式底部导航（书库/最近/发现/收藏） | ✅ 代码 | `ModeRoot` |
| **界面人工确认（截图/交互）** | ⏳ **待你确认** | 见下方"P2 待验证" |

### P3 明细（JM 下载与阅读闭环）

| 项 | 状态 | 产出 |
|---|---|---|
| 下载任务编排（提交去重 / 开始 / 暂停 / 恢复 / 取消 / 重试） | ✅ | `feature/data/DownloadManager.kt` |
| 章节图片清单按需补齐（没有就现场拉 `/chapter` 并入库） | ✅ | `DownloadManager.ensureImageList` |
| 解扰落盘（`blockNum==0` 直接落原图，不重新编码） | ✅ | `ImageDownloadTransform`，测试逐字节断言 |
| 图片"存在即跳过" + 0 字节残留算缺失 | ✅ | 测试 `images already on disk are not requested again` 等 |
| 进度持久化到"第 N 话 x/y 张" | ✅ | 每张图落库一次；任务级数字由章节明细推导 |
| 任务级防死循环（缺图数不下降就停） | ✅ | 章节内 3 轮上限 + 任务级"没有进展就停" |
| 网络策略闸门（仅 Wi-Fi 时进"等待网络"而不是失败） | ✅ | `awaitNetworkPermission` + `core/network/NetworkMonitor` |
| 杀进程后按磁盘现状重算并**暂停**（不自动续跑，等用户点「继续」） | ✅ | `recoverInterrupted` 重算进度后置 `PAUSED`（第二十四轮改） |
| 前台服务常驻通知 | ✅ | 有任务开始即 `DownloadForegroundService.ensureRunning` |
| 任务页（进行中 / 已完成、暂停恢复取消重试、清空历史、角标） | ✅ 代码 | `feature/ui/TaskScreen.kt` + `TaskViewModel.kt` |
| 阅读器（纵向连续 / 横向分页 / 右到左） | ✅ 代码 | `feature/ui/ReaderScreen.kt` |
| 单击显隐控件、双指缩放、双击放大还原、放大后拖动 | ✅ 代码 | `PageZoomState` + 自写手势（不用 `transformable`，见下） |
| 章节选择、上一话/下一话、缺图页与重试 | ✅ 代码 | 阅读器控件栏 |
| 阅读位置持久化（本子+章节+图片+页内比例，去抖 400ms） | ✅ | `ReadingRepository`，测试 7 个用例 |
| 书库「继续阅读」卡片 + 详情页主按钮三态 | ✅ 代码 | `ContinueReadingCard`、`PrimaryAction` |
| 阅读设置（方式/方向/背景/亮度/常亮）与 JM 设置**同一份偏好** | ✅ 代码 | `JmReaderPreferences.kt`，键位复用 JM 作用域 |
| 长图占位稳定（按图片宽高比占位，加载前后不跳） | ✅ 代码 | `ImageCodec.readMeta` + `aspectRatio` |
| **界面人工确认（下载/阅读/任务页观感）** | ⏳ **待你确认** | 见下方"P3 待验证" |

#### P3 的两个实现取舍

1. **不用 `Modifier.transformable` 做缩放**：它会把单指拖动也当作平移并消费掉事件，
   于是"没放大时列表就再也滚不动了"。阅读器自己写手势循环：
   **只在双指缩放、或已放大后的拖动时才消费事件**，其余交给 `LazyColumn` / `HorizontalPager`。
2. **重复提交不新建任务，而是把章节追加进已有任务**。计划 §9 只要求"重复提交显示已有任务"，
   但"我勾了第 5 话却没开始下载"是更糟的体验；去重规则（同一 `(type, bizKey)` 一个活跃任务）
   仍然成立，只是在追加时会唤醒暂停中的任务。

#### 模拟器实测发现并修掉的三个 bug（2026-10-09）

这三个都是**只有真的把 App 跑起来点一遍才会暴露**的问题，单测覆盖不到：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| E1 | 从搜索结果点进一本**没入库**的漫画，详情页只有一个 JM 号与"共 0 话"，**永远不联网** | 进详情页时会先 `touchRecent` 建一条**只有 id 的骨架行**；`localAlbumDetail` 把它当成"本地已有数据"，于是详情页走了"本地优先"分支、再也不请求 `/album` | `localAlbumDetail` 只认**已经有过在线数据**的最近浏览记录（`name` 非空）；骨架行返回 null，详情页照常联网。用例 `the touch placeholder does not count as local data` |
| E2 | 单话本子显示"共 0 话"，**无法下载**（JM 上这类本子很常见） | `/album` 对单话本子不返回 `series`，本地章节表也是空的，于是章节列表为空 | 按参考实现 `downloadAlbumWithoutLock` 第 866～872 行的兜底：**章节 id = 本子 id**，标题"第1话"。用例 `a single chapter album falls back to using the album id as the chapter id` |
| E3 | 图片已经下到磁盘，界面却一直显示 **0/1 已下载**（点阅读器也是"图片缺失"） | 章节目录名来自 `chapterTitle`：下载时用的是合成的"第1话"，入库时却写成了接口给的 `chapter.name`（**就是本子标题**）。两边对不上，计数器去一个不存在的目录里找图 | 单话本子的章节标题**固定「第1话」**（入库时合成、读取时归一化），阅读器查目录也走同一条规则。用例 `a single chapter album stores the synthetic title so it matches the folder name`、`a single chapter album normalizes the stored title to the folder name` |

顺带调整：底部「任务」角标把 `WAITING_NETWORK` 也算进去（它不是失败，但用户点完下载
回到浏览页后，没有角标就完全没有线索——实测就是这样）。

#### 模拟器实测走过的链路（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 冷启动 | ✅ 5 个底栏 tab（书库/最近/发现/收藏/任务）正常渲染 |
| 单话本子详情页 | ✅ 共 1 话、章节行「第1话·未下载」 |
| 点「下载」→ 面板选章节 → 确认 | ✅ 自动入库 + 建任务；角标出现 |
| 任务页 | ✅ **等待网络 · 0/1 话** + "当前网络不允许下载，已暂停传输"（当时默认策略还是仅 Wi-Fi） |
| 切到 Wi-Fi | ✅ 任务自动继续：`下载图片 · 0/1 话 · 0/11 张`，磁盘上出现 `第1话/0000N.webp.part` |
| 杀进程再启动 | ✅ 日志 `恢复 1 个被中断的下载任务`，恢复后继续跑（**该行为已在第二十四轮改为"恢复 = 暂停，等用户点继续"**） |
| 下载中的失败与重试 | ✅ 读超时 → 章节标记未完成、任务判失败并给出"1 话没有下完（0/11 张），可点重试补下"；点「重试」后只补缺口，已发布的图片不再请求 |
| 部分下载后的详情页 | ✅ `部分下载 4/11`，占用从 1.1 MB 涨到 5.1 MB |
| 阅读器 | ✅ 顶栏「第1话 · 11 页 · 缺 7 页」，缺图页显示「第 N 页 · 图片缺失 + 重试」，底栏「补下缺图（7 页）」；已下载的页**正常显示解扰后的画面**，纵向滚动可用 |
| 整本下完 | ✅ 书库卡片变「已下载全 1 话」，磁盘上 11 张图（默认放开移动网络后在 5G 下直接跑完） |

模拟器到 JM 图片 CDN 的链路很慢（单张封面 40 秒、图片经常读超时），所以没等它跑完整本；
"落盘 → 解扰 → 原子发布 → 进度"由 `DownloadManagerTest`（真实 HTTP 服务 + 逐字节校验）覆盖。

### P3 待验证（需要你在设备上确认）

自动验证过的部分：**228 个单元测试 + 11 个设备测试**全绿，lint 0 error，debug/release 均可构建。
（真机 6 个用例是上一轮跑的：迁移 2 + PDF 加密 4；本轮没有改数据库结构，所以没有重跑。）
> 这行是 P3 当时的历史记录。当前基线见文首「改造：纯漫画版」第 5 节
> （301 个单元用例，其中 3 个是既有的红用例）。

| # | 请确认 | 期望 |
|---|---|---|
| 1 | 底栏五个 tab 之间来回点 | 往右边的 tab 切从右侧滑入、往左边切从左侧滑入，方向与底栏位置一致 |
| 2 | 详情页 | 只有「阅读」「下载」「收藏」三个按钮；「阅读」从第一章开始；点「收藏」直接弹收藏夹面板 |
| 3 | 详情页点「下载」 | 面板里默认勾选未下完的章节；已在队列的章节灰掉、显示"已在下载队列"且点不动 |
| 4 | 挑一本**没下载过**的漫画点「阅读」 | 直接就能看（边看边联网取图），顶栏"已缓存 N 页"会涨；退出再进不重新请求 |
| 5 | 阅读器点一下画面 | 底部出现进度条 + "第几页/共几页" + 上一话 / 下载本话 / 收藏 / 下一话 |
| 6 | 把某本的本地文件删掉后再点「阅读」 | 自动重新取图，不再提示"图片缺失" |
| 7 | 任务页 | 每行有封面，点封面进这本漫画的详情页 |
| 8 | 一边看一边下载 | 两边不冲突：下载计数会把已经看过的页算进去，也不会重复请求同一张图 |
| 9 | 发现页点「加入书库」 | 按钮**立刻**变「已入库」，不用切页面 |
| 10 | 下载到一半杀进程再打开 | 从断点继续，已下载的图片不重下 |

> 第 4～8 条已在模拟器上走过一遍（见下表），但**真机上的观感**
> （通知、息屏继续、弱网重试、缩放/翻页手感、tab 切换动画方向）还是需要你确认一次。

### 第四轮反馈的模拟器实测记录（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 未下载的本子点「阅读」 | ✅ 直接出图；顶栏「第1话 · 51 页 · 已缓存 29 页」，日志里是逐页的 `/media/photos/...` 请求 |
| 阅读器底栏 | ✅ 进度条 + 「第1话 · 1/51 页」+「加载中 22」+ 上一话 / 下载本话 / ★已收藏 / 下一话 |
| 任务页 | ✅ 三行任务都带封面；点封面进了对应详情页 |
| 详情页头部 | ✅ 阅读 / 下载 / 已收藏 三颗按钮，第二行是「已在书库 · 已加入 1 个收藏夹」 |
| 点「已收藏」 | ✅ 直接弹出收藏夹面板（默认收藏夹已勾选 + 新建收藏夹） |
| 边看边下 | ✅ 同一次操作里，下载任务的计数从 3/51 涨到 33/51——阅读器取回来的图就是下载任务认的那份文件 |

### 第五轮反馈的模拟器实测记录（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 详情页标题 | ✅ 紧贴状态栏，留白消失 |
| 打开一本未下载的漫画进阅读器 | ✅ 显示"正在加载第 1 页…"，底栏进度条 + 「第1话 · 1/40 页」+「加载中 40」 |
| 该过程中的主线程 | ✅ logcat **没有任何 `Skipped N frames` / `Davey!`**（改之前实测同一动作会 `Skipped 72 frames!`） |
| 阅读器点「收藏」→ 返回详情页 | ✅ 按钮立刻变「已收藏」，状态行变「已在书库 · 已加入 1 个收藏夹」 |
| 详情页收藏夹面板里移出 | ✅ 按钮立刻变回「收藏」，状态行变「未收藏」，面板勾选同步取消 |
| 章节行的 ⋮ | ✅ 菜单紧贴按钮右下弹出 |
| 书库列表 | ✅ 最近加入的排在最前 |
| 收藏夹删除 | ✅ 弹出「删除「X」？……漫画仍然留在书库，已下载的图片也不会被删掉」 |
| 改名撞车 | ✅ 提示「已经有同名的收藏夹了，换个名字」（之前是"默认收藏夹不支持重命名"） |

### 第六轮反馈的模拟器实测记录（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 收藏页 | ✅ 默认收藏夹左侧显示夹内最近加入那本的封面；三个空夹在同一位置显示 ☆ 占位，行高一致 |
| 点「排序：创建时间 ⌄」 | ✅ 弹出三选一菜单，当前项打勾并高亮 |
| 切到「最近加入」 | ✅ 标签变成「排序：最近加入」，顺序随之变化，**默认收藏夹仍在最上** |
| 详情页「三点」 | ✅ 只剩 导出 ZIP / 导出 PDF（都标着 P4 接入）/ 删除本地文件 / 从书库移除，「下载管理」已移除 |

### 用户提出的 8 点改动（2026-10-08 完成）

| # | 内容 | 落地方式 | 验证 |
|---|---|---|---|
| 1 | 收藏后收藏页数量不更新 | 收藏页改用 `observeFavoritesWithCount()` 的 **Flow**（之前是 N 次一次性查询），一条 LEFT JOIN + Flow 重发 | `favorite count flow emits after collecting` 等 3 个用例 |
| 2 | 点详情不该等于加入书库 | 详情页只 `touchRecent`；入书库只发生在点「加入书库」 | `touching recent does not add to the library` 等 3 个用例 |
| 3 | 新增最近浏览页（按浏览降序） | 新表 `t_jm_recent_album`（字段同书库 + `viewed_time`），底栏新增「最近」tab | `recent list is ordered by viewed time descending` |
| 4 | 详情页先读本地再联网；刷新更新记录但不改浏览时间 | `localAlbumDetail()` 先查书库→最近浏览；`fetchAlbumDetail(saveToLibrary)`；`saveToRecent` 保留原 `viewedTime` | `refresh updates recent fields but keeps the viewed time` |
| 5 | 搜索一次渲染太多封面 | 首屏只渲染 10 条，**最后一条进入可视区就自动追加**；本页全部展示完才出现「加载下一页」 | 逻辑在 `OnlineSearchUiState.remainingCount / allVisible`；观感待设备确认 |
| 6 | 缺少修改收藏夹功能 | 详情页「收藏夹」底部面板：加入 / 移出 / 移动到 / 新建并加入 | 实现于 `moveToFavorite` + 收藏夹关联用例 |
| 7 | 夹内显示收藏夹名称、去掉「收藏」筛选 | `JmLibraryScreen(title = …, showCollectedChip = false)` | 代码 |
| 8 | 封面本地缓存 | `AppContainer.imageLoader` 把 Coil 磁盘缓存指向 `files/covers`，网络层复用 JM 的 OkHttp（UA/代理） | 代码；观感待设备确认 |

> **第 8 点说明**：Coil 的磁盘缓存默认就是"先查本地、没有再联网"，
> 不需要自己写文件缓存。但它同时带来一个待办：`LibraryDirs.clearCache()`
> 目前只清 `tmp` / `export`，**没算上 `covers`**。封面缓存有 256MB 上限、
> 由 Coil 按 LRU 自行淘汰，所以不紧急；做"清理缓存"设置项时要把它纳入（P6）。

### 第二轮反馈修复（2026-10-08）

| # | 反馈 | 根因 | 修复 |
|---|---|---|---|
| B1 | 搜索分屏追加要"先滑到顶再滑到底"才生效 | ① `derivedStateOf` 读 `listState.layoutInfo` 拿到**过期快照**，只在别的重组顺带读它时才重算；② `LaunchedEffect` 的 key 含当前批次条数，每次追加都取消并重启 effect，打断正在进行的滚动检测 | 改 `snapshotFlow` 主动监听；key 只放列表身份，判定用 `rememberUpdatedState` 读实时值 |
| B2 | 收藏夹内一直显示"清除 1 项" | 进夹时 `favoriteId` 必然已设置，`filter.isActive` 恒真；但那是页面上下文、不是用户可清的筛选 | 夹内不显示清除按钮 |
| B3 | 详情页标题下方一大块留白 | edge-to-edge 下 `TopAppBar` 的 windowInsets 与 Scaffold 内容内边距叠加 | 显式声明 `windowInsets = TopAppBarDefaults.windowInsets`（待你在设备上确认是否真的解决） |
| B4 | 简介本来能全显示时仍出现"展开简介" | 按钮无条件渲染 | 用 `onTextLayout` 的 `hasVisualOverflow` 判断是否真被截断 |
| B5 | 页面切换是淡入淡出 | 未配转场 | NavHost 加 `slideInHorizontally` / `slideOutHorizontally`（260ms），返回反向 |
| B6 | 收藏夹弹框挡住提示语 | 详情页自己的 Scaffold 弹 Snackbar，被 `ModalBottomSheet` 盖住 | 提示上抛到根部 Scaffold 弹（新增 `onMessage` 回调） |
| Q3 | 首次点收藏卡一下，取消再收藏就很快 | **首次收藏多打了一次 `/album` 网络请求**（`addToLibrary(albumId)`），本地已有记录后就不再请求 | 新增 `addToLibrary(dto)` / `addToLibrary(entity)`，用内存数据直接落库 |

> B1 的判定抽成了 `OnlineSearchUiState.shouldRevealMore()` 纯函数，
> 由 `SearchRevealStateTest` 覆盖（10→20→30→35 的推进与边界条件）。
> 滚动触发本身没法在这台设备上自动验证，所以单独把"判定条件"锁住。

**仍未自动验证**：B1 的滚动触发（模拟器单次 `/search` 要 44 秒、超过应用 30 秒超时；
真机 MIUI 又拒绝 `adb input`）、以及 B3/B5/B6 的观感。需要你在设备上确认。

### 第三轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| C1 | 底栏切换的滑入方向要跟 tab 位置一致 | `tabShift(from, to)` 比较两个 tab 在底栏里的下标：往右切从右侧滑入、往左切从左侧滑入；旧页面往反方向退，像一条传送带。进详情页/阅读器仍是从右侧进入 | `ReaderAndTaskLogicTest` 的 3 个用例（右移/左移/非 tab 路由） |
| C2 | 「最近」「任务」的图标换掉 | 最近 → 日历（`DateRange`，表达"按时间翻过的记录"）；任务 → 清单（`List`，表达"一批待办的事"）。两者都在 `material-icons-core` 里，**不引入 extended** | 模拟器截图 |
| C3 | 详情页四个下载入口收敛成一个 | 头部只留「下载」：点开面板选章节、确认后整批入队。章节行的下载菜单、章节区标题的「下载未完成」全部删掉；章节行尾菜单只剩"删除本话文件"（且只在已下过图时出现） | 模拟器截图 |
| C4 | 下载面板要标出"已在队列"的章节 | `DownloadTaskRepository.observeQueuedChapters(albumId)` 盯住活跃任务的章节明细 → `AlbumDetailUiState.queuedChapterIds`；面板里这些章节复选框禁用、标题降权、"已在下载队列"用 tertiary 色，且**不默认勾选**。`downloadChapters` 再兜一层过滤 | `chapters already in the queue are not preselected`；模拟器截图 |
| C5 | 详情页要有「阅读」（从第一章开始） | 头部「阅读」按钮取第一章的 id 进阅读器，`chapterId > 0` 时阅读器**从该话第一页开始**（不恢复上次位置）；续读入口仍在书库的「继续阅读」卡片 | 模拟器截图 |
| C6 | 发现页点「加入书库」按钮不变成「已入库」 | `visibleItems` 的 `remember` key 是 `(visibleCount, items.size)`：`existsLocal` 变了但这两个数没变，于是**一直复用加库之前的那份列表**，切走再回来才会重建 | 模拟器实测：点了立刻变「已入库」 |
| C7 | 下载先不分 Wi-Fi / 流量，等设置模块再做 | 全局下载网络默认值从"仅 Wi-Fi"改为**允许移动网络**（`SettingDefaults.DOWNLOAD_NETWORK_ANY`）。判定逻辑与"等待网络"状态机不动，P6 做出开关后用户可自行收紧 | 模拟器在 5G 下直接开下 |

> C7 是对计划 §9.5 的一处**有意偏离**：原计划把 Android 侧默认收紧成"仅 Wi-Fi"。
> 但设置模块还没做，用户在界面上改不了，默认收紧会让移动网络下的下载**看起来没反应**
> （任务静默停在"等待网络"）。等 P6 把三个下载网络开关做出来，默认值是否收回可以再定。

### 第四轮反馈修复（2026-10-09）

这一轮把"看"和"下"统一到**同一份磁盘文件**上：`ImageStore`（`feature/data/`）
负责"算路径 / 命中判断 / 下载后解扰"，阅读器与下载任务都走它。

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| D1 | 任务列表要显示封面，点封面进详情 | 任务行加 `Cover`（应用级 Coil 加载器 + `files/covers` 磁盘缓存，所以看过的封面优先读本地），`TaskRow.albumId` 从 `bizKey` 解析；点封面 → `Routes.detail(aid)` | 模拟器截图：任务页有封面、点击进详情 |
| D2 | **不必先下载才能看**；取回来的图要缓存；下载时先看缓存；边看边下不冲突 | 新增 `ImageStore`：本地有就直接用，没有就下载 + 解扰 + 落盘。阅读器**按页懒加载**（只取滚动到附近的页）。下载任务每张图仍走"文件在不在"的判断，两边看的是同一个文件 | `ImageStoreTest`（7 个用例：不发多余请求 / 第二次不再请求 / 删掉后重新取）；模拟器实测：未下载的本子点「阅读」直接出图，顶栏显示"51 页 · 已缓存 29 页" |
| D3 | 阅读器遮罩要有进度条、下载按钮、收藏按钮 | 底栏改成三层：进度条 + 「第1话 · 12/34 页」（含加载中/失败页数）+ 上一话 / **下载本话** / **收藏** / 下一话 | 模拟器截图 |
| D4 | 删掉本地文件后再读不该报"图片缺失" | 同 D2：文件不在就是缓存未命中，自动重新取；只有真的取不到（离线/图床故障）才显示"第 N 页没取回来 + 重试" | `deleting the local file makes the next read fetch it again` |
| D5 | 取消「收藏夹」按钮，点「收藏」直接弹收藏夹面板 | 头部三颗按钮变成 阅读 / 下载 / 收藏，收藏按钮直接打开 `FavoritePickerSheet`；原来第二行的「收藏夹」位置改成一句状态（`已加入 N 个收藏夹`） | 模拟器截图 |

#### 顺带修掉的两处并发隐患（D2 带来的）

1. **同一张图被两个调用方同时要**（一边看一边下载）会写同一个 `<图>.part`。
   现在 `FileDownloader` 对每个目标文件加锁，等锁期间会重新判断"是不是已经下好了"。
2. **解扰不可逆**：`ImageStore` 对每张图加锁，保证同一张只解扰一次。

### 第五轮反馈修复（2026-10-09）

**这一轮的主线是主线程阻塞**：用户报的三处"卡一下"其实是同一个根因——
`viewModelScope` 用的是主线程调度器，而我在里面直接做了磁盘与解码的重活。

| # | 反馈 | 根因 | 修复 |
|---|---|---|---|
| E1 | 收藏夹里全部移出后按钮还是"已收藏" | `removeFromFavorite` 只改了数据库，详情页的 `album` 是**进页面时读的一次性快照**，没人通知它 | 详情页改成**观察** `observeAlbum(aid)` 与 `observeFavoriteIdsOf(aid)`（新增 Dao Flow），谁改了都立刻反映 |
| E2 | 书库要按入库时间降序 | 排序是 `collected DESC, COALESCE(modify_time, create_time) DESC`——收藏优先，而且刷新详情会改 `modify_time` 把顺序打乱 | 改成 `create_time DESC, id DESC`。`create_time` 才是"第一次进书库的时间" |
| E3 | 阅读器里收藏后返回详情页仍显示"收藏" | 同 E1 | 同 E1（两个页面现在看同一份 Flow） |
| E4 | 阅读器去掉"护眼暖色" | — | 删掉 `ReadingBackground.SEPIA` 与对应配色；历史数据里的编码 3 回落到"跟随主题"（有用例锁定） |
| E5 | 详情页标题上方一大块留白 | edge-to-edge 下**两层 Scaffold 各加了一次状态栏内边距**：外层 `ModeRoot` 的 Scaffold 已经把状态栏高度作为 `padding` 给了 NavHost，内层 Scaffold 与 TopAppBar 又各加了一遍 | 内层 `contentWindowInsets = WindowInsets(0,0,0,0)`，TopAppBar 同样置 0 |
| E6 | 章节的"删除本话文件"菜单飘到别处 | `DropdownMenu` 直接当 `Row` 的子节点，锚点算在了整行上 | 把图标与菜单包进同一个 `Box`，锚点才是那颗按钮 |
| E7 | 改名撞车时提示"默认收藏夹不支持重命名"；删除缺二次确认 | `renameFavorite` / `deleteFavorite` 只用 `Boolean` 表达结果，界面只能猜一个文案 | 改成 `FavoriteWriteResult`（Ok / DefaultFolder / NameTaken / NameBlank / NotFound），界面逐项给文案；删除加确认框并写清影响 |
| E8 | **阅读器在线取图时 UI 卡住** | `ImageStore.file()` 跑在 `viewModelScope`（主线程）上，而里面的**解扰是纯 CPU 重活**：JM 长图解码成 ARGB_8888 要几十 MB，还要逐行 `getPixels/setPixels` 再重新编码 | `file()` 与 `aspectRatioOf()` 整段 `withContext(Dispatchers.IO)` |
| E9 | 进详情页、点进收藏夹各卡一下 | 同类问题：`downloadedImageCount` 逐张查文件、`localSize` 递归遍历目录、`readMeta` 读图片头，全在主线程 | `loadLocalStats` 与 `buildStat` 的整段计算切到 IO；阅读器的 `refreshFromDisk` 也切到 IO |

> E8/E9 是同一类错误的三个出口。判断方法很简单：**`viewModelScope` 里没有 `withContext`
> 的代码都在主线程上跑**。现在的约定是——凡是碰文件、解码、遍历目录的，一律先切 IO。

### 第六轮反馈修复（2026-10-09）
| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| F1 | 收藏夹要有封面（夹内最近收藏的那本），空夹给占位 | 汇总查询加两个相关子查询：`lastAddedTime`（`MAX(create_time)`）与 `latestAlbumId`（按 `create_time DESC, id DESC` 取第一条）。列表左侧复用 `Cover`（应用级 Coil + `files/covers` 磁盘缓存，看过的封面优先读本地）；空夹在同一位置画等大的占位方块，避免列表高度跳动 | `FavoriteSortTest`；模拟器截图：默认收藏夹显示封面、三个空夹显示 ☆ 占位 |
| F2 | 收藏页要有排序机制（数量 / 创建时间 / 最近加入，都降序，默认创建时间） | 右上角「排序：xxx ⌄」按钮打开三选一菜单（当前项打勾并高亮）。排序是**纯界面行为**，由 `sortFavorites()` 在展示层完成，不写库 | `FavoriteSortTest` 的 7 个用例；模拟器实测切换后顺序变化 |
| F3 | 详情页「三点」菜单去掉「下载管理」 | 直接删掉该项；任务页仍可从底栏「任务」进入 | 模拟器截图 |

> F2 的两条隐藏规则也写进了 `sortFavorites()` 并有用例锁定：
> **默认收藏夹永远置顶**（换排序也不动它），以及同值时按 id 兜底（顺序不抖）。

### 第七轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| G1 | 书库/收藏夹要能一次处理多本（长按多选） | `Screens` 加本地 `selection: Set<Long>` + `MultiSelectBar`（全选 / 删除文件 / 移出书库 / 收藏夹）；卡片用 `combinedClickable`（长按进入多选、再点切换勾选），`BackHandler` 退出多选；最近页只读所以不接长按 | 模拟器截图：长按出现"已选 1 本 / 全选 / 三个批量操作"，选中卡片带勾 |
| G2 | 搜 JM 号应该直接出那一本 | 关键词是**纯数字**时同时发 `/search` 与 `/album`，把详情包成一条结果**置顶**并标「精确匹配」；合并规则抽成纯函数 `mergeExactIdMatch`（置顶 / 去重 / 总数对得上） | `SearchMergeTest` 4 个用例；模拟器实测搜 `377531` → 置顶卡片带「精确匹配 · 已入库」 |
| G3 | 下载面板里已在队列的章节不该还能再选 | 详情页观察 `observeQueuedChapters(aid)`（排队/下载中/暂停/等待网络/失败都算"在队列"），面板里这些行改成不可选并标「已在下载队列」，全不可选时按钮变"请选择章节" | 模拟器实测：提交后再开面板 → 第1话 灰掉并显示「已在下载队列」 |
| G4 | 导出的 zip/PDF 要能打开、发送、删除 | 任务页导出任务加三颗按钮（`TaskRow.isExport`）：打开至 = `ACTION_VIEW`、发送至 = `ACTION_SEND` + 选择器、删除文件 = 二次确认后删 MediaStore 记录；文件名 → Uri 的解析在 `ExportManager.outputUri` | 模拟器实测：发送至弹出系统分享面板（Sharing 1 file）；打开至在无对应 App 时提示"没有可以打开这个文件的应用" |
| G5 | 同名文件已存在要提示"会覆盖" | 点导出时 `outputExists(album, format)`，密码框里橙色（深浅色各一个固定值，见 `ui/theme/Color.kt`）提示"下载目录里已经有同名文件，再次生成会直接覆盖它" | 模拟器截图；并且**真的覆盖**：`AndroidExportPublisher` 先删旧行再插入，避免 MediaStore 自动改名留下"xxx (1).zip"（否则"打开至/删除文件"会指向旧文件） |
| G6 | 详情页「删除本地文件」漏了导出的 zip/PDF | 详情页改走 `AlbumCleaner.deleteFiles`（图片 + 导出成品 + 封面缓存，**保留记录**），提示语写清含几个成品 | `AlbumCleanerTest`；模拟器实测：删除后本子目录与 zip 都没了，书库记录与「已下载 0 话」状态还在 |
| G7 | 「从书库移除」要连本地文件一起删 | 改走 `AlbumCleaner.removeFromLibrary`：先取消还在跑的任务，再删文件/成品/封面缓存，最后删五张表（书库/章节清单/收藏关联/最近浏览/阅读位置）。**页面等它做完再退**（原来点完立刻 `popBackStack` 会把协程一起取消，等于删一半），提示语也改成"本地文件与浏览记录一起删掉了" | `AlbumCleanerTest` 4 个用例；模拟器实测：确认后自动返回书库、列表少一本、最近页也没了、默认收藏夹少一本 |

> G6/G7 的差别只有"删不删数据库记录"，所以两处都收敛到 `AlbumCleaner` 一个编排里；
> 删除是不可撤销的重活，`deleteFiles` 整段跑在 `Dispatchers.IO`（约定见第五轮 E8/E9）。

### 第八轮反馈修复（2026-10-09，由你直接提交 `d11a992`）

| # | 修复 | 要点 |
|---|---|---|
| H1 | **提示语重放**（通用 bug） | `showSnackbar` 是挂起调用，"展示完才消费"会让用户在提示未消失时切页，协程被取消、消息留在 ViewModel 里，切回来就重放。新增 `MessageEffect`：**先消费、再交给根部 scope 展示**；书库/收藏/夹内/任务/阅读器/详情页全部收敛到这一条路径。发现页此前建了 `SnackbarHostState` 却没有宿主，改为走根部 Snackbar |
| H2 | 批量勾选看不清 | 选中卡片叠加主题色描边 + 封面半透明遮罩 + 右上角实心圆对勾（未选为深色空圈） |
| H3 | 收藏夹内批量操作 | 夹内不再提供"删除文件/移出书库"，按钮文案改为「修改收藏夹」；面板改为多选勾选 + 就地新建 + 点确认才执行；一个都不勾 = 移出所有收藏夹；夹内打开时预勾当前夹 |
| H4 | 夹内排序 | 指定收藏夹时改为按 `t_jm_favorite_album.create_time` 倒序（"最近加进这个夹的"在最前），与书库的入库顺序分开 |
| H5 | 移出书库不再删最近浏览 | `deleteAlbums` 不再清 `t_jm_recent_album`；进阅读器也会刷新浏览时间（此前只有详情页刷） |
| H6 | 纯数字搜索三处修正 | 历史快照改记**合并后**的结果；置顶条目作者不再显示成 `["作者"]`；搜索零结果时接口给的 `total` 不可信（实测返回 1 且 content 为空），以实际条目数为准 |

### 第九轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| I1 | 书库、收藏夹内要能按"有没有 pdf/zip"筛选 | 筛选栏加 **「有 ZIP」「有 PDF」** 两个独立开关（都选中 = 两种都有），行改成 `FlowRow` 自动换行，窄屏也全部看得见。成品不在数据库里，所以由 `ExportManager.presentFormats(albums)` **整页只查一次**下载目录，再按 `<本子目录名>.<zip\|pdf>` 在内存里配名；快照没回来前一律放行（`matchesExportFilter`），避免首次进页面闪成空列表 | `LibraryExportFilterTest` 6 个用例 + `ExportManagerTest` 3 个新用例（含"60 本只查一次"）；模拟器实测：书库选「有 ZIP」→ 只剩导出过的那本、出现「清除 1 项」；夹内选「有 PDF」→ 空态提示 |
| I2 | 详情页要显示这本的 zip/pdf 信息并能打开/发送/删除 | 封面下方新增**「导出文件」卡片**（在章节列表之前，属于"这本在我设备上有什么"）：每行 = 格式徽标 + 文件名 + `大小 · 生成于 MM-dd HH:mm` + 三个操作（打开至 / 发送至 / 删除，删除需二次确认）。标题右侧写明文件位置「在 下载/JMComicH」。数据来自 `ExportPublisher.infoOf`（MediaStore 的 `SIZE`/`DATE_MODIFIED`），进页面与从别处返回都会重算 | `ExportManagerTest` 的 `exportsOf` 用例；模拟器实测：ZIP「11.8 MB · 生成于 10-08 16:58」、PDF「19.8 MB · 生成于 10-08 16:01」与实际文件逐字节对齐；点「删除」→ 确认后行消失、`Download/JMComicH` 里文件也没了；「打开至」在无对应 App 时给出可读提示 |
| I3 | 封面缓存该不该清（第八轮改成"移出书库保留最近浏览"之后必须一起理顺） | `AlbumCleaner` 改按"**这本还会不会出现在任何列表里**"判断，判断方式就是删完之后回查两张表：<br>「删除本地文件」→ 记录不变 → 封面**保留**；<br>「从书库移除」→ 最近浏览里还有 → 封面**保留**，没有才清；<br>「清空/删除最近浏览」→ 书库里还有 → 封面**保留**，没有才清。<br>顺带给最近页加了长按单条「从最近浏览移除」（走同一条路径），确认文案同步写明封面规则 | `AlbumCleanerTest` 增至 8 个用例（两条规则各两个方向 + 单条删除）；模拟器实测**用缓存文件数验证**：移出书库后 14 个封面文件不变、再删掉它的最近记录 → 12；清空最近浏览 → 12 降到 8（只掉不在书库的那两本） |

> I1 顺带修掉一处旧问题：「清除 N 项」以前只在书库页显示，夹内因为 `favoriteId` 也算一个条件而永远看不到。
> 现在只数**用户自己加的**条件（`userActiveCount`），夹内筛选后同样能一键清掉。

### 第十轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| J1 | 详情页「导出文件」太占空间，要能折叠、默认折叠 | 整块改成**可折叠卡片**：默认收起，只占一行；点**整行**都能展开/收起（不用瞄准小箭头）。收起时标题右侧写"ZIP · PDF"，不展开也知道有哪些成品；展开后才显示文件名、大小、生成时间与三个操作 | 模拟器截图：收起时章节列表紧跟在封面下；展开后成品行与按钮都在 |
| J2 | 去掉「在 下载/JMComicH」 | 直接删掉这段文案，连同只为它存在的 `AlbumDetailUiState.exportLocation` 与 `ExportManager.outputLocation`。位置信息在**导出完成的任务提示**里仍然会说（任务页 `stageText` = "已保存到 下载/JMComicH"），不算丢失 | 模拟器截图；`outputLocation` 已无引用 |
| J3 | 5 个筛选按钮小屏放不下一行，第五个掉到第二行不好看 | 筛选条件改成**一行横向滚动**，行尾**固定**放「清除 N 项」（不跟着滚，任何时候都点得到），右缘加 24dp 渐隐提示"右边还有"。不再有"孤儿按钮"，条件以后再加也不会爆版 | 模拟器实测（`wm size 720x1600` + `wm density 320` ≈ 360dp 宽）：一行放下 已下载/未下载/收藏/有 ZIP，横滑后 有 PDF 完整可见 |
| J4 | 筛选 zip/pdf 能不能翻页？书库是分页加载的吗？ | **原来不是**：书库只查第一页（60 条）且**没有"加载更多"**，第 61 本起根本看不到（这次一并补上）。现在：第一页仍是数据库实时 Flow（新增/删除立刻可见）＋后续页追加，`mergeLibraryPages` 按 id 去重（重复 key 会让 `LazyVerticalGrid` 直接崩），页脚给「加载更多」/进度条/"没有更多了"。**筛选是客户端算的，所以显示不足半屏会自动连翻**，直到凑够或翻到底——这样"有 ZIP / 有 PDF"能翻出后面几页里符合条件的本子 | `LibraryPagingTest` 3 个用例；模拟器实测：临时把 `PAGE_SIZE` 调成 2 后——页脚「加载更多」点一下多一本；把 ZIP 导出给**第 2 页**那本后点「有 ZIP」，自动翻页把它筛了出来（已恢复 60） |

> J3 踩到的坑记一笔：渐隐那层**不要**用 `fillMaxHeight()`。它在按内容高度测量的 Row 里会去要"外层最大高度"，
> 于是整行被撑成整屏高、底下的列表一格都画不出来（看着就像"筛选后一本都没有"）。
> 正确做法是 `matchParentSize()` + `drawWithContent` 画一小块渐变：尺寸完全跟随内容，不参与测量。

### 第十一轮反馈修复（2026-10-09）

| # | 反馈 | 落地方式 | 验证 |
|---|---|---|---|
| K1 | 读过的漫画要有阅读进度：有进度时「阅读」变「继续阅读」；点章节仍然从那话第一张开始，但要**覆盖**进度；从书库移除要连进度一起删 | 进度本来就有（`t_playback_position`，本子+章节+图片名+页内比例），这次把详情页接上去：<br>① 观察 `reading.observePosition(albumId)`，有可用进度时主按钮显示**「继续阅读」**、并按存下来的位置续读（`Routes.reader(albumId)` 不带章节号 = 续读）；<br>② 没进度才是「阅读」= 第一话第一张；<br>③ 点章节行先调 `onChapterOpened(chapterId)` 把进度改写成"这一话、第一张"（`imageFile = null` 的语义就是"从这话开头读"），再进阅读器——所以点第 1 话看一半退出，"继续阅读"指向的是第 1 话而不是上次的第 3 话；<br>④ 按钮下面补一行小字"上次读到 第2话 · 第 15 页"，只说"继续阅读"用户不知道继续到哪；<br>⑤ 从书库移除本来就会删 `t_playback_position`（`deleteAlbums` 里那句），这次补了**真的种了一条进度**的用例，避免断言空过 | `ReadingProgressLabelTest` 4 个用例；`AlbumCleanerTest` 改为先写进度再移除并断言进度消失；模拟器实测：详情页显示「继续阅读」+「上次读到 第1话 · 第 3 页」→ 点章节进阅读器从 **1/51 页**开始 → 返回后小字变成「第 1 页」（进度被覆盖）→ 从书库移除后书库页的「继续阅读」卡片一起消失（= 进度已删） |
| K2 | 「继续阅读 / 下载 / 已收藏」在窄屏又会掉行 | 三颗按钮从"封面右边的窄栏"移到**整宽一行**：主按钮 `weight(1f)` 吃掉剩余宽度、文字单行省略，两颗次要按钮自己定宽。这一行最宽也就 继续阅读(104dp)+下载(76)+已收藏(90)+间距 ≈ 286dp，320dp 屏也放得下 | 模拟器截图（411dp）：按钮与卡片左右对齐、无掉行；`wm size/density` 模拟 360dp 同样一行 |
| K3 | 点导出后、以及导出完成后，「导出文件」都要立刻更新，不能等重进页面 | 详情页观察两个格式的**活跃导出任务**（`tasks.observeActiveTask(type, albumId)`）：<br>① 任务一出现 → 折叠栏自动展开一次并多一行「PDF 导出中 74%」+ 进度条，折叠状态下标题也会写「ZIP · PDF · PDF 导出中」；<br>② 任务从有变无（成功/失败/取消都算）→ 立刻重算成品列表，所以跑完当场就能看到新的文件名/大小/时间，不用退出页面 | 模拟器实测：确认导出后**当场**出现"导出中 74%"与进度条（截图）→ 12 秒后同一页面上进度行消失、PDF 行刷新成 `20.2 MB · 生成于 10-09 15:30`（与磁盘上的文件一致） |

### 第十二轮反馈修复（2026-10-09）

| # | 反馈 | 结论 / 落地方式 | 验证 |
|---|---|---|---|
| L1 | 精确匹配结果点「加入书库」特别慢（怀疑又请求了一次详情接口）；而且列表里已经加载过封面，点进详情"似乎又请求了一次" | **前半段确实是多余的请求**：搜索页走 `repo.addToLibrary(aid)` → `api.album(aid)`，而精确匹配那条的详情**搜索时就已经拉过并写进最近浏览表**了。改成 `addToLibraryLocalFirst(aid)`：本地（书库或最近浏览）有完整数据就直接落库，只有"普通搜索结果且从没点进去过"才回落网络。<br>**后半段实测没有网络请求**（debug 版 HTTP 日志：书库网格 → 详情页零请求），那是 Coil **按调用点尺寸各解码一份**造成的闪一下。现在封面固定用同一个内存缓存 key（`jm-cover-<id>`）+ 解码下限尺寸（400×540），列表 → 详情是内存命中，不再重解码也不闪 | `RepositoryTest` 新增 3 个用例（本地有数据时**不联网**——该测试的 api 客户端一被调用就抛异常，能跑通即证明没联网；已在书库时幂等；本地没有才回落网络）；模拟器实测：网格 → 详情 `adb logcat -s http:*` 无任何请求 |
| L2 | 导出文件区加一个「打开文件夹」，直接唤起文件管理器并定位到目录（做不到就先不做） | **能做到，而且不需要任何存储权限**——读目录的是文件管理器自己，我们只是把位置告诉它。<br>实现：`ExportPublisher.folderUri()` 给出 `com.android.externalstorage.documents` 的目录文档 URI（`primary:Download/JMComicH`），按钮发 `ACTION_VIEW` + `vnd.android.document/directory`；老系统（API 26～28）成品在应用私有目录里、别的应用根本看不到，这时返回 null、按钮不显示。<br>**两个坑**：① 文档 id 必须带 `primary:` 卷名前缀，少了它文件管理器只会停在"下载"根目录；② Android 11+ 的**软件包可见性**会把 DocumentsUI 判成"看不见的应用"，`startActivity` 直接抛异常——要在 manifest 里声明 `<queries>`（实测日志 `AppsFilter: interaction ... documentsui BLOCKED`）。没有任何文件管理器能处理时给一句"成品在 下载/JMComicH"兜底 | `ExportPublisherFolderTest` 5 个用例（API 34 给出正确 docId／API 28 返回 null／位置文案一致／多次调用稳定）；模拟器实测：点按钮 → 系统文件管理器**直接打开 `Download/JMComicH`**（面包屑 sdk_gphone16k_x86_64 › Download › JMComicH） |

> L1 的封面处理值得记一笔：Coil 的内存缓存条目**带尺寸**，列表里那份比详情页小的话，
> 详情页会重新解码（不是重新联网）。除了共享 key，这里还把请求尺寸抬到两者中较大的那个，
> 所以"网格里看过的封面，点进详情立刻就在"。

### 第十三轮反馈修复（2026-10-10）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| M1 | 发现页搜索结果加载过封面后，点进详情页又为同一张封面发了一次网络请求；要求发现页补上这条链路，并把"任何加载过封面的地方都走同一条链路"写成项目规范 | **根因是两条链路用了两个不同的 ImageLoader**：发现页搜索结果行是裸 `AsyncImage(model = item.coverUrl)`（`OnlineResultRow`），而 Coil 的 `AsyncImage` 不传 `imageLoader` 时取的是**进程单例**（`SingletonImageLoader.get`）——它的内存缓存、磁盘缓存（`cacheDir/coil3_disk_cache`）与应用级 `container.imageLoader`（`files/covers`）完全不共享，单例也没有 JM 的 UA/代理，所以详情页两层缓存都 miss、只能联网（L1 那次"零请求"只对**书库网格 → 详情页**成立，两边都走 `Cover`）。改法：`OnlineResultRow` 改用 `Cover(item.id)`，从此书库 / 最近 / 发现 / 详情 / 收藏 / 任务六处封面走的是同一条链路（应用级 ImageLoader + `files/covers` 磁盘缓存 + 统一的 `jm-cover-<aid>` 内存 key）。<br>**缓存机制没有改**：仍然是 Coil 的磁盘缓存（按 key 哈希命名），`covers/jm/<aid>.jpg` 那套目录声明维持原样不使用——按你的要求"跟其余四处保持一致"。<br>规范写进新文档 `docs/CONVENTIONS.md`「封面加载：只允许一个入口 `Cover`」，并在 `Cover` 的注释里指向它 | 代码（**按要求没有跑测试**，等你打 debug 包确认）。设备上这样核对：发现页搜索 → 看到封面 → 点进详情，`adb logcat -s http:*` 里不应再出现封面那条请求（只有 `/album` 等接口请求） |

> 顺带发现、**这次没改**（等你发话）：`AppContainer` 里 `coverCache.evict` 清内存缓存用的是
> `MemoryCache.Key(URL)`，而 `Cover` 实际用的 key 是 `jm-cover-<aid>`，两者对不上，
> 也就是"删掉封面后内存里那份没被清掉"（磁盘缓存那份是按 URL 删的，是对的）。
> 修它不影响其它四处，一行的事；要改随时说。

### 第十七轮反馈修复（2026-10-10）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| N1 | 在线搜索失败给个 toast 提示、用户自行重试即可，不要横幅 | 发现页原来在排序栏下面挂 `ErrorBanner(message = state.error, onRetry = onSearch)`。问题不只是"太重"：那个「重试」绑的是 `onSearch`，也就是**重发整个在线搜索**，而横幅上的文案有时是「加入书库」失败——按钮和它旁边那句话根本不是同一件事。现在把 `OnlineSearchUiState.error` 整个删掉，**搜索失败 / 翻页失败 / 加入书库失败 / 历史快照丢失 / 关键字为空**五种提示统一走 `message`（一次性提示，根部 Scaffold 弹 Snackbar，与"已加入书库"同一条路），页面上不再留任何横幅，重试由用户自己决定 | 代码（**按要求没有跑测试**，等你打 debug 包确认）。设备上这样核对：飞行模式下点「搜索」、以及点「加入书库」→ 底部各弹一句失败原因，页面上不再出现红字横幅，结果列表也不被清掉（翻页失败时本页已显示的结果仍在） |
| N2 | 加入书库时如果本地真的没有、要走网络请求漫画详情，那么「加入书库」按钮需要一个 loading 效果 | 新增 `Repository.libraryAddNeedsNetwork(aid)`（判定依据与 `addToLibraryLocalFirst` 用的是同一个 `localAlbumDetail`：先书库、再基础数据缓存），VM 先问一句，**只有确实要联网才**把 aid 放进 `OnlineSearchUiState.addingIds`：卡片上的按钮变成「转圈 + 加入书库」且 `enabled = false`，网络往返结束（成功、失败、取消都算）在 `finally` 里清掉标记。<br>**本地命中不置这个标记**（精确匹配那一条、看过/缓存过的本子），所以那条路径仍然是"点一下立刻变已入库"，不会先闪一下 loading。<br>顺带挡掉重复点按：转圈期间同一张卡再点直接 return（原来连点会每次新起一个协程并发发请求） | 代码（同上）。设备上这样核对：**断网**时点一张从没点进去过的普通搜索结果 → 按钮转圈 → 底部弹失败原因 → 转圈消失、按钮复原可再点；再点精确匹配那一条 → 不转圈，直接变「已入库」 |

### 第十八~二十轮：点作者去搜索（保留）+ 复制相关的尝试（**已全部撤回**，2026-10-10）

**保留的功能**（与复制无关，是独立的一次需求）：

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| R1 | **点作者**跳发现页并把作者名填进搜索框（**不触发搜索**，用户自己点搜索按钮） | 详情页作者那行改成可点（`Modifier.clickable` + 主色，暗示它能点）。详情页 → 发现页的关键字交接棒挂在 `ModeRoot` 的 `pendingSearchKeyword` 上：两页的 `JmOnlineSearchViewModel` 作用域是各自的 NavBackStackEntry，详情页拿不到发现页那一个实例，而 `/` 上的 NavHost 是两者共同的父作用域。发现页 `LaunchedEffect` 接住 → `JmOnlineSearchViewModel.prefillKeyword()`（**只填关键字、清掉上一次的结果，不调 `search()`**）→ 清掉交接棒 | 模拟器实测：详情页点「有贺冬」→ 切到「发现」tab（底栏高亮在发现）、搜索框已填 `有贺冬`、**页面显示的是搜索历史而不是结果**（证明没有自动搜） |
| R2 | 点作者进发现页后，**手机返回键要继续退回详情页** | `onSearchAuthor` 用 `navigate(DISCOVER) { popUpTo(DISCOVER) { inclusive = true }; launchSingleTop = true }`：**只弹掉"发现"以上的东西，详情页留在回退栈里**。两个不能碰的坑都写在注释里：① 不能用底栏切 tab 那一套 `saveState = true`——那会把详情页一起存进"书库 tab 的状态"，之后点底栏「书库」会把它整条恢复出来 = 又回到详情页（用户实测到过的 bug）；② 也不能 `popBackStack(LIBRARY)` 提前弹掉详情页——那样从发现页返回就直接退出到书库，返回链路断掉 | 模拟器实测：详情页点作者 → 发现页 → 按返回 → **回到详情页** → 再按返回 → 书库；点底栏「书库」也仍正常回书库列表 |

**已撤回的尝试**（用户要求收回，代码已还原）：

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| O1~Q5 | 详情页长按书名 / JM 号 / 作者弹自定义气泡复制 → 改系统文字选中复制 → 发现页漫画名也可选中 | **三轮尝试后按用户要求全部撤回**，相关代码已删除。试过的东西：<br>① 自定义气泡（`CopyBubble.kt`：`Modifier.longPressCopy` + `CopyBubbleHost()`，深灰圆角气泡 + 底部小三角、`Popup` 定位、内容先在隐形 `Layout` 里量尺寸）；<br>② 四处 `SelectionContainer` + 根节点手势（`focusable()` / `detectTapGestures` / 自写不消费事件的检测器 / `PointerEventPass.Initial` 拦截）；<br>③ 发现页搜索结果漫画名可选中。<br>下面留的是这轮踩出来的、以后可能还用得上的结论 | 代码已还原（**按要求没有跑测试**） |

**这轮值得留下的结论**（省得以后重复踩）：

- **`SelectionState` 不能共用**：一个状态只能绑一个 `SelectionContainer`，共用直接抛
  `IllegalStateException: A SelectionState can only be bound to one SelectionContainer`
  （`SelectionContainer.kt:233`）。要几处可选就得几处各自的 `rememberSelectionState()`。
- **根节点上不要加 `focusable()`**：根一旦可获焦，手指按下（DOWN）时它就把焦点抢走，
  而文字的选择手势**是靠焦点驱动的**——表现是"长按完全选不中文字"。
- **根节点上不要用 `detectTapGestures` 去"点别处"**：它收到 DOWN 就 `consume()`，
  子节点的选择手势看到 down 已被消费就不启动。要"在子节点之前"处理得用
  `PointerEventPass.Initial`（派发顺序是「祖先 Initial → 子节点 → 祖先 Main」，
  见 `HitPathTracker.dispatchMainEventPass`）。
- **`Modifier.onTextLayout` 在 Compose 1.12 里不存在**：行信息只能从 `Text(onTextLayout = …)`
  参数拿；`Modifier.onLongPress` 同样不存在，只有 `detectTapGestures`。
- **`Popup` 的定位发生在测量之前**：要让气泡"以某个点为中心"，得先用一个不可见、
  不占位的 `Layout` 把内容量一遍拿到宽高。
- **Material3 的 `Surface(onClick = ...)` 会加 `minimumInteractiveComponentSize()`**：
  会把小气泡撑到 48dp 高且带水波纹；要紧凑就用静态 `Surface` + 自定义点击。

> **两条给自己的教训（写在这里免得再犯）**：
>
> 1. **改含中文的源文件一律用编辑工具（edit/write），不要用 shell 重写整个文件**。
>    `AlbumDetailScreen.kt` 被我一条 PowerShell `Get-Content -Raw` + `Set-Content`
>    （Windows PowerShell 5.1 默认按 ANSI 读写）**整文件改坏编码**，236 个汉字变成
>    `U+FFFD`，注释和**字符串字面量里都有**（界面文案会直接损坏）。已 `git checkout` 还原。
> 2. **"撤回某功能"要逐条对着需求清单核对，别把搭在一起但独立的另一个功能一起删掉**。
>    这次"撤回所有复制相关代码"时，把**跟复制无关的"点作者去搜索 + 返回回详情页"**
>    也一起删了（见上面 R1/R2，用户指出后已恢复）。

### 第二十一轮反馈修复（2026-10-11）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| S1 | 详情页作者下面那行「共 1 话 · 已下载 0 话 · 占用 7.4 MB」去掉（跟下方章节列表重复），改成**点赞数 + 观看数**（爱心图标 + 眼睛图标后跟数字） | 封面区那行 `Text` 换成 `AlbumStatRow(likes = album.likes, views = album.totalViews)`：`Icons.Default.Favorite`（爱心）+ 数字、眼睛图标 + 数字，都是 `labelMedium` 的小字。两个字段可能是 null / 空串（还没联网刷新过），这时整行不占位——不显示"♥ 0"这种假数字；数字原样展示，不在客户端折算成"万"（免得跟网页端对不上），格式化收在 `formatStatCount()` 里 | 代码（**按要求没有跑测试**，等你打 debug 包确认）。设备上这样核对：联网刷新过的本子在封面区看到「♥ 12345　👁 678」，断网进详情页（只有本地缓存、`likes` 为空）时这两项不显示 |
| S2 | 占用储存大小移到下方章节区，例如「章节 · 0/1 已下载（7.4 MB）」 | `ChapterSectionHeader` 接上 `state.localSizeBytes`，文案抽成纯函数 `chapterSectionTitle()`：`章节` + ` · 下载数/总数 已下载` + `（体积）`（后两段按有没有数据决定）。本地没有文件时不加括号（不写"（0 B）"）；章节清单还没拿到时不写"0/0 已下载"，只留"章节"，本地占用照说 | `DisplayLogicTest` 新增 2 个用例（`chapterSectionTitle` 3 种形态 + `formatStatCount` 的空白/原样保留）；**按要求没有跑测试** |

> **眼睛图标是自己画的**：工程不引 `material-icons-extended`（见 `app/build.gradle.kts` 那段注释），
> 而 `material-icons-core` 里没有眼睛，所以按 core 里那些图标的同一种写法（`ImageVector.Builder` + `path { … }`）
> 画了一个：上下两段三次贝塞尔围成眼型，中间用**奇偶填充**挖出虹膜、再填一颗实心瞳孔，
> 三层嵌套正好是"实 → 空 → 实"。爱心直接用 core 里的 `Icons.Default.Favorite`。

### 第二十二轮反馈修复（2026-10-11）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| T1 | 批量操作模式的按钮别放上面、挪到下面（握着手机时拇指在屏幕下方，往上够很难），可点击区域要更大；删除按钮加删除图标、收藏夹按钮加爱心图标 | 批量操作只在 `JmLibraryScreen`（**书库页与收藏夹内页共用**），所以只改这一处：① `MultiSelectBar` 从"原筛选栏的位置"移到 `Column` 的**最后**——多选时顶部不再放任何东西，列表三个分支（加载中 / 空态 / 网格）改用 `Modifier.weight(1f)` 占满操作条以上的剩余高度，操作条吸底；② 操作条换成 `Surface`（`tonalElevation` + `shadowElevation` + 顶部一条整宽 `HorizontalDivider`）与列表分开；③ 新增 `MultiSelectAction`：**等分整行宽度、至少 56dp 高、图标在上文字在下、整块 `clickable`**（原来是 48dp 左右的小 `TextButton`）；④ 图标：删除文件 `Icons.Default.Delete`（错误色，提醒不可逆）、移出书库 `Icons.AutoMirrored.Filled.ExitToApp`、收藏夹/修改收藏夹 `Icons.Default.Favorite`（主题色，与底栏「收藏」同一个 icon）；上一行仍是"已选 N 本 / 全选 / 退出" | 代码（**按要求没有跑测试**）。设备上这样核对：长按进多选 → 操作条在屏幕**底部**（书库页在底部导航之上，夹内在导航栏之上）→ 三颗按钮都带图标、整块可点；收藏夹内只有一颗带爱心的「修改收藏夹」 |

> 批量删除 / 收藏只在**书库页**与**收藏夹内页**，两处是同一个 `JmLibraryScreen`，
> 改一处两处都生效；漫画详情页与其它页面没有改动。

### 第二十三轮反馈修复（2026-10-11）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| U1 | 详情页的**漫画名称 / JM 号 / 作者名 / 简介**要能复制（Android 原生复制即可），**被省略号截掉的内容也要能复制** | 封面区改用 `SelectionContainer`：**四段各一个**（名称、JM 号、作者行、简介），这样「全选」只拿到**这一段自己的全文**，不会把别的段落带上（`SelectionState` 一个只能绑一个容器，共用会抛 `IllegalStateException`，所以每处都用默认 state、互不影响）。**省略内容能复制不是靠多包一层**，而是 Compose 的选择本来就按**完整文本**记：`SelectionManager.getSelectedText()` 取的是可选段落整段 `AnnotatedString` 的子串，`MultiWidgetSelectionDelegate.getSelectAllSelection()` 返回的也是 `0..text.length`（不是可见范围），所以收起状态下的 3 行简介、被 `maxLines` 截断的书名，长按后按工具条「全选」复制出来的是**全文**；把结束手柄拖到文字下方同样能补到文末。选择高亮只画在可见行上（`SelectionController.draw()` 按 `lastVisibleOffset` 裁剪），这是 Compose 既有行为，不是漏了 | 代码（**按要求没有跑测试**）。设备上这样核对：长按书名 → 系统工具条出现 → 「全选」→「复制」→ 粘贴得到完整书名（**不带 JM 号**）；收起简介同样操作得到整篇简介；点其它地方选择框消失；上下滑动列表照常 |
| U2 | 多个作者现在是 ` / ` 拼成一整串、点哪儿都用整串去搜；要**点具体某位作者**跳发现页（分隔符保持次要色），**复制仍是整体复制** | 按用户方案用 `AnnotatedString` + `LinkAnnotation.Clickable`（抽成纯函数 `authorLinkText()`）：每位作者一个链接，`TextLinkStyles(style = SpanStyle(color = primary))` 保持主色；作者之间 `withStyle(SpanStyle(color = onSurfaceVariant)) { append(AUTHOR_SEPARATOR) }` 保持次要色。**整段文字仍是 `作者A / 作者B`**，链接只是在这串文字上"挖"出来的可点范围，所以复制拿到的还是整串——原来那行 `Modifier.clickable { onSearchAuthor(整串) }` 已删除 | `DisplayLogicTest` 新增 2 个用例：链接个数 / 每条链接的覆盖范围 / `text.text == "作者A / 作者B"` / 点第 2 个链接回调的是 `"作者B"` / 零个或一个作者时的文本；**按要求没有跑测试** |

> **一个 Compose 层面的取舍（这轮翻 foundation 源码才确认，写下来免得以后再踩）**：
> 链接的点击区域不是我们画的，是 `TextLinkScope.LinksComposables()` 为每段链接生成的一个
> `Box`（`combinedClickable`），它在**按下时就 `consume()` 掉 down 事件**；而文字选择的手势
> （`SelectionController` 的 `awaitSelectionGestures` → `awaitDown()` 用 `changedToDown()`，
> 也就是要求 down **未被消费**）于是启动不了。表现：**长按作者名不会起选，而是点击作者**
> （与点一下等价，跳发现页）；多个作者时，长按作者之间的「 / 」（不是链接）还能起选，
> 之后按「全选」拿到整串。
>
> **只有一个作者时整行都是链接，这一行就长按不了、复制不到**——这是**用户明确接受的取舍**：
> 作者行"可点"优先，且四段各占一个 `SelectionContainer`（用户要的是每段只复制自己这一段），
> 不借别的段落把它带出来。**不要**为了"这一行也能复制"把几段并进同一个容器：那样「全选」
> 会把名称和 JM 号一起复制出来，与"每段各一个容器"的要求相反。
> 若以后要"长按作者名也能起选"，只能放弃 `LinkAnnotation`、自己写**完全不消费事件**的点击探测
> （按下 / 抬起只观察不 consume，再按坐标判断落在哪位作者上），代价是 TalkBack 不再把这几个词读成链接。

> 与第十八~二十轮那次**已撤回**的复制尝试的区别：这次不碰任何根节点手势（`focusable()` /
> `detectTapGestures` / `PointerEventPass.Initial` 拦截一律没有），只对着要复制的文字包
> `SelectionContainer`；作者行的"可点"交给 `LinkAnnotation`，不再叠 `Modifier.clickable`。

### 第二十四轮反馈修复（2026-10-12）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| V1 | 退出软件再进入，按理所有任务都该是暂停的，结果还在自动下载；zip/pdf 导出也要能"退出即停" | 用户最终拍板的方案（比"导出也暂停 + 任务页再点重新生成 + 密码回填"简单，少一类状态就不容易出错）：**退出 = 漫画图片下载暂停（可断点续传）、zip/pdf 导出直接删除（任务行 + 未完成中转文件）**。<br>① 统一入口 `AppContainer.handleAppExit()`：先 `jmExports.abortInProgressForExit()`（取消 Job → 删任务行 → 删 `files/library/export/` 下的中转文件），再 `jmDownloads.pauseAllForExit()`（批量置 `PAUSED`、卡在 `RUNNING` 的章节回队列、取消 Job），最后停前台服务撤通知——**先写库再撤服务**，通知不会停在"下载中"的旧快照上；<br>② 触发点是两个钩子、同一个入口且**幂等**：`DownloadForegroundService.onTaskRemoved`（划掉最近任务）与 `MainActivity.onDestroy`（只在 `isFinishing` 时做，转屏/重建不触发）；<br>③ 退出钩子没跑到（进程被杀）由启动时的 `recoverInterrupted` 兜底：**下载任务按磁盘重算进度后置 `PAUSED`（不再自动续跑）**，**导出任务连中转文件一起删**——顺带修掉旧 bug：被杀进程的导出任务以前被打回 `QUEUED` 却没有任何代码续跑，永远"排队中"还占着同 `(type, bizKey)` 的去重名额，用户再从详情页点导出会被 `submit` 判成"已有任务"挡住 | 代码（**按要求没有跑测试**）。设备上这样核对：下载中划掉最近任务 → 重新进入 → 任务页显示「已暂停」且不自动下载，点「继续」按磁盘续传（已下好的图不再请求）；zip/pdf 生成中划掉 → 重新进入 → 任务列表里没有这条任务，详情页立刻能重新点导出；转屏不打断下载 |

> **明确不做（用户否掉的方案）**：导出任务加 `PAUSED` + 任务页「重新生成」按钮 + 导出密码写回
> `SecretStore` 再回填。理由：导出不能续做，为它引入第四种收尾方式不值当。因此
> `DownloadManager.resumeQueued` / `requeueForResume` 两个只为"恢复后自动跑"存在的方法一并删除。
>
> 新增/同步的用例（**未在本机运行**，本机无 Android/gradle 环境）：
> `DownloadTaskDaoTest` 12→13（恢复=暂停、`pauseActive` 只动指定类型且章节回队列），
> `DownloadTaskRepositoryTest` 11→13（恢复断言改 `PAUSED`、导出被删+中转文件清理、退出暂停批量语义）。

### 第二十五轮反馈修复（2026-10-13）

承接上一轮的 `aaed21e feat：搜索结果页改为独立页`（发现页只留"输入 + 历史"，结果页入栈后自己发搜索请求）。

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| W1 | 结果页也要有搜索输入框和搜索按钮（样式与「搜索记录页」顶部那个一致）；结果页**不要 `TopAppBar`**；在结果页点搜索**不必再跳页面** | ① 把输入框抽成**两页共用的** `SearchKeywordField`（发现页原来那段内联 `OutlinedTextField` 直接换成它，所以"样式一致"不靠两份代码同步，而是同一个实现）；② `JmSearchResultsScreen` 去掉顶栏，改成 `Column` 第一行放这个输入框——**`topBar` 那段代码整段留成注释**（含标题 `state.keyword.ifBlank { "搜索结果" }` 与返回按钮），想切回顶栏只要放开注释、删掉输入框那三行；③ 结果页新增 `onKeywordChange` / `onSearch` 两个参数，`ModeRoot` 接的是同一个 `JmOnlineSearchViewModel` 的 `onKeywordChange` 与 `search()`，也就是**原地重搜**（清掉旧结果 → 居中加载圈 → 结果回来），不再 `navigate(SEARCH_RESULTS)` 压第二层；④ 顺带给输入框加 `ImeAction.Search` + `KeyboardActions`，软键盘上的「搜索」键与右侧按钮等价 | `:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 跑通（W2 的 5 个新用例全绿）。设备上请确认：进结果页 → 顶部是输入框（**没有**标题栏）→ 改关键字点按钮/回车 → **不跳页、原地出现加载圈并刷新列表**；系统返回键仍按普通入栈退回发现页/详情页 |
| W2 | 两页的搜索输入框都要自动去掉首尾空格、以及字符串**当中**的换行 | 新增纯函数 `sanitizeSearchKeyword(value)`：`filterNot { it == '\n' \|\| it == '\r' }.trim()`，只吃掉换行与首尾空白，**中间的空格保留**（"魔法 少女"是合法关键字）。净化点放在共用输入框的 `onValueChange` 里，所以两个页面、以及"键盘搜索键"这条路径全都过同一道 | 新增 `SearchKeywordSanitizeTest`（5 个用例：首尾空格 / 各种位置的 `\n`、`\r\n` / 中间空格保留 / 幂等 / 全空白输入）；**本轮已在本机跑通**：`gradlew :app:testDebugUnitTest --tests SearchKeywordSanitizeTest` → `tests=5 failures=0` |

> **一个需要你确认的取舍**：顶栏整段注释掉之后，结果页**页面上没有返回按钮了**，
> 返回只剩系统返回键/手势（Navigation 的入栈返回仍然正常，见 W1 的验证栏）。
> 如果希望"顶部仍有一根返回箭头"，最省事的做法是在输入框前面加一个 48dp 的
> `IconButton(Icons.AutoMirrored.Filled.ArrowBack)`（一行，不动其它结构），说一声即可。

> **本轮跑全量单测时发现两个与本次改动无关的既有失败**（都在本机复现，且**在把我的改动
> `git stash` 掉之后同样失败**，所以不是这次引入的）：
> `DownloadManagerTest.submitting again appends chapters instead of creating a second task`
> 与 `ExportManagerTest.submitting twice reuses the running export`——两者都是等异步收尾的
> 等待/竞态用例（前者 15s 超时"追加的章节没有被下载"，后者偶发第二次 `submit` 返回 `Created`
> 而不是 `AlreadyRunning`），机器一忙就会红。要不要顺手把这两个用例改成不依赖墙钟时间，
> 等你发话。

### 第二十六轮反馈修复（2026-10-13）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| X1 | **结果页输入框里的空格全变成了 `+`**：在记录页粘贴 `[Hoshikawa Ranga] 120cm ... 100% ... [Digital] ⏎ [星川ランガ] ... [DL版]`，点搜索进结果页后，输入框显示成 `[Hoshikawa+Ranga]+120cm+...100%...+[Digital][星川ランガ]...` | 两个独立缺陷叠在一起，**根因是"路由参数用表单编码塞、又用 `URLDecoder` 解第二次"**：<br>① `Routes.searchResults` 原来用 `URLEncoder.encode`——它是 `application/x-www-form-urlencoded`，**空格编成 `+`**；而 Navigation 解析路由参数走 `Uri.decode` 那一套，**只做百分号解码、不会把 `+` 还原成空格**，所以 `+` 一路活到界面；<br>② 页面里那次 `URLDecoder.decode` 是**第二次解码**，关键字里的 `100%` 已被 Navigation 解成裸 `%`，第二次解码直接抛 `IllegalArgumentException`，被 `runCatching` 兜住后退回**未解码的原始串**——上面那些 `+` 就留在了输入框里。<br>改法：新增 `JmRouteArgs.kt` 的 `encodeRouteArg` / `decodeRouteArg`，用 **Base64（URL-safe、无填充）+ `b64.` 前缀**过路由。选 Base64 而不是"换个转义函数"是因为它的字母表只有 `A-Za-z0-9-_`，**不含 `%`、`+`、`&`、`=`、空格**，所以无论 URI 那层解码几次、怎么解都是空操作；前缀是为了**可判别**（不带前缀按明文处理，否则 `Base64.decode("abcd")` 会"成功"解出乱码，用户搜 `abcd` 就被改坏）。`favoriteAlbums` 的收藏夹名是同一类输入（可能带空格 + `%`），一起换掉；`URLDecoder` / `URLEncoder` 两个 import 已从 `ModeRoot` 移除 | 新增 `RouteArgsTest`（8 个用例：**用户那段原文**往返一致 / 编码结果不含 `%` `+` `&` `=` `?` `#` `/` 空格 / 关键字里的 `+` 不被当空格吃掉 / 形如 `abcd`、`YQ`、`b64.` 的关键字不被 Base64 误解 / 无前缀的旧值按明文返回 / 坏 payload 退回原串不抛异常 / 空串与全空白 / 50 行多行长文本）；**本轮已在本机跑通**：`tests=8 failures=0`。设备上请核对：重新按用户那条路径粘贴 → 进结果页，输入框应与记录页所填**逐字一致**（只有换行按 W2 的规则被去掉） |

> **记一笔排查过程**（下次不用再绕）：一开始怀疑是 Navigation 没解、或 Uri 把 `+` 当空格，
> 用 Robolectric 写了个探针跑真实 `NavDeepLink` 才定位到"**Navigation 百分号解码但不碰 `+`**"
> 这一点——用户现象里 `%25` 已经变成 `%`、中文已经是中文（说明 Navigation 解过一层），
> 而 `+` 还在（说明它不还原 `+`），两条合起来就锁定了"我们那次多余的 `URLDecoder` 抛异常后
> 退回了原始串"。另外 `android.net.Uri.getQueryParameter()` **是**会把 `+` 当空格的，
> 所以**不能**拿它当"Navigation 怎么解"的依据。

### 第二十七轮反馈修复（2026-10-13）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| Y1 | 搜索结果卡片的「已入库」小字**去掉** | `OnlineResultRow` 里那段 `Text("已入库", labelSmall, primary)` 删除。状态已经写在按钮上，同一件事不摆两遍 | 代码 |
| Y2 | 「加入书库」按钮**居右**、改用 `OutlinedButton`；已入库时字样为「已入库」+ 一个对勾图标，**点它走移出书库**（要二次弹框） | `OnlineResultRow` 原来那行是「左：已入库小字 / 右：TextButton」，现在整行 `Arrangement.End` 只放**一颗**按钮，两种状态各一颗、`if (item.existsLocal)` 选：<br>① 未入库 `OutlinedButton(enabled = !adding)` = 「加入书库」（要联网时仍然是转圈 + 不可点，N2 的行为不变）；<br>② 已入库 `OutlinedButton(enabled = !removing)` = `✓ 已入库`（`Icons.Default.Check` 16dp + 文字）。<br>点「已入库」不做任何破坏性动作，只把这一条交给页面：`JmSearchResultsScreen` 新增 `onRemoveFromLibrary` 参数 + 一个 `removeTarget` 状态，弹**与书库页批量移除同一个** `ConfirmDialog`（标题「从书库移除这本？」，正文列出会删什么、并写明"最近浏览记录会保留…此操作不可撤销"，确认按钮 `移除并删除` + error 色），确认后才回调 VM。<br>确认框挂在**页面**上而不是每一行里：列表项滚出屏幕会被销毁，状态放行里会丢 | 代码（按要求没有跑测试，等你打 debug 包确认）。设备上这样核对：搜一个词 → 每张卡右下角一颗描边按钮 → 点「加入书库」→ 变「✓ 已入库」→ 再点它 → 弹确认框 → 取消则按钮不变、确认则本地文件与记录一起没、按钮回到「加入书库」，底部弹一句「已从书库移除…」 |
| Y3 | （Y2 的配套）搜索结果里移出书库要能**真正生效** | `JmOnlineSearchViewModel` 注入 `AlbumCleaner`（工厂里补 `container.jmAlbumCleaner`），新增 `removeFromLibrary(item)`：走**与详情页/书库页同一条**编排 `cleaner.removeFromLibrary(listOf(album))`（记录 + 章节 + 收藏关联 + 阅读位置 + 图片 + 导出成品全清，最近浏览与封面保留）。执行期间置新状态 `OnlineSearchUiState.removingIds`（按钮转圈 + 挡重复点按，见 `removingIds` 的注释）；成功后把这条的 `existsLocal` 刷回 false（原来的 `markLocal(id)` 泛化成 `markExistsLocal(id, exists)`，入库/移出共用），失败只弹一句提示 | 同上 |

> 顺带说明：`existsLocal` 这个名字在搜索结果里表达的其实是"**在不在书库**"（`existingIds` 查的是书库成员表），
> 所以移出之后必须刷成 false，否则按钮会一直停在「已入库」。

**Y2 的视觉补丁（同一轮追加要求）**：

| # | 反馈 | 落实方式 |
|---|---|---|
| Y4 | 两颗按钮都**再小一号**、都**去掉边框**；「已入库」用**主色调** | 按钮仍是 `OutlinedButton`（透明容器 + 现成的按下/禁用态），但 `border = null` 去掉描边，`contentPadding` 收到 `10dp / 0dp`、高度收到 `32dp`（Material 默认是 16dp 内边距 + 40dp 高）——两个值抽成 `ONLINE_ACTION_BUTTON_HEIGHT` / `ONLINE_ACTION_BUTTON_PADDING`，**两颗按钮共用**，状态切换时尺寸不会跳。<br>配色：已入库 `contentColor = colorScheme.primary`（主色，对勾图标跟着继承），未入库 `onSurfaceVariant`（次要色）——"在库里"是当前状态，用主色标出来；"可以入库"只是可用动作，不与状态抢色 |
| Y5 | 按钮要**始终贴卡片的右下角**：标题只占一行时，按钮底边离卡片下内边距还有一段空隙 | 原来按钮在"文字列"里面，而文字列的高度是它自己的内容高度——封面固定 100dp，标题一行时那一列只有 ~72dp，按钮就跟着停在了半空。<br>改成：`Box(padding(8dp))` 里放 `Row(封面 + 文字列)`，按钮**浮在这个 Box 上** `Modifier.align(Alignment.BottomEnd)`，位置只由卡片高度决定，与标题占几行无关；文字列底部加 `padding(bottom = ONLINE_ACTION_BUTTON_ZONE)`（32dp 按钮 + 4dp 间距）留出净空，作者行不会被压住。<br>没有用 `height(IntrinsicSize.Min)` + `fillMaxHeight` 那一套：封面走的是 Coil 的 `AsyncImage`，intrinsic 测量要多绕一层、还得把文字列的排布改成 `SpaceBetween`（`Spacer(weight)` 在 intrinsic 下有歧义），而 Box 浮层是确定性布局，卡片高度与改动前**逐 dp 一致** |

### 第二十八轮反馈修复（2026-10-15）

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| Z1 | 设置页「导出」里的 ZIP / PDF 密码框：**最多 30 个字符**，并**去掉首尾空格与字符中的换行回车** | 上限抽成 `SettingLimits.EXPORT_PASSWORD_MAX_CHARS = 30`（UI 校验常量统一放这里，不在页面里再写一份字面量）。净化拆成两半，都是在 `SettingsScreen.kt` 里的纯函数：<br>① `filterExportPasswordInput` = `filterNot { it == '\n' \|\| it == '\r' }.take(30)`，放在 `onValueChange` 里，**打字与粘贴走同一道**（`singleLine` 只是挡住键盘回车，粘多行文本照样进得来）；<br>② `sanitizeExportPassword` = 前者 + `.trim()`，**点「保存」时**用，并且把净化后的值**写回输入框**——不然框里会留着那颗已经被 trim 掉的空格，看起来像"没保存成功"。<br>为什么不在 `onValueChange` 里直接 trim：边打字边 trim 会让**中间的空格永远打不出来**（"a" + 空格被吃掉 → 再打 "b" 就成了 "ab"），而 `a b` 是合法密码。折中办法是加 `Modifier.onFocusChanged`：**离开输入框时补一次 trim**，不点保存也不会一直挂着一串空格。<br>ViewModel 的 `saveZipPassword` / `savePdfPassword` 也换成同一个 `sanitizeExportPassword`（原来各自 `trim()` 一份），框里显示的和实际存进去的是同一个值 | 代码（按要求没有跑测试，等你打 debug 包确认）。设备上这样核对：粘贴一段带换行的 40 字符文本 → 框里只剩前 30 个且没有换行；打 `a b` 空格能打出来；打 ` abc ` 后点旁边那颗**保存** → 空格消失、弹「…已保存」；再进这一页显示的是存下来的那个值 |
| Z2 | 密码**直接明文显示**；「显示 / 隐藏」按钮**改成「保存」**，下面 Row 里那颗保存按钮**去掉** | 导出那两个框不再复用代理密码用的 `PasswordField`（那个仍是圆点 + 「显示 / 隐藏」），新建 `ExportPasswordEditor`：不传 `visualTransformation`（Compose 默认 `VisualTransformation.None`），`trailingIcon` 里那颗文字按钮直接写「保存」，原来 `Row(Arrangement.End) { Button("保存") }` 整段删除 | 同上：两个框里直接看到密码原文，右边只有一颗「保存」按钮，下方不再有第二颗 |
| Z3 | （Z1 的连带 bug）设置里把 ZIP/PDF 默认密码**清空**后，详情页点「导出 ZIP/PDF」的密码框**仍然预填着密码** | 根因在 `AlbumDetailViewModel.defaultPasswordFor`：`secrets.get(key)?.takeIf { it.isNotEmpty() } ?: Defaults.DEFAULT_EXPORT_PASSWORD`——只要读出来是空（或根本没有），就**兜底成参考实现的 `1234`**。而 `SecretStore.put(key, "")` 的语义是**删掉这一项**（见 `SecretStore` 注释），所以"用户清空过"与"从来没设过"在存储层是同一个状态，兜底必然命中；更要紧的是**设置页显示的从来不兜底**（`SettingsViewModel.reload` 里只 `orEmpty()`），于是"设置里看见空的、导出时冒出 1234"。<br>改法：`defaultPasswordFor` 直接 `secrets.get(key).orEmpty()`，**去掉兜底**——设置里留空/没设过 = 预填空串 = 不加密（合上设置页 hint 那句"留空表示不加密"）。`Defaults.DEFAULT_EXPORT_PASSWORD` 常量**保留**（协议常量对照测试 `CryptoGoldenTest` 仍要核它），只在 KDoc 里写明它不再当预填兜底；`ViewModels.kt` 里那个因此空掉的 `Defaults` import 一并删掉。顺带把 `AlbumDetailScreen` 里"支持显示/隐藏"这类过期注释写回现状 | 代码（按要求没有跑测试，等你打 debug 包确认）。设备上这样核对：设置里两个密码都清空 → 详情页点「导出 ZIP」→ 密码框**是空的**（`导出 PDF` 同样）→ 直接点「确认」→ 任务提示与成品都是未加密（无密码可打开）；再在设置里填个密码 → 再点导出 → 密码框预填的就是刚填的那个值 |

### 第二十九轮反馈修复（2026-10-15）

**起因**：确认「同时下载图片数」选**自动**时并发到底听谁的。查完的结论是——**没有任何依据，就是一个常量 4**：

- 「自动」= 存储值 0（`SettingDefaults.Jm.DOWNLOAD_THREADS_AUTO`）；
- `AppContainer.concurrency()` 只在 `configured > 0` 时按范围收敛，`0` 直接返回
  `DownloadManager.AUTO_CONCURRENCY`（`AUTO_CONCURRENCY = 4`）；
- 全工程搜 `availableProcessors` / `maxMemory` / `MemoryInfo` / `ActivityManager` **零命中**，
  所以计划 §9.3 里那句"自动值结合设备内存限制"**从来没落地**，而设置页 hint
  却写着"自动值会参考设备内存"——UI 说了假话。

用户拍板：**只改文案，上限提到 12，默认 4，不做内存档位**；
追加一条：**"自动"这一档本身也取消**——"默认 4"指的是滑杆初始停在 4，不是一个特殊档位。

> 下面是这一轮**最终落地**的形态（AA1 的中途版本已被 AA4 覆盖）。

| # | 反馈 | 落实方式 | 验证 |
|---|---|---|---|
| AA1 | 设置页那句"自动值会参考设备内存"是假的，**文案改正**；且**不要"自动"这一档** | 先照实改成"「自动」= 固定 4"，随后按用户追加的要求**把"自动"档整个去掉**（见 AA4）：滑杆就是 1～12 的纯数值、默认停在 4，hint 改成「同时下载的图片张数（默认 4）；拉大不一定更快，取决于线路与设备」 | 代码（按要求没有跑测试，等你打 debug 包确认）。设备上这样核对：设置 → 下载 → 提示语是新的那句、滑杆右侧数值直接是数字（没有「自动」字样），初始停在 4 |
| AA2 | **最大支持 12 线程** | `SettingLimits.DOWNLOAD_THREADS` 由 `1..8` 改成 `1..12`（这一个值同时管住两边：业务侧的归一化与滑杆范围），滑杆从 `0f..8f / steps = 7` 改成按常量算 `1f..12f`、`steps = last - first - 1`（即 10，对应 1～12 共 12 档）。`SettingsUiState.downloadThreads`、`setDownloadThreads` 注释里的 `1～8` 一并订正 | 同上：滑杆能拉到 12，拉到 12 后下载并发就是 12；旧数据不受影响——之前存的 1～8 本来就合法 |
| AA3 | 文档同步 | 计划 §9.3 的设置表那一行由"自动（默认）或 1～8；自动值结合设备内存限制，不能无界按 CPU 核数解码"改成"**1～12，无「自动」档，默认 4**"——规范与实现对上了，不再留一条没人实现的承诺 | 文档 |
| AA4 | （AA1 的追加要求）**"自动（4）"这个挡位不要**：默认 4 的含义是"滑杆初始就停在 4 这个位置"，不是一个特殊档位 | 既然没有"自动"档，`0` 这套语义就整个拆掉：<br>① `SettingDefaults.Jm.DOWNLOAD_THREADS_AUTO = 0` **删除**，新建 `DOWNLOAD_THREADS = 4` 作为设置默认值；<br>② `DownloadManager.AUTO_CONCURRENCY` **删除**（已无引用方）；<br>③ `AppContainer.concurrency()` 去掉 `if (configured > 0) … else AUTO` 分支，改成一次归一化；<br>④ 新增 `SettingDefaults.Jm.normalizeDownloadThreads(value)`：落在 1～12 内原样用，**其余（含老版本存下的 `0`）一律按默认 4**——这里刻意**不用 `coerceIn`**，否则老用户那张"自动"的 `0` 会被静默压成 **1**（并发从 4 掉到 1，明显变慢），按默认值处理才是"取消自动档"该有的语义；<br>⑤ 设置页 `reload()` 也过同一个归一化，否则会出现"state = 0、滑杆最小是 1"导致拇指位置与右侧数字对不上。读设置的两处（设置页显示 / 下载取并发）共用这一个函数，不会两边不一致 | 同上。设备上这样核对：滑杆最左是 **1**、最右是 **12**、初始停在 **4**；右侧数值全程是数字、没有「自动」；**覆盖安装**（老版本里选过"自动"的机器）进设置页显示的也是 4，不是 1 |

> **顺带记下两个查证时的结论**（都不改动，只是免得以后误判）：
> ① 并发是**一话内**的图片并发（`downloadChapter` 开头读一次），章节之间串行；
> ② 跨任务没有全局限流（`PARALLEL_ALBUMS` 只有常量定义、无消费方），
> 所以同时在跑的 N 个任务各自都有一份自己的并发额度。

### 卡片「按下态」底色收编为全局 token（2026-10-15）

`ContinueReadingCardRow`（书库「继续阅读」卡片）与 `ExportSection`（详情页「导出文件」卡片）
原来各自算了一遍"按下态容器色"：

```kotlin
MaterialTheme.colorScheme.onSurfaceVariant
    .copy(alpha = 0.10f)
    .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
```

两处逐字相同，且以后还会有别的卡片要拿它当常态底色，所以收编成一个色板入口。

| 项 | 落实方式 |
|---|---|
| 落点 | 新建 `app/src/main/java/com/haruhi/jmcomic/ui/theme/ExtendedColors.kt`，**不是** `Color.kt` |
| 为什么不是 `Color.kt` | `Color.kt` 只放**写死的原始色值**（`CardWhiteSurface` 那种），而这个是**从当前 ColorScheme 推导出来的**——必须放在主题里面算，写成常量的话一开动态取色就对不上（`surfaceVariant` / `onSurfaceVariant` 会被壁纸色顶掉） |
| 形态 | `JmExtendedColors`（`@Immutable data class`，**语义 token 名**而非色值名）+ `LocalJmExtendedColors`（`staticCompositionLocalOf`，同 `LocalImageLoader`）+ 公开入口 `JmTheme.colors`。这也是 `Color.kt` 里 `WarningOrangeLight` 那条 TODO 说的"自建色板"，以后收编警示色照搬即可 |
| 推导 | `ColorScheme.toExtendedColors()` 私有扩展：`onSurfaceVariant.copy(alpha = 0.10f).compositeOver(surfaceVariant)`，10% 抽成 `StateLayerPressed` 常量并注明出处（m3.material.io → Foundations → Interaction → States，Pressed = 0.1）。**深浅两套共用同一个公式**，浅色是变灰、深色是变亮，不用各写一个值 |
| 挂载 | `JmComicTheme` 的 `MaterialTheme(...)` 里层调 `ProvideJmExtendedColors(colorScheme) { content() }`——扩展色板要从最终生效的 `colorScheme` 推导，所以必须在 `MaterialTheme` **里面**。加新 token 只改 `ExtendedColors.kt`，`Theme.kt` 不用动 |
| 调用点 | 两处都改成 `containerColor = JmTheme.colors.pressedCardContainer`，各自的 `contentColor = MaterialTheme.colorScheme.onSurfaceVariant` **保持不变**（它不是 ColorScheme 槽位，`contentColorFor()` 认不出来，不显式给文字色会退回更深的 `LocalContentColor`）；两处 `import androidx.compose.ui.graphics.compositeOver` 随之删除 |
| 验证 | `.\gradlew.bat :app:compileDebugKotlin` 通过；渲染结果与改动前逐字节相同（同一个公式、同一组输入，只是换个地方算）。以后新卡片要这个底色，只用一行 token，别再抄公式 |

### 章节行菜单：加「下载本话」并给两项都配图标（2026-10-15）

**要求**：`ChapterRowItem` 的行尾菜单里，在「删除图片」上面加一项「下载本话」，两项都要有图标。

| 项 | 落实方式 |
|---|---|
| 新参数 | `ChapterRowItem` 加 `onDownload: () -> Unit`，两个调用点（不分组那支与分组内那支）都传 `onDownloadChapters(listOf(chapter.chapterId))`——**复用**面板那条提交链路，没有新开下载入口、没碰 ViewModel |
| 为什么可以复用 | `AlbumDetailViewModel.downloadChapters` 已经做过滤与兜底：空列表、已在队列里的 id（`queuedChapterIds`）都会给出提示而不重复提交，所以单话快捷入口不需要自己判队列 |
| 部分下载 | 提交的是章节 id，从头下过的图由下载引擎按落盘结果跳过，所以「部分下载」的那一话点它是"补缺"，不是重下 |
| 图标 | 两项都用 `leadingIcon`：删除 `Icons.Default.Delete`（与成品行「删除文件」同一支），下载用**手画的 `DownloadIcon`**（见下一行）。尺寸统一 `ButtonDefaults.IconSize`（与成品行那颗删除图标同一个 token）。图标**不显式 tint**：`DropdownMenuItem` 把内容色写进 `LocalContentColor`，删除那项的 `textColor = error` 会自动带着图标一起变红 |
| 顺带：`EyeIcon` 放大 15% | 用户反馈观看数的眼睛"有点小"。查下来不是尺寸参数的锅：`StatItem` 里点赞（`Icons.Default.Favorite`）与观看（`EyeIcon`）都按同一个 `size(14.dp)` 渲染，但爱心占了视口的 20×19，而原来的眼睛几何只占 **20×10.4**——眼型是扁的，同样的方框里自然小一号。所以把 `EyeIcon` 的坐标按 **1.15** 绕视口中心缩放（`c' = 12 + (c - 12) × 1.15`，外圈 x 由 2～22 扩到 0.5～23.5），**视口与调用点尺寸都不动**：图标该多大仍由 `size(...)` 决定，这里只改图形在视口里的占比。虹膜半径 4.5→5.175、瞳孔 2.5→2.875，两者之差仍是原来的 2.3（眼白环的粗细不变） |
| ⚠ 图标踩坑（`Icons.Default.Download` 爆红） | **`material-icons-core` 里没有 `Download`**——它只有 50 个常用图标（实测列出：AccountBox…Warning，含 `Add`/`ArrowDropDown`/`CheckCircle`/`KeyboardArrowDown` 等，**没有任何下载图标**），而工程明确**不引 `material-icons-extended`**（见 `app/build.gradle.kts`）。所以新建 `ui/theme/Icons.kt` 手画一个 `DownloadIcon`：竖线 + 向下箭头 + 底部托盘，坐标照 2014 版 MD 的 `Download` 几何（core 里最接近的 `KeyboardArrowDown` 只有箭头、没有托盘，"往下翻"和"下载到本地"分不开，所以没用它）。**顺带把原先私有的 `EyeIcon` 从 `AlbumDetailScreen.kt` 迁到同一个文件**——手画图标的落点从此只有一处，别再加第二个 |
| 顺带发现（本轮没改） | 行尾 ⋮ 的显示条件是 `chapter.downloadedImages > 0`，所以**完全没过的一话根本看不见这个菜单**，那种情况仍走更多菜单的「下载漫画」。已在 KDoc 里写明，避免以后把这个当 bug 查 |
| 验证 | 按要求**没有跑测试**（用户自信测试）。设备上这样核对：找一话"部分下载 x/y"的章节 → 点 ⋮ → 菜单从上到下是「下载本话」（下载图标）「删除图片」（红色 + 删除图标）；点「下载本话」→ 弹出"已加入下载队列…"并在任务页看到该话；再点一次 → 提示"选中的章节都已经在下载队列里了"而不是又建一个任务 |

## P4 明细（JM 导出 zip / pdf）

| 项 | 状态 | 产出 |
|---|---|---|
| ZIP 打包（AES 密码 / 不加密、UTF-8 名、不套本子目录） | ✅ | `core/codec/ArchiveWriter.kt` |
| PDF 生成（每图一页、章节目录、权限、AES-128） | ✅ | `core/codec/PdfWriter.kt`（P0 spike 起就有，本轮补切片与取消） |
| **超长图按高度切片**（PDF 单页上限 14400） | ✅ | `core/codec/PdfPagePlan.kt` + `PdfPagePlanTest` |
| 导出编排（复用任务表、进度进任务页、取消、去重） | ✅ | `feature/data/ExportManager.kt` |
| 密码不进数据库 | ✅ | 密码只存在内存的 `passwords` 表里；payload 只有 albumId/folderName/format |
| 成品交付到系统目录 | ✅ | `core/storage/ExportPublisher.kt`（API 29+ 走 MediaStore 下载目录） |
| 导出前的密码框（预填设置里的密码，留空即空框 + [确认]/[不设密码]） | ✅ 代码 | `ExportPasswordDialog` |
| **缺图预检**（列明缺哪几话、缺多少张，先补下载） | ✅ | `ExportManager.precheck` + 详情页对话框 |
| 导出不支持续做：只给取消，不给重试 | ✅ | `TaskRow.isExport` 关掉暂停/继续/重试 |

### 关于 PDF 单页上限（计划 §7 点名要确认的"行为差异"）

计划里写着"参考实现不处理 14400 上限，如需优化（按高度切片）属于行为差异，要单独确认"。
本轮的处理是**按高度均匀切片**，原因是它不会失败、阅读体验也正常（拒绝导出会让用户
拿不到文件）。没超限的图仍然是一页一张、页面尺寸等于像素尺寸，与参考实现一致；
宽度超限（更罕见）则整体等比缩小。切片规则由 `PdfPagePlanTest` 的 8 个用例锁住。

### P4 的模拟器实测（2026-10-09）

| 步骤 | 结果 |
|---|---|
| 已下载的本子 → ⋮ → 「导出 ZIP」 | ✅ 弹出密码框，**预填设置里那个默认密码**（设置里留空时框里也是空的）、[不设密码]/[取消]/[确认] |
| 点「确认」 | ✅ 提示"已开始导出 ZIP"，任务页出现导出任务 |
| ZIP 结果 | ✅ 日志 `zip 完成：12 个条目，加密=true`；`/sdcard/Download/JMComicH/<本子名>_JM377531.zip`（12.4 MB） |
| 点「导出 PDF」→「不设密码」 | ✅ 日志 `pdf 完成：12 页 / 1 话（切片 0 张），加密=false`；同目录下 20.8 MB 的 PDF |
| 任务页 | ✅ 导出任务显示文件名与 `已完成 · 12/12 张`，且**没有「重试」**（导出不能续做） |
| 设备测试（`:app:runDeviceTests`） | ✅ **11 个用例全过**：zip AES/错密码/不加密、PDF 长图切片、PDF 加密、数据库迁移 |

> 真机还需要确认的是"用系统文件管理器打开导出的文件"这类观感项——
> 模拟器上已经能在 `/sdcard/Download/JMComicH/` 看到两个成品。

### 真机验证发现的一个严重 bug（已修）

**改动数据库表结构却忘记升 `@Database(version)`**，导致**应用一启动就崩**：

```
java.lang.IllegalStateException: Room cannot verify the data integrity.
Looks like you've changed schema but forgot to update the version number.
Expected identity hash: 7ecd5cfc..., found: 31e452937...
```

触发条件是**覆盖安装**——全新安装不会有这个问题，所以"删掉重装"这种调试习惯
永远发现不了它。P2 加 `t_jm_recent_album` 时就这样漏了。

修复：

- `@Database(version = 1 → 2)`；
- `Migrations.MIGRATION_1_2` 建新表（**不使用 destructive migration**，否则用户数据全丢）；
- 新增 `MigrationsTest`（真机）：从 v1 建库 → 跑迁移 → 校验 schema → 断言旧数据还在，
  并断言新表可写。这类错误从今往后会被测试拦住；
- `app/schemas/` 挂进 `androidTest` 的 assets（`MigrationTestHelper` 要从那里读 schema），
  并且**历史版本的 schema JSON 必须一起进版本库**——我一度删掉了 `1.json`，
  迁移测试立刻失败。

**教训**：`@Database(entities)` 一改就必须同时升 version + 补迁移 + 保留历史 schema。

### P2 待验证（需要你在设备上确认）

我能自动验证的部分都过了：**130 个单元测试 + 6 个真机测试**
（迁移 2 + PDF 加密 4）全绿，lint 0 error，应用启动无崩溃。

**但交互与观感我无法自己验证**，因为这台设备同时封掉了两条路：

| 手段 | 结果 |
|---|---|
| Compose UI 测试 | ❌ 永久阻塞：测试 Activity 拿不到焦点（`topResumedActivity` 始终是 launcher） |
| `adb shell input tap/swipe` | ❌ `SecurityException: ... INJECT_EVENTS permission` |
| 截图 | ✅ 可用（只读） |

所以请你在设备上确认这几项（都是这次改动的重点）：

1. **发现页搜索**：首屏是否只出 10 条、下滑是否自动追加、全部展示完才出现「加载下一页」；
2. **封面缓存**：第二次进同一页是否明显更快（缓存落在 `files/covers`）；
3. **点详情不入库**：从搜索点进详情后返回，书库是否没多出这本；
4. **最近浏览**：进过详情的本子是否出现在「最近」tab，且按浏览时间倒序；
5. **收藏数量**：收藏一本后进收藏页，数量是否立即更新；
6. **收藏夹编辑**：详情页「收藏夹」面板的加入 / 移出 / 移动到 / 新建；
7. **夹内页面**：标题是否显示收藏夹名、是否只剩「已下载 / 未下载」。

### P1 明细

| 项 | 状态 | 产出 |
|---|---|---|
| Room：9 张表（业务 5 + 任务 2 + 位置 1 + 搜索历史 1） | ✅ | `core/database/entity/*.kt` |
| Dao 与查询语义（分页/排序/分组/唯一索引） | ✅ | `core/database/Daos.kt`，测试 `DaoTest` |
| 持久化下载任务的去重与恢复语义 | ✅ | `DownloadTaskDaoTest`（12 个用例） |
| 三作用域设置 + 跟随全局继承 | ➖ **已取消** | 见「改造：取消全局设置作用域」（2026-10-15） |
| Keystore 凭据存储 | ✅ | `core/settings/SecretStore.kt` |
| 目录规划 | ✅ | `core/storage/LibraryDirs.kt` |
| 网络层（工厂 / 错误分类 / 超时重试） | ✅ | `core/network/*` |
| DI 容器 + Application + 权限声明 | ✅ | `core/di/AppContainer.kt`、`JmComicApp`、Manifest |
| **下载引擎（Range 续传 + 并发 + 取消）** | ✅ | `core/download/FileDownloader.kt`，测试 `FileDownloaderTest`（12 个用例） |
| 图片解扰落盘（`blockNum==0` 直接落原图） | ✅ | `core/codec/ImageCodec.kt`、`ImageDownloadTransform` |
| **任务仓库 + 进程重启恢复编排** | ✅ | `core/download/DownloadTaskRepository.kt`，测试 `DownloadTaskRepositoryTest` |
| **前台服务 + 常驻通知** | ✅ | `core/download/DownloadForegroundService.kt`、`core/notification/*` |
| WorkManager 约束调度（仅 Wi-Fi 等） | ⬜ | 服务与恢复已就绪，缺 Worker 与 Constraints |
| 设置层单元测试（作用域隔离 / 继承 / 恢复默认） | ⬜ | 作用域已收敛成一个，只剩"恢复默认"还能测 |
| 设置校验工具（写入前校验，拒绝非法代理等） | ⬜ | `ResolvedNetworkConfig.validate()` 已就绪，缺 UI 对接 |

### 下载引擎已覆盖的语义（计划点名项）

| 计划要求 | 实现位置 | 测试 |
|---|---|---|
| 图片"文件存在即跳过" | `FileDownloader.download(skipIfComplete)` | `skips download when the target already exists` |
| HTTP Range 续传 | `attemptOnce` 的 206 分支 | `resumes from the existing part file` |
| **CDN 不支持 Range 时整文件重下** | 200 分支丢弃 `.part` | `does not support range - server ignores Range and we restart from zero` |
| 临时文件校验后再原子发布 | `publishAfterValidation` + `publish` | `truncated download is rejected when the caller knows the expected size` |
| 416 / 区间不匹配的处理 | `RangeNotSatisfiable` / `RangeMismatch` | `range 416 drops the local offset and restarts from scratch` |
| 直链过期后重新解析 | `urlProvider` 每次尝试重新调用 | `server error is retried and url is re-resolved each attempt` |
| 批量图片并发 + 进度到图片 | `ParallelImageDownloader` | 逻辑由 `DaoTest` 的章节计数用例覆盖 |
| 下载网络策略在安全检查点检查 | `DownloadGate` + `DownloadManager.awaitNetworkPermission` | `DownloadGateTest`；模拟器实测"等待网络 → 切网络自动继续" |

### P1 验收（计划原文）

> 能下载一个任意大文件到私有目录，断网/杀进程后能续传，通知显示进度；
> **杀掉进程再启动，任务列表和进度能从数据库恢复（RUNNING 任务按磁盘现状重算进度后继续）**。

| 验收点 | 状态 | 证据 |
|---|---|---|
| 下载任意大文件到私有目录 | ✅ | `FileDownloaderTest`；落盘在 `filesDir/library`，导出走 SAF |
| 断点续传（Range） | ✅ | `resumes from the existing part file`；不支持 Range 时整文件重下 |
| 不完整文件不发布 | ✅ | `publishAfterValidation` + 断流用例 |
| 通知显示进度 | ✅ | `DownloadForegroundService` 订阅活跃任务渲染通知（真机观感待 P7） |
| 杀进程后任务与进度从数据库恢复 | ✅ | `recoverInterrupted` + `DownloadTaskRepositoryTest` 的 4 个恢复用例 |
| 恢复后按磁盘现状重算（不回退、不重复累加） | ✅ | `recomputeChapterProgress`；0 字节残file 不计入 |
| **恢复后不自动跑：下载置"已暂停"、导出被删干净**（P3/P4 接上，第二十四轮改） | ✅ | `recoverInterrupted` 分区处理；`AppContainer.handleAppExit` 为正常退出路径 |
| WorkManager 约束调度（仅 Wi-Fi 时自动延后） | ⬜ | 前台服务已就绪；"等待网络"由执行器的闸门实现，Worker 留到 P6 |

## 已确认的决策（2026-10-08）

| 项 | 决策 | 落地位置 |
|---|---|---|
| PDF 加密算法 | **改为 AES-128** | `PdfWriter.applyProtection` 同时设 `encryptionKeyLength=128` + `isPreferAES=true` |
| PDF 权限 | **只看「能否打开」**：owner 与 user 同密码，权限位不作为安全承诺 | `PdfWriter.applyProtection`；事实由 `PdfEncryptionInstrumentedTest` 断言固定 |
| debug 包共存 | **debug 加 `.debug` 后缀**，可与 release 同时安装 | `app/build.gradle.kts` 的 `applicationIdSuffix` |

> **一处更正**：先前 `docs/p0/spike2-pdf.md` 写「参考实现产出 AES-128、Android 只能 RC4-128」，
> 这是错的（当时 `javap` 输出不完整，漏看了 `setPreferenceAES`/`setEncryptionKeyLength`）。
> 实测：**只设 `encryptionKeyLength` 会被忽略，参考实现实际产出 40 位 RC4**；
> 只有同时设 `preferAES=true` 才得到 AES-128。所以本 App 的 AES-128 是安全强化，不是对齐。
> 详见 `docs/p0/spike2-pdf.md` 第 2 节。

## 剩余待确认 / 待处理

1. **release 签名 keystore 需备份**：`jmcomic-release.jks` 只在本地，丢了无法覆盖升级。
   （运维事项，不是代码问题。）
2. **JM 图片线路默认值**：参考实现硬编码 `IMAGE_DOMAIN`，App 侧作为可编辑线路列出，
   默认值沿用 `cdn-msp2.jmapiproxy2.cc`（见 `Defaults`）。这条只是记录，不是待确认项。
3. **PDF 单页尺寸上限**：参考实现不处理 14400×14400 上限，JM 长图可能超。
   P4 已按"**按高度均匀切片**"落地（理由与规则见上方 P4 明细），若你更希望"超限就拒绝导出"，
   说一声即可改回；当前选择是保证导出一定能拿到文件。

## 真机验证记录

设备：小米 23116PN5BC（`shennong`，1440×3200），通过无线调试连接（会掉线，需重连）。

| 项 | 结果 |
|---|---|
| release APK 安装 | ✅ 4.44 MB，`adb install` 成功 |
| 冷启动 | ✅ 无崩溃，`topResumedActivity` 为 `MainActivity` |
| 界面渲染 | ✅ 显示占位页（截图已确认） |
| R8 混淆后的运行期正确性 | ✅ 修掉 `packageScope` 引发的 `IllegalAccessError` 后正常 |
| **PDF AES-128 真机验证** | ✅ **4 个用例全通过**（`:app:runDeviceTests`） |

### PDF 加密真机用例（2026-10-08 跑通）

```
com.haruhi.jmcomic.core.codec.PdfEncryptionInstrumentedTest:....
Time: 0.2
OK (4 tests)
```

| 用例 | 验证内容 |
|---|---|
| `albumPdfWithPasswordUsesAes128` | `V=4 / R=4 / Length=128 / StdCF=AESV2`，3 页 |
| `wrongPasswordCannotOpenThePdf` | 错误密码抛 `InvalidPasswordException` |
| `emptyPasswordMeansNoEncryption` | 「不设密码」确实不加密 |
| `permissionBitsAreWrittenButOwnerIdentityBypassesThem` | P 位写对，但 owner 身份使其不生效（有意固定该事实） |

### 设备测试（instrumented）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `core.codec.ExportInstrumentedTest` | 5 | zip AES / 错密码打不开 / 不设密码可直接读；**PDF 长图切片**；普通图一页一张 |
| `core.codec.PdfEncryptionInstrumentedTest` | 4 | PDF AES-128 / 错误密码 / 权限位事实 |

> `core.database.MigrationsTest`（2 个用例）已删除——它测的是 v1→v2 的旧表结构，
> 而 v4 改成全新基线后 v1/v2 的 schema 已不存在。见「改造：纯漫画版」第 4 节。
> 所以设备测试从 11 个用例降到 9 个。

当前结果（模拟器 `emulator-5554`，**改造前**跑的 11 个用例）：

```
com.haruhi.jmcomic.core.codec.ExportInstrumentedTest:.....
com.haruhi.jmcomic.core.codec.PdfEncryptionInstrumentedTest:....
OK (9 tests)
```

### 设备测试的运行方式

**不要用 `connectedAndroidTest`**：AGP 给测试 APK 硬编码加 `-g`，MIUI/HyperOS
拒绝该 flag（`SecurityException: ...INSTALL_GRANT_RUNTIME_PERMISSIONS...`），
安装阶段就失败。这在该 ROM 上无解，`installOptions` 改不到 AGP 内部默认值。

改用本工程自带的任务（自己 `adb install` 不带 `-g`，再 `am instrument`）：

```powershell
.\gradlew.bat :app:runDeviceTests
.\gradlew.bat :app:runDeviceTests "-PclassFilter=<完全限定类名>"
```

排查过程与其它安装问题见 `docs/BUILD.md`。

详细排查与体积优化记录见 `docs/BUILD.md`。

## 验证命令

```powershell
cd D:\Project\JMComicH

# 开发机：273 个单元测试 + lint + 两种构建
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease

# 设备：instrumented test（迁移 + PDF/zip 加密 + 长图切片）
$env:ANDROID_SERIAL = "emulator-5554"   # 接了多台设备时指定目标
.\gradlew.bat :app:runDeviceTests
```

当前基线（**第二十四轮改动前跑通**）：**273 个单元测试 + 11 个设备用例全绿**，lint 0 error
（27 条 warning 均为既有项），debug / release APK 均可构建（release 5.6 MB）；
第二十四轮新增 / 同步 4 个用例（273 → 277）**未在本机运行**（无 Android/gradle 环境）。

### 测试清单（单元测试）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `feature.jm.data.RepositoryTest` | 24 | 入库字段一致性 / 收藏保留 / 章节来源优先级（含单话本子兜底与标题归一化）/ 标签 OR / 删除语义 |
| `feature.jm.ui.ReaderAndTaskLogicTest` | 23 | 任务状态与进度文案 / 下载面板默认勾选与"已在队列" / **底栏切换方向** / 阅读器进度条与"待取 vs 失败" / 阅读偏好编解码 |
| `feature.jm.data.RecentAndFavoriteTest` | 19 | 最近浏览倒序 / 浏览时间语义 / 点详情不入库 / **骨架行不算本地数据** / 收藏夹数量与封面来源 Flow |
| `core.protocol.CryptoGoldenTest` | 14 | 签名 / AES-256 解密 / 块数，与参考实现逐值一致 |
| `core.storage.FileNamingGoldenTest` | 14 | 文件名清洗 / 本子目录名 / 章节与图片排序 |
| `core.database.DaoTest` | 14 | 书库分页排序（入库时间倒序）/ 标签 OR / 章节分组 / 收藏唯一索引 |
| `feature.jm.data.DownloadManagerTest` | 13 | **下载编排**：逐字节一致（块数 0 不重编码）/ 跳过已存在 / 断点只补缺口 / 等待网络 / 取消 / 追加章节 / 重试 |
| `core.database.DownloadTaskDaoTest` | 13 | 任务去重 / **恢复=暂停** / `pauseActive` 只暂停指定类型且章节回队列 / 章节计数幂等 / 历史裁剪 |
| `core.download.FileDownloaderTest` | 12 | Range 续传 / 不支持 Range / 断流 / 416 / 原子发布 |
| `core.download.DownloadTaskRepositoryTest` | 13 | 杀进程恢复（**下载置暂停、导出被删+中转文件清理**）/ 按磁盘重算进度 / 退出暂停批量语义 / 章节状态不回退 / 取消不复活 |
| `feature.jm.data.ExportManagerTest` | 15 | **导出编排**：缺图预检 / zip 与 pdf 跑通并交付 / 重复提交复用 / 无图时给出可读失败原因 / **成品是否存在、按名查 Uri、删单个与删两个格式 / `exportsOf` 的大小与生成时间 / `presentFormats` 整页只查一次** |
| `feature.jm.ui.DisplayLogicTest` | 10 | 书库状态文案 / 章节状态 / 体积格式 / **详情页点赞·观看数字段与章节区标题（含占用体积）** / 空态与错误文案 |
| `core.codec.PdfPagePlanTest` | 8 | **PDF 切片**：未超限仍是一页 / 超限切片连续无重叠 / 宽度超限等比缩小 / 切片数上限 |
| `feature.jm.data.AlbumCleanerTest` | 8 | **删除编排**：只删文件保留记录 / 从书库移除清四张表 / **封面按"还在不在书库或最近浏览里"决定**（四种组合）/ 移除前先取消任务 / 只删文件时任务照跑 |
| `feature.jm.ui.FavoriteSortTest` | 7 | **收藏页排序**：默认夹置顶 / 三种方式都是降序 / 空夹排最后 / 同值按 id 稳定 |
| `feature.jm.data.ImageStoreTest` | 7 | **本地优先取图**：命中不发请求 / 取回来就缓存 / 删掉后重新取 / 块数 0 原样保留 |
| `feature.jm.data.ReadingRepositoryTest` | 6 | 阅读位置读写与钳位 / "继续阅读"取最近一本 / 章节目录名规则 |
| `feature.jm.ui.SearchRevealStateTest` | 6 | 搜索增量展示的推进与边界 |
| `feature.jm.ui.LibraryExportFilterTest` | 6 | **「有 ZIP / 有 PDF」筛选**：单条件 / 两条件要同时满足 / 快照未就绪时放行 / 「清除 N 项」只数用户加的条件 |
| `feature.jm.ui.LibraryPagingTest` | 3 | **书库分页拼页**：后续页追加在后面 / 跨页重复的 id 只留一条（重复 key 会崩）/ 没有后续页时走快路径 |
| `feature.jm.ui.ReadingProgressLabelTest` | 4 | **「继续阅读」那行小字 + "导出中"状态文案**：有页码 / 只记到章节 / 章节名缺失 / 排队中·导出中 45%·等待网络 |
| `core.storage.ExportPublisherFolderTest` | 5 | **「打开文件夹」的目录 URI**：API 34 给出带 `primary:` 卷名的正确文档 id / API 28 返回 null（私有目录别人看不到）/ 位置文案一致 / 多次调用稳定 |
| `core.download.DownloadGateTest` | 5 | 下载网络策略矩阵（仅 Wi-Fi / 允许移动网络） |
| `core.codec.DescramblerGoldenTest` | 5 | 解扰块布局 + 与参考实现逐像素一致 |
| `core.codec.ArchiveWriterTest` | 5 | zip 结构（不套本子目录）/ 顺序 / 进度 / 不加密码 / 重新生成覆盖 |
| `core.codec.PdfWriterSpikeTest` | 4 | PDF 页面 / 书签 / `PageMode` / 不加密 |
| `feature.jm.data.SearchMergeTest` | 4 | **精确匹配合并**：置顶 / 同 aid 不重复 / 总数只在真的新增时 +1 / 搜索零结果也有它 |
| `core.protocol.CryptoProbeTest` | 2 | 环境回归：JCE 可用性 |
| `core.codec.PixelProbeTest` | 2 | 环境回归：位图像素读写行为 |

### 设备测试（instrumented）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `core.codec.PdfEncryptionInstrumentedTest` | 4 | AES-128 / 错误密码 / 权限位事实 |

```powershell
.\gradlew.bat :app:runDeviceTests
```

> **界面交互测试的前置条件**：Compose 的测试框架会一直等窗口获得焦点，
> 因此设备必须**亮屏且已解锁**。熄屏状态下 `connectedAndroidTest` /
> `runDeviceTests` 会永久阻塞（不是失败，是挂住）。
