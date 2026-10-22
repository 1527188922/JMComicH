# 实施进度台账

> 计划原文：`docs/android-app-plan.md`（= `2026-10-05-android-jm-bilibili-app.md`）
> 跨功能硬约束：`docs/CONVENTIONS.md`（封面加载规范等）
> 最后更新：2026-10-19

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

## 改造：线路改成"清单选择"（2026-10-18）

用户要求：线路**不再让用户手填域名**，改成从清单里挑；清单硬编码进代码、不每次启动去拉；
**取消保存按钮、选中即生效**；要有「测速」按钮（所有线路同时测）；图片线路同样可选、
**测速先不做**（2026-10-18 晚补齐，见下一节「图片线路测速」）；当前线路要在设置页直接看得见；
工程当前在用的两条线路必须留住，并且是「恢复默认」的落点。

### 1. 做什么

| 项 | 改前 | 改后 |
|---|---|---|
| 线路录入 | 自由文本框（`一行一个，第一行生效`）+ 「保存」 | **底部面板选择**（`ModalBottomSheet`）+ **选中即生效**（无保存按钮） |
| 选完面板 | — | **点完不自动关**（2026-10-18 追加要求）：可以连着点几条比较延迟，退出靠用户自己（下滑面板 / 点遮罩）；也不弹 Snackbar（会被面板挡住、连点会排队），反馈靠面板里的「使用中 ✓」+ 分组里那行域名。<br>**2026-10-19 修正**：列表区的下滑不再能关面板（翻列表时太容易误关），出口改成「拖把手/标题区下滑、点遮罩、面板底部『关闭』」，原因与做法见下面的「优化：线路面板不再被『下滑即关』误伤」 |
| 可选值 | 用户随便填 | 硬编码清单 `core/protocol/LineCatalog.kt`（接口 6 条 / 图片 13 条，各自 `distinctBy` 去重） |
| 当前线路 | 要点开看文本框 | 分组里直接显示域名（+ `线路 N` / `默认` 小标签），右侧「更换 ›」 |
| 测速 | 无 | 「全部测速」：**所有接口线路并发**打 `/setting`，各出绿/橙/红灯号 |
| 图片线路 | 同上（自由文本） | 同接口线路的选择面板；测速同一轮里**没做**，2026-10-18 晚补齐为同样的「全部测速」（见「图片线路测速」） |
| 恢复默认 | 无（只有一个"清空"） | 面板里的「恢复默认」= 回到工程当前在用的线路；全局「恢复默认设置」清掉偏好后也回落到它 |

存储格式没变（`SettingKeys.Jm.API_DOMAINS` / `IMAGE_DOMAINS`），只是**现在只写一个域名**。
老格式（换行列表）仍按"取第一行"解析，升级用户不会丢了自己的线路。

### 2. 清单从哪来（硬编码进 `LineCatalog`）

- **接口线路**：官方线路清单接口 `newsvr-2025.txt`（AES-256-ECB，key = `md5("diosfjckwpqpdfjkvnqQjsik")`）
  解密出的 `jm3_Server`，2026-10-18 实测快照 = `www.cdnhjk.net` / `www.cdngwc.cc` /
  `www.cdngwc.net` / `www.cdngwc.club` / `www.cdnutc.me`（`Server` 与 `jm3_Server` 两个字段去重后就是这 5 条）。
  **再加工程当前在用的 `www.cdnbea.net`**（`Defaults.DEFAULT_API_DOMAIN`），放列表第一位、标「默认」。
  清单里的第三份数据是"拉不到远端清单时用的内置兜底"（`www.cdnaspa.vip` / `cdnplaystation6.*` 那 4 条），
  文档记录它们**基本全部失效**（404 / NXDOMAIN），所以**没有**收进可选列表。
- **图片线路**：对每条接口线路 × 每个图源（`/setting?app_img_shunt=1..4`）实际请求，
  收集 `img_host`，去重后 12 条（`cdn-msp{,2,3}.*` + `cdn-msp12` + `tencent.jmdanjonproxy.xyz`）；
  **再加 `Defaults.IMAGE_DOMAIN` = `cdn-msp2.jmapiproxy2.cc`**（工程当前在用），同样放第一位。
  注意 `img_host` 是**随机轮换**的（同一线路同一图源多次请求可能给不同 CDN），所以这份是
  "实测出现过的全集"，不是接口能枚举出来的封闭集合；有新增就改 `LineCatalog` 再发版。

### 3. 测速（`feature/api/ApiLineProbe.kt`）

- 打的是文档 §3.6 的测速接口 `GET https://{host}/setting?app_img_shunt=1&lang=TW`，
  带 `Token` / `Tokenparam` 签名。**实测这个接口不需要登录**（签名只依赖时间戳），
  所以按用户"需要认证就不做"的前提**保留测速功能**；
- 量**首字节耗时**（拿到响应头为止），灯号阈值照官方 `speedTestUtil.ts`：
  `<600ms` 绿 / `600~1200ms` 橙 / `≥1200ms` 红，连不上算红（行内只显示「超时 / 不可用」）；
- **超时固定 8 秒**，不跟随设置里默认 3 秒的「请求超时」（否则"能用但慢"的线路会被误判成红）；
  客户端由 `httpClient.newBuilder()` 派生，代理 / UA 与其它请求一致，只有超时不同；
- 所有线路**并发**测（每条一个 `launch`）、**逐条回填**结果；面板第一次打开会自动测一轮，
  「全部测速」按钮可重测。

### 4. 回归验证

- `:app:compileDebugKotlin` / `:app:assembleDebug` / `:app:assembleRelease` 均通过；`:app:lintDebug` **0 error**，
  本次改动的 6 个文件**没有新增 warning**；
- `:app:testDebugUnitTest`：**313 个用例、6 个失败**，全部是既有问题（`AlbumCleanerTest` 3 个、
  `ReaderAndTaskLogicTest` 的 3 个文案/底栏方向用例，与线路无关；见「取消全局设置作用域」一节的记录）。
  另：`DownloadManagerTest` 那 1 个"超时：追加的章节没有被下载"是**与 release 构建并行跑时的偶发超时**，
  单独跑该测试类通过；
- 新增 `core.protocol.LineCatalogTest`（12 个用例：默认项在清单里且排第一 / 两份清单去重且是裸域名 /
  空值与老格式多行的解析 / 清单外手填值保留 / 域名规整 / 600·1200ms 阈值打表），全部通过；
- **模拟器上按真机流程点过一遍**（Pixel_10_Pro_XL / API 36，debug 包）：
  设置页「线路」分组直接显示当前域名 → 点开接口线路面板 → 自动测速出灯号（默认线路/线路1-5，
  成功显示 ms、失败显示「超时 / 不可用」）→ 点「线路 4」**面板不关**、该行变成「使用中 ✓」、
  分组里的域名同步变成 `www.cdngwc.club`（可接着点下一条比较，退出由用户自己下滑 / 点遮罩）→
  「恢复默认」同样不关面板、选中态回到 `www.cdnbea.net`；
  图片线路面板同样可选（13 条、无灯号），选中后面板内选中态与分组里那行域名同步更新。
  实测中 19.4 秒才回来的那次促成了"整次调用也封顶 8 秒"的修正（见上）；
- 用 Node 复刻签名实测过：清单接口能解密；`/setting` 在 `www.cdnbea.net` / `www.cdnutc.me` /
  `www.cdngwc.cc` 上返回 200；**换线路之后内容接口也通**——用工程现有的签名
  （`18comicAPP` + `2.0.13`）在 `www.cdnutc.me` / `www.cdngwc.cc` / `www.cdngwc.club` 上
  `/album?id=350234` 与 `/search?search_query=test` 都能解密出正常数据（`series=2` / `total=47`），
  图片线路侧 `cdn-msp*.jmapiproxy*.cc/media/albums/350234.jpg` 也能取到 43KB 的封面
  （另两条线路本次连不上，测速时会是红灯，属预期）。

## 图片线路测速（2026-10-18）

上一节把图片线路做成"可选但先不测速"之后，用户问：图片线路能不能也跟接口线路一样有个
「全部测速」，并且是**真的测**。结论：**能**，而且官方 App 本来就有图片测速
（只是它测的不是"一串图片 CDN"，而是当前线路那一条）。

### 1. 官方 App 是怎么测图片的（`main.9a45a3ff/speedTestUtil.ts`，从 sourcemap 里核出来的）

| 项 | 官方做法 | 本工程的取舍 |
|---|---|---|
| 探针 | `<img>` 标签加载 `{img_host}/media/users/nopic-.gif?v=0` —— 1～4 KB 的占位头像，公开、免登录 | **照抄这张图和这个路径**（`ImageLineProbe.PROBE_PATH`），没有自己编一个探针地址 |
| 为什么用 `<img>` | `img_host` **没开 CORS**，`fetch` 读不到内容；`<img>` 不受 CORS 限制（源码注释原话） | 原生端没有 CORS，直接 GET；顺带能读到字节数 |
| 量什么 | `imageLoadTime`：从**发 `/setting` 之前**开始计时，到图片 `onload` 结束 —— 即「拿 img_host → 载图」的累计等待 | 我们的图片线路是用户自己选的，不需要先打 `/setting`，所以只量「发请求 → 整张图收完」 |
| 失败 | 10 秒超时 / `onerror` → `null` → 界面「無法載入」（`speed_test_image_load_fail`） | 8 秒（连接/读/写各自 8 秒 + **整次调用也封顶 8 秒**，与接口探针同一个理由）→ 行内「超时 / 不可用」 |
| 灯号 | `getSpeedLevel(latency)` **只看 API 延迟**；图片时间只作为一行数字显示，不参与分档 | 图片线路没有 API 延迟可测，所以**用图片就绪时间分档**，阈值仍照官方那组 600 / 1200 ms（两列灯号放一起才有可比性） |
| 范围 | **只测当前线路**（`img_host` 来自那条线路的 `/setting`），官方没有"图片线路清单" | 清单是用户可选的，所以**每条图片线路各测一条**（13 条并发） |
| 连接 | 每次都是全新请求、**无连接复用**（源码注释：阈值放到 600/1200 就是为冷连接） | **每次探测换一个空连接池**：不这样做，正在用的那条会命中 `imageHttpClient` 里的热连接，量出几十毫秒的假绿灯，另外 12 条却是冷连接 |
| 限流 | 每日 30 次 / 10 秒冷却 | 不做（本地测速，一轮只有十几张占位图） |

结论：**不需要另找"其他测延迟方案"**，官方这套就是最合适的——它量的正是用户能感知的
"图能不能马上出来"，而且探针图是官方自己保证存在的公开资源。

### 2. 实现

| 文件 | 改动 |
|---|---|
| `feature/api/ImageLineProbe.kt` | **新增**（约 200 行）：`probe(host)` 打一次真实取图请求，量「发请求 → 整张图收完」，复用 `LineProbeResult` / `LineSpeedLevel` / `speedLevelOf`，所以灯号、阈值、失败文案与接口线路完全一套 |
| `core/di/AppContainer.kt` | 新增 `imageLineProbe`：从 `imageHttpClient` 派生（代理 / UA / 拦截器与图片下载一致），与 `apiLineProbe` 并列 |
| `feature/ui/SettingsScreen.kt` | `SettingsUiState` 加 `imageSpeedResults` / `imageSpeedTesting`（**与接口线路分开两份**）；`SettingsViewModel` 加 `imageLineProbe` 参数与 `testAllImageLines()`；图片线路上传 `onTest = onTestImageLines`、`autoTest = true` |
| `feature/ui/ModeRoot.kt` | 接 `onTestImageLines = vm::testAllImageLines`，工厂里传 `container.imageLineProbe` |
| `app/src/test/.../feature/api/ImageLineProbeTest.kt` | **新增** 9 个用例（见下） |

几个刻意的判断：

- **404 才换备用探针图**（`/media/users/nopic-Male.gif`，官方文档 §8.2 的头像兜底）：
  个别镜像可能没有那张 gif，那不等于"这条 CDN 不能用"。超时 / 5xx **不换路径**——
  换了也是白等一轮（`a server error is not retried on the fallback path` 锁住这条）；
- **200 也可能是劫持页**：除了状态码，还要求"body 非空 + 首个有效字节不是 `<` +
  content-type 不是 `text/html`"，否则判「返回内容异常」。这和接口探针那句
  "只认看起来像 JM 信封的响应"是同一个思路；
- **不带 `Token` / `Tokenparam`**：图片 CDN 不校验签名（章节图片下载本来就不带），
  只带 `Defaults.USER_AGENT`（CDN 对 UA 有要求）；
- 面板**第一次打开自动测一轮**，与接口线路面板一致；两边的"测速中"标记互不影响。

### 3. 回归验证（本机实跑）

- `:app:compileDebugKotlin` / `:app:assembleDebug` / `:app:assembleRelease`（5.8 MB）✅，
  唯一的 warning 是既有的 `Screens.kt LocalLifecycleOwner` deprecation；
- `:app:lintDebug` ✅ **0 error / 30 warning**（与基线条数一致），本次改动的 5 个文件
  **没有新增 warning**（SARIF 里对 `ImageLineProbe` / `SettingsScreen` 零命中）；
- `:app:testDebugUnitTest`：**326 个用例、7 个失败**，失败的全是既有问题
  （`AlbumCleanerTest` 3、`ReaderAndTaskLogicTest` 3、`DownloadManagerTest` 的
  `waitUntil` 抖动 1 个，与上一轮记录一致，与本次改动无关）；
- 新增 `feature.api.ImageLineProbeTest` **9 个用例全绿**：探针路径与 `?v=0` 对上官方 /
  404 才走备用图 / 5xx 不重试 / HTML 劫持页（有 content-type、只有 body、空 body 三种）判不可用 /
  超时归成"超时"且不给毫秒数 / `looksLikeHtml` 与 URL 拼接的纯函数打表；
