# JMComic3 官方 App `2.1.11` 接口逆向文档

> 逆向对象：`official/2.1.11.apk`（29.8 MB，`com.a7m3p9xv.t6qk2z8.app`）
> 逆向时间：2026-10-17
> 本文档只包含**在官方 App 内实测或反编译确认过**的事实；表格里「实测」列为 `—` 的条目是**按源码推断**（未真实请求）。
>
> ▶ **要查接口先看 `docs/official-api-manga.md`**：那是只收录"漫画相关接口"的实测版参考
> （路径 / 方法 / 鉴权 / 入参 / 出参 / 真实响应示例，2026-10 用真实账号跑过）。
> 本文保留的是**逆向过程、App 全局结构（线路 / 开屏 / 热更新 / 广告）**与历史结论。

---

## 0. 三个问题的直接答案

### ① 开屏「选线路」的线路有哪几个，域名是多少？

App 启动时先从远端拉一份**加密的线路清单**，然后在开屏页 `FirstCover` 把 `jm3_Server` 数组渲染成一排按钮让用户选，选完才能进 App。

当前（2026-10-17）清单内容：

| 按钮 | 域名 | 是否在随机池 `Server` | 本次实测 |
|---|---|---|---|
| 線路1 | `www.cdnhjk.net` | 是 | 本机多次超时（未取到） |
| 線路2 | `www.cdngwc.cc` | 是 | ✅ 200 |
| 線路3 | `www.cdngwc.net` | 是 | 本机多次超时（未取到） |
| 線路4 | `www.cdngwc.club` | 是 | ✅ 200 |
| 線路5 | `www.cdnutc.me` | **否** | ✅ 200 |

- 清单来源：`https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt`（AES-256-ECB 加密的文本）
- 解密密钥：`md5("diosfjckwpqpdfjkvnqQjsik")` 的 32 个 ASCII 字节
- 详见 §3。

### ② 分类接口是哪个？类别参数怎么给搜索接口用？搜索接口是哪个？

| 用途 | 接口 | 关键参数 |
|---|---|---|
| **分类树**（标题栏的 tab、子分类、排序按钮） | `GET /categories` | `lang` |
| **分类内容列表**（真正的漫画列表） | `GET /categories/filter` | `c`、`o`、`page` |
| **搜索** | `GET /search` | `search_query`、`o`、`page`、`search_type`、`y`、`m` |

类别参数的构造规则（`Categories.tsx`）：

```text
c = slug + (sub_slug ? "_" + sub_slug : "")
# 例：分类「同人」(slug=doujin) + 子分类「漢化」(slug=chinese)
c = "doujin_chinese"
# 不带子分类：
c = "doujin"
```

**重要结论**：分类的 `slug` **不会**传给 `/search`。官方 App 里「分类 → 搜索」的唯一跳转是**标签**：
点分类页的「熱門標籤」或详情页的 tag / 作者 / 作品 / 角色，都会跳到前端路由 `/search?filter=<标签>`，
该路由再把 `filter` 当作 `search_query` 去调 `GET /search`（`o` 传空串）。
详见 §4.4 / §5.4。

### ③ 漫画详情页的评论接口是哪个？

```http
GET https://{线路域名}/forum?mode=all&page=1&aid={漫画id}&lang=TW
```

- 入口：详情页第 3 个 tab（文案 `漫畫介紹` / `目錄` / **`評論`**，`Comment` 组件，`section="comic_comments"`）
- 评论**总数**不来自 `/forum`，而是 `GET /album` 响应里的 `comment_total`
- 列表每页 **10** 条，`total` 在服务端被截断到 **5000**
- 详见 §6。

---

## 1. 逆向方法（可复现）

### 1.1 APK 结构

官方 2.1.11 **不是原生 Android 应用**，而是一个 **Cordova + Capacitor 的 WebView 壳**：

```
classes.dex (1.0 MB)        ← 纯壳：Cordova / Capacitor 插件，没有业务逻辑、没有域名
resources.arsc
assets/
  capacitor.config.json     ← appId=com.a7m3p9xv.t6qk2z8.app, appName=JMComic3
  public/                   ← 整个 React SPA（CRA 构建产物）
    index.html
    cordova.js
    static/js/main.9a45a3ff.js          (794 KB)
    static/js/*.chunk.js                (按路由 lazy load)
    static/js/*.js.map                  ← ★ 完整 sourcemap，含 sourcesContent
  ...
```

**关键**：`*.js.map` 里带 `sourcesContent`，共 **1556 个原始源码文件**（`.ts/.tsx`，含中文注释）。
因此「反编译」等价于「从 sourcemap 还原源码」，不需要 jadx。

### 1.2 还原源码

```powershell
# 1) APK 改名后解压（Expand-Archive 只认 .zip 后缀）
Copy-Item official/2.1.11.apk $env:TEMP/a.zip -Force
Expand-Archive $env:TEMP/a.zip -DestinationPath <out> -Force

# 2) 用 node 把每个 *.js.map 的 sourcesContent 落盘
node -e "
const fs=require('fs'),path=require('path');
const dir='<out>/assets/public/static/js', out='<out>/src';
for (const f of fs.readdirSync(dir)) {
  if (!f.endsWith('.js.map')) continue;
  const m=JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
  m.sources.forEach((s,i)=>{
    const c=m.sourcesContent[i]; if(c==null) return;
    const rel=s.replace(/^webpack:\/\/[^/]*\//,'').replace(/^\.\//,'').replace(/[:*?\"<>|]/g,'_');
    const d=path.join(out,f.replace(/\.js\.map$/,''),rel);
    fs.mkdirSync(path.dirname(d),{recursive:true}); fs.writeFileSync(d,c);
  });
}"
```

还原后的关键目录（本文所有 `xxx.ts` 引用都指这里）：

```text
main.9a45a3ff/
  api/apiPaths.ts          ← 全部 85 个接口路径常量表 ★
  api/ApiEndpointUtil.ts   ← 线路清单拉取 + 解密 + apiUrl 拼接
  api/HttpUtil.ts          ← 请求头签名 / 响应解密 / 重试 / 超时
  utils/Function.ts        ← 线路清单解密密钥、图片解扰算法
  utils/speedTestUtil.ts   ← 线路测速（对每条线路打 /setting）
  App.tsx                  ← 启动流程（拉线路、热更新、路由表）
  GlobalContext.tsx        ← 全局配置（host/setting/img_host/图源）
  actions/*.ts             ← 每个接口的入参与回调
5840…chunk/…/FirstCover.tsx ← 开屏「选线路」页面 ★
3704…chunk/…/Categories.tsx ← 分类页 ★
6931…chunk/…/Search.tsx     ← 搜索页 ★
8625…chunk/…/Comment.tsx    ← 详情页评论区 ★
5360…chunk/…/ForumList.tsx  ← 评论列表渲染（分页 10/页）
7522…chunk/…/Read.tsx       ← 阅读页（comic_read + 解扰）
9907…chunk/…               ← CRA 的 process.env 内联表（全部 REACT_APP_* 值）
```

### 1.3 实测方式

脚本用 Node 内置 `https` + `crypto` 复刻签名与解密（§11 给了完整可运行版本）。
本机实测期间部分镜像会间歇性 `ECONNRESET` / 超时，**重试即可**（官方 App 本身也有 3 次重试）。

---

## 2. 协议基础

### 2.1 基础 URL

```text
https://{线路域名}/{接口路径}?{query}
```

- `apiUrl` 由用户在开屏页选中的线路决定，形态是 `https://{host}/`
  （`FirstCover.switchHost`：`GlobalStore.updateApiUrl('https://' + host + location.pathname)`，
  注意它把当前 path 也拼进去了，但 `getApiEndpoint()` 只是字符串拼接 `apiUrl + path`，
  所以实际生效的是 `https://{host}/` 前缀）
- 接口路径全部是**相对根路径**，没有 `/api` 之类的公共前缀，例如 `/album`、`/search`、`/categories/filter`

### 2.2 请求头签名（每个请求都要带）

`api/HttpUtil.ts` 顶部（模块加载时**只算一次**，缓存到 `token` / `tokenParam` 两个变量里）：

```ts
const version = "2.1.11";                       // process.env.REACT_APP_VERSION
const time    = Math.floor(Date.now() / 1000);  // 秒级 Unix 时间戳
const tokenParam = time + "," + version;        // 例："1792196301,2.1.11"
const token      = md5(String(time) + "185Hcomic3PAPP7R");
```

所有请求（含 `fetchImage`）统一带：

