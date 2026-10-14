# 安卓单机版「JM漫画 + B站视频」App 实施计划

> 本文档供**新会话**直接执行。新会话没有历史上下文，请先完整读完第 0～3 节再动手。

---

## 0. 给新会话的开场信息

**参考实现（只读，不要修改它）**

- 项目根目录：`D:\JavaProject\haruhibot-server`
- 技术栈：**Java 21** + Spring Boot（服务端）+ Vue2/Element-UI（Web 端）
  （`pom.xml` 里 `maven.compiler.source/target/release` 均为 `21`；本机 IDEA 项目 SDK 也是 21。跑黄金用例脚本时注意用 JDK 21）
- 当前分支：`dev/ai-refactor`
- 本任务只把它当**可执行的需求说明书 + 参考实现**：所有业务规则以它的源码为准。

**本次任务**

新建一个**纯单机安卓应用**（不用任何后端服务器），把下面两块功能完整搬到手机上：

1. **B站视频**：视频记录落库、视频下载到手机、列表展示与播放、删除。
2. **JM 漫画**：在线搜索、记录落库、下载（含图片解扰）、收藏夹、zip 导出、pdf 导出、阅读。

**必须遵守的已定决策（用户已确认）**

| 决策 | 内容 |
|---|---|
| UI | **Jetpack Compose**（用户于 2026-10-06 确认），使用安卓原生 UI |
| 服务器 | **没有服务器**。所有数据、文件、数据库都在手机本地；不做多端同步 |
| 账号 | **不做 JM 账号/登录**（用户已确认暂不需要）。JM 请求只依赖时间戳签名，不依赖登录态（见 §5.1）；B站 Cookie 只在 B站模式内部配置，不做任何首次引导 |
| JM PDF | **必须实现**（不是可选项） |
| 导出密码 | 生成 zip/pdf 时弹**密码框**（默认填设置里的密码）+ **[确认]** / **[不设密码]** 两个按钮；`不设密码` = 不加密（见 §9 第 7 条） |
| 图片落盘 | `blockNum == 0` 时**直接落原图**，不再统一重编码成 webp |
| 下载任务 | **必须持久化**（杀进程后可恢复），进度粒度**到章节 + 具体图片**（见 §4.5） |
| 库导入 | **不做导入通道**（服务器上已有的库不搬，手机上重新添加/下载） |
| 语言 | **Kotlin**（用户于 2026-10-06 确认）；UI 与业务代码使用 Kotlin，允许调用兼容 Android 的 Java 库（见 §12） |
| B站清晰度 | 现阶段保持参考实现的行为（未设置 `fnval`，走 progressive `durl` 单文件直链），但**必须预留 DASH + MediaMuxer 合流的扩展点** |
| 参考实现 | 可自由重写、优化，但**行为要能对齐**；已确认的移动端交互差异以 §9 为准 |
| UI 与设置 | 内容优先的双模式媒体库；JM / B站独立设置 + 全局 App 设置；用户界面不显示配置 key（见 §9） |

**建议的目录**

```
<新 Android 工程>/
  docs/android-app-plan.md      ← 把本文件复制过去
  app/ ...
```

---

## 1. 范围：做什么 / 不做什么

### 1.1 要做（用户明确列出的）

| 功能 | 说明 |
|---|---|
| B站视频记录落库 | 视频元数据（BV/AV/CID、标题、封面、UP、标签、时长、简介等）存本地库 |
| B站视频下载 | 下载单文件 mp4 到手机本地；支持进度、取消、失败原因、重复下载拦截 |
| B站视频展示 | 列表、筛选、排序、分页、封面、下载状态、播放 |
| JM 下载 | 按本子/章节下载全部图片到本地，支持并发、续传（已存在的图片跳过）、进度、取消、重试 |
| JM 记录落库 | 本子、章节图片入库；"是否已入库/是否已下载"状态 |
| JM 收藏 | 收藏夹（文件夹）增删改、本子加入/移出收藏夹、按收藏夹浏览 |
| JM zip | 把本子目录打包成**带密码的 AES zip** |
| JM pdf | 把本子目录生成**带密码的 PDF**（每章书签目录） |
| JM 阅读 | 先进入**漫画详情页**（本地章节优先展示、在线刷新 + 标记已下载章节），点章节才进**阅读器**（翻页、缩放、章节切换） |

### 1.2 不要做（参考实现里有、但本 App 明确排除）

QQ/OneBot 相关的一切、B站直播订阅与推送（`t_bilibili_subscribe`）、WebSocket 推送层、登录鉴权与用户体系、配置管理页/日志监控/数据库管理/服务器文件管理等后台菜单、定时任务、词条/聊天记录/Pixiv 等其它功能模块。

另外**明确不做**：把服务器上已有的库（`jmcomic/`、`video/bilibili/` 目录和 `.db`）导入手机的通道 —— 手机上重新添加与下载即可（用户已确认）。

---

## 2. 参考实现地图（新会话必读）

### 2.1 必须精读的源文件

**JM 漫画**

| 文件（相对 `D:\JavaProject\haruhibot-server`） | 行数 | 作用 / 需要抄的逻辑 |
|---|---|---|
| `src/main/java/com/haruhi/botserver/features/jmcomic/service/JmcomicService.java` | 1622 | **核心**。JM 接口调用、签名、AES 解密、图片下载、解扰、zip、pdf、任务模型 |
| `.../features/jmcomic/service/JmcomicSqliteServiceImpl.java` | 1006 | 本子/章节图片/收藏夹的增删改查与分页查询语义 |
| `.../features/jmcomic/service/JmTaskQueue.java` | 454 | 任务队列：串行/并行、去重、取消、重试、进度上报 |
| `.../features/jmcomic/service/JmTaskContext.java` / `JmTaskPushService.java` | 小 | 任务上下文与进度回调（安卓上换成 Flow/回调） |
| `.../features/jmcomic/service/JmOnlineSearchHistoryStore.java` | 183 | 在线搜索历史（注意确认它的存储方式，见 2.4 待确认项） |
| `.../features/jmcomic/controller/JmcomicController.java` | 366 | 对外功能清单（哪些能力存在、参数是什么） |
| `.../features/jmcomic/client/model/*.java` | ~400 | `Album`/`Chapter`/`Series`/`SearchResp`/`UserProfile`/`DownloadParam` 数据模型 |
| `.../features/jmcomic/persistence/entity/*.java` | ~120 | 4 张表的字段定义 |
| `src/main/resources/config/jm.properties` | — | 需要变成 App 设置项的配置 |

关键行号锚点（`JmcomicService.java`）：

| 位置 | 内容 |
|---|---|
| 85～104 | JM API 密钥/版本/UA/超时重试常量 |
| 119～175 | `getJmApiDomain()`（域名与容错） |
| 177～200 | `login(username,password)`（JM 账号登录）——**本 App 不实现**，只作了解；主流程不依赖它 |
| 543～590 | **zip 打包**（zip4j + AES 密码） |
| 592～760 | **pdf 生成**（PDFBox：页面、书签、加密、权限） |
| 674～744 | `albumToPdf` / `addImageToPdf`（页面尺寸=图片像素尺寸，一章一个书签） |
| 746～790 | `sortFolders` / `sortFiles` / `extractChapterNumber` / `extractImageNumber`（章节与图片排序） |
| 798～930 | `downloadAlbum`（整本下载、章节循环、进度、重试） |
| 930～1020 | `downloadChapter`（并发分片、`.tmp` 中转、跳过已存在、防无限重试） |
| 1072～1085 | **`calculateBlockNum`（解扰块数算法）** |
| 1087～1164 | **`saveImg` / `stitchImg` / `copyImageBlock`（解扰实现，基于 `WritableRaster`）** |
| 1197～1260 | `requestAlbum`（`/album`，AES 解密响应） |
| 1253～1450 | `search`（`/search`）、`/chapter`、`/chapter_view_template` |
| 1439～1475 | `getScrambleId(chapterId)`（解析 `var scramble_id = `） |
| 1440～1520 | **`buildHeaders`（tokenparam/token 签名）+ `decryptData`（AES 解密）** |
| 1183～1189 | `buildAlbumFolderName`（本子文件夹命名规则） |

**B站视频**

| 文件 | 行数 | 作用 |
|---|---|---|
| `.../features/bilibili/service/BilibiliService.java` | 604 | B站接口：WBI 签名、视频详情、playurl、ticket、下载 |
| `.../features/bilibili/service/BilibiliVideoDownloadService.java` | 294 | 下载任务（内存任务表、`bvid_cid` 去重、`.downloading` 中转、快照） |
| `.../features/bilibili/service/BilibiliVideoService.java` | 218 | 视频列表/筛选/排序/分页、增删 |
| `.../features/bilibili/service/BilibiliVideoSqliteServiceImpl.java` | 153 | `t_bilibili_video` 的读写 |
| `.../features/bilibili/support/BilibiliIdConverter.java` / `BilibiliSidUtil.java` | 89+ | BV↔AV 互转、URL 解析出 id |
| `.../features/bilibili/client/model/bilibili/*.java` | ~400 | `VideoDetail`/`PlayUrlInfo`（只有 durl！）/`BilibiliBaseResp` |
| `.../features/bilibili/persistence/entity/BilibiliVideoSqlite.java` | ~105 | 表字段 |
| `src/main/resources/config/bilibili.properties` | — | cookie 等设置项 |