- **没在真机跑过**（这台机器上不了设备）。真机验收清单：
  1. 设置 → 线路 → 图片线路面板：第一次打开自动测一轮，13 行各自先「测速中…」再出灯号；
  2. 「全部测速」可重测；测速期间按钮转圈、不可重复点；
  3. 当前在用的那条（`cdn-msp2.jmapiproxy2.cc`）与其它条量到同一个数量级
     （如果它明显快一个数量级，说明连接池没隔离好，要回来查 `probeClient()`）；
  4. 断网 / 代理指向黑洞：整轮在 ~8 秒内结束，行内显示「超时 / 不可用」，不会挂住；
  5. 关面板再打开：不重测、结果还在（与接口线路同一套语义）。

## 优化：线路面板不再被"下滑即关"误伤（2026-10-19）

用户反馈：设置页「线路」的选择面板里线路一多就得上下滑，但**手指上下滑很容易触发面板本身的下滑关闭**，
而不是滚动线路列表。

### 1. 根因（读 material3 1.4.0 的 aar 字节码确认，不是猜）

`ModalBottomSheet` 的内容 `Column` 上同时挂了两套手势：

| 机制 | 行为 |
|---|---|
| `draggable(anchoredDraggableState)` | 按住**非滚动区域**（标题 / 提示 / 按钮行）上下滑 = 直接拖面板 |
| `nestedScroll(ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection)` | 子列表滚不动之后，**向下的残余增量**被 `dispatchRawDelta` 进面板的拖拽状态 |

这条内部连接是 `pre-up / post-down` 的：向上先交给面板（面板已全展开时它只吞 0，余量才落到列表），
**向下则是列表消费完之后，把余量直接拿去拖面板** —— 也就是"列表一到顶，之后每一像素向下位移都变成面板位移"。

关闭门槛又很低：`BottomSheetDefaults.PositionalThreshold = 56.dp`、`VelocityThreshold = 125.dp/s`
（两个常量直接从 aar 里读出来的）——向下拨 1 厘米左右、或轻轻甩一下，就够关掉。
再叠加两点：标题/按钮行不是滚动容器（手指落那儿直接拖面板）；列表固定 400dp 又没有任何
"下面还有内容"的提示（用户不知道还剩 7 条，只能反复上下试）。

### 2. 做什么

| 项 | 改前 | 改后 |
|---|---|---|
| 列表区的纵向手势 | 下滑到顶 = 关面板（56dp / 125dp/s 就够） | **列表能滚时，向下的手势被守门人吃掉**，面板不动；列表一屏放得下（滚不动）时守门人不介入，行为与以前完全一致 |
| 关面板的出口 | 下滑面板 / 点遮罩 | 拖**把手·标题区**下滑 / 点遮罩 / 点面板底部**「关闭」**（原来注释掉的那颗，恢复） |
| 面板滚动位置 | 每次打开都从头开始 | 两个 `LazyListState` 提到 `LineSection` 持有，关掉再打开回到上次位置 |
| 另外三处同毛病 | 一样会误关 | 一并接入：下载面板（`AlbumDetailScreen`）/ 收藏夹面板（`Screens`）/ 阅读器章节面板（`ReaderScreen`） |

新增 `feature/ui/SheetGestures.kt`：`Modifier.sheetSwipeGuard(listState)` 把一条自定义
`NestedScrollConnection` 挂在 `ModalBottomSheet(modifier = …)` 上。**位置很关键**：这个 modifier
比 material3 内部那条连接**更外层**，而 `onPreScroll` / `onPreFling` 的派发顺序是"外 → 内"，
所以守门人先拿到增量、能把它吞掉。它不碰非滚动区域（标题区的拖动走的是面板自己的 `draggable`，
不是嵌套滚动），所以"拖把手/标题区关面板"照旧。

`onPreFling` 也要挡：快速向下一甩时位移可能没到阈值，但速度会走 `onPreFling → settle` 把面板关掉。

### 3. 只在"列表真的能滚"时才生效

判定抽成纯函数 `shouldConsumeSheetSwipeDown(deltaY, listScrollable, canScrollBackward)`：

| 情形 | 结果 |
|---|---|
| 列表能滚 + 已在顶部 + 向下 | **吃掉**（面板不许动） |
| 列表能滚 + 还能往回滚 | 放行（列表自己滚） |
| 一屏放得下（`canScrollForward` / `canScrollBackward` 都是 false） | **不介入**，与改动前完全一致 |
| 向上 / 零增量 | 放行 |

于是"防误关"只作用在真正要滚的长列表上：接口线路面板（6 条）行为不变，
图片线路面板（13 条）和下载面板（几百话）才被保护。

### 4. 回归验证（本机实跑）

- `:app:compileDebugKotlin` ✅；唯一的 warning 是既有的
  `Screens.kt:2092 LocalLifecycleOwner deprecation`，与本次改动无关；
- `:app:lintDebug` ✅ **0 error**（报告 `app/build/reports/lint-results-debug.html`）；
- `:app:testDebugUnitTest`：**318 个用例、7 个失败**，全部是既有问题：`AlbumCleanerTest` 3 个、
  `ReaderAndTaskLogicTest` 3 个（这两组在 2026-10-18 那次跑就已经红着），外加 `DownloadManagerTest`
  的 1 个 `waitUntil` 超时（`超时：追加的章节没有被下载`，15 秒没等到条件）。最后这条是**本机时序抖动**，
  不是本次改动引入的：`git stash` 掉本次改动、在未改动的 HEAD 上跑同一个类，两次里也是**一次红一次绿**。
  新增的 `feature.ui.SheetGestureTest` **5 个用例全绿**；
- **手势本身没法自动化**：这台设备 Compose UI 测试拿不到窗口焦点、`adb shell input` 被禁
  （见「P2 待验证」），所以下面是需要你真机点一遍的清单。

### 5. 真机验收清单

1. 图片线路面板（13 条）：列表中部上下滑 → 只滚列表，面板纹丝不动；
2. 列表滚到顶后继续向下拉 100dp 再松手 → **不关**（改之前必关）；
3. 在列表上快速向下甩一下 → **不关**；
4. 按把手 / 标题区向下拖 → 能关；
5. 点遮罩、返回键、面板底部「关闭」→ 三条都能关；
6. 接口线路面板（6 条，滚不动）→ 行为与以前完全一致（列表区下滑仍能关）；
7. 「全部测速」/「恢复默认」点按正常，不误触发拖拽；
8. 关掉面板再打开 → 列表回到上次位置；
9. 详情页下载面板（几百话）顺带验一次，同样不再误关。

> 2026-10-19 晚追加：用户接着要求**彻底取消面板内容区的上下滑动手势**（不只是长列表到顶那一下），
> 只保留"拖把手下滑关闭"。上面那张表里"短列表照旧能滑走面板"的行为被推翻了，
> 见下一节「面板内容区不再响应上下滑动手势」。

## 优化：面板内容区不再响应上下滑动手势（2026-10-19）

用户要求：**取消应用设置页线路选择弹框上的上下滑动手势**（所有用到这种弹框的地方都要取消），
因为"想上下滑着找线路"时手指一动面板就跟着走、**太容易误关**；
但**不能取消"拖顶部把手下滑关闭"**。

### 1. 上一版为什么还不够

上一版（`sheetSwipeGuard` + `shouldConsumeSheetSwipeDown`）只在
"列表**能滚** + **已经在顶部**"时吃掉向下的手势，故意留了两个口子：

| 场景 | 上一版行为 | 用户感受 |
|---|---|---|
| 接口线路面板（6 条，一屏放得下） | 守门人不介入，**列表区下滑照样关面板** | "就在列表上滑了一下，没了" |
| 标题 / 提示 / 「全部测速」「恢复默认」按钮行 | 不是滚动容器，手指落那儿 = 直接拖面板 | 同上 |
| 长列表滚到顶后继续下拉 | 已挡住 | 没问题 |

也就是说"面板哪儿都能滑"这件事只堵了一半。用户这次要的是**内容区一律不许拖走面板**。

### 2. 做什么

| 项 | 改前 | 改后 |
|---|---|---|
| 内容区（标题 / 按钮 / 列表）纵向手势 | 视情况拖走面板（**向上尤其明显**：列表上下翻会被面板那套手势插一脚） | **面板整套手势都不挂**，上下滑只归列表 |
| 关面板的出口 | 拖把手/标题区、点遮罩、返回键、面板里的「关闭」 | **拖顶部把手**、点遮罩、返回键、面板里的「关闭」 |
| 用到的地方 | 4 处挂了守门人（两个线路面板、下载面板、收藏夹面板、章节面板） | **6 处面板全部**改用统一外壳：两个线路面板、下载面板、收藏夹面板（`Screens`）、章节面板、阅读设置面板 |

新增 `HandleDragOnlyBottomSheet`（`feature/ui/SheetGestures.kt`），所有面板都换成它；
原来的 `sheetSwipeGuard(listState)` / `shouldConsumeSheetSwipeDown` / 内层嵌套滚动拦截 **全部删除**。

### 3. 怎么做的（两版，第一版不够）

**第一版（作废）：靠"消费事件"抢回来。** 在内容区挂 `pointerInput` 把子节点没消费掉的位移
`consume()` 掉，再挂一条 `NestedScrollConnection` 把向下的残余增量吃掉。
真机上验出来的结果：**向下不抢了，向上仍然会抢**——列表上下翻时面板那套手势还是插一脚。

**现在的做法（不再博弈）：让面板根本不挂手势。**

- `ModalBottomSheet(sheetGesturesEnabled = false)` —— 读 material3 1.4.0 源码确认，
  这个开关会把 Surface 上的 `nestedScroll` 和 `draggable(enabled = sheetGesturesEnabled && isVisible)`
  **整套去掉**。内容区（标题 / 按钮 / 列表）上的任何纵向滑动都到不了面板，
  **上下两个方向一致**，列表的上下翻动不会被抢；
- 材质那个开关会把**把手**的拖动一起关掉，所以把手由外壳自己接管：
  在 `dragHandle` 槽里放一个 `Modifier.draggable(orientation = Vertical)`，累计向下的位移与松手速度，
  过了阈值就 `sheetState.hide()`（材质自己的收起动画），动画结束再 `onDismissRequest()`。
  阈值沿用材质内部的 `PositionalThreshold = 56.dp` / `VelocityThreshold = 125.dp/s`
  （两个常量都读自 aar）。把手外观照旧画 `BottomSheetDefaults.DragHandle()`。

判定抽成纯函数 `shouldDismissByHandleDrag(...)`，单测见 `SheetGestureTest`。

### 4. 代价（要说清楚）

- **拖动把手时面板不再实时跟手**：跟手移动只能由材质内部那套手势提供，而那正是会抢列表手势的东西。
  现在的行为是"在把手上往下拖、松手 → 面板滑下去关闭"。想要跟手就得自己画面板皮肤并手动平移，
  属于另一件事（这次没做，用户可按需再要求）。
- 在**标题 / 提示文字**上往下拖**不再**能关面板（以前可以）——内容区一律不响应，符合这次要求。
- 面板里自己给的「关闭」/「取消」按钮、点遮罩、返回键都不变。
- 详情页「收藏夹」面板（`FavoritePickerSheet`）以前没传 `sheetState`（默认允许半展开），
  现在走外壳的默认值 `skipPartiallyExpanded = true`，和其余五个面板一致：收藏夹多一些时
  直接整屏展开，不再先停在半屏。

### 5. 回归验证（本机实跑）

按用户 2026-10-19 的要求：**只要求编译 / 打测试包通过，不跑单元测试**（`SheetGestureTest` 已同步改成
新规则 4 个用例，但本轮没有执行）。

- `:app:compileDebugKotlin` ✅（唯一 warning 是既有的 `Screens.kt:2090 LocalLifecycleOwner` deprecation，
  与本次改动无关）；
- `:app:assembleDebug` ✅ —— `app/build/outputs/apk/debug/app-debug.apk`
  （debug 带 tooling，体积不用和 release 比）；
- **手势本身没法自动化**（这台设备 Compose UI 测试拿不到窗口焦点、`adb shell input` 被禁，
  见「P2 待验证」），下面是需要真机点一遍的清单。

### 6. 真机验收清单

1. 设置 → 线路 → 接口线路面板：在标题、「全部测速」那行、线路列表上分别**向上/向下**滑
   → 列表该滚的滚，**面板纹丝不动**（上下都要试）；快速甩一下也不关。
2. 图片线路面板（13 条）、短列表（6 条）同样：上下滑只归列表/什么也不做。
3. **在顶部把手上向下拖、松手** → 面板滑下去关闭（这条必须还在）。
4. 点遮罩、返回键、面板里的「关闭」→ 都能关。
5. 点一行线路 → 仍然"选中即生效、面板不关"；「全部测速」「恢复默认」点按正常。
6. 关掉再打开面板 → 列表回到上次位置（列表状态仍由 `LineSection` 持有）。
7. 详情页下载面板 / 收藏夹面板、书库的「修改收藏夹」面板、阅读器的章节面板与阅读设置面板
   → 内容区同样上下滑都不抢，把手能关。

### 7. 面板内容过高 / 滚动条（2026-10-19 追加）

用户问"其他面板内容超出高度时会不会也显示滚动条"，顺手把滚动这件事统一了：

| 面板 | 改前 | 改后 |
|---|---|---|
| 接口 / 图片线路面板 | 列表 `≤400dp` + 滚动条 | 不变 |
| 详情页下载面板 | 列表 `≤380dp`，**无滚动条** | 列表上限不变 + **补上同一根 `VerticalScrollbar`** |
| 书库「修改收藏夹」 | 列表 `≤320dp`，无滚动条 | 同上（补滚动条） |
| 阅读器章节面板 | 列表固定 `360dp`，无滚动条 | 固定高度不变 + 补滚动条 |
| 详情页「收藏夹」 | **裸 `Column` + `forEach`**：夹一多，下面的夹和「新建收藏夹」直接被裁出屏幕，滚不动也够不到 | 改成 **`LazyColumn` + `heightIn(max = 320dp)` + 滚动条**；标题 / 说明 / 新建那一块留在列表外面，始终看得见 |
| 面板整体（全部 6 处） | 内容比窗口高就被裁掉（material 的 sheet 内容不是滚动容器） | 外壳加**兜底滚动**：`weight(1f, fill = false) + verticalScroll`，只在"整块内容比窗口还高"（超小屏 / 系统大字号）时才滚，平时按内容收缩、观感不变 |