| Header | 值 | 说明 |
|---|---|---|
| `Token` | `md5(ts + "185Hcomic3PAPP7R")` | 小写 hex md5 |
| `Tokenparam` | `"{ts},2.1.11"` | 逗号分隔，无空格 |
| `Authorization` | `Bearer {jwttoken}` | 已登录才有，未登录传空串 |
| `Cookie` | `AVS={memberInfo.s}` | 已登录才有 |
| `Content-Type` | `application/json` | 仅 `fetchPostJson`（少数接口） |

补充说明：

- **签名只依赖时间戳，不依赖登录态**。所以匿名也能调 `/search`、`/album`、`/forum`、`/categories` 等。
- WebView 侧不显式设置 `User-Agent`；从原生复刻时建议用浏览器 UA
  （服务端对 UA 有校验，参考 `core/protocol/Defaults.kt` 的注释）。
- `ts` 是**模块加载时**取一次并全程复用，所以 App 长时间挂后台后请求可能因为 `ts` 过期而解不开密文；
  原生实现里建议「每个请求取一次 ts」，并保证**重试时复用同一个 ts**（解密密钥由 ts 派生）。
- 实测把版本串换成 `2.0.13` 也能通（服务端未严格校验 `Tokenparam` 的版本号）。

### 2.3 响应信封与解密

所有接口返回 JSON 信封：

```json
{ "code": 200, "data": "<Base64 密文>" }
```

- `code == 200` 才算成功；`401` = 未登录；`400` = 业务/校验错误（这两者官方都不重试）
- `data` 是 **AES-256-ECB / PKCS7 / Base64**，密钥是「md5 十六进制串的 32 个 ASCII 字节」：

```text
key1 = md5(ts + "185Hcomic3PAPP7R")   // 主密钥
key2 = md5(ts + "18comicAPPContent")  // 备用（内容类接口的旧密钥）
```

`tryDecryption` 的**逐字逻辑**（`api/HttpUtil.ts:20`）：

```ts
const possibleKeys = ["185Hcomic3PAPP7R", "18comicAPPContent"];
for (const content of possibleKeys) {
  // 广告接口例外：key 不带 ts
  const isAd = url.includes("ad_content_all") || url.includes("advertise_all");
  const keyStr = isAd ? md5(content) : md5(time + content);
  const key = CryptoJS.enc.Utf8.parse(keyStr);          // 32 字节 ASCII
  const plain = CryptoJS.AES.decrypt(data, key, { mode: CryptoJS.mode.ECB }).toString(Utf8);
  if (可 JSON.parse) 命中;
}
```

即：**普通接口**先试 `md5(ts+"185Hcomic3PAPP7R")`，失败再试 `md5(ts+"18comicAPPContent")`；
**广告接口**（`ad_content_all`、`advertise_all`）用 `md5("185Hcomic3PAPP7R")`（**不拼 ts**）。

### 2.4 通用 query 参数

| 参数 | 出现范围 | 取值 |
|---|---|---|
| `lang` | `fetchGet` **自动追加**（若调用方没传） | `"TW"`（繁中） / `"CN"`（简中），来自 `localStorage.lang` |
| `app_img_shunt` | `/setting` | 图源编号 `"1".."4"`（对应 `app_shunts`） |
| `t` | `/setting`（GET/POST） | 秒级时间戳，防缓存 |
| `v` | 广告类接口 | `setting.ad_cache_version` |
| `ipcountry` | 广告类接口 | `setting.ipcountry`（如 `"CN"`/`"TW"`） |

**空值参数会被丢弃**（`fetchGet` 里 `filter(([,v]) => v !== undefined && v !== null && v !== "")`），
所以默认排序时 `o` 根本不出现在 URL 里，`y` / `m` / `c` 同理。复刻时把空串当「不传」处理即可。

### 2.5 网络行为

- 超时 **15 000 ms**（`fetchWithTimeout`），超时后弹错误弹窗（弹窗里有测速入口）
- 非 200/401/400 会**自动重试最多 3 次**（GET/POST 各自独立计数，全局变量，不按接口区分）
- POST 一律 `multipart/form-data`（`FormData`），数组/嵌套对象走 `fetchPostJson`（`application/json`）

### 2.6 语言（`lang`）：简中 / 繁中怎么切

官方 App 的开关在 **我的 → 設定 → 切換語言**
（`MemberModal.tsx`：`setting.switch_language`，两个选项
`setting.traditional_chinese` / `setting.simplified_chinese`），点「確認」后走
`SettingList.tsx` 的 `switchLanguage()`：

| 动作 | 代码 | 作用 |
|---|---|---|
| 换界面文案 | `i18n.changeLanguage("zh-TW" / "zh-CN")` | 只影响 App 自身界面（`assets/locales` 两套） |
| 记偏好 | `localStorage.setItem("langCode", "0"/"1")`、`setItem("lang", "TW"/"CN")` | ★ 接口用的就是 `lang`（`"0"`=繁中、`"1"`=简中） |
| 通知服务端 | `dispatch(FETCH_SETTINGS_THUNK(langCode))` → `POST /setting`（`language=0/1`） | 声明过的"另一种切换方式"，切换语言时**确实会打** |
| 清列表 | `dispatch(CLEAR_MAIN_LIST("mainList"))` | 让首页/列表按新语言重拉 |
| 重拉封面广告 | `FETCH_COVER_ADS_THUNK({ lang, ipcountry: lang, v })` | 广告文案也跟着语言走 |

让**接口响应里的中文**变简体的只有一件事：`HttpUtil.fetchGet` 给每个 GET 请求自动补
`lang`（`localStorage.getItem("lang") || "TW"`），响应就是服务端按这个值渲染的。
`fetchPost`（multipart）**不补** `lang`；广告类接口还会另带 `ipcountry`（同一门语言的取值）。

本工程用到的接口里，受 `lang` 影响的内容：

| 接口 | 受影响的中文 | 依据 |
|---|---|---|
| `/categories` | 主分类名、二级分类名（繁中 `漢化` / 简中 `汉化`）、`blocks[].title` | ✅ 实测（`lang=TW` 就是官方那套繁体文案） |
| `/categories/filter` | 列表项的 `category.title`、`category_sub.title`、`tags` | 响应里的中文都来自服务端 |
| `/search` | `description`、`category.title`、`tags` | 同上 |
| `/album` | `tags`、`works`、`actors`、`description` | 同上 |
| `/forum` | `addtime` 的格式（带 `lang` 是 `2026-10-16 12:34`，不带时实测是英文 `Oct 11, 2026`）、`expinfo.level_name` | ✅ 实测（§6.3 与 §6.6 的差异） |
| `/setting`（测速探针也打它） | `app_shunts[].title` 等展示文案 | 推断（未逐字对比两门语言） |

> 少带 `lang` 时服务端会怎么处理**没有实测过**（官方 `fetchGet` 从不省略它），
> 所以复刻时一律显式带上最稳。
>
> **本工程的落地（2026-10-20）**：设置页「语言 → 接口语言」默认 **简体中文**；
> `ApiClient` 在拼 URL 时给所有 GET 请求统一补 `lang`（调用方自己传了就不动）。
> 官方切换语言时会 `CLEAR_MAIN_LIST` 重拉列表，本工程**暂时不这么做**：按用户要求，
> 切换语言**不清理任何缓存**（分类树 / 列表 / 评论都保留，重启应用后自然刷新）；
> "清缓存重拉"的代码留在 `CategoryViewModel` 与 `ModeRoot` 里注释着，放开即可恢复。
> `/chapter_view_template`（旧机制的 HTML 模板）**不带** `lang`，与旧版官方 URL 一致。

---

## 3. 开屏「选线路」详解

### 3.1 启动流程

```text
index.html → main.js → App.tsx
  │
  ├─ usePWAProtection()   非 PWA/非原生 → 弹「请去官网下载 App」并跳 /blocked
  ├─ useDevtoolsBlocker() 每 5s 检测 debugger / console → 命中就跳 /blocked
  │
  ├─ fetchHostData()  ──►  api/ApiEndpointUtil.FETCH_HOST()          ★ 拉线路清单
  │                         ├─ 成功 → setGlobalHostFromData(data)
  │                         │        ├─ GlobalStore.apiUrl   = https://{Server 里随机一个}/
  │                         │        └─ GlobalStore.hostServer = data.jm3_Server
  │                         └─ 失败 → 解密内置 hostCode（备用线路集合）
  │
  ├─ setConfig({ hostReady: true, host: 随机一条非「線路5」的 host })
  │
  └─ <Main/> → coverOpen === 2 → <FirstCover/>   ★ 选线路页
                ├─ 未点过 → 展示 jm3_Server 全部线路按钮
                ├─ 点某条 → GlobalStore.updateApiUrl("https://{host}/") + clearAuth()
                └─ onNext() → 进首页（并顺带展示 SecondCover/ThreeCover 广告封面）
```