关键行号锚点（`BilibiliService.java`）：49（nav/WBI key）、94～120（`getVideoDetail`）、129～170（`getPlayUrlInfo`，注意 `fnval=0`、`fnval=1024` 被注释）、467～530（GenWebTicket）、532～640（WBI 签名与 mixinKey 缓存）。

### 2.2 前端（只作为 UI/交互参考，不移植代码）

`webui/src/views/jmcomic/*`（约 5200 行 Vue）与 `webui/src/views/bilibili-video/*`（约 2075 行 Vue）。**用途**：确定每个页面上有哪些按钮、字段、筛选条件、空态/错误态。UI 要原生重写，但功能点要对齐。

### 2.3 可以直接丢弃

`features/jmcomic/handler/JmcomicHandler.java`、`features/bilibili/handler/*`、`features/bilibili/job/BilibiliLiveJob.java`、`features/bilibili/service/BilibiliSubscribe*`、`*PushService.java`、`infrastructure/web/**`（拦截器/WebSocket）、`administration/**`、`configuration/**`（配置管理页）、`features/chatrecord|wordstrip|pixiv|searchimg` 等。合计约 1900+ 行无需移植。

### 2.4 已核实事项（2026-10-06，依据当前工作区源码）

1. 搜索历史通过 `KvStoreService` 持久化，`KvEntry` 映射到 `t_dictionary`，key 为 `jm.online_search.history`。应保留数量上限、相邻同条件刷新时间和删除语义；Android 存储方案仍需落实。
2. `getChapterPath` 优先使用 `Series.title`，为空则取 `Series.name`，再为空则取 `Series.id`；不能假设目录必然是 `<序号>_<标题>`。图片文件名沿用接口原始文件名；解扰算法输入使用**去扩展名后的文件名**。
3. `getScrambleId` 请求并解析 `/chapter_view_template`，异常时返回固定值 **`220980`**，该方法没有静态兜底表查询。兜底是否仍有效需用异常用例验证。

---

## 3. 架构：模块划分与关键接口

### 3.1 模块建议

```
app/                     壳：开屏选择、导航、设置、下载中心、权限
core/
  network/               OkHttp 封装、UA/Referer、重试、代理设置
  database/              Room：Entities / Dao / Migration
  storage/               本地目录规划、SAF/MediaStore 导出、文件命名与清洗
  download/              下载引擎（Range 续传、并发、进度、取消、重试）
  taskqueue/             业务级任务队列（对齐 JmTaskQueue 语义）
  codec/                 ImageCodec（解码/解扰/编码）、ArchiveWriter（zip）、PdfWriter
feature-jm/              JM 接口客户端 + 仓库 + 用例
feature-bili/            B站接口客户端 + 仓库 + 用例
ui-jm/ ui-bili/ ui-common/
```

### 3.2 必须抽象出来的接口（这是移植成本和 DASH 扩展点的关键）

```kotlin
// 图片处理：把 JVM 的 ImageIO/AWT 换掉
interface ImageCodec {
    /** 读图片尺寸/格式（替代 ImageIO.read + ImageReader） */
    suspend fun readMeta(file: File): ImageMeta
    /** 解码 + 按块数解扰 + 重新编码（替代 saveImg/stitchImg） */
    suspend fun descrambleAndSave(srcTmp: File, dst: File, blockNum: Int, format: OutFormat)
    /** 转成 JPEG 字节（PDF 内嵌用：PDF 不支持 WebP） */
    suspend fun toJpeg(file: File, quality: Int): ByteArray
}

// 打包：zip（password == null/空 表示不加密，对应用户点"不设密码"）
interface ArchiveWriter { suspend fun zipDir(dir: File, out: File, password: CharArray?) }

// PDF：必须实现（章节书签 + 可选密码 + 权限；password == null/空 表示不加密）
interface PdfWriter { suspend fun albumToPdf(albumDir: File, out: File, password: String?) }

// 视频：现在是 durl 单文件；DASH 扩展点
sealed interface VideoStream {
    data class Progressive(val url: String) : VideoStream          // 现阶段的 durl
    data class Dash(val videoUrls: List<String>, val audioUrls: List<String>) : VideoStream // 预留
}
interface BiliStreamResolver { suspend fun resolve(bvid: String, avid: Long?, cid: Long): VideoStream }
/** 只被 Dash 分支使用；Progressive 分支直接落盘。预留实现：MediaMuxer */
interface StreamMuxer { suspend fun mux(video: File, audio: File, out: File) }
```

> **DASH 扩展点的硬性要求**：下载任务、进度、存储、播放全部只依赖 `VideoStream`/`BiliStreamResolver`，**不允许**在下载流程里硬编码 `getDurlFirst()`。将来只需新增一个 `DashResolver` + `MediaMuxerStreamMuxer`，其余代码不动。

### 3.3 并发/任务模型

参考实现的语义（要保留）：

- JM 任务队列 `JmTaskQueue`：任务 id、排队/执行中/成功/失败/取消、`jm.operation.parallel.enabled=false`（默认所有 JM 操作串行）、单本下载内按 `jm.download.threads` 分片并发、进度上报、"重试数不变则终止"的防死循环。
- B站下载：内存任务表，key = `bvid_cid`，同 key 只允许一个进行中任务；已完成任务只保留最近 20 条；**"是否已下载"始终以磁盘文件是否存在为准**（不是以任务状态为准）；下载中用 `<file>.downloading` 中转，完成才改名。
- 安卓侧必须这样做：`WorkManager`（可被系统调度、进程被杀后可恢复）+ 前台服务（长下载常驻通知）+ 协程 `Flow` 上报进度。**任务状态必须持久化**（用户已确认，参考实现只在内存里是它服务端场景的取舍，手机上不适用）。

---

## 4. 数据模型（Room）

直接对应当前 SQLite 表（表名常量见 `infrastructure/persistence/DataBaseConst.java`）。字段以下面为准（来自各 `*Sqlite` 实体类）。

### 4.1 `t_jm_album`（对应 `JmAlbumSqlite`，主键 `id` = JM 号，非自增）

`id: Long(PK)`、`name`、`albumFolderName`、`images`、`addTime`、`description`、`totalViews`、`likes`、`series`、`seriesId`、`commentTotal`、`author`、`tags`、`works`、`actors`、`relatedList`、`liked: Boolean?`、`isFavorite: Boolean?`、`collected: Boolean?`、`isAids: Boolean?`、`price`、`purchased`、`raw`、`createTime`、`modifyTime`

### 4.2 `t_jm_chapter_image`（对应 `JmChapterImageSqlite`）

`id: Long(PK,自增)`、`albumId`、`chapterId`、`chapterSort`、`chapterTitle`、`chapterName`、`chapterAddTime`、`seriesId`、`liked: Boolean?`、`isFavorite: Boolean?`、`imageFile`、`imageSort`

> 参考实现对该表有专门的自定义查询（`src/main/resources/mapper/features/jmcomic/JmChapterImageSqliteMapper.xml`：`selectChapterList`、`selectImages` 等），移植时照抄 SQL 语义。

### 4.3 `t_jm_favorite`（收藏夹）/ `t_jm_favorite_album`（关联）

- `t_jm_favorite`：`id`、`name`（**唯一**）、`sortOrder`、`createTime`、`modifyTime`
- `t_jm_favorite_album`：`id`、`favoriteId`、`albumId`、`createTime`；唯一索引 `(favorite_id, album_id)`
- 默认收藏夹名：`默认收藏夹`

### 4.4 `t_bilibili_video`（对应 `BilibiliVideoSqlite`）

`id: Long(PK,自增)`、`bvid`、`avid: Long?`、`cid: Long?`、`title`、`tag`、`desc`（列名是保留字，参考实现用 `` `desc` ``）、`pic`、`duration: Long?`、`ownerMid: Long?`、`ownerName`、`ownerFace`、`videoDetailRaw`、`playUrlRaw`、`pubdate: Long?`、`ctime: Long?`、`createTime`、`updateTime`
唯一索引：`(bvid, cid)`

### 4.5 安卓侧新增：持久化下载任务（**已确认必须做**，进度到章节 + 具体图片）

参考实现的任务只在内存（重启即清空），手机上必须落库，否则杀进程后进度全丢。建议两张表：

**`t_download_task`**（任务主表）

| 字段 | 说明 |
|---|---|
| `id` | 自增主键 |
| `type` | `JM_ALBUM` / `BILI_VIDEO` |
| `bizKey` | JM：aid；B站：`bvid_cid`（**同 bizKey 只允许一个进行中任务**，照抄参考实现的去重规则） |
| `status` | `QUEUED` / `RUNNING` / `PAUSED` / `SUCCESS` / `FAIL` / `CANCELLED` |
| `totalChapters` / `doneChapters` | 章节级进度 |
| `totalImages` / `doneImages` | 图片级进度（B站视频用 `totalBytes` / `downloadedBytes` 表达） |
| `stageText` | 当前阶段文案（如"下载漫画图片"），对应参考实现的进度阶段上报 |
| `message` | 失败原因 |
| `payload` | JSON：本次任务的参数快照（aid、章节 id 列表等），用于恢复时重建上下文 |
| `createTime` / `startTime` / `endTime` / `updateTime` | 时间戳 |
| `retryCount` | 重试次数（配合参考实现的指数退避） |