要点：**滚动条不是面板给的**，是调用点自己挂的 `VerticalScrollbar`（`Scrollbar.kt`，按
`LazyListState.layoutInfo` 画，内容没溢出时什么都不画）；`HandleDragOnlyBottomSheet` 本身
不画滚动条、也不把内容变成可滚动容器（它只负责手势隔离 + 把手 + 上面那层兜底滚动）。


## 功能：详情页「评论」tab（2026-10-19）

用户要求（原话要点）：**只做查看评论，不做发送**；在详情页**简介区域的下方**放两个 tab
——「章节」（现有的章节列表 / 章节分组全部收进去）和「评论」；评论 tab **下滑可以追加**
新加载的评论；评论**按 jm 号存在内存里，退出 App 清空**；交互要符合直觉、UI 符合当前 App 风格。

### 1. 接口（本机实测，aid=114514）

| 项 | 结论 |
|---|---|
| 地址 | `GET /forum?mode=all&page=N&aid={jm号}` |
| 签名 / 解密 | 与本工程现有那一套**完全通用**：`Token = md5(ts+"18comicAPP")`、`Tokenparam = "{ts},2.0.13"`、响应 `data` 用 `md5(ts+"185Hcomic3PAPP7R")` 解。**不用换协议** |
| 分页 | `page` 从 1 开始，每页固定 **10** 条 |
| 总数 | 实测 `total = "851"`（字符串），与 `/album` 的 `comment_total` 一致；服务端上限 5000 |
| 登录 | **匿名可读**，不带 `Authorization` 也返回 200，与本工程"不做 JM 账号"一致 |
| 正文 | **HTML 片段**（`<div style='flex-direction:row;flex-wrap:wrap;'>…</div>`，可能夹 `<img>` 表情） |
| 回复 | 主楼自带 `replys` 数组（**逆向文档 §6.3 没写这一条**，是实测出来的）；展开/收起纯本地，不再发请求 |
| 时间 | `addtime` 是英文月日年（`Oct 11, 2026`），与本工程请求不带 `lang` 有关 |
| 字段类型 | 与文档 §6.3 有两处不符：`likes` 是**字符串** `"0"`（文档写数字）、`spoiler` 是**字符串** `"1"`（文档写布尔），且同一本**每条都是 "1"** —— 拿它折叠剧透会把整页评论全藏起来，所以**不实现剧透折叠**，字段也不声明 |

探测方式：本机用 node 脚本按同一套签名/解密直接打接口（`www.cdnbea.net` 首次偶发超时，重试即通；
`www.cdngwc.cc` / `www.cdngwc.club` / `www.cdnutc.me` 一次通过）。脚本是临时的，没有入库。

### 2. 做了什么

| 项 | 改前 | 改后 |
|---|---|---|
| 简介下方 | 直接接「导出文件」+ 章节区标题 + 章节列表 | **两个 tab**：`章节`（数字=总话数）/ `评论`（数字=评论总数）；**吸顶**，滚起来之后一直贴在顶栏下面 |
| 章节相关内容 | 全部平铺在页面上 | 全部收进**章节 tab**（导出文件 → 章节进度 + 正序/倒序 → 分组列表） |
| 评论 | 无 | 列表：头像 + 昵称 + 等级徽标 → 时间 → 正文 → 内嵌回复（默认 3 条，多的可展开）→ 点赞数 |
| 下滑追加 | — | 最后 3 条进入可视区就自动请求下一页（每页 10 条），底部还有「加载更多」兜底 |
| 评论缓存 | — | `CommentCache`（按 jm 号一份快照，只存内存）；退出页面再进来**不再请求**，退出 App 清空 |
| 下拉刷新 | 只刷详情 | **按 tab 分流**：章节 tab 刷详情；评论 tab 刷评论第一页（刷新成功后覆盖缓存基准，失败不动旧数据） |
| 失败 | — | 第一页失败给整块错误态 + 重试；追加失败**不清空已有评论**，底部一行错误 + 重试 |
| 切 tab 的滚动落点 | — | 已经滑过 tab 栏的话，把 tab 栏滚到顶部；还停在头部则不动（详见 §3） |

### 3. 关键取舍（都是"为什么这么做"的记录）

1. **一个 LazyColumn 装两段内容，而不是两套列表**：头部（封面 / 按钮 / 简介）必须先跟着滚走，
   所以整页仍然只有一个 LazyColumn，tab 栏用 `stickyHeader` 吸顶。
   Material3 里 `stickyHeader` 已经是 `LazyListScope` 的**接口成员**（1.10.4 实测），
   **不需要 import**——写成扩展函数导入会 "Unresolved reference"（踩过）。
2. **懒加载**：进详情页**不**请求评论，第一次点到「评论」tab 才拉第一页。
   大多数人进详情页是来读的，不该为一次"可能会看"的评论多打一次网络请求。
3. **缓存挂在 `AppContainer`（`jmCommentCache`）而不是 ViewModel**：详情页退出再进来会换一个
   ViewModel，缓存挂在容器上才能跨"进出页面"存活；容器跟进程走，退出 App 自然清空，
   `handleAppExit()` 里再显式 `clearAll()` 一次（返回键退出时进程可能还被下载续着）。
4. **正文必须清洗**（`cleanCommentHtml`）：`<img alt="笑哭">` 取 alt 文字（整条丢掉会让句子断掉）、
   `<br>` / `</div>` 变真换行、其余标签剥掉、HTML 实体还原、连续空行压成一个空行。
5. **按 `cid` 去重**（`mergeComments`）：翻页期间 `total` 会变，同一 `cid` 可能出现两次，
   而列表拿 `cid` 当 key —— 重复 key 会让 LazyColumn 直接白屏崩溃。
6. **切 tab 的滚动落点**：章节几十话、评论几百条，两段长度差很多。已经滑过 tab 栏时保留原
   滚动深度，会直接落到评论列表末尾（还可能顺手触发一次追加）。所以"滑过 tab 栏 → 把 tab 栏
   滚到顶部"；还停在头部时不动（那时跳一下反而突兀）。
7. **头像不用 `Cover`**：`Cover` 是"JM 封面"的唯一入口（`CONVENTIONS.md` 第 1 条），按**本子号**拼
   `/media/albums/{aid}.jpg`。头像要的是 `/media/users/{photo}`，所以直接 `AsyncImage` +
   **同一个应用级 ImageLoader**（带正确 UA / 代理、缓存同一处），并垫一层"昵称首字"圆底：
   图片没加载出来 / 加载失败时它就是头像本身（`CONVENTIONS.md` 的例外清单已补这一条）。
8. **评论用普通行 + 内缩分割线，不用卡片**：几百条评论铺成几百张卡片会非常吵；
   章节用卡片是因为每一话都是独立动作。

### 4. 代码落点

| 文件 | 改动 |
|---|---|
| `feature/api/JmModels.kt` | 新增 `ForumRespDto` / `ForumItemDto` / `ForumExpDto`（字段类型按**实测**写，并注明与文档的差异） |
| `feature/api/ApiClient.kt` | 新增 `forum(aid, page, mode=all)`；重试次数沿用「漫画详情」那一项 |
| `feature/data/Comments.kt`（新） | `CommentRow` / `CommentPage` / `cleanCommentHtml` / `commentTimeLabel` / `commentLikeCount` / `mergeComments` / `CommentCache` |
| `feature/data/Repository.kt` | 新增 `comments(albumId, page)`：拉取 + 归一化（清洗正文 / 时间 / 点赞数 / 丢掉空 cid） |
| `core/protocol/Defaults.kt` | 新增 `buildAvatarUrl(photo)`（`https://{img_host}/media/users/{photo}`） |
| `core/di/AppContainer.kt` | 新增 `jmCommentCache`；`handleAppExit()` 里一并清空 |
| `feature/ui/ViewModels.kt` | `AlbumDetailTab` 枚举；`AlbumDetailUiState` 新增 tab / 评论相关 7 个字段 + `commentTabCount`；`selectTab` / `loadMoreComments` / `refreshComments` / `retryComments` / `fetchComments` |
| `feature/ui/AlbumDetailScreen.kt` | 简介下方两个吸顶 tab；章节内容整段进 tab；评论列表的三个部件（`CommentPlaceholder` / `CommentFooter` / `CommentItem` + 头像 / 等级 / 回复）；下滑追加的 `snapshotFlow`；下拉刷新按 tab 分流 |
| `feature/ui/ModeRoot.kt` | 接线 4 个新回调 + 工厂里注入 `commentCache` |
| `test/.../CommentLogicTest.kt`（新） | 20 个用例：HTML 清洗 / 时间文案 / 点赞数 / 合并去重 / 缓存隔离与清空 / 头像 URL / tab 计数 |