`FirstCover` 只在**冷启动且 `sessionStorage` 没有 `state`** 时出现（`Main.tsx`）：
`mainStatus` 存在 → `coverOpen = 6` 直接进首页；否则 `coverOpen = 2` 停在选线路页。

选线路页的界面文案（`assets/locales`，繁中）：

| i18n key | 文案 |
|---|---|
| `modal.please_select_network` | 請選擇網路 |
| `modal.network_troubleshooting_hint` | ＊如果不能連結或不能截圖，請嘗試切換線路 |
| `modal.loading_network` | 線路讀取中 |

按钮 = `jm3_Server` 里的每一条（`線路1 … 線路5`）+ 一个绿色圆点（当前版本恒为绿色，见 §3.6）；
顶部还有一个广告位 `app_route`（来自 `/ad_content_all`，非会员才显示）。

### 3.2 线路清单从哪来

`api/ApiEndpointUtil.ts`：

```ts
const urlPrimary   = "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt"; // 实际使用
const urlBackup    = "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt"; // 只在未使用的 thunk 里
const urlSecondary = "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt";  // 仅内联进 env，未被引用
const hostCode     = "X+bnzYIcwF6C7Rd3T7njP…";  // 内置密文兜底
```

- **真正生效的只有 `c02`**：`App.tsx` 调的是 `ApiEndpointUtil.FETCH_HOST()`，其 URL 数组只有 `[c02]`。
- `actions/settingAction.ts` 里的 `FETCH_HOST_THUNK`（`[c02, c01]`）在 2.1.11 中**没有被 dispatch**，属死代码。
- `c03` 只出现在 CRA 内联的 `process.env` 表里（`9907…chunk`），没有任何调用点。

三个源返回的都是**同一份加密文本**，解密后结构如下（实测）：

```json
{
  "Setting":    ["www.cdnhjk.net","www.cdngwc.cc","www.cdngwc.net","www.cdngwc.club"],
  "Server":     ["www.cdnhjk.net","www.cdngwc.cc","www.cdngwc.net","www.cdngwc.club"],
  "jm3_Server": [["www.cdnhjk.net","線路1"],["www.cdngwc.cc","線路2"],
                 ["www.cdngwc.net","線路3"],["www.cdngwc.club","線路4"],
                 ["www.cdnutc.me","線路5"]]
}
```

| 字段 | 用途 |
|---|---|
| `Setting` | 预留（当前代码未用） |
| `Server` | **随机池**：`setGlobalHostFromData` 从这里随机挑一条作为初始 `apiUrl`（**不含** `www.cdnutc.me`） |
| `jm3_Server` | **UI 列表**：`[域名, 线路名]`，开屏页按这个渲染按钮（**含** `www.cdnutc.me`） |

### 3.3 清单解密（`utils/Function.ts`）

```ts
const key = CryptoJS.enc.Utf8.parse(md5("diosfjckwpqpdfjkvnqQjsik"));  // 32 字节 ASCII
export const decryptData = (encryptedText) =>
  JSON.parse(CryptoJS.AES.decrypt(encryptedText, key, { mode: CryptoJS.mode.ECB })
                      .toString(CryptoJS.enc.Utf8));
```

纯文本 → Base64 → AES-256-ECB/PKCS7，**key 与时间戳无关**，可离线解密任意历史快照。

### 3.4 备用线路集合（内置 `hostCode` 解密结果）

当 `c02` 拉取失败时，App 解密内置的 `X+bnzYIcw…` 密文，得到**另一组**域名（同样是 `線路1..4`）：

```json
{
  "Setting":    ["www.cdnaspa.vip","www.cdnaspa.club","www.cdnplaystation6.vip","www.cdnplaystation6.cc"],
  "Server":     ["www.cdnaspa.vip","www.cdnaspa.club","www.cdnplaystation6.vip","www.cdnplaystation6.cc"],
  "jm3_Server": [["www.cdnaspa.vip","線路1"],["www.cdnaspa.club","線路2"],
                 ["www.cdnplaystation6.vip","線路3"],["www.cdnplaystation6.cc","線路4"]]
}
```

本次实测这 4 条已基本不可用（`cdnaspa.vip/.club` → 404，`cdnplaystation6.vip` → NXDOMAIN，`.cc` → 301），
可以理解为**历史备份**，正常网络下走不到这条分支。

### 3.5 选完线路之后

1. `GlobalStore.updateApiUrl("https://{host}/")` → 之后所有接口都用这条线路
2. `clearAuth()` 清掉登录态（换线路等于换会话域）
3. `config.host` 更新 → 触发 `GlobalContext` 里拉 `/setting`：

```http
GET /setting?app_img_shunt=1&lang=TW&t=1792196301
```

`/setting` 返回的 `img_host` 才是**图片 CDN**（每条线路的 `img_host` 还不一样，见 §8）。
`/setting` 里其它有用字段（实测值，線路4）：

| 字段 | 值 / 含义 |
|---|---|
| `version` / `jm3_version` | `1.8.2` / `2.1.11`（网页版 / JM3 App 版本） |
| `base_url` | `https://www.cdngwc.club`（当前线路） |
| `main_web_host` | `18comic.vip`（网页版主站，站内跳转用） |
| `img_host` | `https://cdn-msp3.jmapiproxy1.cc`（图片 CDN，见 §8） |
| `api_banner_path` / `logo_path` | 图片资源路径（拼 `img_host`） |
| `ipcountry` | `CN`（用于广告请求） |
| `ad_cache_version` | `1792196301`（广告缓存版本，等于 `v` 参数） |
| `app_shunts` | `[{"title":"圖源1","key":1} … {"title":"圖源4","key":4}]`（图源列表） |
| `is_cn` / `cn_base_url` | `1` / `https://cn-appdata.jmapiproxy2.cc` |
| `is_hot_update` / `jm3_is_hot_update` | 热更新开关（见 §9） |
| `bundle_url` / `download_url` | 热更新包 / 安装包地址 |
| `app_landing_page` / `donate_url` | 落地页 / 赞助页 |
| `store_link` | Google Play 链接 |
| `jm3_download_url` | `https://comic18j-guta.site/static/jmapp3apk/JMComic3v2.1.11.apk?v=…` |

### 3.6 线路测速（选线页的灯号）

`utils/speedTestUtil.ts` 对**每条线路**各发一次真实请求：

```http
GET https://{host}/setting?lang=TW      （带 Token/Tokenparam）
```

量 `latency`（首字节）与 `downloadSpeed`（吞吐），再用这条线路解密出来的 `img_host`
加载 `{img_host}/media/users/nopic-.gif?v=0` 量图片就绪时间。灯号阈值：

| 等级 | 条件 |
|---|---|
| 绿 `smooth` | `latency < 600 ms` |
| 橙 `normal` | `600 ≤ latency < 1200 ms` |
| 红 `laggy` | `≥ 1200 ms` 或无法连接 |

> 注意：当前版本 `FirstCover` 把测速那段 `useEffect` **注释掉了**，且把 `getSpeedLevel(lineLatency[…])`
> 硬编码成 `getSpeedLevel(500)`，所以开屏页的灯号**恒为绿色**（纯装饰）。
> 真正测速在「我的 → 测速」页（`Member/SpeedTest.tsx`，每日 30 次、10 秒冷却）。

#### 图片测速（`testImageLoadTime`，官方**有**这个功能）

同一个文件里的 `testImageLoadTime` 就是官方的图片测速，`SpeedTest.tsx` 把它显示成
「圖源順暢度」（`member.speed_test_image_ready_time`）。逐字行为（源码确认）：

```ts
const img = new Image();                       // ★ 用 <img>，不是 fetch
const start = performance.now();
img.onload  = () => resolve(performance.now() - start);   // 就绪时间
img.onerror = () => resolve(null);             // 载入失败
setTimeout(…resolve(null), 10000);             // 10 秒超时也算失败
img.src = `${img_host}/media/users/nopic-.gif?v=0`;
```