**`t_download_task_chapter`**（JM 专用章节明细，用于"进度到章节"）

`id`、`taskId`、`chapterId`、`chapterTitle`、`sort`、`status`、`totalImages`、`doneImages`、`message`、`updateTime`
唯一索引 `(taskId, chapterId)`

**"具体图片"粒度怎么实现（重要设计取舍）**

- **不建"一张图一行"的表**：一本几百章 × 每章几十张图，会写出几万行记录，收益极低。
- **每章的 `doneImages/totalImages` 计数器 + 磁盘文件为准**：
  - 断点续传的判定 = 「该图片文件是否已存在于本地」——与参考实现 `downloadChapter` 里 `.filter(e -> !e.getImgFile().exists())` 的行为完全一致；
  - 页面上"第 3 章 12/45"这种图片级进度 = 该章的计数器（恢复时按磁盘重算，照抄参考实现 `reporter.chapterImages(total, 已存在数)` 的做法，保证进度不回退、不重复累加）。
- 如果将来确实要"单张图的下载状态/失败原因"，再加一张 `t_download_task_image` 只记录**失败/待重试**的图片（稀疏表），不要记录成功的。
- 图片级粒度的断点续传验收：下载到一半杀进程 → 重启后继续，已下好的图片**不重新下载**（可用网络流量或文件 mtime 验证）。

- 设置按 Global / JM / Bili 隔离持久化，继承与安全存储规则见 §9.2；附录 B 仅作内部配置映射。
- 新增阅读与播放位置持久化模型：漫画以 albumId/chapterId/imageFile/页内位置定位，视频以 bvid/cid/播放毫秒位置定位；更新节流并在离开页面时保存，文件缺失时保留位置并提示补下载。

---

## 5. 网络契约

### 5.1 JM 漫画接口

- **域名**：`jm.api_domain`（默认 `www.cdnbea.net`，见 `jm.properties`）；图片域名 `IMAGE_DOMAIN = cdn-msp2.jmapiproxy2.cc`（`JmcomicService` 第 89 行）。都必须做成可配置 + 可换域名。
- **UA**：固定 Chrome UA（第 91 行），必须带上。
- **签名（每次请求都要）**：`ts = 当前时间戳`；`tokenparam = "$ts,2.0.13"`；`token = md5(ts + secret)`。header 名是 `token`、`tokenparam`、`user-agent`（见 `headerParam()`，第 1485～1494 行）；`secret` 有两个：`18comicAPP`（第 1488 行，普通接口）与 `18comicAPPContent`（第 1444 行，内容类接口，如 `/chapter_view_template`）。具体哪个接口用哪个 secret **以 1440～1520 行为准**。
  **注意：签名只依赖时间戳，不依赖登录态**，所以本 App 不需要 JM 账号。
- **响应解密**：响应 JSON 的 `data` 字段是密文；`key = md5(ts + "185Hcomic3PAPP7R")` 得到的 **32 字符 hex 串，取其 ASCII 字节（32 字节）当 AES 密钥**——即 AES-256，**不是 md5 摘要的前 16 字节**（第 1499～1504 行，写错会导致解密失败）；`AES/ECB/PKCS5Padding` 解密（第 1496～1520 行）。
- **接口清单**：`POST/GET https://{domain}/album`、`/search`、`/chapter`、`/chapter_view_template`（第 1200、1264、1423、1441 行）。`/login`（第 178 行）**本 App 不实现**。参数与响应结构以源码为准，响应模型对应 `client/model/*`。
- **图片 URL 规则**（第 1479、1482 行）：封面 `https://{cdn}/media/albums/{aid}.jpg`；章节图 `https://{cdn}/media/photos/{chapterId}/{filename}`。
- **重试**：`jm.request.album/search/chapter.retry` 是重试次数，指数退避（1s/2s/4s…），重试期间**沿用第一次请求的签名时间戳**（`jm.properties` 注释里写明了，照抄）。
- JM 模式设置里要暴露：**备选 API 域名**（不需要账号密码）、下载线程数、是否并行、zip/pdf 密码、本子名长度上限、各接口重试次数。

### 5.2 B站接口

- **接口**：
  - `GET https://api.bilibili.com/x/web-interface/nav` —— 取 WBI 的 `img_key`/`sub_key`（第 49、616 行）
  - `GET https://api.bilibili.com/x/web-interface/wbi/view/detail` —— 视频详情（第 102 行）
  - `GET https://api.bilibili.com/x/player/wbi/playurl` —— 播放地址（第 151 行）；请求带 `fnver=0`、**没有设置 `fnval`**（`fnval=1024` 那行在源码里被注释掉了，见第 142、149 行），所以返回的是 progressive 的 `durl`；下载只取 `durl` 第一个链接（`PlayUrlInfo` 里也只建模了 `durl`，没有 DASH 字段）
  - `POST https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket` —— 风控 ticket（第 467 行）
- **WBI 签名**：`img_key+sub_key` → 按固定混淆表重排 → `mixinKey`（缓存，第 589～602 行）；请求参数加 `wts` 后按 key 排序拼 query，`w_rid = md5(query + mixinKey)`。**必须逐行对齐**第 532～640 行。
- **Cookie**：`SESSDATA` + `bili_jct`（`bilibili.properties`），另有自动获取的 `ticket`；请求头需带 `Referer: https://www.bilibili.com/`（第 64 行）。**这些放在「B站模式的设置页」里让用户填写**（不做首次引导，见 §9）。
- **BV↔AV 与 URL 解析**：`BilibiliIdConverter`、`BilibiliSidUtil`；应用内要支持粘贴完整 bilibili 链接（含 `b23.tv` 短链？参考实现的解析正则见 `BilibiliService` 第 78 行，需确认短链是否需要跟随跳转）。
- **下载**：直接把 durl 的 URL 落地为 `.mp4`（`BilibiliVideoDownloadService` 第 174～219 行）。
- **视频时长限制**：`bilibili.download_video.duration_limit`（默认 600 秒）——照抄为设置项。

---

## 6. 算法移植清单（逐条：源位置 → 安卓实现要点）

| # | 算法 | 源位置 | 安卓实现要点 |
|---|---|---|---|
| A1 | JM 请求签名 `tokenparam/token` | JmcomicService 1440～1520 | 纯 MD5，`MessageDigest`，无坑 |
| A2 | JM 响应 AES/ECB 解密 | 1496～1520 | `javax.crypto.Cipher` 安卓可直接用；注意 key = `md5(ts+secret)` 的 **32 字符十六进制字符串的 ASCII 字节（32 字节，AES-256）**，与 §5.1 一致 |
| A3 | 解扰块数 `calculateBlockNum` | 1072～1085 | 规则：`chapterId < scrambleId → 0`；`< 268850 → 10`；否则 `x = chapterId<421926 ? 10 : 8`，`blockNum = (md5(chapterId+filename) 末位字符转 int) % x * 2 + 2`。**注意 `char % x` 用的是字符码点取模**，别改成 hex 解析 |
| A4 | scrambleId 获取 | 1439～1475 | `GET /chapter_view_template`，正则取 `var scramble_id = `；异常/取不到时的兜底行为要照抄 |
| A5 | 图片解扰 `stitchImg` | 1103～1164 | 语义：把图**按垂直方向分成 blockNum 块**，第 0 块吸收 `height % blockNum` 的余数，然后**块顺序倒置**写回。安卓建议：`Bitmap` + `Canvas.drawBitmap(src, srcRect, dstRect, paint)` 逐块拷贝（比逐像素快几个数量级）；也可 `copyPixelsToBuffer` 整行搬运 |
| A6 | 图片落盘 `saveImg` | 1087～1101 | 参考实现**无论是否解扰都重新编码成 webp**；**本 App 已确认改为：`blockNum == 0` 时直接把原文件落盘（不重编码）**，只有 `blockNum > 0` 才 解码→解扰→编码（安卓用 `Bitmap.compress`，格式建议 WEBP_LOSSLESS；若要进一步省 CPU/流量也可保持原格式）。注意：非 webp 原图（如 jpg）本来就不解扰，直接落盘即可 |
| A7 | 只对 webp 解扰 | `downloadChapter` 948～954 | `ext` 不是 `webp` 时 `blockNum=0`，照抄 |
| A8 | zip 打包（AES） | 543～590 | 参考用 `zip4j` + `EncryptionMethod.AES`，密码取 `jm.password.zip`。安卓可直接用 `net.lingala.zip4j:zip4j`（纯 Java）；**密码来自导出密码框**（默认预填配置值），用户选 **[不设密码]** 或留空时**不加密**（`ZipFile` 不传密码）；若不想引第三方库也可自己用 `javax.crypto` 写 AES-ZIP（工作量大，不推荐） |
| A9 | **PDF 生成** | 592～760 | 见第 7 节（本任务最大难点） |
| A10 | 本子文件夹命名 | 1183～1189 | `<清洗后的标题>_JM<aid>`；按**字节数**截断且按字符边界，非法字符 `\ / : * ? " < > |` 与控制字符替换为 `-`；安卓侧改用应用私有目录，风险下降但仍建议保留清洗逻辑（导出到外部存储时 Windows 兼容名更安全） |
| A11 | 章节/图片排序 | 746～790 | 从名字里抽数字排序（`提取序号`），照抄；注意名里没数字时的兜底 |
| A12 | B站 WBI 签名 | BilibiliService 532～640 | `HmacSHA256`/`MD5` 均可用；mixinKey 要缓存并有过期时间 |
| A13 | BV↔AV 转换 | `BilibiliIdConverter` | 位运算 + 固定字符表，纯逻辑，照抄 |
| A14 | 文件名清洗 `sanitizeFileName` | `shared/util/FileUtil.java` 197 行起 | 移植；安卓上还要额外处理 SAF/MediaStore 的显示名 |