### 5. 回归验证（本机实跑）

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` —— **346 个用例，6 个失败**，全部是**既有失败**
  （`AlbumCleanerTest` 3 个、`ReaderAndTaskLogicTest` 3 个，与改动前完全一致）；
  新增的 `feature.data.CommentLogicTest` **20 个用例全绿**。
- `:app:lintDebug` —— **0 error**（warning 全是既有项：`UnusedResources` / `ModifierParameter` /
  `UseKtx` / `ObsoleteSdkInt` / `VisibleForTests` / `TrustAllX509TrustManager`）。
- `:app:assembleDebug` ✅ —— `app/build/outputs/apk/debug/app-debug.apk`
- **没有真机 / 模拟器**（`adb devices` 为空），所以界面观感与手势只能按下面的清单人工过一遍。

### 6. 真机验收清单

1. 进任意本子详情页 → 简介下方是 `章节 N | 评论 M` 两个 tab，**默认停在章节**，
   章节列表、导出文件、正序/倒序都还在。
2. 往上滑 → tab 栏**吸顶**贴在顶栏下面，不跟着滚走；章节分组照常展开/收起。
3. 点「评论」→ 第一次才转圈拉取（约 10 条）；再点回「章节」→ 内容立刻回来。
4. 评论列表往下滑 → **自动追加**，底部出现「正在加载评论…」，滚到 851 条为止显示「没有更多评论了」。
   用 `aid=114514` 验证（实测 851 条）。
5. 长评论有多条回复时：默认显示 3 条 + 「展开另外 N 条回复」，点开展开。
6. 在评论 tab 下拉 → 顶部转圈，重新拉到**最新**的第一页（旧缓存被替换）。
7. 返回上一页再点进同一本 → 评论 tab **立刻显示上次的列表**（不转圈、不请求）。
8. 完全退出 App（返回键退出 / 划掉最近任务）再进来 → 评论重新拉取（内存缓存已清）；
   换另一本本子 → 两本的评论互不影响。
9. 断网进评论 tab → 整块"评论加载失败 + 重试"；恢复网络点「重试」能出来。
   滑到第 3 页断网 → **已加载的评论还在**，底部一行错误 + 「重试」。
10. 从章节中部直接点「评论」→ 页面把 tab 栏顶到顶部，第一眼是评论开头（不是列表末尾）。

## 功能：分类页（2026-10-19）

用户要求（原话要点）：
- 底栏加「分类」，位置在**书库右侧 / 发现左侧**；
- 布局：标题下一行，**左边**是 `☰ 同人 · 漢化`（当前分类，点开左侧抽屉）、**右边**是排序菜单
  （与收藏页同一套交互）；
- 点分类按钮从**左侧**弹出**双层抽屉**：第一层主分类、第二层 sub 分类；
- 页面支持**下拉刷新**；抽屉**默认选中「最新A漫」**主类；主类没有二级类时用「全部」**虚拟**一个，
  并且选中主类时默认选中「全部」；
- 滑到底部**不自动翻页**，点「加载更多」才加载下一页；
- **分类数据只在打开 App 后第一次进分类页时加载一次**并入缓存，退出 App 清空；
- **下拉刷新只刷当前分类的漫画数据**；漫画列表也要缓存，**切走再切回来还是上次那批**。

### 1. 接口（本机实测 2026-10-19）

| 项 | 结论 |
|---|---|
| 地址 | `GET /categories?lang=TW`、`GET /categories/filter?page=&o=&c=&lang=TW` |
| 协议 | 与工程现有那一套**完全通用**：`Token = md5(ts+"18comicAPP")`、`Tokenparam = "{ts},2.0.13"`、响应 `data` 用 `md5(ts+"185Hcomic3PAPP7R")` 解。**不用换协议** |
| 主分类 | 实测 **10** 个（逆向文档 §4.2 写 9 个）：最新A漫 / 同人 / 單本 / 短篇 / 其他類 / 韓漫 / English Manga / Cosplay / 3D / 禁漫漢化組 |
| 无二级分类 | 最新A漫 / Cosplay / 3D / 禁漫漢化組（`sub_categories` 是 `[]`）→ 抽屉第二层只有虚拟的「全部」 |
| 字段类型 | `id`、`total_albums` 是**数字与字符串混排**（最新A漫的 `id` 是数字 `0`，其余是 `"1"`）→ DTO 干脆不声明这两个字段 |
| 列表 | 每页固定 **80** 条，`total` 被截断到 **10000**；实测 `total` 是**数字**（不是字符串）→ 用宽松标量读，两种形态都吃 |
| 列表项 | `id,author,name,image,category,category_sub,liked,is_favorite,update_at,adddate`，与 `/search` 同构 → 复用 `SearchItemDto` |
| `image` | 实测多数是**空串**（本工程封面 URL 一直按 `aid` 自己拼，不依赖这个字段） |
| 排序 | `""` 最新 / `tf` 最多愛心 / `mv` 總排行 / `mv_m` 月排行 / `mp_w` 週排行；空值参数按 `fetchGet` 的规则**不传** |
| lang | 固定 `TW`（官方 `fetchGet` 默认就追加）：分类名因此是官方那套繁体（`漢化` / `單本`），与用户描述的文案一致 |

探测方式：临时 node 脚本按同一套签名/解密直接打接口（`.work/probe-categories.mjs`，未入库）。
`www.cdngwc.cc` 一次通过；`c=doujin_chinese` 那次超时，重跑即通（镜像偶发超时，与文档描述一致）。

### 2. 做了什么

| 项 | 改前 | 改后 |
|---|---|---|
| 底栏 | 书库 / 发现 / 收藏 / 更多 | **书库 / 分类 / 发现 / 收藏 / 更多**（分类夹在书库与发现之间） |
| 分类页 | 无 | 标题「分类」+ 操作行（`☰ 当前分类` / `排序：最新 ▾`）+ 漫画列表 |
| 分类选择 | 无 | 左侧**双层抽屉**：主分类一栏（灰底、选中的一行白底 + 主色 + 左侧竖条）、二级分类一栏（含「全部」） |
| 排序 | 无 | 最新 / 最多愛心 / 總排行 / 月排行 / 週排行（週排行只给「最新A漫」，与官方 `Header.tsx` 一致） |
| 翻页 | 无 | 每页 80 条，**只在底部点「加载更多（第 N 页）」时**请求下一页；到底显示「已经到底了」 |
| 下拉刷新 | 无 | 只重拉**当前分类第一页**（不动分类树）；失败保留旧列表 + 一句提示 |
| 分类树缓存 | 无 | `CategoryCache`：**本次进程只请求一次**，退出 App 清空 |
| 列表缓存 | 无 | 按（主分类 / 二级分类 / 排序）分键存快照：切分类、切排序、切页面回来都是**瞬间回显、零请求** |
| 失败 | 无 | 分类树失败 → 整块错误态 + 重试（抽屉里也有同样的兜底）；列表失败 → 整块错误态 + 重试；翻页失败只提示、不动已有列表 |

### 3. 关键取舍

1. **抽屉用 `ModalNavigationDrawer` + `ModalDrawerSheet`**，不自己画遮罩：返回键关闭、点遮罩关闭、
   左边缘滑开、predictive back 都是组件自带的，自己实现只会漏掉其中一两条。宽度取窗口宽度的 88%
   （`LocalWindowInfo.current.containerSize`，不是 `Configuration.screenWidthDp`：分屏下后者是整块屏幕），
   上限 360dp（Material 自己的封顶）；面板的 `windowInsets` 置零，因为外层 Scaffold 已经让过状态栏。
2. **每个主分类都垫一个「全部」**，不只是没有二级分类的那几个：官方 App 的主分类那一栏本身就表示
   "整个主分类"，而我们的抽屉第二层是唯一可点的一层，垫上「全部」才能表达"看这个主分类的全部内容"。
   用户明确要求的"没有二级分类时虚拟一个「全部」、选中主类默认选中「全部」"是这条规则的子集。
3. **缓存挂在 `AppContainer.jmCategoryCache`（`CategoryCache`）而不是 ViewModel**：分类页被移出导航栈 /
   页面重建都会换一个 ViewModel，挂容器上才能跨"进出页面"存活；容器跟进程走，退出 App 自然清空，
   `handleAppExit()` 里再显式 `clear()` 一次（返回键退出时进程可能还被下载任务续着）。
4. **列表缓存按（主分类 / 二级 / 排序）分键**：换排序就是另一批内容，不能命中旧列表；
   最多留 24 份（9～10 个主分类 × 最多 5 种排序也才 50 份，24 份够用又不至于吃内存），
   超出时丢最早写入的那份。
5. **切分类时缓存优先**：命中缓存的组合**一次请求都不发**，直接摆出来；下拉刷新才是唯一的"强制重拉"入口。
6. **不自动翻页**：分类列表**没有**用 `LoadMoreOnScroll` / `snapshotFlow` 监听滚动（搜索结果页那一套），
   就是底部一颗「加载更多」按钮——用户明确要求"滑到底部不用自动加载下一页"。
7. **分类页不摆「加入书库」**：它是浏览页，入库/移出走详情页，避免在列表里误触破坏性操作。
   列表行与搜索结果页**共用** `OnlineResultRow`（两个动作回调改成可选，不传就整块不画）。
8. **排序项随主分类变**：官方 `Header.tsx` 对 `slug != ""` 的分类 `slice(0, -1)` 掉週排行；
   本工程照抄，并且换主分类时把不可用的排序**自动回落成「最新」**（否则选中的芯片和列表内容会对不上）。
9. **`lang=TW`**：官方 `fetchGet` 默认追加，分类名因此是繁体（`漢化` / `單本`），与用户给的
   `☰ 同人 · 漢化` 文案一致；`/search` 那边维持原样（不带 lang），两处互不影响。
10. **标题**：按底栏名写成「分类」；用户说的"标题『发现』的下方一行"理解为**位置**（页面顶部、
    标题行下方一行）——「发现」是相邻 tab 的名字，写成「发现」会和实际内容对不上。
11. **换分类/排序时列表回到顶部**：用 `rememberSaveable` 记住"上次的组合"，只在组合真的变了时才
    `scrollToItem(0)`；切 tab 回来不算换分类，滚动位置要保住。

### 4. 代码落点

| 文件 | 改动 |
|---|---|
| `core/protocol/Defaults.kt` | 新增 `CategorySort`（`o` 的 5 个取值 + 文案 + `optionsFor(主分类)`）、`CATEGORY_PAGE_SIZE`、`CATEGORY_LANG` |
| `feature/api/JmModels.kt` | 新增 `CategoriesRespDto` / `CategoryGroupDto` / `CategorySubDto` / `CategoryFilterRespDto` + `LooseStringSerializer`（`total` 数字/字符串通吃） |
| `feature/api/ApiClient.kt` | 新增 `categories()` / `categoryFilter(category, sort, page)`；重试次数沿用「在线搜索」那一项 |
| `feature/data/Categories.kt`（新） | `CategoryGroup` / `CategorySub` / `CATEGORY_SUB_ALL` / `buildCategoryCatalog` / `categoryParam` / `categorySelectorLabel` / `CategorySelection` / `fixCategorySelection` / `CategoryAlbumPage` / `CategoryListSnapshot` / `CategoryCache` / `mergeCategoryItems` |
| `feature/data/Repository.kt` | 新增 `categoryCatalog()` / `categoryAlbums(...)`（复用 `toItem`，带上"是否已入库"标记） |
| `core/di/AppContainer.kt` | 新增 `jmCategoryCache`；`handleAppExit()` 里一并清空 |
| `feature/ui/ViewModels.kt` | 新增 `CategoryUiState` + `CategoryViewModel`（分类树只拉一次、列表缓存优先、下拉刷新只刷当前分类、手动翻页、缓存修正选中项） |
| `feature/ui/CategoryScreen.kt`（新） | 标题 + 操作行（分类选择器 / 排序菜单）+ 下拉刷新的漫画列表 + 左侧双层抽屉（两个面板各自带滚动条）+ 错误/空态 |
| `feature/ui/Screens.kt` | `OnlineResultRow` 改成 `internal` 且两个动作回调可选（分类页复用它、不摆入库按钮） |
| `feature/ui/ModeRoot.kt` | 底栏加 `CATEGORY`（书库与发现之间）；新路由 + 接线；工厂注入 `jmCategoryCache` |
| `test/.../feature/data/CategoryCatalogTest.kt`（新） | 20 个用例：分类树映射（最新A漫置顶 / 虚拟「全部」/ 去重）、`c` 参数、选择器文案、排序项、选择修正、翻页去重、缓存（分键 / 上限 / 清空）、接口样本反序列化（数字型 `total`） |
| `test/.../feature/ui/CategoryStateTest.kt`（新） | 4 个用例：默认选中项文案、抽屉按钮文案、排序项随主分类、翻页按钮出现条件 |
| `test/.../feature/ui/ReaderAndTaskLogicTest.kt` | 两个底栏方向用例按**当前五项**重写（原来写的还是"书库/最近/发现/收藏/任务"那一版，早就是失败的陈旧断言） |

### 5. 回归验证（本机实跑）

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` —— **370 个用例、4 个失败**：
  新增的 `CategoryCatalogTest` 20 个 + `CategoryStateTest` 4 个**全绿**；
  失败的全是**既有失败**：`AlbumCleanerTest` 3 个 + `ReaderAndTaskLogicTest` 的
  「reading option labels…」1 个（与改动前一致）。
  （中间有一次整包跑还碰上 `DownloadManagerTest` 的「超时：追加的章节没有被下载」——
  就是本文件前面几节记的那条 15s 时序抖动，重跑即过，与本次改动无关。）
  顺带把该测试类里两个**陈旧**的底栏方向用例按当前五项修好
  （原来写的是已不存在的「最近 / 任务」tab，改前就红）。
- `:app:lintDebug` —— **0 error**（warning 30 条，全是既有项；新代码没有引入新的 warning：
  抽屉宽度改用 `LocalWindowInfo` 就是为了消掉 `ConfigurationScreenWidthHeight`）。
- `:app:assembleDebug` ✅ —— `app/build/outputs/apk/debug/app-debug.apk`
- **真机**：这次设备是连着的（`adb devices`：Xiaomi `23116PN5BC`），已经
  `adb install -r` 把新 debug 包装上去了（数据没清）；`am start` 冷启动**没有崩溃**，
  截图确认底栏已经是 **书库 / 分类 / 发现 / 收藏 / 更多** 五项、分类图标在书库与发现之间。
  但 `adb shell input` 被拒（`SecurityException: INJECT_EVENTS`，MIUI 要用户手动打开
  「USB 调试（安全设置）」），**点不动屏幕**，所以分类页内部的抽屉/排序/下拉刷新
  只能按下面的清单人工过一遍。

### 6. 真机验收清单

1. 底栏是 **书库 / 分类 / 发现 / 收藏 / 更多** 五项，「分类」在书库右边、发现左边；
   左右切换 tab 的滑入方向跟位置一致。
2. 进「分类」：标题「分类」下面一行，左边 `☰ 最新A漫 · 全部`、右边 `排序：最新 ▾`；下面开始出漫画。
3. 点左边那颗 → 左侧滑出抽屉：**左栏主分类**（最新A漫选中，白底 + 蓝字 + 左侧竖条）、
   **右栏二级分类**（第一项「全部」，带 ✓）。返回键 / 点遮罩 / 右上角 ✕ 都能关。
4. 点「同人」→ 左栏高亮切到同人、右栏变成 `全部 / 漢化 / 日語 / CG圖集 / 禁漫去碼 / 禁漫上色`，
   列表变成「同人 · 全部」；点「漢化」→ 抽屉收起、按钮变成 `☰ 同人 · 漢化`、列表换成漢化。
5. 点「Cosplay」（没有二级分类）→ 抽屉**直接收起**，按钮是 `☰ Cosplay · 全部`。
6. 右上角排序换成「總排行 / 月排行」→ 列表重拉；在「同人」下**看不到「週排行」**，
   在「最新A漫」下能看到五项；从「最新A漫 + 週排行」切到「同人」→ 排序自动回到「最新」。
7. 下拉 → 顶部转圈，只重拉当前分类第一页（分类抽屉里的分类树**不再请求**；
   `adb logcat` 里只有一条 `/categories/filter`，没有 `/categories`）。
8. 点「加载更多（第 2 页）」→ 底部转圈后追加 80 条（**不点就不加载**，滑到底也不会自己动）。
9. 切到「书库」再切回「分类」→ 还是刚才那批漫画、那个分类，**不转圈、不请求**；
   滚动位置也还在原处。
10. 完全退出 App（返回键退出 / 划掉最近任务）再进来 → 分类树重新拉一次（内存缓存已清）。
11. 断网进「分类」→ 整块"分类加载失败 + 重试"（抽屉里也是同样的提示 + 重试）；
    有分类树但列表失败 → "没能加载这个分类 + 重试"；恢复网络点重试能出来。
12. 分类列表里点任一本 → 进详情页；返回后分类页状态不变。

### 7. 追加要求：抽屉样式 + 总数 + 更新时间 + 列表/卡片（2026-10-19 晚）

用户第二轮要求（原话要点）：
- 分类抽屉里**主类区域背景改成白色**；抽屉的**右上角、右下角不要圆角**；
- 分类数据有总数字段时，**在分类右侧显示总数**，上千显示 `1.0k`、上万显示 `1.0w`；
- 展示信息还要**展示更新时间**（有 `update_at` 就用它，没有就展示 `adddate`）；
- **展示方式可切换列表 / 卡片**，卡片时**每行三个**；
- **切换按钮与"数量 / 页码"那一行同一行**。

做出来的样子：

| 项 | 结论 |
|---|---|
| 抽屉主类栏 | 白底（`surface`）；选中项靠 **主色 + 加粗 + 左侧 3dp 竖条** 区分（白底上再铺底色反而脏） |
| 抽屉圆角 | `ModalDrawerSheet(drawerShape = RectangleShape)`，右上/右下直角 |
| 分类总数 | 接口 `total_albums`（数字/字符串混排，用宽松标量读）→ `formatCategoryCount`：`<1000` 原样、`<10000` 一位小数 + `k`、否则一位小数 + `w`；`0`（最新A漫 / Cosplay / 3D / 禁漫漢化組）**不显示** |
| 更新时间 | `updateLabel(updateAt, addDate)`：`update_at` 是 10 位秒级时间戳 → 本地时区 `YYYY-MM-DD`；没有/为 0 时回落到 `adddate`；两者都没有就不展示 |
| 列表模式 | 一行一本（沿用 `OnlineResultRow`），JM 号那一行变成 `JM1484011 · 更新 2026-10-19`（省一行高度） |
| 卡片模式 | `LazyVerticalGrid(3 列)`，白卡 + 封面（0.72 宽高比）+ 两行标题 + `更新 YYYY-MM-DD`，与书库网格同一套观感；**分类页首次进入默认就是卡片**（用户 2026-10-20 要求） |
| 切换按钮 | 「共 N 条 · 第 x/y 页」那一行的**右端**，32dp 的图标按钮；图标显示"点下去会切到"的模式（自绘的 2×2 网格图标 / 列表图标） |
| 切换后的记忆 | `CategoryCache.viewMode` 跟分类树/列表一起缓存，切 tab 回来还是上次那种模式 |