| 要点 | 说明 |
|---|---|
| 为什么用 `<img>` | `img_host` **没开 CORS**，`fetch` 读不到内容，但浏览器加载 `<img>` 不受 CORS 限制（源码注释原话）。所以官方**拿不到字节数**，只能量时间，换不成 Mbps |
| 探针图 | `{img_host}/media/users/nopic-.gif?v=0`，1～4 KB 的占位头像，一定存在、不需要登录 |
| 量的是什么 | `imageLoadTime` 从**发出 `/setting` 请求之前**开始计时（`start` 在 `fetch` 之前取），到图片 `onload` 结束 —— 即「打 API 拿到 img_host → 载图」的**累计**等待，不是单纯的图片下载时间 |
| 失败 | 10 秒超时 / `onerror` → `null` → 界面显示「無法載入」（`speed_test_image_load_fail`） |
| 灯号 | `getSpeedLevel(latency)` **只看 API 延迟**，`imageLoadTime` 只作为一行数字显示，不参与分档 |
| 测哪条 | **只测当前线路**：`img_host` 来自那条线路的 `/setting`。官方没有「图片线路清单」，所以没有"每条图片 CDN 各测一遍"的现成逻辑 |
| 连接复用 | 注释写明「每次測速都是對海外線路發出全新請求（含 DNS + TLS 交握，無連線重用）」，所以阈值放到 600 / 1200 ms |

> 计时还受每日 30 次 / 10 秒冷却限制，见 `SpeedTest.tsx` 的 `DAILY_LIMIT` / `COOLDOWN_MS`。
> 我们的客户端没有这两条限制（测速是本地的、请求量只有十几张占位图）。

---

## 4. 分类页（`/categories`）

### 4.1 页面数据流

```text
打开「分類」tab
 ├─ GET /categories            → categoriesList { categories:[], blocks:[] }   （缓存，不重复请求）
 ├─ GET /hot_tags              → 顶部「熱門標籤」
 ├─ GET /tags_favorite         → 「我的收藏」标签（需登录）
 └─ GET /categories/filter     → 漫画列表（切 tab / 切排序 / 翻页都会重打）
```

### 4.2 `GET /categories` — 分类树

```http
GET /categories?lang=TW
```

响应（实测，`data` 解密后）：

```json
{
  "categories": [
    { "id": 0, "name": "最新A漫", "slug": "", "total_albums": 0 },
    { "id": "1", "name": "同人", "slug": "doujin", "type": "…", "total_albums": "354295",
      "sub_categories": [
        { "name": "漢化", "slug": "chinese" },
        { "name": "日語", "slug": "japanese" },
        { "name": "CG圖集", "slug": "CG" },
        { "name": "禁漫去碼", "slug": "no_mosaic" },
        { "name": "禁漫上色", "slug": "coloring" }
      ] },
    …
  ],
  "blocks": [
    { "title": "主題A漫", "content": ["…", "…"] },   // content 是标签字符串数组
    …
  ]
}
```

实测分类（9 项，`id=0` 是前端合成出来的「最新A漫」）：

| id | name | slug | sub_categories(slug) | total_albums |
|---|---|---|---|---|
| 0 | 最新A漫 | `""` | — | 0 |
| 1 | 同人 | `doujin` | `chinese` `japanese` `CG` `no_mosaic` `coloring` | 354295 |
| 2 | 單本 | `single` | `chinese` `japanese` `youth` `no_mosaic` `coloring` | 43316 |
| 3 | 短篇 | `short` | `chinese` `japanese` `no_mosaic` `coloring` | 118884 |
| 4 | 其他類 | `another` | `other` `3d` `cosplay` | 52824 |
| 5 | 韓漫 | `hanman` | `chinese` | 130025 |
| 6 | English Manga | `meiman` | `irodori` `fakku` `18scan` `manhwa` `comic` `other` | 397296 |
| 7 | Cosplay | `another_cosplay` | — | 0 |
| 8 | 3D | `3D` | — | 0 |
| 9 | 禁漫漢化組 | `禁漫漢化組` | — | 0 |

`blocks`（4 组，标题分别「主題A漫」「角色 / 扮演」「特殊PLAY」「其他」，每组 5～15 个标签）。
它们是**标签**，点击后走 `/search?filter=<标签>`。

### 4.3 `GET /categories/filter` — 分类内容列表

```http
GET /categories/filter?page=1&o=&c=doujin_chinese&lang=TW
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `page` | 是 | **从 1 开始**，每页固定 **80** 条 |
| `c` | 是 | 分类标识：`slug` 或 `slug + "_" + 子分类slug`（不带子分类时只传 `slug`；「最新A漫」传空串/不传） |
| `o` | 否 | 排序码，见下表 |
| `lang` | 自动 | 语言 |

排序码（`assets/JsonData.ts` 的 `CatSortData`）：

| `o` | UI 文案 |
|---|---|
| `""` | 最新（默认） |
| `tf` | 最多愛心 |
| `mv` | 總排行 |
| `mv_m` | 月排行 |
| `mp_w` | 週排行 |

> 源码里 `Header.tsx`：`slug === ""`（最新A漫）时显示全部排序，其它分类**少一个**（把週排行 `slice(0,-1)` 去掉）。

响应（实测 `c=doujin_chinese&o=mv`）：

```json
{
  "search_query": "",
  "total": 10000,
  "content": [
    {
      "id": "350234",
      "author": "…",
      "name": "…",
      "image": "/media/albums/350234.jpg",     // 相对路径，需拼 img_host
      "category":     { "id": "1", "title": "同人" },
      "category_sub": { "id": "…", "title": "漢化" },
      "liked": false,
      "is_favorite": false,
      "update_at": 1792100000,
      "adddate": "2026-10-16"
    }
    // … 共 80 条
  ],
  "tags": ["透視", "無修正", "連褲襪"]     // 本页出现的热门标签（少量）
}
```

要点：

- `total` 被**截断到 10000**，所以前端算页数用 `ceil(total/80)`，最多 125 页（`Categories.tsx:71`）
- 列表项没有 `description`（搜索接口有），这是分类列表与搜索结果的唯一字段差异
- `liked` / `is_favorite` 需要登录才有意义，匿名恒为 `false`

### 4.4 分类怎么和搜索接口联动（重点）

源码里**没有任何一处**把分类 `slug` 传给 `/search`。实际链路有两条：

**① 分类浏览（不经过搜索）**

```text
Header tab / 子分类 / 排序按钮
  → setFilter({slug, sub_slug, sort})
  → loadList(page, o=sort, c = slug[_sub])
  → GET /categories/filter  ← 列表数据全部来自这里
```

**② 分类页 → 搜索（只走标签）**

```text
「熱門標籤」chips（来自 /categories/filter 的 tags 或 /hot_tags）
「我的收藏」标签（来自 /tags_favorite）
  → <Link to={`/search?filter=${tag}`}>      前端路由
  → Search.tsx: filter = 查询串
  → loadList(search_query = filter, o = "")
  → GET /search
```

详情页同理（`Desc.tsx`）：tag / 作者 / 作品 / 角色名都跳 `/search?filter=<文案>`。

所以「类别参数 → 搜索接口」的正确理解是：

| 你想做的事 | 该用 |
|---|---|
| 按分类浏览 | `GET /categories/filter?c={slug[_sub]}&o={sort}&page={n}` |
| 按关键字/标签搜索 | `GET /search?search_query={关键字或标签}&o=&page={n}` |
| 按「作品 / 作者 / 角色 / 标签」限定搜索 | `GET /search?...&search_type=work\|author\|character\|tag` |

---

## 5. 搜索页（`/search`）

### 5.1 `GET /search`

```http
GET /search?search_query=test&o=&page=1&search_type=site&y=&m=&lang=TW
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `search_query` | 是 | 关键字；**标签也是走这里**（`#` 号是可选的展示符号，查询时不要带 `#`） |
| `page` | 否 | **从 1 开始**，每页 80 条（80 由前端 `ceil(total/80)` 推断，本次实测 `total=47` 时一页返回 47 条） |
| `o` | 否 | 排序码：`""`最新 / `mv`最多觀看 / `mp`最多圖片 / `tf`最多愛心 / `old`（前端本地按 `adddate` 升序，仍会发给服务端） |
| `search_type` | 否 | `site`（默认，全站）/ `work`（作品）/ `author`（作者）/ `tag`（標籤）/ `character`（角色） |
| `y` | 否 | 年份（`2017..当前年`），空串=全部 |
| `m` | 否 | 月份（`1..12`），空串=全部 |
| `lang` | 自动 | `TW` / `CN` |

### 5.2 响应（实测）