---

## 7. JM PDF 生成（本任务最大难点，单独说明）

**参考实现的行为（必须对齐）**：

1. 每张图片一页，页面尺寸 = **图片像素尺寸**（`new PDRectangle(image.getWidth(), image.getHeight())`）。
2. 图片按章节文件夹排序；每个章节生成一个**书签**（outline），指向该章第一页；根书签标题 `目录`；`PageMode = USE_OUTLINES`。
3. **加密**：`StandardProtectionPolicy(password, password, permission)`，`encryptionKeyLength = 128`；权限：`canModify=false`、`canExtractContent=false`、`canExtractForAccessibility=false`。密码来自**导出时的密码框**（默认预填 `jm.password.pdf`）；用户点 **[不设密码]** 或留空时**不加任何保护**（不调用 `protect()`）。
4. 图片用 `LosslessFactory` 内嵌（无损 = Flate 压缩），文件会明显偏大。

**安卓实现路径（必须先在 P0 做技术验证）**：

| 方案 | 说明 | 风险 |
|---|---|---|
| **B1. `com.tom-roush:pdfbox-android`** | PDFBox 的安卓移植，`LosslessFactory.createFromImage(Bitmap)`；有 `StandardProtectionPolicy` | 需验证：**AES-128 加密 + 书签 + `PDPageContentStream`** 三件套在安卓上是否都可用；版本较老（基于 PDFBox 2.0.x），大页面可能有兼容问题 |
| **B2. 手写 PDF 写入器** | 自己拼 PDF 对象：页树、每页一个 XObject（JPEG 走 `DCTDecode` 直接内嵌，无需重新编码）、`Outlines` 目录树、标准安全字典（RC4-128 或 AES-128，用 `javax.crypto`） | 工作量大（约 400～700 行）、要自己保证 PDF 结构正确（用 Adobe/Chrome/系统 PDF 阅读器验收）；但**体积和速度都优于 B1**，且不引第三方库 |
| B3. 其他 PDF 库 | 例如 iText（AGPL，商用需授权）、pdfium（只能渲染不能生成） | 授权/能力不满足 |

**建议**：P0 先花 0.5～1 天做 B1 的 spike（生成一个带密码 + 书签的 PDF，用系统阅读器和 Chrome 打开验证）。**如果 B1 的加密/书签任一不满足，就走 B2**，并在实现时用"DCTDecode 直嵌 JPEG"替代 `LosslessFactory`（体积可以从几百 MB 降到几十 MB）。

**其它约束**：

- PDF 单页尺寸上限 14400×14400（PDF 规范），JM 长图页高接近上限时要注意；同时很多阅读器对超高页面体验很差 —— 参考实现就是这样，先对齐；如需优化（例如按高度切片）属于**行为差异**，要单独确认。
- WebP 不能直接内嵌 PDF，必须转 JPEG（`Bitmap.compress(JPEG)`）；有损转换会掉画质，考虑质量 90+。
- 生成大 PDF 时必须**流式写**（先写文件，不要在内存里拼 ByteArray），并放在前台服务里跑（可能几分钟）。

---

## 8. Android 平台必须做的事

| 项 | 要点 |
|---|---|
| 存储 | 库文件放应用私有目录（`filesDir`/`getExternalFilesDir`），避免分区存储限制；**导出** zip/pdf 走 MediaStore（Downloads）或 SAF（`ACTION_CREATE_DOCUMENT`）；JM 库可能几 GB，要提供"占用统计 + 清理" |
| 下载 | 长下载用**前台服务 + 常驻通知**（进度、暂停/取消）；用 WorkManager 做可延后/可恢复的队列；请求忽略电池优化；Wi-Fi/移动网络策略让用户选 |
| 续传 | 图片按"文件是否存在"跳过（照抄参考实现）；单文件 mp4 用 HTTP `Range` 续传（参考实现的 `BilibiliVideoStreamController` 已证明服务端支持 Range，但那是对服务端；**对 B站 CDN 是否支持 Range 需要实测**，不支持就整文件重下） |
| 播放 | Media3/ExoPlayer，播放本地 mp4；全屏、屏幕常亮、后台播放按需；**预留 DASH 分支**（Media3 也支持 DASH，但我们的 DASH 是要落盘，不是在线播） |
| 阅读器 | 原生图片阅读器：翻页、双指缩放、双击缩放、左右/上下翻页模式、章节跳转、预加载相邻页；注意**超长图 + ARGB_8888 会 OOM**（400×12000 ≈ 18MB），建议 RGB_565 + 串行处理 + 及时 `recycle()`，必要时用 `BitmapRegionDecoder` 分块 |
| 网络 | 统一 UA/Referer；JM 与 B站都需要**可配置代理**（JM 在部分网络下需要）；HTTPS 证书异常、超时重试（照抄参考实现的指数退避） |
| 权限 | 通知（Android 13+ 运行时权限）、前台服务（`FOREGROUND_SERVICE_DATA_SYNC`）、网络；导出到相册才需要媒体权限 |
| 其它 | 返回键、深链（分享 bilibili 链接给 App）、崩溃日志本地留存、备份/恢复（可选：导出数据库 + 库目录） |

---

## 9. UI、交互与设置设计（2026-10-06 已确认）

**设计方向：内容优先的双模式媒体库，Kotlin + Jetpack Compose + Material 3。** 本节替代此前页面清单；UI 与交互方案已获用户确认。JM、B站分别拥有独立设置，保留统一的全局 App 设置入口。所有用户界面只展示易懂的名称、说明、当前值及操作，**不得展示内部配置 key、数据库字段名或原始配置表单**。附录 B 的 key 仅供开发映射。

### 页面与核心流程

1. **开屏选择**：漫画 / 视频两个入口，不做账号、权限或 Cookie 首次引导。可记住上次模式；全局设置可改为每次选择、上次使用、固定漫画或固定视频。
2. **主框架**：顶栏模式名“漫画 ▾ / 视频 ▾”打开底部切换面板。漫画底部导航为 **书库 / 发现 / 收藏 / 任务**；视频使用顶部 **视频库 / 任务** 标签，不为凑底栏增加页面。各模式保留独立返回栈、筛选、排序和滚动位置。详情页有返回按钮，阅读器/全屏播放器隐藏主导航。模式首页顶栏齿轮直接进入当前模式设置；该页固定提供“全局 App 设置”入口。
3. **设置**：JM 设置、B站视频设置、全局 App 设置三个独立页面，分组和字段详见 §9.2～§9.5；不做账号型“我的”页面。
4. **书库**：有阅读记录时显示紧凑“继续阅读”卡片，其下为全部/已下载/未下载筛选、排序与封面网格。默认三列，可选两列或列表；标题最多两行，状态优先展示下载章节数，避免堆叠徽标。点击书籍进入详情，继续阅读卡片直接打开保存位置。长按进入多选，菜单同时提供“选择”；多选操作含下载、收藏、导出和删除。保留 ID/名称/作者/标签/收藏筛选、排序、分页能力，分页在 UI 表现为连续加载及失败重试。
5. **发现（在线搜索）**：搜索框明确写“搜索在线漫画”，区别于“搜索本地书库”；空态显示搜索历史，不添加无数据支撑的推荐流。支持排序、分页、历史删除、入库、入库并下载。结果显示“已入库”标记，点击进入详情。下载自动入库；添加到收藏夹时尚未入库则一并入库，操作保持幂等。
6. **收藏**：收藏夹列表支持增删改、排序，夹内复用书库布局和批量操作；收藏面板可多选收藏夹并现场新建。移出收藏夹不删除书库记录和本地文件；删除收藏夹仅删除夹及关联，确认框写清影响。
7. **漫画详情（核心中转页）**：
   - 显示封面、标题、JM 号、作者、标签、章节数、收藏状态、下载情况与本地占用；简介默认收起。
   - **本地优先**显示已缓存章节、排序与图片清单，在线刷新增强数据；首次无缓存则展示加载/错误重试。章节来自 `/album` 的 `Album.series`，图片清单来自 `/chapter` 的 `Chapter.images`，应持久保存，不仅从成功下载的图片反推章节。离线/刷新失败不阻断本地阅读。
   - 主按钮按状态显示“继续阅读 / 开始阅读 / 下载首章”；上次位置缺图时说明原因并提供补下载，不静默跳页或进入空白页。
   - 次操作为下载、收藏、更多。下载面板默认勾选未完成章节，支持全部未下载和自选章节；重复提交显示已有任务。更多承接 ZIP/PDF 导出、删除文件和删除记录。
   - 章节支持正序/倒序，每行显示标题与“已下载 / 部分下载 x/y / 未下载”。点章节进入阅读；未下载时显示下载面板，部分下载可读已有图片并明确缺图。行尾菜单或长按可重下、删除章节文件。
   - **导出密码框**：ZIP/PDF 均先弹框，默认预填各自设置密码，支持显示/隐藏；保留 **[确认] / [不设密码]**，留空等同不加密。取消/返回不创建任务。导出完成明确提示“已加密 / 未加密”，提供打开文件和查看位置（目标提供者支持时），不支持定位则显示可用的打开操作与位置说明。
   - 导出进度进入当前模式任务页，不强制跳转；用户可继续浏览。缺图时先列明缺失章节/页数，提供补下载后再导出；首版不静默导出残缺内容。取消清理未完成输出；导出与删除/重下相同文件须协调，避免输出损坏。