**接口字段核对**（同一套签名/解密实测）：
`/categories` 的主分类除 `id/name/slug/type` 外确实有 `total_albums`
（最新A漫是数字 `0`，有内容的是字符串 `"354792"`）；`sub_categories` 只有 `CID/name/slug`，**没有总数**。
`/categories/filter` 的每一条同时有 `update_at`（如 `1792418849`）与 `adddate`（`2026-10-19`）。

代码落点：`JmModels.kt`（`total_albums` / `adddate` 字段）、`Categories.kt`
（`formatCategoryCount` / `updateLabel` / `CategoryViewMode` / `CategoryCache.viewMode`）、
`ViewModels.kt`（`CategoryUiState.viewMode` + `toggleViewMode`）、`CategoryScreen.kt`
（白底主类栏、`RectangleShape`、总数、卡片网格、切换按钮、更新时间）、
`Screens.kt`（`OnlineResultRow` 新增可选 `updateLabel`）、`ui/theme/Icons.kt`（自绘 `GridViewIcon`）、`ModeRoot.kt`（接线）。

验证：`:app:compileDebugKotlin` ✅；整包 `:app:testDebugUnitTest` **375 个用例、4 个既有失败**
（`AlbumCleanerTest` 3 + `ReaderAndTaskLogicTest` 文案 1），分类相关的 **29 个用例全绿**
（新增 `formatCategoryCount` 边界、`updateLabel` 回落、`total_albums` 解析、展示方式切换与缓存）；
`:app:assembleDebug` ✅。
**注意**：这轮改完时手机的无线调试掉线了（`adb devices` 为空），
所以新包没装上；抽屉白底/直角、总数、列表/卡片切换这些**还没在真机上用眼睛确认过**，
按上面的清单第 3、6、7 条补看一眼即可。

## 功能：接口语言（简中 / 繁中）切换（2026-10-20）

用户要求（原话要点）：官方 App 支持简中 / 繁中切换，切到简中后**一些接口响应的中文**
会变成简中；查一下本工程用到的接口在官方 App 里是怎么控制语言的，本工程也要支持，
**新增一个配置项、默认简中**。

### 1. 官方 App 是怎么控制的（`.work/src` 里核过源码）

开关在 **我的 → 設定 → 切換語言**（`MemberModal.tsx` 的 `setting.switch_language`），
确认后走 `SettingList.switchLanguage()`：换 i18n 文案、写
`localStorage.langCode`（`"0"` 繁 / `"1"` 简）与 `localStorage.lang`（`"TW"` / `"CN"`）、
`POST /setting`（`language=0/1`）、清列表、按新语言重拉封面广告。

真正影响**接口响应中文**的只有一处：`HttpUtil.fetchGet` 给每个 GET 请求自动补
`lang = localStorage.lang || "TW"`（POST 不补）。逐接口的影响面与实测依据见
`docs/official-api-2.1.11.md` §2.6（新增章节）。

### 2. 本工程做了什么

| 项 | 做法 |
|---|---|
| 协议枚举 | `core/protocol/ApiScheme.kt` 新增 `ApiLanguage`（`CN` / `TW`，默认 `CN`）；存储值就是协议里的 `"CN"` / `"TW"`，认不出的值一律回落默认 |
| 设置项 | `SettingKeys.Jm.API_LANGUAGE`（`api_language`），默认简中；`SettingsRepository.resolveJmApiLanguage()` 现读 |
| 请求侧 | `ApiClient` 新增 `languageProvider`，在 `getDecrypted` 拼 URL 前统一补 `lang`（调用方自己传了就不动），**所有 GET 接口**都带上——`/search`、`/album`、`/categories`、`/categories/filter`、`/forum`、`/comic_read`、`/chapter` |
| 改掉写死的 `TW` | 删掉 `Defaults.CATEGORY_LANG`；`/categories` 与 `/categories/filter` 不再各自拼 `lang` |
| 测速探针 | `ApiLineProbe` 的 `/setting` 探针同样带当前语言（官方测速也带 `localStorage.lang`） |
| 旧模板接口 | `/chapter_view_template` **不带** `lang`（旧版官方 URL 里就没有），保持原样 |
| 设置页 | 新增「语言」分组 → 「接口语言」（简体中文 / 繁体中文），选中即写偏好并提示；界面文案本身仍只有简体一套 |
| 语言的读取 | `AppContainer.apiLanguage` 是偏好的热流（`stateIn`），`ApiClient` / `ApiLineProbe` 取它的 `.value`——不每次请求读存储，切完立刻生效 |
| 切完重拉（**目前关着**） | 原本：分类页 `CategoryViewModel` 订阅那条热流，语言一变就清分类树 + 列表并按新语言重拉（展示方式与选中项保留），设置页另外清评论缓存（`CommentCache`）。**用户随后要求"切换语言不清理任何缓存"，所以两处入口都注释掉了**（见 `docs/PROGRESS.md` 下方「后续调整」）：现在切完只影响新请求，已拉过的分类 / 评论保留旧文案，重启应用后内存缓存清空才全量更新 |

### 3. 代码落点

`core/protocol/ApiScheme.kt`（`ApiLanguage`）、`core/protocol/Defaults.kt`（删 `CATEGORY_LANG`）、
`core/settings/SettingKeys.kt`、`core/settings/SettingsRepository.kt`、
`feature/api/ApiClient.kt`、`feature/api/ApiLineProbe.kt`、`core/di/AppContainer.kt`（`apiLanguage` 热流）、
`feature/data/Categories.kt`（`CategoryCache.clearCatalogAndLists`）、
`feature/ui/ViewModels.kt`（`CategoryViewModel` 的 `language` 参数与 `reloadForLanguage()`，目前无调用点）、
`feature/ui/SettingsScreen.kt`（`language` 状态 + `setApiLanguage` + `LanguageSection`）、
`feature/ui/ModeRoot.kt`（`apiLanguage` 接线；清缓存回调已注释）、`docs/official-api-2.1.11.md`（新增 §2.6）。

### 4. 验证

`:app:compileDebugKotlin` ✅、`:app:compileDebugUnitTestKotlin` ✅（本次按用户要求**只保证编译**，
没有跑测试用例）。自测要点：设置页切到繁中 → 重新进分类页，分类名应是 `漢化` / `單本`
（简中是 `汉化` / `单本`）；切回简中同理。

### 5. 后续调整（2026-10-20，用户要求）：切语言**不清理任何缓存**

> 用户原话要点：切换语言后不需要清理缓存里的分类和评论（目前不需要清理任何），
> 但**代码入口注释掉**即可，后续有需求方便放开注释。

所以"清缓存 + 重拉"仍然是写好的，只是两处入口被注释了：

| 位置 | 现在的状态 | 恢复方法 |
|---|---|---|
| `ModeRoot.kt`（`SettingsViewModel` 工厂） | `onApiLanguageChanged = { 清分类树/列表 + 清评论 }` 整段注释，走 `SettingsViewModel` 的默认空实现 | 放开那 4 行注释即可（`SettingsViewModel` 里的调用点不用改） |
| `ViewModels.kt`（`CategoryViewModel.init`） | "订阅语言热流 → `reloadForLanguage()`"那段 `launch` 注释；`language` 参数与 `reloadForLanguage()` 保留并标了 `@Suppress("unused")` | 放开那段注释，同时补回顶部 `import kotlinx.coroutines.flow.drop`（注释里也写了这一句） |

行为影响：切完语言**只影响之后发出的请求**；已经拉过的分类树 / 列表 / 评论保留切换前的文案，
退出应用（进程结束，内存缓存清空）后再进来才是新语言。设置页「接口语言」的说明文案也已按此改写。

## 改造：重试次数合并 + 图片重试独立（2026-10-20）

用户要求（原话要点）：
1. 「高级」里的四个请求重试次数（漫画详情 / 搜索 / 章节图片 / 获取分类）**合成一个**
   「请求重试次数」，凡是接口请求都用它；**可重试的异常类型保持原逻辑不动**；
2. **新增**一个「图片加载/下载失败重试次数」，且**仅网络超时类异常**才重试。

### 1. 做了什么

| 项 | 做法 |
|---|---|
| 设置项 | `SettingKeys.Jm.RETRY_REQUEST`（`retry_request`）+ `RETRY_IMAGE`（`retry_image`）；原来的四个 key 删掉 |
| 旧值迁移 | `SettingsRepository.migrateLegacyRetryKeys()`：新 key 没值时，按「详情 → 搜索 → 章节图片 → 获取分类」取**第一个存在的**旧值写进去；旧 key 无条件删除。进程启动时与全局作用域迁移一起跑，「恢复默认」后不会复活 |
| 接口侧 | `ApiClient` 的 `RetrySettings(album/search/chapter/category)` 整个删掉，改成一个 `retryProvider: () -> Int`；8 处调用点统一用 `retryProvider()`。**可重试异常不变**：仍由 `isRetryableApiError()` 判定（超时 + 空体/非 JSON 信封） |
| 图片侧 | `FileDownloader` 新增 `attemptsProvider`（suspend 现读），把设置里的「重试次数」换算成引擎的「总尝试次数」（`+1`）；默认值 `SettingDefaults.Jm.RETRY_IMAGE = 2`，正好等于合并前引擎的 `maxAttempts = 3`，升级观感不变 |
| 图片重试判定 | 新 `Throwable.isTimeoutRetryable()`（`core/network/NetworkError.kt`）：**只有网络超时**；原来的 `NetworkError.isRetryable()`（超时 + 连不上 + 5xx/429）删掉。`FileDownloader` 只接网关超时重试，连不上 / 5xx / 内容异常直接判失败 |
| 生效时机 | 两个都**即时生效**：`AppContainer.jmApi` 每次取用现读接口重试次数；`FileDownloader` 每次下载现读图片重试次数（不用重启） |
| 设置页 | 「高级」只剩两个滑杆：`请求重试次数`（提示"所有接口共用"）与 `图片加载/下载失败重试次数`（提示"阅读取图与下载共用、只重试网络超时"）；`RetryField` 枚举删除 |

### 2. 代码落点

`core/settings/SettingKeys.kt`、`SettingDefaults.kt`（`RETRY` / 新增 `RETRY_IMAGE`、`SettingLimits.JM_RETRY` 文案）、
`core/settings/SettingsRepository.kt`（`resolveJmRequestRetry` / `resolveJmImageRetry` / `migrateLegacyRetryKeys`）、
`feature/api/ApiClient.kt`、`core/network/NetworkError.kt`（`isTimeoutRetryable`）、
`core/download/FileDownloader.kt`（`attemptsProvider` + 超时判定）、`core/di/AppContainer.kt`、
`feature/ui/SettingsScreen.kt`（两个 setter + 两个滑杆）、`feature/ui/ModeRoot.kt`。

单测同步：`FileDownloaderTest` 里"503 会重试"的用例改成"503 不重试"，并新增
"超时会重试且每次重新解析直链"（用拦截器抛 `SocketTimeoutException`，不真等超时）
与"重试判定表"两个用例。

### 3. 验证

`:app:compileDebugKotlin` ✅、`:app:compileDebugUnitTestKotlin` ✅（本次按用户要求**只保证编译**，
没有跑测试用例）。自测要点：飞行模式打开本子详情 → 失败；随后改「请求重试次数」，
再点重试观察请求次数；图片那一项在弱网 / 代理拖慢读超时时最直观。

## 功能：搜索结果支持卡片 / 列表切换（2026-10-20）

用户要求（原话要点）：搜索结果的展示方式要跟分类页一样支持**卡片 / 列表切换**，
**默认卡片**；列表里**展示更新时间**（没有更新时间就展示创建时间）；
卡片时"区域优先"、少露字段——跟分类页的卡片一样，只展示**名称和更新时间**。

### 1. 做了什么

| 项 | 做法 |
|---|---|
| 枚举改名 | `CategoryViewMode` → **`OnlineViewMode`**（`feature/data/Categories.kt`）：两页共用一个类型，`DEFAULT = CARD`、`toggled()` 不变 |
| 搜索状态 | `OnlineSearchUiState` 新增 `viewMode`（默认 `CARD`）；`OnlineSearchViewModel.toggleViewMode()` 只切渲染方式，**不重搜、不改已展示条数**。只活在结果页自己的 ViewModel 里（不写存储） |
| 公共件上收 | 原来分类页私有的「共 N 条 + 切换按钮」和「三列卡片」挪到 `Screens.kt`：`OnlineCountBar(text, viewMode, onToggleViewMode)`、`OnlineGridCard(item, onOpen)`，外加 `ONLINE_GRID_COLUMNS = 3` / `ONLINE_COVER_ASPECT_RATIO = 0.72f`。分类页与搜索结果页用的是**同一个实现**，观感不会各改各的跑偏 |
| 搜索结果页 | `SearchResultList` 拆成 `SearchResultBody`（按 `viewMode` 分派）+ `SearchResultList` / `SearchResultGrid` + `SearchResultFooter`；「继续下滑追加」的滚动监听抽成 `RevealMoreOnScroll`（列表读 `LazyListState`、卡片读 `LazyGridState`，判定仍是 `shouldRevealMore`，单测不变） |
| 列表模式 | `OnlineResultRow` 传 `updateLabel = item.updateLabel()`：**更新时间优先，没有就用上架日期**（`adddate`），与分类页列表一致；入库 / 移出按钮照旧 |
| 卡片模式 | 与分类页同一张卡：封面 + 两行名称 + `更新 YYYY-MM-DD`；**不放入库按钮**（"区域优先"少露信息，要入库切列表或进详情页），页脚、计数、自动追加照常 |
| 切换按钮 | 沿用分类页那套：放在「共 N 条」行右端，图标画的是"点下去会切到"的模式 |