```json
{
  "search_query": "test",
  "search_type": "site",
  "y": "",
  "m": "",
  "total": 47,
  "content": [
    {
      "id": "…",
      "author": "…",
      "description": "…",        // ← 比分类列表多这个字段
      "name": "…",
      "image": "/media/albums/…jpg",
      "category": { "id": "…", "title": "…" },
      "category_sub": { "id": "…", "title": "…" },
      "liked": false,
      "is_favorite": false,
      "update_at": 1792000000,
      "adddate": "2026-10-11"
    }
  ]
}
```

- `total` 同样可能被截断（实测 47 条时 `content` 长度正好 47，即一页内返回全部）
- **`redirect_aid`**：某些关键字会命中「精确作品」，响应里带 `redirect_aid` 时 App 直接跳详情页
  （`Search.tsx:153`，此时不会渲染列表）

### 5.3 分页

`pageLimit = ceil(total / 80)`，`page` 从 1 开始，最后一页 `page == pageLimit`（与分类页一致）。

### 5.4 前端路由 `/search?filter=` 与接口参数的关系

| 前端路由参数 | 接口参数 | 备注 |
|---|---|---|
| `?filter=<x>` | `search_query=<x>`、`o=""` | App 内跳转统一用这种形式（标签、作者、作品、角色名） |
| 搜索框回车 | `search_query=<输入>`、`o=<当前排序>`、`search_type`、`y`、`m` | 请求成功后把 URL 改写为 `?filter=<输入>` 便于刷新恢复 |

即 **`filter` 只是给 React Router / 刷新恢复用的壳**，真正语义是 `search_query`。
（`filter=redirect_aid` 是内部哨兵值：命中「精确作品」跳转后写入 `sessionStorage.searchQuery`，
用来阻止 Search 页再打一次同样的搜索，不是接口参数。）

### 5.5 搜索页其它接口

| 接口 | 用途 |
|---|---|
| `GET /hot_tags?lang=` | 「熱門成人話題」标签云（实测返回 10 个字符串） |
| `GET /random_recommend` | 「隨機推薦」列表（条数未实测） |

---

## 6. 漫画详情页的评论（`/forum`）

### 6.1 入口

详情页有 3 个 tab：`漫畫介紹` / `目錄` / `評論`。评论 tab 挂载即请求：

```ts
// components/Comic/Comment.tsx
dispatch(FETCH_FORUM_THUNK({ mode: "all", page: 1, aid: queryId }));
```

### 6.2 `GET /forum`

```http
GET /forum?mode=all&page=1&aid=350234&lang=TW
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `mode` | 是 | `all`（全部，详情页固定用它）/ `manhua`（漫畫留言）/ `chat`（聊天大厅） |
| `page` | 是 | **从 1 开始**，每页 **10** 条（`ForumList.pageSize = 10`） |
| `aid` | 视场景 | 漫画 id（详情页必传） |
| `bid` | 视场景 | 部落格文章 id（BlogsDetail 用） |
| `nid` | 视场景 | 小说 id（`mode=novel`，小说详情/阅读用） |
| `ncid` | 视场景 | 小说章节 id |
| `uid` | 视场景 | 只看某用户的留言（「我的 → 我的評論」用 `{uid: memberInfo.uid, page}`） |
| `recommendBN` | 否 | 推荐书籍数组，仅发帖时用 |
| `lang` | 自动 | 语言 |

### 6.3 响应（实测 `mode=all&aid=350234`）

```json
{
  "list": [
    {
      "AID": "350234",          // 漫画 id
      "BID": null,              // 部落格 id
      "CID": "…",               // ★ 留言 id（删除/回复都用它）
      "NID": null,              // 小说 id
      "NCID": null,             // 小说章节 id
      "UID": "…",               // 用户 id
      "username": "…",
      "nickname": "…",
      "photo": "…",             // 头像文件名 → {img_host}/media/users/{photo}
      "content": "…",           // 留言正文（可能含 [推薦書籍 xxx] 标记）
      "likes": 0,               // 按讚數
      "gender": "…",
      "parent_CID": null,       // 父留言 id（回复关系）
      "expinfo": {              // 等级/徽章
        "level": 0,
        "level_name": "…",
        "badges": [ { "content": "…", "…": "…" } ]
      },
      "spoiler": false,         // 是否剧透（UI 会折叠）
      "addtime": "2026-10-16 12:34",  // 展示用时间串
      "update_at": 1792100000
    }
  ],
  "total": 5000
}
```

要点：

- `total` 被服务端**截断到 5000**，所以页数上限 `ceil(5000/10)=500`
- 评论列表**匿名可读**（实测无 `Authorization` 也返回 200）
- 头像/徽章图片都要拼 `img_host`：`{img_host}/media/users/{photo}`、`{img_host}{badge.content}`
- 留言正文里 `[推薦書籍 <id> ]` 这种方括号标记会被前端渲染成卡片（`ForumList.renderContent`）

### 6.4 评论总数从哪来

`/album` 响应里的 **`comment_total`**（`Desc.tsx:258` 显示在「評論」入口上）：

```json
{ "id": "350234", "…": "…", "comment_total": "…", "series": [ … ], "tags": [ … ] }
```

`/album` 完整字段（实测）：

```
id, name, images, addtime, description, total_views, total_photos, likes,
series, series_id, real_link, comment_total, author, tags, works, actors,
related_list, liked, is_favorite, is_aids, price, purchased
```

> **`addtime` 与"更新时间"（2026-10-20 复核）**：`addtime` 是 **10 位秒级时间戳字符串**
> （实测 `350234` → `"1655254028"`、`114514` → `"1560587558"`），官方前端只把它当封面 URL 的
> `?v=` 缓存串用，详情页**不展示任何日期**。
> 响应里**没有** `update_at` / `update_time` 这类"更新时间"字段——列表接口才有 `update_at`
> + `adddate`。所以本工程详情页把它当**上架时间**展示：`JM350234 · 上架 2022-06-15`
> （与 JM 号同一行，分隔方式照抄搜索结果列表），见 `albumAddTimeLabel`。

### 6.5 评论相关的其它接口（2026-10-22 已实测）

| 接口 | 方法 | 入参 | 说明 |
|---|---|---|---|
| `comment` | POST | `comment`, `aid`, `bid?`, `comment_id?`（回复时带） | 发评论（multipart） |
| `comment_delete` | POST | `comment_id`, `aid?`/`bid?`/`nid?`/`gid?` | 删评论 |
| `comment_vote` | — | — | **声明但 2.1.11 未使用** |
| `novel` 的 `comment` | POST | `nid`, `comment`, `comment_id?`, `ncid?` | 小说评论（同一路径 `comment`） |

`POST /comment`、`POST /comment_delete` 与 `/forum?uid=` 的**真实响应**现在补在
`docs/official-api-manga.md` §6.1～§6.3（2026-10-22 用真实账号跑通"发 → 回复 → 删"，测试内容已删）。
这里只记四条最容易写错的：

1. 两个 POST 的成败**只看 `data.status == "ok"`**：业务失败同样是 `HTTP 200 + 信封 code 200`；
2. 成功响应里 `cid` / `aid` 是**数字**，`msg` 可能带 `<br>`；
3. 未登录发评论是 **HTTP 401** + `errorMsg:"請先登入會員"`；
4. 「我的留言」用 `GET /forum?uid={自己}&page=n`，**不传 `mode` / `aid`**；回复不会单独成行。


---

### 6.6 本项目复核的实测更正（2026-10-19）

用 `aid=114514` 复核了一遍 `/forum`（签名与解密沿用本工程现有那一套：
`Token = md5(ts+"18comicAPP")`、`Tokenparam="{ts},2.0.13"`、`data` 用 `md5(ts+"185Hcomic3PAPP7R")` 解，
**通用**，不用换协议）。与上文 §6.3 的差异：

| 项 | §6.3 写的 | 实测 |
|---|---|---|
| `total` | 5000（被截断） | `"851"`（**字符串**；上限仍是 5000） |
| `likes` | `0`（数字） | `"0"`（字符串） |
| `spoiler` | `false`（布尔） | `"1"`（字符串），且同一本**每条都是 "1"** |
| `parent_CID` | `null`（主楼） | `"0"`（主楼） |
| `addtime` | `2026-10-16 12:34` | `Oct 11, 2026`（英文月日年；与请求不带 `lang` 有关） |
| `content` | 纯文本 + `[推薦書籍 xxx]` 标记 | **HTML 片段**：`<div style='flex-direction:row;flex-wrap:wrap;'>…</div>`，可能夹 `<img … alt="笑哭" />` 表情 |
| `replys` | 未提及 | **每条主楼自带 `replys` 数组**（内嵌回复，结构与主楼相同，不会再嵌一层） |
| `expinfo` | `level` / `level_name` / `badges` | 另有 `exp` / `expPercent` / `nextLevelExp` / `uid`；`level` 是数字，`badges` 可能是空数组 |

结论：**读评论只用 `/forum` 一个接口**就够（每页 10 条，`page` 从 1 开始），
不用再按 `parent_CID` 单独补回复；`spoiler` 字段拿不到可用语义（全是 `"1"`），不要据此折叠剧透。

## 7. 全量接口清单（85 个 key）

> 路径前缀统一 `https://{线路域名}/`；除注明外，响应都是 `{code, data(密文)}`。
> 「实测」列：✅ = 本次真实请求验证过；— = 按源码推断（未实测）。