8. **阅读器**：默认纵向连续，另支持横向分页并记住选择。单击显示/隐藏控制栏，双指缩放、双击放大/还原；控制栏含返回、章节名、章节选择、进度及阅读设置。支持章节内跳页、上一章/下一章；下一章未下载时显示“下载下一章”，不未经操作自动下载。阅读位置自动持久化（本子、章节、页/图片标识、页内位置），重启后可继续；长图占位稳定，缺图显示具体页码及重试。阅读设置含亮度、背景、阅读方向和屏幕常亮，与 JM 设置使用同一份偏好。
9. **JM 任务**：进行中 / 已完成；进行中包含排队、执行、暂停、等待网络及待处理失败，明确区分状态，不将失败算作正在运行。展示章节和图片计数、阶段、失败原因，提供暂停/恢复/取消/重试；导出不支持中途续做时仅提供取消及重新生成。重试处理未完成部分，取消不自动复活；完成区可清空历史。角标仅统计排队/执行任务，失败另用提示点；提交后短提示，不强制跳任务页。
10. **视频库**：横向封面列表，显示标题、UP、时长和下载状态，保留搜索/筛选/排序/分页/批量删除。已下载点击直接播放，未下载进入简洁详情；行尾菜单提供刷新资料、删除文件、删除记录。默认最近添加在前。
11. **添加视频**：右下角“＋ 添加视频”打开面板，粘贴链接/BV/AV，解析后展示封面、标题、时长；多 P 视频提供分集选择，按选定 CID 入库/下载。按钮为加入视频库、加入并下载。仅用户主动粘贴或系统分享进入时读取内容，不主动扫描剪贴板；解析失败保留输入。短链解析和多段 durl 的完整性需在 P5 验证，不得只下载首段即报完成。
12. **视频任务**：与 JM 共用状态文案和控件规则，数据按模式隔离；显示字节进度、暂停/恢复/取消/重试和失败原因，完成历史保留最近 20 条。列表中的已下载状态由有效本地文件决定，不由任务历史是否保留决定。
13. **播放页**：Media3 本地播放，点击显示控制，拖动进度、双击左右快退/快进、倍速与全屏；自动保存播放位置。首版不加入后台播放或画中画开关，避免展示尚未实现能力。

### 9.1 视觉与通用交互规范

- 浅色用柔和灰白背景，深色用近黑灰；卡片用表面色区分，少阴影。漫画低饱和紫、视频低饱和蓝，强调色主要用于选中、主按钮和进度；统一字体、图标与间距规则。
- 卡片圆角约 12～16dp、内容间距以 8dp 为基准；封面主导，辅助信息降权，页面只突出一个主操作。小图标的点击区域不小于 48dp，状态同时用文字表达，不只依赖颜色。
- 短而自然的过渡，遵循系统减少动画设置；下载更新不令卡片跳动。适配字体放大、深色模式、横屏及系统返回手势；大屏可将主导航转侧栏，保持信息架构不变。
- 筛选生效后显示条件标签或数量，支持清除；返回保留列表位置。底部面板承接筛选、章节选择与收藏等短流程，复杂设置进入独立页面，不堆叠多层弹窗。
- 不使用滑动即删除。删除确认明确区分仅移出书库、仅删除本地文件、删除记录及本地文件，显示数量与空间；与活跃任务冲突时先说明并停止相关任务。清理缓存不删除漫画、视频及导出成品。
- 加载、无内容、搜索无结果、离线、权限拒绝、失败均有对应空态和下一步动作；失败重试不清空已有数据。通知权限在用户首次需要后台任务时按场景说明，不阻塞普通浏览。
- ViewModel 暴露 UI 状态，页面提交事件；旋转/重建不重复创建任务。导航临时状态与阅读/播放位置、持久任务分开保存。

### 9.2 设置入口、页面模板与作用域

**用户看到的路径**：漫画 → 齿轮 → JM 设置；视频 → 齿轮 → B站视频设置；两个模式设置页顶部都有同一个“全局 App 设置”入口。全局页可跳转到两种模式设置，但不复制它们的字段。返回时回到来路模式。

**每行模板**：名称 + 可选一句说明 + 当前值/状态 + 控件。例：“同时下载图片数 · 自动”“下载时长上限 · 10 分钟”“压缩包默认密码 · 已设置”。不显示 `jm.download.threads` 等 key，也不显示 JSON、token 原文或内部枚举。密码摘要只显示已设置/未设置，错误用可理解文案，不输出密钥。

- 开关和单选即时保存；文本、数值、凭据及线路编辑使用“取消 / 保存”，验证通过才提交，失败保留原值。带未保存内容返回时允许放弃或继续编辑。
- 普通偏好分成 Global / JM / Bili 三个独立存储作用域（可为独立 DataStore 或等价隔离仓库）；敏感凭据另行安全存储，不写入日志、普通任务参数或默认备份。迁移保留用户值。
- 模式网络设置采用“跟随全局 / 本模式自定义”。UI 展示有效值，如“跟随全局：仅 Wi-Fi”；自定义只作用于本模式，切回跟随后保留自定义草稿但不生效。不要把全局默认复制成两份，导致全局修改失效。
- 外观和阅读偏好立即生效；线程数、超时、线路、时长上限等对新任务或下次重试生效，不中途替换运行任务配置。改为更严格的下载网络策略时，当前传输在安全检查点暂停；恢复前重新检查条件。
- 各模式“恢复默认设置”只重置自身普通偏好与覆盖项，不影响另一模式、全局、书库和文件；访问凭据与导出密码通过单独的清除操作处理。全局恢复默认只影响全局值，提示跟随全局的模式也会变化。

### 9.3 JM 设置（独立）

| 分组 | 用户可见名称 | 控件 / 默认值 / 行为 |
|---|---|---|
| 阅读与显示 | 书库布局 | 三列封面（默认）/ 两列封面 / 列表；屏幕及字体过大时自适应减少列数 |
| 阅读与显示 | 默认阅读方式 | 纵向连续（默认）/ 横向分页；横向可选左到右或右到左，默认左到右 |
| 阅读与显示 | 阅读背景 | 跟随主题（默认）/ 浅色 / 深色 / 护眼暖色 |
| 阅读与显示 | 阅读亮度 | 跟随系统（默认）/ 自定义；只作用于阅读窗口，退出还原，不改系统亮度 |
| 阅读与显示 | 阅读时保持亮屏 | 默认开启，仅阅读器前台生效 |
| 下载 | 同时下载图片数 | 自动（默认）或 1～8；自动值结合设备内存限制，不能无界按 CPU 核数解码 |
| 下载 | 同时下载多本漫画 | 默认关闭；开启允许不同漫画任务并行，但仍受全局执行资源预算约束 |
| 下载 | 下载网络 | 跟随全局（默认）/ 仅 Wi-Fi / 允许移动网络 |
| 导出 | 压缩包默认密码 | 安全编辑框；迁移参考默认值 1234，列表不明文展示；允许清空，实际导出仍弹密码框 |
| 导出 | PDF 默认密码 | 与压缩包密码独立，同上；本次导出修改密码不自动改默认密码 |
| 搜索历史 | 保存搜索历史 | 默认开启；关闭后停止新增，已有历史保留，可单独清空 |
| 搜索历史 | 保留记录数量 | 默认 20，范围 1～100；减少数量保存时说明并清除超额最旧记录 |
| 搜索历史 | 清空搜索历史 | 显示数量并确认，不影响收藏和书库 |
| 网络与线路 | 接口线路 | 当前线路及备用列表；添加、编辑、排序、移除、测试连接；默认沿用参考域名，不宣称必然可用 |
| 网络与线路 | 图片线路 | 与接口线路分别维护，默认参考图片域名；连接测试应使用已知图片请求，无样本时明确只验证可达性 |
| 网络与线路 | 代理 / 请求超时 | 分别跟随全局或单独配置；保存不自动更改另一模式 |
| 高级 | 请求失败后重试 | 详情 / 搜索 / 章节三项分别设置，默认各 0，范围 0～5；0 显示“不自动重试”，不是请求次数为零 |
| 高级 | 文件夹名称长度 | 默认 150 字节，范围 32～240；说明主要用于文件兼容，修改只影响新建目录，不重命名已有下载 |
| 存储与维护 | 漫画占用 / 缓存清理 | 仅统计和清理 JM 作用域，已下载内容单列管理 |
| 存储与维护 | 恢复 JM 默认设置 | 遵循 §9.2 的隔离与敏感值保留规则 |

不添加 JM 登录、账号或用户体系。高级技术选项折叠展示；内部协议版本与签名参数保留实现扩展能力，不把原始 secret 配置暴露为普通设置列表。

### 9.4 B站视频设置（独立）