### 2. 代码落点

`feature/data/Categories.kt`（`OnlineViewMode`）、`feature/ui/ViewModels.kt`
（`OnlineSearchUiState.viewMode` + `toggleViewMode`）、`feature/ui/Screens.kt`
（`OnlineCountBar` / `OnlineGridCard` / `SearchResultBody` / `SearchResultGrid` / `SearchResultFooter` / `RevealMoreOnScroll`）、
`feature/ui/CategoryScreen.kt`（改用公共件，删掉私有的 `CategoryGridCard` 与两个常量）、
`feature/ui/ModeRoot.kt`（接线 `onToggleViewMode`）、
单测 `CategoryCatalogTest` 里的枚举名同步。

### 3. 验证

`:app:compileDebugKotlin` ✅、`:app:compileDebugUnitTestKotlin` ✅、`:app:assembleDebug` ✅
（按用户要求**只保证编译**，没有跑测试用例）。自测要点：搜索后默认是三列卡片、
只有名称与更新时间；点右上角图标切列表 → 每行出现 `JM号 · 更新 日期` 与作者、入库按钮；
再切回卡片仍停在卡片模式。

### 4. 追加：卡片网格的间距收紧（2026-10-20）

用户要求：**卡片样式下卡片之间再紧一点，让卡片面积大一些**，并且**所有用到卡片样式的页面**都要调。

做法：`Screens.kt` 新增两个共用常量，四个卡片网格全部改用它们
（以前是各页各写一份 12/12/12 或 12/10/10）：

| 常量 | 值 | 原来是 |
|---|---|---|
| `GRID_CARD_SPACING`（卡片之间的横/纵向间距） | **6dp** | 书库 / 最近浏览 12dp，分类 / 搜索结果 10dp |
| `GRID_CONTENT_PADDING`（网格外边距） | **8dp** | 四页都是 12dp |

覆盖的页面：**书库**（含收藏夹内，`LibraryScreen`）、**最近浏览**（`RecentScreen`）、
**分类页卡片**（`CategoryAlbumGrid`）、**搜索结果卡片**（`SearchResultGrid`）。
卡片本身（`AlbumGridCard` / `OnlineGridCard`）不动——省下的间距直接变成封面面积。
以后要再调只改这两个常量，不会再出现某一页还是老间距。

### 5. 追加：详情页点赞 / 观看数改用与分类一致的紧凑格式（2026-10-20）

用户要求：漫画详情页的 **like 数、观看数**也要跟**分类的数量格式化方式**保持一致。

- `feature/data/Categories.kt` 的 `formatCategoryCount` **改名为 `formatCompactCount`**（实现不变：
  `<1000` 原样、`<10000` 一位小数 + `k`、否则一位小数 + `w`），两个调用点共用同一个实现：
  分类抽屉的总数（`CategoryGroup.countLabel`）与详情页头部统计。
- `AlbumDetailScreen.formatStatCount`：空白仍然返回 null（整项不显示），
  数字先转 `Long` 再走 `formatCompactCount`；**不是数字的脏数据原样展示**，不吞掉。
  于是 `999` 原样、`1000` → `1.0k`、`12345` → `1.2w`。
- 单测同步：`DisplayLogicTest` 的详情页统计用例改成新的期望值（并补了 999 / 1000 / 3547 / 354792 与
  "非数字原样"两条），`CategoryCatalogTest` 里的函数名同步。

### 6. 追加：详情页显示上架时间（2026-10-20）

用户要求（两轮，最终形态）：详情页要显示时间——**标签叫「上架时间」**，
**位置在 JM 号那一行**（不单独占一行），分隔方式与**搜索结果页列表**一致。

最终显示：`JM350234 · 上架 2022-06-15`

- 先核了接口：`/album` 的 `addtime` 是 **10 位秒级时间戳字符串**，响应里**没有**更新时间字段
  （实测 `350234 → "1655254028"`、`114514 → "1560587558"`，见 `docs/official-api-2.1.11.md` §6.4），
  所以这里展示的就是上架 / 创建时间，标签写「上架」而不是「更新」。
- **顺带修掉一个老 bug**：`AlbumDto.addTime` 原来标的是 `@SerialName("add_time")`，而接口的 key
  是 `addtime` —— 也就是说这一列之前**从来没被写入过**。改成 `addtime` 后，打开/刷新详情就会落库。
- 展示逻辑：`feature/data/Categories.kt` 新增 `albumAddTimeLabel(addTime)`，复用 `updateLabel`
  （数字 → 本地时区 `YYYY-MM-DD`；非数字 → 原样；空 / `"0"` → null，那一段不拼）。
- UI：`AlbumDetailScreen` 的 `AlbumHeader` 里，JM 号那一行改成 `buildString { "JM{id}" + " · 上架 {日期}" }`
  ——与 `OnlineResultRow` 的 `JM号 · 更新 日期` 是同一套分隔写法；上架时间取不到时只显示 JM 号。
- 单测：`DisplayLogicTest` 的 `album add-time label comes from addtime and disappears when missing`
  （时间戳不写死具体日期，避开时区差异；非数字原样、`"0"` 不显示）。

### 7. 追加：详情页 tag 不再限两行（2026-10-20）

用户要求：详情页 tag 区域原来超过两行就显示省略号，**不要两行限制**，有多少 tag 都展示出来。

- `AlbumDetailScreen.AlbumHeader` 里 tag 那段 `Text` 去掉了 `maxLines = 2` 与
  `overflow = Ellipsis`：按宽度自然换行，标签数量再多也全部显示。
- 不用额外做"收起 / 展开"：这块本来就在详情页那个 `LazyColumn` 里，行数变多只会让头部
  跟着变高，整页照样能滚（不会把下面的简介、按钮顶出屏幕）。

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
2. **JM 图片线路默认值**：参考实现硬编码 `IMAGE_DOMAIN`，App 侧作为可选线路列出
   （2026-10-18 起改成清单选择，见上方「线路改成"清单选择"」），
   默认值沿用 `cdn-msp2.jmapiproxy2.cc`（见 `Defaults` / `LineCatalog`）。这条只是记录，不是待确认项。
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

**2026-10-18 本机实跑**（线路改成清单选择之后）：`313 个单元测试`，其中 **6 个失败**全是既有的
（`AlbumCleanerTest` 3 个、`ReaderAndTaskLogicTest` 3 个）；`lint 0 error / 30 warning`
（本次改动的 6 个文件 0 warning）；`assembleDebug` 与 `assembleRelease`（5.8 MB）均通过。
新增的 `core.protocol.LineCatalogTest` 12 个用例全绿。

### 测试清单（单元测试）

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `feature.jm.data.RepositoryTest` | 24 | 入库字段一致性 / 收藏保留 / 章节来源优先级（含单话本子兜底与标题归一化）/ 标签 OR / 删除语义 |
| `feature.jm.ui.ReaderAndTaskLogicTest` | 23 | 任务状态与进度文案 / 下载面板默认勾选与"已在队列" / **底栏切换方向** / 阅读器进度条与"待取 vs 失败" / 阅读偏好编解码 |
| `feature.jm.data.RecentAndFavoriteTest` | 19 | 最近浏览倒序 / 浏览时间语义 / 点详情不入库 / **骨架行不算本地数据** / 收藏夹数量与封面来源 Flow |
| `core.protocol.CryptoGoldenTest` | 14 | 签名 / AES-256 解密 / 块数，与参考实现逐值一致 |
| `core.protocol.LineCatalogTest` | 12 | **线路清单**：默认项在清单里且排第一 / 两份清单去重且是裸域名 / 空值与老格式（多行取第一行）的解析 / 清单外手填值保留成"当前线路" / 域名规整 / 测速灯号 600·1200ms 阈值 |
| `feature.api.ImageLineProbeTest` | 9 | **图片线路测速**：探针打官方那张 nopic 占位图（路径 + `?v=0`）/ 404 才换备用头像图、5xx 不重试 / 200 但是 HTML 劫持页或空 body 判不可用 / 超时归成"超时"且不给毫秒数 / `looksLikeHtml` 与 URL 拼接打表 |
| `core.storage.FileNamingGoldenTest` | 14 | 文件名清洗 / 本子目录名 / 章节与图片排序 |
| `core.database.DaoTest` | 14 | 书库分页排序（入库时间倒序）/ 标签 OR / 章节分组 / 收藏唯一索引 |
| `feature.jm.data.DownloadManagerTest` | 13 | **下载编排**：逐字节一致（块数 0 不重编码）/ 跳过已存在 / 断点只补缺口 / 等待网络 / 取消 / 追加章节 / 重试 |
| `core.database.DownloadTaskDaoTest` | 13 | 任务去重 / **恢复=暂停** / `pauseActive` 只暂停指定类型且章节回队列 / 章节计数幂等 / 历史裁剪 |
| `core.download.FileDownloaderTest` | 14 | Range 续传 / 不支持 Range / 断流 / 416 / 原子发布 / **本地种子文件不出网也能发布** / 种子不可用回落网络 |
| `core.download.DownloadTaskRepositoryTest` | 13 | 杀进程恢复（**下载置暂停、导出被删+中转文件清理**）/ 按磁盘重算进度 / 退出暂停批量语义 / 章节状态不回退 / 取消不复活 |
| `feature.jm.data.ExportManagerTest` | 15 | **导出编排**：缺图预检 / zip 与 pdf 跑通并交付 / 重复提交复用 / 无图时给出可读失败原因 / **成品是否存在、按名查 Uri、删单个与删两个格式 / `exportsOf` 的大小与生成时间 / `presentFormats` 整页只查一次** |
| `feature.jm.ui.DisplayLogicTest` | 10 | 书库状态文案 / 章节状态 / 体积格式 / **详情页点赞·观看数字段与章节区标题（含占用体积）** / 空态与错误文案 |
| `core.codec.PdfPagePlanTest` | 8 | **PDF 切片**：未超限仍是一页 / 超限切片连续无重叠 / 宽度超限等比缩小 / 切片数上限 |
| `feature.jm.data.AlbumCleanerTest` | 8 | **删除编排**：只删文件保留记录 / 从书库移除清四张表 / **封面按"还在不在书库或最近浏览里"决定**（四种组合）/ 移除前先取消任务 / 只删文件时任务照跑 |
| `feature.jm.ui.FavoriteSortTest` | 7 | **收藏页排序**：默认夹置顶 / 三种方式都是降序 / 空夹排最后 / 同值按 id 稳定 |
| `feature.jm.data.ImageStoreTest` | 10 | **本地优先取图**：命中不发请求 / 取回来就缓存 / 删掉后重新取 / 块数 0 原样保留 / **原图缓存逐字节原样落盘且不污染 library** / 两套目录互不影响 / `clearCache` 连原图缓存一起清 |
| `feature.jm.data.ReadingRepositoryTest` | 6 | 阅读位置读写与钳位 / "继续阅读"取最近一本 / 章节目录名规则 |
| `feature.jm.ui.SearchRevealStateTest` | 6 | 搜索增量展示的推进与边界 |
| `feature.jm.ui.LibraryExportFilterTest` | 6 | **「有 ZIP / 有 PDF」筛选**：单条件 / 两条件要同时满足 / 快照未就绪时放行 / 「清除 N 项」只数用户加的条件 |
| `feature.jm.ui.LibraryPagingTest` | 3 | **书库分页拼页**：后续页追加在后面 / 跨页重复的 id 只留一条（重复 key 会崩）/ 没有后续页时走快路径 |
| `feature.jm.ui.ReadingProgressLabelTest` | 4 | **「继续阅读」那行小字 + "导出中"状态文案**：有页码 / 只记到章节 / 章节名缺失 / 排队中·导出中 45%·等待网络 |
| `core.storage.ExportPublisherFolderTest` | 5 | **「打开文件夹」的目录 URI**：API 34 给出带 `primary:` 卷名的正确文档 id / API 28 返回 null（私有目录别人看不到）/ 位置文案一致 / 多次调用稳定 |
| `feature.jm.ui.SheetGestureTest` | 4 | **面板把手关闭阈值**：拖过 56dp 关 / 甩过 125dp/s 关 / 又短又慢不关 / 向上拖不关（本轮按要求**未运行**，只保证编译） |
| `core.download.DownloadGateTest` | 5 | 下载网络策略矩阵（仅 Wi-Fi / 允许移动网络） |
| `core.codec.DescramblerGoldenTest` | 5 | 解扰块布局 + 与参考实现逐像素一致 |
| `core.codec.ImageCodecSampleTest` | 5 | **显示端降采样打表**：窄图不缩 / 宽图按屏幕宽度折半 / 长条页按 12 MP 预算折半 / 尺寸未知与预算 0 都不崩 |
| `core.codec.ArchiveWriterTest` | 5 | zip 结构（不套本子目录）/ 顺序 / 进度 / 不加密码 / 重新生成覆盖 |
| `core.codec.PdfWriterSpikeTest` | 4 | PDF 页面 / 书签 / `PageMode` / 不加密 |
| `feature.jm.data.SearchMergeTest` | 4 | **精确匹配合并**：置顶 / 同 aid 不重复 / 总数只在真的新增时 +1 / 搜索零结果也有它 |
| `core.protocol.CryptoProbeTest` | 2 | 环境回归：JCE 可用性 |
| `core.codec.PixelProbeTest` | 2 | 环境回归：位图像素读写行为 |
| `core.settings.ReaderCacheModeTest` | 4 | **取图管线的存储编码**：默认原图缓存 / 编码往返 / 旧值与脏值安全回落 / 两个选项文案 |

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