### 7.1 App 设置 / 线路

| key | 路径 | 方法 | 参数 | 说明 | 实测 |
|---|---|---|---|---|---|
| `API_APP_SETTING` | `/setting` | GET | `app_img_shunt`,`lang`,`t` | 全局设置：`img_host`、版本、`ipcountry`、`ad_cache_version`、`app_shunts`… **选线路后第一个请求** | ✅ |
| `API_APP_SETTING` | `/setting` | POST | `language`,`t` | 同上；**切换语言时会打**（`SettingList.switchLanguage` → `FETCH_SETTINGS_THUNK`，详见 §2.6） | — |
| `token`（非路径） | — | — | — | `apiPaths.token = "185Hcomic3PAPP7R"`，仅用于签名 | ✅ |

### 7.2 首页

| key | 路径 | 方法 | 参数 | 说明 | 实测 |
|---|---|---|---|---|---|
| `API_COMIC_PROMOTE` | `/promote` | GET | — | 首页主推荐：**8 个分组**，每组 `content` 30 条 | ✅ |
| `API_COMIC_LATEST` | `/latest` | GET | `page`（**0 起**） | 首页「最新」瀑布流，30/页 | — |
| `API_COMIC_PROMOTE_LIST` | `/promote_list` | GET | `id`,`page` | 首页推荐分组「更多」 | — |
| `API_COMIC_SER_MORE_LIST` | `/serialization` | GET | `type`,`date`,`page` | 连载「更多」 | — |
| `API_COMIC_RANDOM_RECOMMEND` | `/random_recommend` | GET | — | 随机推荐 | — |
| `API_COMIC_HOT_TAGS` | `/hot_tags` | GET | `lang` | 热门标签，返回 `string[]`（实测 10 个） | ✅ |

`/promote` 响应（实测）：

```json
[ { "id":"1","title":"…","slug":"…","type":"…","filter_val":"…",
    "content":[ { "id","author","name","image","category":{id,title},
                  "category_sub":{id,title},"liked","is_favorite","update_at" } ] } ]
```

### 7.3 分类

| key | 路径 | 方法 | 参数 | 说明 | 实测 |
|---|---|---|---|---|---|
| `API_CATEGORIES_LIST` | `/categories` | GET | `lang` | 分类树（`categories[]` + `blocks[]`） | ✅ |
| `API_CATEGORIES_FILTER_LIST` | `/categories/filter` | GET | `page`,`o`,`c`,`lang` | 分类内容列表，80/页 | ✅ |

### 7.4 搜索

| key | 路径 | 方法 | 参数 | 说明 | 实测 |
|---|---|---|---|---|---|
| `API_COMIC_SEARCH` | `/search` | GET | `search_query`,`o`,`page`,`search_type`,`y`,`m`,`lang` | 漫画搜索，80/页，可能返回 `redirect_aid` | ✅ |
| `API_NOVEL_SEARCH` | `/search_novels` | GET | `search_query` | 小说搜索 | — |

### 7.5 漫画

| key | 路径 | 方法 | 参数 | 说明 | 实测 |
|---|---|---|---|---|---|
| `API_COMIC_DETAIL` | `/album` | GET | `id`,`lang` | 详情（含 `comment_total`、`series` 章节数组） | ✅ |
| `API_COMIC_READ` | `/comic_read` | GET | `id`(**章节id**),`express?` | 阅读内容：`images[{page,image}]`、`scramble_id`、`total_page` | ✅ |
| `API_COMIC_CHAPTER` | `/chapter` | GET | `id` | 旧版章节图片清单，**2.1.11 未使用**（改用 `comic_read`） | 未使用 |
| `API_ALBUM_DOWNLOAD` | `/album_download_2/{id}` | GET | 路径里的 `id` | 下载用的图片/压缩包清单 | — |
| `API_COIN_BUY_COMICS` | `/coin_buy_comics` | POST | `id` | 金币购买 AI 漫画 | — |
| `API_CHECK_RECOMMEND_BN` | `/check_recommend_book` | POST | `aid` | 检查推荐书籍（发评论时用） | — |

`/comic_read` 响应（实测 `id=350234`）：

```json
{ "id":"350234", "scramble_id":"220980", "name":"…", "total_page":25,
  "images":[ { "page":1, "image":"https://{cdn}/media/photos/350234/xxx.webp" } ],
  "addtime":…, "adddt":"…", "series_id":"…", "real_link":"…",
  "is_favorite":false, "liked":false }
```

- `images[].image` 是**完整 URL**（已指向 CDN），无需再拼 `img_host`
- 解扰需要 `aid`（漫画 id） + `scramble_id` + **图片文件名去掉扩展名**，算法见 §8.3

### 7.6 评论 / 社区

| key | 路径 | 方法 | 参数 | 说明 | 实测 |
|---|---|---|---|---|---|
| `API_FORUM_LIST` | `/forum` | GET | `mode`,`page`,`aid`/`bid`/`nid`/`ncid`/`uid`,`lang` | **评论列表**，10/页，`total` 上限 5000 | ✅ |
| `API_COMMENT_SEND` | `/comment` | POST | `comment`,`aid`,`bid?`,`comment_id?` | 发评论（mp4/form-data） | — |
| `API_COMMENT_DETELE` | `/comment_delete` | POST | `comment_id`,`aid?`/`bid?`/`nid?`/`gid?` | 删评论 | — |
| `API_COMMENT_VOTE` | `/comment_vote` | — | — | **声明未使用** | 未使用 |
| `API_NOVEL_COMMENT` | `/comment` | POST | `nid`,`comment`,`comment_id?`,`ncid?` | 小说评论（与上同路径） | — |

### 7.7 收藏 / 点赞 / 历史

| key | 路径 | 方法 | 参数 | 说明 |
|---|---|---|---|---|
| `API_FAVORITE_LIST` | `/favorite` | GET | `page`,`folder_id`,`o` | 收藏列表 |
| `API_FAVORITE_LIST` | `/favorite` | POST | `aid` | 加入收藏 |
| `API_LIKE_DATA` | `/like` | POST | `id`,`like_type?` | 按讚（`like_type=novel` 用于小说） |
| `API_FAVORITE_FOLDER` | `/favorite_folder` | POST | `type`,`folder_id?`,`folder_name?`,`aid?` | 收藏夹增删改 |
| `API_TAGS_FAVORITE` | `/tags_favorite` | GET | — | 收藏的标签列表 |
| `API_TAGS_FAVORITE_UPDATE` | `/tags_favorite_update` | POST | `type`,`tags` | 更新标签收藏 |
| `API_HISTORY_LIST` | `/watch_list` | GET | `page` | 观看历史 |
| `API_HISTORY_LIST` | `/watch_list` | POST | `id` | 上报观看 |

### 7.8 会员 / 签到 / 通知