| 分组 | 用户可见名称 | 控件 / 默认值 / 行为 |
|---|---|---|
| 访问凭据 | B站访问凭据 | 未配置/已配置/待验证/验证失败；点击安全编辑，可“粘贴完整 Cookie”自动提取所需项，或填写“会话凭据”“请求校验值”；说明从已有浏览器会话获取，不要求在本 App 登录 |
| 访问凭据 | 检查可用性 | 用户主动发起请求；网络异常显示“暂时无法验证”，不要误报凭据失效；配置凭据不代表所有清晰度或内容均可访问 |
| 访问凭据 | 访问校验状态 | 风控票据的友好状态及最近刷新时间，不显示 ticket/token 原文；由应用管理，无常驻原始票据编辑框 |
| 访问凭据 | 清除访问凭据 | 单独确认，清除 Cookie 与缓存票据；不删除本地视频，后续请求使用未配置状态 |
| 下载 | 下载时长上限 | 默认 10 分钟，范围 1～1440 分钟；针对所选分 P 判断，超限在提交前说明，不静默跳过；多 P 显示逐项结果 |
| 下载 | 下载清晰度 | 只读“当前接口可用清晰度”；说明当前为兼容下载模式，不显示不可用的高清选择器；解析后展示实际返回清晰度 |
| 下载 | 下载网络 | 跟随全局（默认）/ 仅 Wi-Fi / 允许移动网络 |
| 播放 | 默认播放速度 | 默认 1.0×，可选 0.5/0.75/1.0/1.25/1.5/2.0×；播放器本次调整不自动改默认值 |
| 播放 | 播放时保持亮屏 | 默认开启，仅播放器前台且正在播放时生效 |
| 播放 | 清空播放记录 | 单独确认，仅清除播放位置，不删除视频文件；自动记忆播放位置为固定行为 |
| 网络 | 代理 / 请求超时 | 各自跟随全局或单独配置，与 JM 隔离 |
| 存储与维护 | 视频占用 / 缓存清理 | 只统计和清理 B站作用域，视频成品单列管理 |
| 存储与维护 | 恢复 B站默认设置 | 遵循 §9.2，不自动清除访问凭据 |

首版不展示 DASH 合流、后台播放、画中画等未实现功能的空开关；后续实现后再增加设置。

### 9.5 全局 App 设置（固定入口，可扩展）

| 分组 | 用户可见名称 | 控件 / 默认值 / 行为 |
|---|---|---|
| 外观 | 主题 | 跟随系统（默认）/ 浅色 / 深色；两种模式共用，保留各自强调色 |
| 启动 | 打开应用时 | 每次选择（默认）/ 上次使用 / 漫画 / 视频；与开屏“记住上次选择”共用同一状态 |
| 网络默认值 | 下载网络 | 默认仅 Wi-Fi；移动网络下展示等待原因和前往修改设置入口，不偷偷开始大下载 |
| 网络默认值 | 代理 | 默认不使用；可配置 HTTP 或 SOCKS 代理，填写地址、端口及可选认证；地址和端口验证通过才保存，认证值隐藏并安全保存 |
| 网络默认值 | 请求超时 | 默认 30 秒，范围 5～120 秒；说明为连接/单次读写等待上限，不是整个下载的总时长 |
| 存储 | 存储空间 | 分开显示漫画、视频、缓存、临时文件占用；私有库位置只读，不承诺任意迁移目录；用户外部导出文件仅在仍有访问权时统计 |
| 存储 | 清理缓存与临时文件 | 展示可释放空间，不选中正在使用的临时文件；下载成品在各模式单独删除 |
| 通知与后台 | 下载通知 | 显示系统授权状态，提供系统设置入口；系统权限状态不伪装为能直接控制的普通开关 |
| 通知与后台 | 后台运行帮助 | 根据实际受限状态说明并链接系统设置；不在首次启动强制索取权限或白名单 |
| 关于与维护 | 版本 / 开源许可 | 展示版本、构建信息及依赖许可 |
| 关于与维护 | 恢复全局默认设置 | 只恢复全局偏好；确认说明跟随全局的模式会受影响，不删除业务数据和文件 |

设置框架按稳定的分组和作用域注册入口，为未来语言、备份等能力留扩展点；**未实现的功能不展示空页面或占位开关**。ZIP/PDF 目标通过导出时的系统文件选择器确定，取消选择不开始生成；若记住上次位置，必须校验授权仍有效，失败时重新选择。

### 9.6 UI 与设置验收

- 漫画 → 视频 → 漫画返回原筛选和列表位置；系统返回按当前栈退回，阅读/播放位置重启后仍在。
- 飞行模式下从书库进入详情与已下载章节；加载失败不抹掉本地数据；继续阅读缺图时能补下载。
- 多选、章节下载、收藏、导出密码三路径、取消导出及删除文件/记录均能完成，重复点击不产生重复任务。
- JM/B站配置分别修改并重启后互不覆盖；全局值改变只影响跟随项，自定义项不变；恢复默认的范围符合 §9.2。
- 所有设置页、搜索结果、摘要、校验错误均无内部 key；敏感值默认隐藏，未配置与验证失败区分显示。
- 数值边界、无效代理、线路失败、未保存返回、Cookie 解析失败均不覆盖旧有效值；新任务应用新配置，运行中任务遵循生效规则。
- 大字体、深色模式、横屏、权限拒绝、任务失败和空库可操作；设置和任务相关页面具有可读标签与足够点击区域。

---
## 10. 分阶段实施计划

> 每阶段结束必须有**可运行的产物 + 验收记录**，不要一次性写完再测。

### P0 环境与基线（2～3 天）

- 建 **Kotlin + Jetpack Compose** 空工程、模块划分、DI、日志、CI（lint + unit test）、把本文件复制进工程；P0 固定兼容的构建工具与依赖版本。
- **spike 1**：JM 签名 + AES 解密（用源项目产出的黄金数据对比，见第 11 节）。
- **spike 2**：PDF 方案选型（B1 验证加密+书签；不通过就定 B2）。
- **spike 3**：解扰在 Android 上跑通（取一张真实 webp，Java 版与 Kotlin 版输出逐像素对比）。
- 验收：三个 spike 都有结论记录（写在工程 docs 里）。

### P1 基础设施（4～6 天）

- Room 建 5 张业务表 + **`t_download_task` / `t_download_task_chapter`（持久化任务，见 §4.5）** + 迁移方案；网络层；三作用域设置、安全凭据及继承规则（§9.2）；阅读/播放位置存储；目录规划；下载引擎（Range 续传 + 并发 + 取消）；前台服务 + 通知。
- 验收：能下载一个任意大文件到私有目录，断网/杀进程后能续传，通知显示进度；**杀掉进程再启动，任务列表和进度能从数据库恢复（RUNNING 任务按磁盘现状重算进度后继续）**。

### P2 JM 数据与浏览（4～6 天）

- JM 客户端（**搜索/本子详情/章节列表/章节图片列表**，不含登录）、本子与章节入库、收藏夹 CRUD、`本子库 / 在线搜索 / 收藏夹 / 漫画详情` 4 个页面（详情页要本地优先展示章节、在线刷新并标记本地已下载的章节）。
- 验收：搜到本子 → 入库 → 进详情页看到章节列表（已下载章节有标记）→ 收藏 → 重启 App 数据仍在；与参考实现同一 aid 的入库字段一致。

### P3 JM 下载与阅读闭环（原估 6～8 天，增加阅读器后需重估）

- 章节图片下载（并发、`.tmp` 中转、跳过已存在、防死循环重试）、解扰落盘（**`blockNum==0` 直接落原图**）、**章节级 + 图片级进度持久化**、取消、重试、任务中心页面。
- 同阶段实现 §9 第 8 条阅读器、进度记忆和继续阅读入口，不延至 P6；验证单章下载后即可离线阅读。
- 验收：
  - 下载一整本，图片数量/命名/内容与参考实现一致（抽样逐像素比对解扰结果）；
  - 下载中取消能停下；已下载的重复下载会跳过；
  - **下载到一半杀进程 → 重启后从断点继续，已完成图片不重下**；进度显示能精确到"第 N 章 x/y 张"。
  - `blockNum==0` 的图片文件与源文件字节一致（未被重新编码）。

### P4 JM 导出 zip / pdf（4～6 天，PDF 占大头）

- zip（AES 密码 / 不加密两种）；pdf（密码 / 不加密、书签、权限、页尺寸）；**导出前的密码框交互（默认填配置密码 + [确认] / [不设密码]）**。
- 验收：zip 用密码能解压、错误密码打不开、选"不设密码"时无密码可直接解压；PDF 用错误密码打不开、用正确密码能打开、书签按章节、权限（不可修改/不可复制）符合预期、选"不设密码"时无密码可打开；导出到 Downloads 能在系统文件管理器看到。

### P5 B站（4～6 天）

- 详情入库（WBI 签名）、列表/筛选/排序、分 P 选择及多段地址完整性处理、下载（`VideoStream.Progressive` 分支）、播放（Media3 + 位置记忆）、删除文件与记录、下载中心、B站设置。
- 验收：粘贴一个链接 → 入库 → 下载 → 播放；同 `bvid+cid` 不能重复下载；删除文件后状态回到"未下载"；**`BiliStreamResolver` 换成 Dash 实现时其余代码不改动**（用一个假的 Dash resolver 跑一遍单测证明扩展点成立）。

### P6 壳与体验（3～4 天）