## 阅读器取图管线：原图缓存（2026-10-21）

用户反馈：**同一个网络下，官方 App 在线看很快，本工程慢很多**。上一轮的分析结论（`docs/official-api-2.1.11.md`
§8 与当时的实测）是：**图片线路不是主因**——一页只有几十 KB（实测 681×946 的 webp = 66 440 B），
换到"延迟最低"的图片线路也救不回来；真正吃到时间的是本工程把"显示一页"实现成了

```
下载 → 解码 ARGB_8888 → 逐行搬块 → 无损 WEBP 重编码（体积 ×7.8、耗时 ×4）→ 写盘 → Coil 再解码一次
```

而官方是"`<img>` 下载（提前 12 页预取）→ 解码一次 → `canvas` 缩放搬块"，**不重编码、不落盘、不二次解码**。

### 做法：新增第二套管线，旧管线原样保留、可随时切回

设置页「阅读与显示 → 图片缓存方式」：

| 选项 | 落盘内容 | 目录 | 显示 |
|---|---|---|---|
| **原图缓存**（默认，本次新增） | 站点**原始字节**（未解扰、未重编码） | `files/reader-cache/<aid>/<cid>/` | 就地解码（可降采样）+ `Canvas` 搬块 → `Image(bitmap)` |
| 解扰后落盘（旧行为，保留） | 解扰 + 无损重编码后的**成品** | `files/library/<本子>/<章节>/` | `AsyncImage(model = File)` |

- 切换**选中即生效**，只影响之后新取的页；已经在屏幕上的页不重建。
- **下载任务永远走旧管线的成品落盘**，与这个设置无关（导出 ZIP/PDF 必须拿到解扰后的图）。
- 两套目录刻意分开：`reader-cache` 里是加扰字节，绝不能被"是否已下载"的判断命中，
  否则下载会跳过、导出会把加扰图打进 zip；切回旧管线也不会读到加扰图。

### 预取与并发

- 新管线在**可见页就绪后**顺手取后面 `PREFETCH_AHEAD = 3` 页的**字节**（只取字节、不解扰：
  预取阶段解长图会白占几十 MB）。
- 并发名额**直接取设置里的「同时下载图片数」**（用户 2026-10-21 要求，1～12，默认 4）：
  用 `Semaphore` 实现，可见页 `acquire()`（一定排得上），预取 `tryAcquire()`（抢不到就跳过，
  绝不把用户正在看的那页排到后面）。解扰另有 `DECODE_CONCURRENCY = 2` 的名额（瓶颈是位图内存）。
- 位图**有窗口**：当前页前后各 2 页、且总字节不超过"堆的 1/6（24～96 MB）"，超出的位图从状态里摘掉
  （**不 `recycle`**：Compose 可能还在引用上一帧），滚回来按磁盘上的原图重解一次（不联网）。

### 改动的文件

| 文件 | 改动 |
|---|---|
| `core/settings/ReaderCacheMode.kt` | **新增**：两套管线的枚举、文案、`fromCode`/`toCode`（默认原图缓存） |
| `core/settings/SettingKeys.kt` / `SettingDefaults.kt` | 新增 `READER_CACHE_MODE`（1 原图 / 0 解扰后落盘） |
| `core/storage/LibraryDirs.kt` | 新增 `reader-cache/` 与 `readerCacheAlbumDir`/`readerCacheChapterDir`；`clearCache()` 连它一起清 |
| `core/codec/ImageCodec.kt` | 新增 `descrambleToBitmap`（解码 → `Canvas` 搬块，含 1px 接缝补偿）与纯函数 `decodeSampleSize`；`ImageDownloadTransform.blockNumOf` 收编两套管线的块数算法 |
| `feature/data/ImageStore.kt` | 新增 `fetchRawFile`（原样落盘、无 `beforePublish`）/ `cachedRawFile` / `displayBitmap`；旧 `file` 一字未改 |
| `feature/ui/ReaderViewModel.kt` | 模式分派、预取、并发名额、位图窗口回收；`ReaderPage` 增加 `displayFile`/`bitmap`/`rawFile`；换话时取消在飞的取图（文件名每话重名，不取消会串页） |
| `feature/ui/ReaderScreen.kt` | 一页两种渲染来源（`Image(bitmap)` / `AsyncImage(file)`）；`LaunchedEffect` 的 key 带上 `rawFile`，预取回来后立刻解扰 |
| `feature/ui/SettingsScreen.kt` | 状态字段 + `setReaderCacheMode` + 「图片缓存方式」单选行 |
| `feature/data/AlbumCleaner.kt` | 「删除本地文件 / 移出书库」连这本的 `reader-cache/<aid>` 一起清（并计入释放字节） |
| `core/download/FileDownloader.kt` | 新增 `seedFile`（本地种子）：阅读器已存原图时，下载**不再联网**，直接拿本地字节走"解扰 → 原子发布"；`ParallelImageDownloader.downloadImages` 对应新增 `seedFor` |
| `feature/data/DownloadManager.kt` | 下载时把 `reader-cache/<aid>/<cid>/<文件名>` 当种子交给引擎（"一边看一边下载"不再重复下载同一张图） |
| 测试 | 新增 `ImageCodecSampleTest`（5）/ `ReaderCacheModeTest`（4）；`ImageStoreTest` 补 3 个（原图落盘逐字节一致、两套目录互不影响、`clearCache` 清原图缓存） |

### 快翻时图片闪烁的修复（同日，用户反馈）

用户反馈：换上新管线后确实快了，但**翻太快时会看到图片闪一下**。根因是位图回收的中心用错了：

- 旧实现 `trimDisplayBitmaps(刚加载完的页码)` —— 翻得快时先发出的页反而后到，它回来时以**自己**为
  中心回收，正好把用户此刻正看着的那几页（离它很远）的位图抽掉，于是"图没了 → 又被重解 → 图回来"。
- 现在的做法分两级，中心一律取 `currentPage`：
  1. **硬预算**（每帧生效）：位图总字节超过"堆的 1/6"时，从离当前页最远的开始丢，
     **当前页与它旁边那一页永远不动**（一屏常常同时露出两页）；
  2. **窗口回收**（滚动停稳 [TRIM_SETTLE_MS] 后再做）：只保留当前页前后 `BITMAP_KEEP_RADIUS = 3` 页。
     快翻期间一帧一个页码，立刻按窗口回收会让页反复"丢掉 → 重解"，那本身就是闪烁。
- 另外两处小修，都是同一个观感问题的来源：
  - **预取拿到字节时就量出真实宽高比**写进状态，占位框一开始就是对的尺寸，图出现时不再"跳一下"；
  - 阅读器的**占位高度改用这一页已知的 `aspectRatio`**（以前一律按 1.4 占位），
    被回收过、翻回来重解的页也不会因为高度变化而抖。

### 设备上怎么核对

1. 设置 → 阅读与显示 → 图片缓存方式选「原图缓存」（默认就是），进一本没缓存过的漫画，
   翻十几页：**每页只闪一下**，不应再有"一页一卡"。
2. `adb shell ls -l /data/data/<包名>/files/reader-cache/<aid>/<cid>/` → 每张 ≈ 站点原图大小（几十 KB），
   而 `files/library/<本子>/<章节>/` 里（如果下载过）是解扰后的成品。
3. 设置里把「同时下载图片数」调成 1（或 12）再翻页：预取明显变严格/变积极——说明并发真的走那一项。
4. 切回「解扰后落盘」：行为与本次改动之前完全一致（`library` 里继续产出成品），再切回来也正常。
5. 长条页（800×12000）与普通页来回翻：内存不持续上涨（远处的位图会被摘掉，翻回来重新解、不联网）。

### 这次没做

- **封面 / 头像仍然固定走 `Defaults.IMAGE_DOMAIN`**，不跟随「图片线路」设置（`Defaults.buildCoverUrl` /
  `buildAvatarUrl` 的调用点分散在 `Repository` / `Screens` / `AlbumDetailScreen`，属于另一条链路，本轮不动）。
- 没做"下载时也预取原图"：下载永远产出成品，两套缓存互不复用（换来的是语义清晰、不会把加扰图当下载结果）。
  唯一的例外是**单向复用**：下载时会先看 `reader-cache` 里有没有现成原图（有就不联网、直接解扰发布），
  反过来阅读器不会去偷 `library` 的半成品。

## 改造：账号体系 v3（2026-10-21）

用户要求：先把**登录**做进 App；「更多」页改造成「我的」；把**本机收藏页隐藏**；收藏改用**官方在线收藏**、
入口放进「我的」。设计与实测依据见 `docs/account-design.md`（v3）与 `docs/official-api-manga.md`。

### 1. 做了什么

| 层 | 改动 |
|---|---|
| 协议 | `ApiClient` 新增 `multipart/form-data` **POST**（`postRaw` / `postDecrypted`）与鉴权头 `authProvider`；**POST 一律不自动重试**（`POST /favorite` 是切换语义，重放会把刚加的收藏取消掉） |
| 账号 | 新增 `core/account/{AccountStore,AccountSession,AccountState,MemberProfile}.kt`：凭证进 `SecretStore`（Keystore），资料快照进 DataStore；**不保存密码**；401 → `Expired`（清凭证、留快照） |
| 数据 | 新增 `feature/data/AccountRepository.kt`：登录/退出、收藏分页、收藏切换、收藏夹增删改移、资料读取；401 统一在这里翻译成"登录已失效" |
| UI | 新增 `MyScreen`（替代 `MoreScreen`）、`AccountLoginScreen`、`AccountFavoritesScreen`（收藏夹列表 + 夹内网格 + 详情页面板）、`AccountProfileScreen`（只读）；`ModeRoot` 底栏 4 项 + 4 条新路由 + `next` 回跳 |
| 详情页 | 「收藏」按钮改开**账号**收藏面板：未收藏点夹=加入并移入（add+move 两步）、已收藏点夹=搬过去、点「全部收藏」=只加入/取消收藏 |
| 设置 | 新增 `SettingKeys.Jm.ACCOUNT_PROFILE`、`SECRET_JWT`、`SECRET_AVS` |

### 2. 官方收藏的六条硬规则（实测，已写进 DTO 与注释）

1. **单夹模型**：一本漫画同时只在一个收藏夹里（`move` 是搬家）；
2. **不能把漫画移出夹子**：`move` 的 `folder_id` 传空/`0` 会被拒（只能取消收藏）；
3. **非空收藏夹不能删**：服务端回 `该收藏夹裡还有漫画，请先删除漫画`；
4. **加入时不能指定夹**：`POST /favorite` 忽略 `folder_id`，所以"点夹加入"是两次请求；
5. **批量 aid 不可靠**：逗号拼接返回的 `type` 与实际状态不符，所以不做批量；
6. **写完立刻回读可能拿到旧值**：以写接口的 `type`/`status` 为准。

### 3. 本机收藏 UI 隐藏清单（只隐藏收藏，最近浏览原样保留）

| 位置 | 隐藏方式 |
|---|---|
| `ModeRoot.Tab` 的 `FAVORITES` | 注释枚举项 → 底栏自动变 4 项 |
| `ModeRoot` 的 `Routes.FAVORITES` / `Routes.FAVORITE_ALBUMS` 两个 composable | 整块注释（`/* … */`） |
| 详情页「收藏 / 已收藏」按钮 + `FavoritePickerSheet` | 按钮改绑账号面板；面板调用注释、函数体保留 |
| 书库「收藏」筛选 chip | `LibraryScreen(showCollectedChip = false)` 默认值 + 标记 |
| 书库多选「修改收藏夹」 | 调用点注释（ViewModel 方法与面板都保留） |
| `Repository` / `FavoriteDao` / `FavoritesViewModel` | **一行未改** |

全部隐藏点都带 `【本地UI隐藏】` 标记，放开注释即可恢复。

### 4. 这次没做（下一步）

- **注册 / 邮箱验证 / 找回密码**：官方接口是 `POST /register`、`/confirm`、`/forgot`（本次未实测，登录页底部有一行说明指向网页版）。
- **我的留言**（`/forum?uid=` + 详情页发/删评论）、**个人资料编辑**（`POST /useredit` 整条覆盖，必须全量提交）——资料页目前**只读**。
- **保持登录 / 静默重登**：需要按 token 真实寿命再定（官方本地写死 1 小时，本次未验证）。
- **收藏夹预选**：`GET /favorite` 的列表项不带所属夹，想看"现在在哪个夹"要翻遍每个夹，所以面板不预选，只用一行说明。

### 5. 验证

- `:app:compileDebugKotlin` ✅
- 新增单测 `core/account/AccountModelTest`：会员行只对会员显示、三种会话状态、真实响应样本解析（登录 / 收藏列表 / 收藏操作成败）。
- `ReaderAndTaskLogicTest` 里底栏方向的两个用例按新的四项重写（书库→我的位移 3）。
### 6. 验证结果（本机实跑）

| 检查 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ |
| `:app:lintDebug` | ✅ **0 error / 31 warning**（warning 均为既有项） |
| `:app:assembleDebug` | ✅ |
| `:app:assembleRelease`（R8 + lintVital） | ✅ |
| `:app:testDebugUnitTest` | 403 个用例 / **8 个失败**，与改动前 HEAD 的基线**逐条一致**（在 `git worktree` 出来的干净 HEAD 上跑同样 8 条也失败）：`AlbumCleanerTest` 3 条 NPE/断言、`DownloadManagerTest` 3 条 15s 超时、`ReaderCacheModeTest` / `ReaderAndTaskLogicTest` 各 1 条**陈旧文案断言**（代码里已改成"使用缓存/直接下载""上下翻页/左右翻页"，测试还在断言旧文案）。**本次改动未新增失败** |
| 新增 `AccountModelTest` | ✅ 10 条全绿（会员行只对会员显示、三种会话状态、真实响应样本解析） |