| key | 路径 | 方法 | 参数 | 说明 |
|---|---|---|---|---|
| `API_MEMBER_LOGIN` | `/login` | POST | `username`,`password` | 登录，返回 `data.jwttoken` + 会员信息 |
| `API_MEMBER_LOOUT` | `/logout` | POST | — | 登出 |
| `API_MEMBER_REGISTER` | `/register` | POST | `username`,`password`,`password_confirm`,`email`,`gender` | 注册 |
| `API_MEMBER_REGISTER_CONFIRM` | `/confirm` | POST | `username`,`numcode?`,`password?` | 注册验证码 |
| `API_MEMBER_FORGOT` | `/forgot` | POST | `email` | 忘记密码 |
| `API_USEREDIT` | `/useredit/{uid}` | GET / POST | 路径 `uid`；POST 为资料字段 | 用户资料读写 |
| `API_DAILY` | `/daily` | GET | `user_id` | 签到面板 |
| `API_DAILY_CHECK` | `/daily_chk` | POST | `user_id`,`daily_id`,`date?` | 签到打卡 |
| `API_DAILY_LIST` | `/daily_list` | GET | `user_id` | 签到奖励列表 |
| `API_DAILY_LIST_FILTER` | `/daily_list/filter` | POST | `data` | 签到筛选 |
| `API_TASKS_LIST` | `/tasks` | GET / POST | GET：`type`,`filter`；POST：`type`,`uid`,`task_id?`,`new_sort_ids?` | 成就/任务 |
| `API_TASKS_BUY_LIST` | `/coin` | POST | `uid`,`task_id?` | 任务金币购买 |
| `API_COIN_BUY_CHARGE` | `/coin_buy_charge` | POST | — | 金币储值 |
| `API_AD_FREE` | `/ad_free` | POST | `type` | 去广告（`day`/`month`） |
| `API_AD_FREE_PAY` | `/payment` | GET | `key`,`pid`,`uid` | 支付方式/价格表 |
| `API_NOTIFICATIONS` | `/notifications` | GET | `type`,`subType`,`page` | 通知列表 |
| `API_NOTIFICATIONS` | `/notifications` | POST | `id`,`read` | 标记已读 |
| `API_NOTIFICATIONS_UNREAD` | `/notifications/unreadCount` | GET | — | 未读数 |
| `API_NOTIFICATIONS_SERTRACK` | `/album_sertracking` | GET / POST | `id` | 连载追踪状态/开关 |
| `API_NOTIFICATIONS_TRACK_LIST` | `/album_tracking` | POST | `page` | 追踪列表 |
| `API_TAG_BLOCK_SETTING` | `/tag_block` | GET / POST(JSON) | GET：`action`,`tag`；POST：`{tags:[...]}` | 标签屏蔽设置 |

### 7.9 小说

| key | 路径 | 方法 | 参数 | 说明 |
|---|---|---|---|---|
| `API_NOVEL_LIST` | `/novels` | GET | `o`,`t`,`nid` | 小说列表 |
| `API_NOVEL_DETAIL` | `/novel` | GET | `o`,`t`,`nid` | 小说详情 |
| `API_NOVEL_CHAPTERS` | `/novelchapters` | GET | `ncid`,`lang` | 小说章节正文 |
| `API_NOVEL_FAVORITES` | `/novel_favorites` | GET / POST | GET：`page`,`folder_id`,`o`；POST：`nid` | 小说收藏 |
| `API_EDIT_NOVEL_FAVORITES` | `/novel_favorites_folder` | POST | `type`,`folder_name`,`folder_id`,`nid` | 小说收藏夹 |
| `API_NOVEL_COIN_BUY` | `/coin_buy_nc` | POST | `id` | 购买小说章节 |
| `API_NOVEL_LIKE` | `/like` | POST | `id`,`like_type=novel` | 小说按讚 |

### 7.10 影片 / 游戏 / 部落格 / 书库

| key | 路径 | 方法 | 参数 | 说明 |
|---|---|---|---|---|
| `API_VIDEOS_LIST` | `/videos` | GET | `page`,`search_query`,`video_type`,`sub_category` | 影片列表（`video_type=movie_exclude` 为分区） |
| `API_LATEST_HANIME` | `/latest_hanime` | GET | — | 最新动画 |
| `API_VIDEO_INFO` | `/video` | GET | `id`,`video_type` | 影片详情/播放地址 |
| `API_BAITU_CREATE_TOKEN` | `/baitu-create-token` | GET | — | 播放器 token |
| `API_GAMES_LIST` | `/allgames` | GET | `page`,`search`,`category`,`game_type` | 游戏列表（同接口返回 `categories`/`hot_games`） |
| `API_GAME_INFO` | `/game/{id}` | GET | 路径 `id` | 游戏详情 |
| `API_BLOGS_LIST` | `/blogs` | GET | `page`,`blog_type` | 部落格列表 |
| `API_BLOG_INFO` | `/blog` | GET | `id` | 部落格文章 |
| `API_WEEK` | `/week` | GET | — | 每週必看 |
| `API_WEEK__FILTER_LIST` | `/week/filter` | GET | `id`,`type`,`page` | 每週必看筛选 |
| `API_CREATOR_AUTHOR` | `/creator_author` | GET | `page`,`search_query` | 书库-作者列表 |
| `API_CREATOR_WORK` | `/creator_work` | GET | `page`,`search_value`,`lang`,`source` | 书库-作品列表 |
| `API_CREATOR_WORK_DETAIL` | `/creator_author_work` | GET | `id`,`lang`,`source` | 作者作品 |
| `API_CREATOR_WORK_INFO` | `/creator_work_info` | GET | `id` | 作品资料 |
| `API_CREATOR_WORK_INFO_DETAIL` | `/creator_work_info_detail` | GET | `id` | 作品资料详情 |

### 7.11 广告

| key | 路径 | 方法 | 参数 | 解密 key | 说明 |
|---|---|---|---|---|---|
| `API_ADVERTISE_ALL` | `/ad_content_all` | GET | `ipcountry`,`v`,`lang` | **md5("185Hcomic3PAPP7R")**（不拼 ts） | 全部广告位，`data` 是以广告位 key 为键的 map |
| `API_ADVERTISE_CONTENT_COVER` | `/advertise_all` | GET | `ipcountry`,`v` | 同上 | 开屏/浮层广告 `{img:{app_pop3_cover,app_pop3_img}, link:{exchange_link}}` |
| `API_ADVERTISE` | `/advertise` | GET | `type=img`,`group`,`v` | 常规 | 单组 banner，`data.adv`；`group` 例：`app_movies_top_banner`、`game_banners` |
| `API_ADVERTISE_CONTENT` | `/ad_content` | — | — | 常规 | **声明未使用** |

广告位 key 例：`app_home_top`（首页顶部）、`app_route`（**选线路页**）、`app_search_bottom_jm3`、
`app_detail_introduction_bottom_jm3`、`app_forum_middle`、`app_movies_bottom` …（共 30+，见 `assets/JsonData.ts:filterAdKey`）。

### 7.12 客服 / 错误上报

| key | 路径 | 方法 | 参数 | 说明 |
|---|---|---|---|---|
| `API_SUPPORT_REPORT` | `/support_report` | GET | `action`（`form`/`list`/`detail`/`image`）,`srid`,`page` | 客服工单：表单分类 / 列表 / 详情 / 图片（`fetchImage`） |
| `API_SUPPORT_REPORT` | `/support_report` | POST | `category_id`,`sub_category_id`,`carrier_name`,`device_model`,`description`,`image?`,`verification` | 提交工单（multipart，可带图） |
| `API_ERROR_LOG` | `/error_log` | — | — | **声明未使用** |

---

## 8. 图片与解扰

### 8.1 `img_host` 从哪来

`GET /setting` 的 `img_host`（**每条线路不同**）：

| 线路 | `img_host`（实测） |
|---|---|
| `www.cdngwc.cc` | `https://cdn-msp.jmdanjonproxy.xyz` |
| `www.cdngwc.club` | `https://cdn-msp3.jmapiproxy1.cc` |
| `www.cdnutc.me` | `https://cdn-msp2.jmapiproxy3.cc` |

`app_img_shunt`（图源 1..4）会切换不同图床，UI 在「設定 → 切換圖源」。

### 8.2 URL 规则

| 用途 | 规则 |
|---|---|
| 封面（3:4） | `{img_host}/media/albums/{aid}_3x4.jpg?v={update_at}` |
| 封面（原始） | `{img_host}{item.image}`（`image` 字段形如 `/media/albums/350234.jpg`） |
| 章节图 | **`/comic_read` 直接返回完整 URL**：`https://{cdn}/media/photos/{chapterId}/{filename}.webp` |
| 用户头像 | `{img_host}/media/users/{photo}`；兜底 `{img_host}/media/users/nopic-Male.gif` |
| 徽章/图标 | `{img_host}{badge.content}` |
| 小说封面 | `{img_host}{novelDetail.images}` |

### 8.3 章节图解扰（scramble）

2.1.11 **不需要** `chapter_view_template`，`scramble_id` 就在 `/comic_read` 响应里。
`utils/Function.ts` 的实现：

```js
scramble_image(img, aid, scramble_id, page):
  if (img.src 含 ".gif" || Number(aid) < Number(scramble_id)) → 不处理，直接显示
  else num = get_num(aid, page); 按 num 把图切成 num 段并倒序拼接

get_num(aid, page):
  key = md5(String(aid) + String(page))       // page = 文件名去掉扩展名
  key = key 的最后一个字符的 charCode
  if (268850 <= aid <= 421925) key %= 10
  else if (aid >= 421926)      key %= 8
  num = {0:2, 1:4, 2:6, 3:8, 4:10, 5:12, 6:14, 7:16, 8:18, 9:20}[key] ?? 10
```