- 开屏选择（不做首次引导）、模式切换、分模式设置页 + 通用设置页、**漫画详情页与阅读器的衔接打磨**、占用统计与清理、错误提示与空态。
- 验收：从开屏选 JM → 用 → 切到 B站 → 用 → 返回 JM 状态保留；逐条执行 §9.6 的 UI/设置验收，覆盖设置隔离、全局继承、不显示 key、恢复默认及配置生效时机；各页面无 ANR/崩溃。

### P7 验收与真机（3～5 天）

- 真机（低端机 + 高端机各一台）跑第 12 节验收清单；内存/流量/电量粗测；长时间下载稳定性。
- 交付：可安装 APK + 验收记录 + 已知问题清单。

**排期说明**：原 P0～P7 区间合计 30～44 个工作日；2026-10-06 已补充阅读/播放位置、完整 UI 与三作用域设置，原阶段天数仅作基线，不再作为完整范围承诺。P0 后结合阅读器、后台和 PDF 验证结果重估。

---

## 11. 测试策略

### 11.1 纯逻辑黄金用例（最高性价比）

源项目是普通 Maven 工程（**JDK 21**），这些算法都是纯函数，**在源项目里写一个临时 `main`/JUnit 打印期望值**即可（不需要联网）。
现成入口：`JmcomicService` 末尾（第 1522 行起）本来就有一个 `main(...)` + 若干 `testN()` 的临时测试方法，可以直接照这个套路加打印、跑 `mvn -q exec:java` 或 IDE 里直接运行。

| 用例 | 输入 | 期望输出 |
|---|---|---|
| `calculateBlockNum` | 若干 `(scrambleId, chapterId, filename)` | 用 Java 版打印，Kotlin 版断言一致（覆盖 268850 / 421926 两个阈值边界） |
| JM 签名 | 固定 `ts` | `token`、`tokenparam` 字符串完全一致 |
| AES 解密 | 固定 `ts` + 用同一密钥加密的密文 | 明文一致（**注意是 AES-256：32 字符 hex 串的 ASCII 字节做密钥**） |
| WBI 签名 | 固定 `img_key/sub_key/参数表/wts` | `w_rid` 一致 |
| BV↔AV | 已知 `BV1xx411c7mD` 等 | 与 Java 版一致 |
| 文件名清洗 | 含非法字符/超长/中文的标题 | 与 Java 版逐字节一致 |
| 解扰 | 一张真实 webp + 指定 blockNum | 与 Java 版输出**逐像素一致**（把 Java 版输出存成基准图） |

### 11.2 其它

- **网络契约**：MockWebServer 回放真实响应样本（把参考实现抓到的 JSON 存成 fixture），验证解析、AES 解密、分页字段。
- **数据库**：Room 迁移测试 + Dao 语义测试（分页/排序/唯一索引冲突）。
- **下载引擎**：本地起一个 HTTP 服务模拟 Range 分片、超时、断流。
- **UI**：关键路径的 instrumented test（开屏选择 → 列表 → 详情页 → 下载 → 阅读/播放），外加**导出密码框**三种路径（默认密码确认 / 改密码 / 不设密码）与**杀进程恢复**（下载中断后重启继续）。
- **持久化任务**：Dao 测试（同 `bizKey` 去重、RUNNING 任务恢复时按磁盘重算进度、章节计数器不回退）。
- **真机验收清单**（P7 逐条打勾）：见第 10 节各阶段"验收"，外加：弱网、切后台、来电打断、杀进程恢复、存储写满、超长图阅读、大 PDF 生成、密码错误提示、代理可用。

---

## 12. 风险与开放问题

**技术风险**

1. **PDF 是最大不确定项**：P0 spike 必须先验证 B1（PdfBox-Android）的加密+书签能力，否则切 B2（手写）。这会影响 1～2 周排期。
2. **超长图内存**：JM 图片很长，解码 + 解扰 + 编码需要整图在内存；必须 RGB_565 + 串行 + 及时回收，否则中低端机 OOM。
3. **JM 接口是逆向的 App API**：密钥/版本/域名随时可能变（参考实现把域名和版本都做成了配置），App 侧也要**把域名/版本/secret 留在可远程更新的位置**（例如设置项 + 内置多套备选）。
4. **B站 cookie/WBI/风控**：需要用户自己提供 cookie；`GenWebTicket` 是风控相关，行为可能变；durl 清晰度受限（用户已确认现阶段接受）。
5. **B站 CDN 是否支持 Range**：影响 mp4 断点续传，需实测；不支持就整文件重下。
6. **durl 兼容性**：部分视频可能只返回 DASH（`durl` 为空），此时需给出明确错误提示并依赖后续 DASH 实现。
7. **后台被杀**：国产 ROM 对前台服务/WorkManager 限制严格，需要引导用户加白名单。

**合规/分发风险（需用户知情）**

- JM 属成人内容，Google Play 不可能上架，实际是自签名侧载；参考实现里的逆向 secret 打进 APK 等于公开（可反编译）。
- 保留"仅个人使用"的范围，不要做分享/传播功能。

**用户已确认的决策（2026-10-05 确认，2026-10-06 补充技术栈）**

1. ~~zip/pdf 密码策略~~ → **已确认**：生成时弹**密码输入框**，输入框**默认填入设置里的密码**（`jm.password.zip` / `jm.password.pdf`），弹框提供 **[确认]** 与 **[不设密码]** 两个按钮（`不设密码` = 不加密）。实现要求见 §9 第 7 条。
2. ~~图片是否统一重编码~~ → **已确认**：`blockNum == 0` 时**直接落原图**（不重新编码），只有需要解扰时才解码→解扰→编码。
3. ~~下载任务是否持久化~~ → **已确认**：**必须持久化**（杀进程后可恢复），**进度粒度要到"章节 + 具体图片"**。实现要求见 §4.5、§10 P1/P3。
4. ~~是否需要库导入通道~~ → **已确认**：**不做导入通道**，手机上重新添加/下载即可。

**技术栈已确认 / 页面策略保留后续评估**

5. **UI 框架 + 开发语言**：用户于 **2026-10-06 已确认 Kotlin + Jetpack Compose**，不再作为开工前待决策项。
6. PDF 的页面尺寸：参考实现是"一张图一页、页面尺寸=图片像素尺寸"（JM 长图会出现 800×12000 这种超高页面，部分阅读器体验差）。**默认先照抄对齐**；是否改成按高度切片，等 P4 做完再评估（属于行为差异，需用户确认）。

### 技术栈：Kotlin + Jetpack Compose（2026-10-06 已确认）