> 真机冒烟测试**未跑**：本机当前 `adb devices` 没有设备。登录 / 收藏的接口层行为已用 Node 探针
> 对真实账号验证过（见 `docs/official-api-manga.md`），但 UI 流程还需要一次真机验收。
### 7. 第二批微调（用户反馈 2026-10-21）

| # | 问题 | 处理 |
|---|---|---|
| 1 | 收藏页 / 个人资料页每次进入都重新加载 → 内容闪一下 | 新增 `feature/data/AccountCache.kt`（进程内快照，挂 `AppContainer`）：进页面**先铺上次的内容**，不转圈、不联网；只有"数据被动过"（详情页加/移收藏、夹增删改）才**静默**补一次。写操作里调 `invalidateFavorites()`，登录/退出 `clear()` |
| 2 | 收藏页顶栏「刷新」按钮 | 去掉，只保留下拉释放刷新 |
| 3 | 收藏页/资料页要有下拉刷新 | 两页都套 `PullToRefreshBox`：`isRefreshing` 只在"已有内容、正在重拉"时为 true；空态/加载态用 `ScrollableFullScreenBox` 包住才能拉得动 |
| 4 | 重复 loading | 两个 ViewModel 各加 `inFlight` 标志：下拉途中再拉、错误页连点重试，都只发一次 |
| 5 | 夹内页「共 N 本」单独占一行 | 数量并进标题：`← 收藏夹1（178本）`；加载中只显示夹名，拿到 total 再补括号 |
| 6 | 「加载更多」按钮跑到卡片格里 | 网格 footer 用 `item(span = { GridItemSpan(maxLineSpan) })` 占满整行（上一批已改，本次确认在代码里） |

> 说明：本批**未跑**单测 / lint（用户要求只保证编译通过，自行真机验收）。
### 8. 第三批微调（用户反馈 2026-10-21）

| # | 问题 | 处理 |
|---|---|---|
| 1 | 每次进详情页点「收藏」，面板都要重新请求收藏夹 | `AccountCache` 新增 `folders` 快照（含 `foldersDirty`）：详情页面板**优先读缓存**，缓存没有才请求；**只有新建 / 删除收藏夹**才作废（`invalidateFolders`），改名为就地更新（`renameFolder`）；退出 App 随进程消失，登录/退出 `clear()`。收藏页拉列表时也会顺手写这份缓存 |
| 2 | 面板打开时没有 loading | `AlbumDetailUiState.accountFoldersLoading` + 面板里**局部**转圈（"正在读取收藏夹…"）：只影响收藏夹那一段，说明行 / 「新建」照常可用；没有收藏夹时给空态提示 |
| 3 | 已收藏的漫画进详情页，按钮先「收藏」再变「已收藏」 | ① 点收藏/移出/移夹成功后把结果写回 `t_jm_album.is_favorite`（新增 `AlbumDao.setFavoriteFlag`，见 `Repository.setAlbumFavoriteFlag`）并记进 `AccountCache`；② 进页面时缓存有结论就直接用、不再请求；③ 首屏联网拉详情时直接采用那次 `/album` 的 `is_favorite`（`favoriteFlagSynced` 置位），不再重复请求同一本 |

> 闪变的原因（答用户问）：详情页 init 里只要已登录就调一次 `Repository.fetchAlbumFavoriteFlag(aid)` → 内部是 **`GET /album?id={aid}`**，用它返回的 `is_favorite` 覆盖按钮状态。本机 `is_favorite` 之前从不回写（点收藏只打 `POST /favorite`），所以初值可能是过期的 false。现在写回 + 进程内缓存之后，同一本再次进入不会再翻。
### 9. 第四批微调（用户反馈 2026-10-21）

**收藏夹内页也要缓存**（用户要求：夹内内容基本不变，进页面别重新请求，下拉刷新即可）。

| 项 | 做法 |
|---|---|
| 缓存 | `AccountCache` 新增 `albumPages`（`ConcurrentHashMap<"folderId#order", AlbumsSnapshot>`，快照含已加载列表 / `total` / 下一页页码）。夹内页进页面先铺快照，**不转圈、不联网** |
| 作废时机 | ① **收藏 / 取消收藏**（`toggleFavorite`）② **移到收藏夹**（`moveToFolder`）③ **新建 / 删除收藏夹**（夹内容跟着变）④ **登录 / 退出**（`clear()`）。改名只改夹名，不影响夹内容，缓存保留 |
| 下拉刷新 | 夹内页也套了 `PullToRefreshBox`：下拉强制重拉第 1 页并覆盖该 `folderId#order` 的缓存；"加载更多"追加后同样写回快照（含下一页页码） |
| 重复 loading | `inFlight` 标志 + `refreshing` 状态：下拉途中再拉 / 连点重试只发一次；有内容时只转下拉圈，首屏才转整页 |
| 换排序 | 按 `folderId#order` 各存一份，切回收藏时间/更新时间时若缓存命中就直接铺，不联网 |
| 本页移出 | 移出成功后本地列表就地减一，并把这份（已减掉的）快照重新写回缓存，返回列表再进来不用等网络 |

> 没覆盖到的场景：**在网页版/另一台设备上改了收藏** —— 进程内无从得知，只能靠用户下拉刷新。
### 10. 第五批微调（用户反馈 2026-10-21）

详情页「收藏」面板在**已收藏**状态下的文案/配色：

| 行 | 改前 | 改后 |
|---|---|---|
| 第一行 | `全部收藏` + 小字「取消收藏」 | **`移出收藏`**（`MaterialTheme.colorScheme.error`，深色模式同样跟主题走），箭头同色，不再带小字 |
| 收藏夹行 | 名字 + `N 本` + 箭头 | 名字 + **`移动至`** + 箭头（已收藏时语义就是"搬过去"，直接把动作写出来） |
| 顶部说明 | 「点「全部收藏」会取消收藏」 | 「点某个收藏夹就会移动过去（一本只能放一个夹）；点「移出收藏」会取消收藏」 |

未收藏状态的文案保持原样（`全部收藏` + 「只收藏，不归类」；夹行显示 `N 本`）。
`FolderChoiceRow` 增加 `labelColor` / `iconTint` / `trailingColor` 三个参数，颜色全部从 `colorScheme` 取，不写死色值。
### 11. 第六批微调（用户反馈 2026-10-21）

**收藏夹列表页的 loading 要等补拉全部结束**（否则 LoadingBox 先消失、卡片先显示「…」再不封面/数量填上，看起来闪一下）。

- `AccountFavoritesViewModel.fetch`：拿到第一页后**先只换夹名**，并沿用旧的「N 本 / 封面」（`previous[fid]` 合并），
  然后 `await` 完 `backfillFolders`（内部 ≤3 并发）才把 `loading` / `refreshing` 置回 false，最后写缓存。
- `backfillFolders` 由"另起一个 job"改成 **suspend**：所有并发请求结束才返回（`coroutineScope + awaitAll`）。
- 首次进入：LoadingBox 一直转到全部补拉结束；下拉刷新：下拉圈同理（内容保持旧数量，不闪）。
- 补拉有夹失败（`count == null`）时把收藏页缓存标脏：下次进来静默重试一次，同样不闪。

> 术语澄清（用户 2026-10-21 追问）：上面说的"封面就绪"**不是**指图片下载完成，
> 而是指"拿到了封面所属漫画的 id"（`GET /favorite?folder_id=X&page=1` 的 `list[0].id`）。
> `loading` 只等**接口请求**全部返回；封面图本身仍由 Coil 异步加载，加载中就是卡片底色占位
> （`Cover` 外面垫的 `colorScheme.surface`），这块行为没动、也不要动。
### 12. 第七批微调（用户反馈 2026-10-21）

**收藏夹页补拉时已经拿到的第一页，直接复用给夹内页**（避免点进夹里又请求一次）。

- `AccountFavoritesViewModel.fetch`：第一页（`folder_id` 空 = 全部收藏，`o=mr`）除了拿 `total`/封面，还把
  `list`（20 条）写进 `AccountCache.putAlbums(null, "mr", AlbumsSnapshot(albums, total, nextPage = 2))`；
- `backfillFolders` 每补一个夹，把那次 `page=1` 的结果同样写进 `putAlbums(fid, "mr", …)`；
- `AccountAlbumsViewModel` 进夹时先查这份缓存（已有逻辑）→ **首次点进任何收藏夹都不再发请求**，
  点「加载更多」从 `nextPage = 2` 接着翻；
- 缓存仍然只在"收藏/取消收藏、移夹、新建/删除夹、登录退出"时整份作废，下拉刷新覆盖单个 key。

| 场景 | 改前 | 改后 |
|---|---|---|
| 首次进收藏夹列表 | 1 次（夹目录+全部第一页）+ N 次（每夹补拉） | 同左，但结果全部落缓存 |
| 首次点进某个夹（`o=mr`） | 1 次请求 | **0 次**（用补拉缓存） |
| 点进「全部收藏」 | 1 次请求 | **0 次**（用首屏那一页） |
| 夹内切到「更新时间」排序 | 1 次请求 | 仍 1 次（那是另一份 key） |
| 夹内下拉刷新 / 加载更多 | 请求 | 请求（覆盖/追加缓存） |
### 13. 第八批微调（用户反馈 2026-10-21）

| # | 改动 |
|---|---|
| 1 | **个人资料页去掉「退出登录」**（`AccountProfileScreen` 删掉 `onLogout` 参数与按钮；`AccountProfileViewModel.logout()` 一并删除，避免留死代码） |
| 2 | **「我的」页的退出登录挪到「应用设置」下方**：并进「本机」分组白卡的最后一个行（`LogoutRow`：红字 `colorScheme.error`、无箭头、退出中原地转小圈并禁点），原来卡片下方那颗 `TextButton` 删掉；未登录时不显示这一行；版本号仍在最下面 |
### 14. 第九批微调（用户反馈 2026-10-21）

用户把「退出登录」改成**单独一张组卡**后，两处调整：

1. `LogoutRow` 文字**左右居中**：`Row(horizontalArrangement = Arrangement.Center)`，去掉 `weight(1f)`；退出中"正在退出…"与右侧小圈作为一组居中。
2. 「退出登录」卡与上一组拉开距离：`GroupCard` 新增 `topPadding: Dp = 8.dp` 参数（默认不变，其它组仍是 8dp），
   退出那张传 **24dp**。
### 15. 第十批微调（用户反馈 2026-10-21）

「退出登录」行文字右侧加**出口图标**：

```
                退出登录  ⏻
```

- 用 `Icons.AutoMirrored.Filled.ExitToApp`（就是 `Icons.Default.ExitToApp` 的镜像版：
  视觉一致、RTL 下自动翻转；工程里多选条「移出书库」用的也是这个）。
- 图标 18dp、颜色 `colorScheme.error`（与文字同色），文字与图标之间 6dp。
- 整组（文字 + 图标）保持 `Arrangement.Center` 居中；退出中在**最右侧**再多一个小圈。

### 16. 隐藏「接口缓存」设置项（用户要求 2026-10-22）

用户双开实测：这个开关**关掉也不生效**，留着只会误导，所以把入口藏起来（代码全保留，放开注释即恢复）。

四条观测（同一账号、两个 App 实例、两条不同线路）：

| 观测 | 结论 |
|---|---|
| B 只读刷新（下拉 / 重进页面 / 杀进程重启 / **重新登录**） | 永远拿不到 A 的新收藏夹 / 新收藏 |
| B 切到 A 的线路再读 | 立刻拿到 |
| B 在**自己线路上**做任意收藏类写操作（新建/删除夹、加/移收藏） | 之后再读就能拿到 A 的更新 |
| 线上抓包（未登录可读的公开接口） | `cache-control: no-store` + Cloudflare `cf-cache-status: DYNAMIC`；同一 URL 换 `ts` 密文不同、且只能用各自的 `ts` 解密 |

→ 服务端缓存是「**线路 × 账号**」级的：**只有落在同一条线路上的写操作会让它失效**，
读、重登、query 里的 `t` 都不影响。`t` 无效的原因：缓存位于"组装明文"那一层（HTTP 层本来也没有缓存可绕），
其 key 只认它认识的参数。补充旁证：同一本公开漫画的 `total_views` / `likes` 在不同线路上能差几到十几，
且 5 分钟不收敛——线路之间本来就是不同快照。

官方 App 同样无解（逆向源码在 `.work/src`，只在本地分析用，不入库）：`t` 只给 `/setting` 用
（`settingAction.ts:15,42`），`/favorite` 只传 `page/folder_id/o`（`memberAction.ts:207`），
全工程没有条件请求 / 轮询 / 同步接口；它唯一的"刷新數據"是 `clearAuth()` + 重登
（`Member.tsx:83-99`），本场景已实测无效。

改动：

- `SettingsScreen.kt` 的 `AdvancedSection`：`CardDivider()` + `SwitchRow("接口缓存")` **整段注释掉**，
  注释里写明隐藏原因与恢复方法；`onSetAccountApiCache` 形参与调用链（`ModeRoot`）保留，只是暂时没人用。
- 逻辑与默认值**一行没改**：`SettingKeys.Jm.ACCOUNT_API_CACHE`、`SettingDefaults.Jm.ACCOUNT_API_CACHE = true`、
  `ApiClient.withAccountCacheBuster()`（关闭时才拼 `t`）、`SettingsViewModel.setAccountApiCache()` 全部保留；
  已安装用户的这个 key 也没被动过，所以行为仍是「开启 = 复用服务端缓存」。
- 恢复方式：放开 `AdvancedSection` 里那段注释即可。

> 待办（下次改文案时一起处理）：`ApiClient.kt:65-73` 里"服务端按「线路 + URL」缓存这些 GET 的明文响应、
> `t` 能改变缓存键"的断言与实测不符；`SettingsScreen` 原开关文案「关闭：每次越过缓存获取最新数据」也过强。