拼接细节：第 `i` 段源区域 `y = h - copyH*(i+1) - remainder`，第 0 段高度加 `remainder`，
其余段起点后移 `remainder`；渲染时用 canvas 覆盖在原 `<img>` 上，每段多垫 1px 消除接缝。

---

## 9. 热更新（OTA）

启动时（`utils/hotModule.ts`）：

| 用途 | 地址 |
|---|---|
| 版本检查 | `{apiUrl}static/jmapp3apk/version.json?t={Date.now()}` → `{"version":"2.1.11"}` |
| 加密 JS 包 | `{apiUrl}static/jmapp3apk/build.enc?v={本地版本}` |
| 解密参数 | `{apiUrl}static/jmapp3apk/encrypt_key.json` → `{"keyHex":"…","ivHex":"…"}` |
| 安装包 | `/setting` 的 `jm3_download_url` |

流程：`needUpdate()` → 下载 `build.enc` → `AES-CBC(Pkcs7, keyHex, ivHex)` 解密 → `JSZip` 解到
`Cache/update_{ver}/` → `WebView.setServerBasePath` 指向新目录 → reload。
远端版本比内置版本旧时忽略（防镜像未同步）。本次实测远端 `version.json` = `2.1.11`，与 APK 一致，**没有待更新包**。

> 这意味着「线上跑的前端代码 == APK 内的前端代码」，本文结论对当前线上同样成立。

---

## 10. 非 JM 的第三方接口

| 地址 | 用途 |
|---|---|
| `https://api4.ipify.org?format=json` | 测速页取公网 IPv4 |
| `https://ipwho.is/{ip}` | 测速页查 IP 归属国家 |
| `https://www.googletagmanager.com/gtag/js` | Google Analytics |
| `https://www.clarity.ms/tag/tqghr4tg54` | Microsoft Clarity（会注入脚本） |
| `https://discord.gg/V74p7HM` | 错误弹窗里的客服群 |
| `https://18comic.vip/` | 网页版主站（`main_web_host`） |

---

## 11. 最小可运行复现脚本（Node）

```js
const crypto = require('crypto');
const https  = require('https');

const VERSION = '2.1.11';
const SECRET  = '185Hcomic3PAPP7R';       // 注意：不是老协议的 18comicAPP
const HOST    = 'www.cdngwc.club';        // 任选一条线路

const md5 = s => crypto.createHash('md5').update(s).digest('hex');

function get(path, params = {}) {
  const ts = Math.floor(Date.now() / 1000);
  const url = `https://${HOST}/${path}?${new URLSearchParams({ ...params, lang: 'TW' })}`;
  return new Promise((resolve, reject) => {
    https.get(url, {
      headers: {
        'Token': md5(String(ts) + SECRET),
        'Tokenparam': `${ts},${VERSION}`,
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36',
      },
    }, res => {
      const buf = [];
      res.on('data', d => buf.push(d));
      res.on('end', () => {
        const body = JSON.parse(Buffer.concat(buf).toString('utf8'));
        // 两种 key 都试；广告接口改成 md5(SECRET)（不拼 ts）
        for (const seed of [SECRET, '18comicAPPContent']) {
          try {
            const key = Buffer.from(md5(ts + seed), 'utf8');   // 32 字节 ASCII！
            const d = crypto.createDecipheriv('aes-256-ecb', key, null);
            let out = d.update(body.data, 'base64', 'utf8');
            out += d.final('utf8');
            return resolve({ code: body.code, data: JSON.parse(out) });
          } catch (e) { /* 换下一个 key */ }
        }
        resolve({ code: body.code, data: null });
      });
    }).on('error', reject);
  });
}

(async () => {
  console.log((await get('categories')).data.categories.length);          // 9
  console.log((await get('categories/filter', { page: 1, o: '', c: 'doujin_chinese' })).data.total);
  console.log((await get('search', { search_query: 'test', page: 1, o: '' })).data.total);
  console.log((await get('forum', { mode: 'all', page: 1, aid: '350234' })).data.list.length); // 10
})();
```

线路清单解密（离线）：

```js
const key = Buffer.from(md5('diosfjckwpqpdfjkvnqQjsik'), 'utf8');  // 32 字节 ASCII
const d = crypto.createDecipheriv('aes-256-ecb', key, null);
let txt = d.update(cipherBase64, 'base64', 'utf8'); txt += d.final('utf8');
const { Server, jm3_Server } = JSON.parse(txt);
```

---

## 12. 与仓库现有实现（`feature/api/ApiClient.kt`）的差异

| 项 | 官方 2.1.11 | 现有 `ApiClient` |
|---|---|---|
| 签名密钥 | `185Hcomic3PAPP7R` | `18comicAPP` |
| `Tokenparam` 版本 | `2.1.11` | `2.0.13` |
| 搜索默认排序 `o` | `""`（最新） | `mr` |
| 分类 | `/categories` + `/categories/filter?c=&o=` | 未实现 |
| 评论 | `/forum?mode=&aid=&page=` | 未实现 |
| 章节图片 | `/comic_read?id={chapterId}` → 完整 URL + `scramble_id` | `/chapter` + `/chapter_view_template` |
| 线路发现 | 远端 `newsvr-2025.txt`（加密） | 手动配置域名 |
| 图片域名 | 每个请求从 `/setting.img_host` 取 | 常量 `IMAGE_DOMAIN` |

> 说明：两套签名/接口是不同代际的协议面，能同时存在；文档只描述官方 App 2.1.11 实际使用的那一套。

---

## 13. 字段字典（列表项）

漫画列表项（`/categories/filter`、`/search`、`/promote.content`、`/latest.list` 等通用）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 漫画 id（**字符串**，注意别当数字） |
| `name` | string | 标题 |
| `author` | string | 作者（可能空） |
| `description` | string | 简介（仅 `/search` 有） |
| `image` | string | 封面相对路径 `/media/albums/{id}.jpg` |
| `category` | `{id,title}` | 主分类 |
| `category_sub` | `{id,title}` | 子分类（可能 `null`） |
| `liked` | bool | 当前用户是否按讚 |
| `is_favorite` | bool | 是否已收藏 |
| `update_at` | number | 更新时间戳（秒） |
| `adddate` | string | `YYYY-MM-DD` |
| `tags` | string[] | 标签（部分接口带） |

---

## 14. 附录：接口 key → 源文件

| 源文件 | 负责的 key |
|---|---|
| `actions/settingAction.ts` | `setting`、`FETCH_HOST_THUNK`（死代码） |
| `api/ApiEndpointUtil.ts` | 线路清单拉取/解密、`getApiEndpoint()` |
| `api/HttpUtil.ts` | 签名、解密、重试、超时、四种 fetch |
| `actions/mainAction.ts` | `promote`、`latest`、`promote_list`、`serialization`、`coin_buy_comics`、`advertise_all`、`ad_content_all` |
| `actions/searchAction.ts` | `search`、`hot_tags`、`random_recommend` |
| `actions/categoriesAction.ts` | `categories`、`categories/filter` |
| `actions/detailAction.ts` | `album`、`album_download_2/{id}`、`comic_read` |
| `actions/forumAction.ts` | `forum`、`comment`、`comment_delete`、`check_recommend_book` |
| `actions/memberAction.ts` | 登录/注册/登出/忘记密码、`coin_buy_charge`、`ad_free`、`payment`、`favorite`、`like`、`favorite_folder`、`tags_favorite(_update)`、`tasks`、`coin`、`daily*`、`watch_list`、`useredit/{uid}`、`notifications*`、`album_sertracking`、`album_tracking`、`tag_block` |
| `actions/weekAction.ts` | `week`、`week/filter` |
| `actions/novelAction.ts` | `novels`、`novel`、`novelchapters`、`search_novels`、`novel_favorites(_folder)`、`coin_buy_nc` |
| `actions/moviesAction.ts` | `videos`、`latest_hanime`、`advertise`、`baitu-create-token` |
| `actions/moviesPlayerAction.ts` | `video` |
| `actions/gamesAction.ts` | `allgames`、`advertise` |
| `actions/blogsAction.ts` | `blogs`、`blog`、`game/{id}` |
| `actions/creatorAction.ts` | `creator_*` |
| `actions/contactAction.ts` | `support_report`（GET/POST/图片） |
| 无引用 | `error_log`、`comment_vote`、`ad_content`、`chapter` |