- **语言：Kotlin**。业务、协程/Flow、Room DAO 与 UI 均按 Kotlin 实现。
- **UI：Jetpack Compose**。§9 页面按 Compose 组织；底层媒体/图片组件有需要时通过互操作接入。
- 参考实现仍为 Java 21，本次不改写源服务端。Kotlin 可以调用兼容 Android 的 Java 库，AWT/ImageIO 等桌面 API 需替换。
- 构建 JDK、Kotlin/JVM target、Java 语言特性和 Android API 可用性是不同约束，不能笼统断言“Java 21 语法在安卓上一律不可用”。P0 根据 AGP、Gradle、Kotlin、Compose 插件及 minSdk 固定兼容组合。参考：[Android 构建中的 Java 版本](https://developer.android.com/build/jdks)。
- §3.2 的 Kotlin 接口与附录 A 目标映射继续适用，不再保留 Java/XML+View 选型分支。

---

## 附录 A：源文件 → 目标类映射（建议）

| 源（Java） | 目标（Kotlin） |
|---|---|
| `JmcomicService`（接口+签名+解密） | `feature-jm/.../JmApiClient.kt` |
| `JmcomicService`（下载/解扰/zip/pdf） | `feature-jm/.../JmDownloader.kt` + `core/codec/ImageCodec.kt` + `ArchiveWriter.kt` + `PdfWriter.kt` |
| `JmTaskQueue` + `JmTaskContext` + `JmTaskPushService` | `core/taskqueue/JmTaskQueue.kt`（+ Flow 进度） |
| `JmcomicSqliteServiceImpl` + 4 个 entity + mapper xml | `core/database/...`（Room Entity/Dao/Repository） |
| `BilibiliService` | `feature-bili/.../BiliApiClient.kt`（WBI、详情、playurl、ticket） |
| `BilibiliVideoDownloadService` | `feature-bili/.../BiliDownloader.kt` + `core/download/DownloadEngine.kt` |
| `BilibiliVideoService` + `BilibiliVideoSqliteServiceImpl` | `feature-bili/.../BiliVideoRepository.kt` + Room |
| `BilibiliIdConverter` / `BilibiliSidUtil` | `feature-bili/.../BiliId.kt` |
| `shared/util/FileUtil`（文件名/目录相关） | `core/storage/FileNaming.kt` |
| `webui/src/views/jmcomic/*`、`webui/src/views/bilibili-video/*` | 原生 UI（仅作交互参考） |

## 附录 B：配置项 → App 设置项（仅供开发映射）

> 本表内部 key 不得展示在 App 中。用户可见名称、分组、默认值及生效规则以 §9.2～§9.5 为准；参考默认值与 Android 设计不同处按 §9 执行。

| 源配置 | 默认值 | 用途 | 放到哪 |
|---|---|---|---|
| `jm.password.zip` | `1234` | zip 密码 | JM 模式设置 |
| `jm.password.pdf` | `1234` | pdf 密码 | JM 模式设置 |
| `jm.download.threads` | `0`（=CPU 核数） | 单本下载并发 | JM 模式设置 |
| `jm.operation.parallel.enabled` | `false` | 不同 JM ID 是否并行 | JM 模式设置 |
| `jm.album.name_max_length` | `150` | 本子文件夹名上限（字节） | JM 模式设置 |
| `jm.api_domain` | `www.cdnbea.net` | JM API 域名 | JM 模式设置 |
| `jm.search.history.limit` | `20` | 在线搜索历史条数 | JM 模式设置 |
| `jm.request.album/search/chapter.retry` | `0` | 接口重试次数 | JM 模式设置 |
| `bilibili.cookies.sessdata` / `bili_jct` / `ticket` | 空 | B站鉴权 | **B站模式设置** |
| `bilibili.download_video.duration_limit` | `600` | 下载时长上限（秒） | **B站模式设置** |

> 没有 JM 账号相关配置项：本 App 不做 JM 登录（§5.1）。

## 附录 C：一句话验收标准（Definition of Done）

> 手机上：开屏选 JM → 搜到一本 → 入库 → **进漫画详情页（本地优先展示章节、联网刷新，已下载的章节有标记）** → 下载（可整本可单章；图片解扰正确、进度精确到章节与图片、可取消、**中途杀进程重启能接着下**）→ **点章节进阅读器**（翻页/缩放/切章正常）→ 收藏 → 导出 zip 与 pdf（**弹密码框，默认填配置里的密码，也可选"不设密码"**；密码正确才能打开、错误密码打不开、书签正确）→ 切到 B站 → 在 B站设置里填好 Cookie → 粘贴链接入库 → 下载 → 播放 → 删除；全程**不需要任何服务器**，杀进程/断网/切后台都不丢数据。

---

## 附录 D：计划审阅与优化建议（2026-10-06）

> 以下保留首轮审阅记录。后续用户已批准 UI/交互方案，并要求独立模式设置及全局入口，现已写入 §9：离线详情、阅读/播放记忆、分 P 选择、Compose 状态及设置设计不再是待批准建议；相关实施阶段已同步。其他未被 §9 明确采纳的技术建议仍待收敛，不能视为全部获批。

### D.1 优先处理：正确性与可实施性

| 优先级 | 位置 / 问题 | 建议与验收补充 |
|---|---|---|
| 高 | §4.5 所谓“具体图片”实际只有章节计数；§4.2 已有一图一行的业务表，“几万行收益极低”不足以否定图片明细 | 先明确只需 x/y，还是需要文件名、单图状态与失败原因。可利用既有图片记录或任务明细；必须有稳定的章节图片清单，才能准确判断漏图。无需预先决定新增全量任务图片表。 |
| 高 | §9 详情页依赖在线拉章节，可能阻断离线阅读 | 先展示本地章节、排序和图片清单，联网刷新作为增强；不能只从已下载图片反推全部章节。增加飞行模式下“库 → 详情 → 已下载章节 → 阅读/切章”验收。 |
| 高 | §3.3/§8 未明确 WorkManager 与前台服务谁执行任务，也未区分系统回收、强行停止和重启 | 持久任务表作为事实来源，同一任务只有一个执行者；定义暂停/取消/失败/重试迁移，恢复不应复活已取消任务。设置页“强行停止”后，以用户再次打开 App 时恢复作为验收边界，不能承诺立即自动恢复。 |
| 高 | 新版 Android 后台限制未进入 P0 | Android 15 对相应 targetSdk 的 dataSync 前台服务有时限；Android 16 长时间 Worker 会消耗任务配额。P0 按目标系统评估 WorkManager、直接前台服务或用户发起的数据传输任务；验证超时、停止、检查点恢复。导出任务也需单独核实适用执行方式。 |
| 高 | §4.5/§8 仅凭文件存在认定成功，Range 条件不完整 | 临时文件校验后再原子发布，排除零字节/损坏文件，恢复时协调文件与数据库。覆盖 206/Content-Range、200 忽略 Range 后从头写、416 完整性判断、ETag/Last-Modified 变化；直链过期后重新解析。文件被删或损坏时进度应允许纠正，不能绝对要求“不回退”。 |
| 高 | §7 将 128 位密钥视为 AES，且 owner/user 同密码，与“不可修改/复制”验收冲突 | 源码只设置密钥长度 128，未显式 preferAES，不能据此认定 AES-128。P0 检查实际加密字典；若阅读密码需受权限限制，应区分 owner/user 密码。PDF 权限依赖阅读器遵守，不能验收成绝对防复制。策略变更需明确记录。 |
| 高 | §7 PDF 失败就手写，且“400～700 行”“速度体积都更好”无实测支撑 | 不建议把手写加密 PDF 引擎作为默认兜底。先验证成熟库的加密、书签、内存、临时磁盘和长页兼容，再评估替代库能力及许可。JPEG 是有损选项，WebP 也可解码后无损嵌入；接口不宜强制所有图片转 JPEG。 |

官方核查依据：[长时间运行的 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)、[前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)、[PDFBox StandardProtectionPolicy](https://pdfbox.apache.org/docs/2.0.8/javadocs/org/apache/pdfbox/pdmodel/encryption/StandardProtectionPolicy.html)。PDFBox 文档说明 API 语义，Android 移植库仍需实测。

### D.2 建议补齐：数据、媒体与交互边界

1. **图片内存与黄金用例冲突（§8/§11/§12）**：RGB_565 降低颜色精度，不能同时要求任意原图逐像素一致。算法测试应比较相同像素格式的无损结果；阅读预览可降采样，导出质量单独定义。并发按解码内存预算限制，区分网络与解扰并发；超长图分块需验证实际图片格式。用例补高度余数、零块数及去扩展名输入。
2. **任务字段不完整（§4.5）**：文字提到 totalBytes/downloadedBytes，表格未列出；还需文件定位、校验信息、调度关联和暂停原因。定义 `(type, bizKey)` 活跃任务去重，明确整本和单章下载重叠时合并、等待或拒绝。
3. **数据库与历史（§4）**：补字段类型、时间单位、JSON 转换、图片唯一键、外键/删除语义、查询索引；明确 cid 为空时的视频唯一性。搜索历史已有持久化语义，Android 可选独立 Room 表或结构化 DataStore。阅读位置记忆可作为体验增强项评估，明确章节、页码和位置模型。
4. **B站分 P、短链及多段 durl（§3.2/§5.2/§9）**：定义分 P 选择与 CID 保存、b23.tv 跳转和无效链接处理。多段 durl 应处理完整或明确提示不支持，不能取首段即视为下载完整。DASH 模型还需音视频轨、备用 URL、请求头、编码兼容及地址刷新；假 resolver 单测无法证明实际合流可用。
5. **导出/删除与下载并发（§9/P4）**：明确缺图时拒绝还是允许部分导出并提示；导出采用清单快照，协调同一本的删除/重下。补取消、临时文件清理、空间不足和 SAF 目标失败处理。分别定义删除记录、删除文件、删除收藏夹的影响。
6. **设置与存储生命周期（§8/附录 B）**：Cookie、密码不应明文进入日志、任务 payload 或默认备份；凭据存储可评估 Android Keystore。区分私有库目录和导出目录，明确卸载/目录不可访问的行为。通过 SAF/MediaStore 创建自己的导出文件，不应笼统要求读取媒体权限。可选“备份/恢复”与“不做库导入”需澄清范围；纯单机约束下优先本地配置，不直接引入远程配置服务。
7. **Compose 状态归属（§3/§9）**：建议 ViewModel 暴露 StateFlow<UiState>，页面只提交事件；旋转或导航重建不能重复提交下载。补返回栈、模式状态保存、深色模式、字体缩放、无障碍标签及加载/空/错误/离线态。内存 UI 状态不能代替持久任务表。

### D.3 实施顺序与验收优化

- **P0 工程基线**：确定新工程目录、applicationId、minSdk/targetSdk/compileSdk、构建依赖兼容版本、签名及升级方式；本次审阅不代替用户选最低支持机型。固定参考源码 commit，行号仅辅助定位。
- **参考只读冲突**：§11 建议在源项目加 main/JUnit，与 §0“只读”冲突。黄金数据应从独立测试工程或源码副本生成并记录来源。
- **先打通纵向链路**：基础设施后先完成“单章下载 → 本地详情 → 阅读 → 断网/进程中断恢复”，再扩整本及导出。阅读器不能只留在 P6“衔接打磨”，需明确实现任务与超长图验收。
- **导出纳入任务设计**：当前 type 只有 JM_ALBUM/BILI_VIDEO；需定义 zip/pdf 状态、进度和取消，进程中断后重新生成还是提示重试，避免 P4 再造任务体系。
- **故障验收矩阵**：增加拒绝通知权限、强行停止后再打开、设备重启、损坏正式文件、重复提交、下载中删除、导出中断、直链过期、多 P/多段 durl、数据库升级；确定低端机内存预算及样本规模。
- **排期**：P0～P7 原区间合计 **30～44 个工作日**，每周 5 天约 **6～8.8 周**，尚无风险缓冲；P0 验证 PDF、后台与内存后再承诺日期。
- **任务拆分**：当前更接近总体设计和路线图。按阶段拆出文件、接口、验收用例和可勾选任务；初期不必把每个逻辑目录都建成独立 Gradle 模块。