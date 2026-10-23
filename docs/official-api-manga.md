# 禁漫（JMComic3）漫画接口参考 · 实测版

> **范围**：官方 App `2.1.11` 里**与漫画相关**的全部接口 —— 设置/线路、首页推荐、分类、搜索、详情、阅读、
> 评论、登录注册、个人资料、收藏、观看记录、标签。
> **不含**：小说、影片、游戏、部落格、广告、客服工单、签到、金币支付、通知（清单见 §10）。
>
> **实测环境**：`2026-10` 于本机 Node 脚本（`node v24`）直连 `www.cdngwc.club`，
> 用真实账号（`uid=6404735`）跑通登录 → 收藏/收藏夹 → 取消 → 还原，未留下任何测试数据。
> 文中的 `示例` 都是**真实响应**（只做两件事：脱敏 `jwttoken`/`s`/`email`/密码、长数组裁成 1～2 条）。
>
> `2026-10-22` 又用同一个账号把**评论**那三个接口补齐了实测（§6.1 / §6.2 / §6.3）：
> 发评论 → 发回复 → 删回复 → 删评论，测试内容当日已删干净。
>
> 标注 **⚠ 未实测** 的接口是按官方源码推断的（本机没跑，因为会产生公开数据，例如点赞）。
> 逆向过程与官方源码位置见 `docs/official-api-2.1.11.md`。
>
> **两套签名都能用**（实测）：官方 `2.1.11`（`185Hcomic3PAPP7R` / `2.1.11`）与
> 旧版（`18comicAPP` / `2.0.13`）对本文所有接口都返回正常结果。
> 本工程默认走官方这套（`core/protocol/ApiScheme.kt` 的 `SignatureScheme.DEFAULT`）。

---

## 1. 通用约定

### 1.1 基址

```
https://{线路域名}/{接口路径}?{query}
```

- 路径**没有** `/api` 之类前缀，直接是 `album`、`search`、`categories/filter`…（源码里都不带前导 `/`）。
- 线路域名由开屏页从加密清单里取（`docs/official-api-2.1.11.md` §3），实测可用：
  `www.cdngwc.cc`（本次偶发 522）、`www.cdngwc.club`、`www.cdnutc.me`。
- 图片域名**不写死**：取 `GET /setting` 的 `img_host`，每条线路不同。

### 1.2 请求头（每个请求都要）

| Header | 值 | 说明 |
|---|---|---|
| `Token` | `md5(String(ts) + "185Hcomic3PAPP7R")` | 小写 hex |
| `Tokenparam` | `"{ts},2.1.11"` | 秒级时间戳 + 逗号 + 版本，**无空格** |
| `User-Agent` | 浏览器 UA | 官方 WebView 不显式设置；原生复刻必须带一个正常 UA |
| `Authorization` | `Bearer {jwttoken}` | **仅登录后**；官方未登录时也发空串 |
| `Cookie` | `AVS={s}` | **仅登录后**，`s` 来自 `/login` 的 `data.s` |
| `Content-Type` | `multipart/form-data; boundary=…` | 仅 POST（官方用 `FormData`） |

- `ts` 是**每个请求**取一次；重试要沿用同一个 `ts`（解密密钥由它派生）。
- 签名**与登录态无关**：匿名就能调 `/album`、`/search`、`/forum`、`/categories`。

### 1.3 响应信封与解密

```json
{ "code": 200, "data": "<Base64 密文>" }
```

- `code == 200` 才算成功。
- `data` 是 **AES-256-ECB / PKCS7 / Base64**，密钥是 **`md5(ts + "185Hcomic3PAPP7R")` 这 32 个 hex 字符的
  ASCII 字节**（= 32 字节，AES-256；**不是** md5 摘要的前 16 字节）。
- 解不开时依次换 `md5(ts + "18comicAPPContent")`、`md5(ts + "18comicAPP")`。
- 广告接口（`ad_content_all` / `advertise_all`）例外：key 用 `md5("185Hcomic3PAPP7R")`（**不拼 ts**）。
- 明文通常是 JSON；**但 `/chapter_view_template` 返回的是 HTML**（不走信封，见 §5.4）。

### 1.4 状态码与错误形态（实测）

| 情形 | HTTP | 信封 `code` | `data` | 例子 |
|---|---|---|---|---|
| 成功 | 200 | 200 | 业务 JSON | 见各接口 |
| 未登录 / 登录失效 | **401** | 401 | `{"type":"auth_fail"}`（仍加密） | `GET /favorite` 带假 token |
| 业务失败 | **200** | 200 | `{"status":"fail","msg":"…","code":400}` | `DELETE` 型操作、参数不合法 |
| 参数完全不懂 | 400 | 400 | `{"status":0,"msg":"Invalid request"}` | `GET /tag_block` 不带参数 |
| 记录不存在 | 200 | 200 | **`data` 为 `null`** | `GET /album?id=99999999` |

> 两条容易踩的：
> ① **业务失败也是 HTTP 200**，必须进 `data` 里看 `status`/`code`；
> ② **`data:null` 是合法响应**（记录不存在时），不要当成解密失败。

### 1.5 通用参数

| 参数 | 出现范围 | 取值 / 说明 |
|---|---|---|
| `lang` | **所有 GET** | `CN` 简中 / `TW` 繁中。官方 `fetchGet` 自动补（`localStorage.lang \|\| "TW"`）。**POST 不补** |
| `page` | 分页接口 | 多数 **从 1 开始**；`/latest` **从 0 开始** |
| `o` | 排序 | 分类：`""`/`tf`/`mv`/`mv_m`/`mp_w`；搜索：`""`/`mv`/`mp`/`tf`/`old`；收藏：`mr`/`mp` |
| `t` | `/setting` | 秒级时间戳，防缓存 |
| `app_img_shunt` | `/setting` | 图源编号 `1..4`（对应 `app_shunts`） |

- 官方 `fetchGet` 会把 `undefined/null/""` 的**参数丢弃**；服务端对"显式传空串"也能接受（实测）。
- **字段类型很不规整**，见 §11 的清单（`total` 有时字符串有时数字、`update_at` 有时字符串…）。

---

## 2. 设置与线路

### 2.1 `GET /setting`

获取当前线路的全局配置。**选完线路后的第一个请求**，`img_host` 就来自这里。

| 参数 | 必填 | 说明 |
|---|---|---|
| `app_img_shunt` | 否 | 图源 `1..4` |
| `lang` | 自动 | `CN`/`TW` |
| `t` | 否 | 时间戳，防缓存 |

**返回（35 个字段，只列有用的）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `img_host` | string | **图片 CDN 基址**，拼封面/头像/章节图 |
| `version` / `jm3_version` | string | 网页版 `1.8.2` / App `2.1.11` |
| `base_url` | string | 当前线路 |
| `main_web_host` | string | 网页版主站 `18comic.vip` |
| `ipcountry` | string | 广告用 |
| `ad_cache_version` | string | 广告 `v` 参数 |
| `app_shunts` | array | `[{title,key}]` 图源列表 |
| `is_cn` / `cn_base_url` | number/string | 大陆线路判断与备用基址 |
| `is_hot_update` / `jm3_is_hot_update` | bool | 热更新开关 |
| `bundle_url` / `download_url` / `jm3_download_url` | string | 热更新包 / 安装包 |
| `app_landing_page` / `donate_url` / `store_link` | string | 落地页 / 赞助页 / 商店 |

```json
{
  "version": "1.8.2",
  "jm3_version": "2.1.11",
  "ipcountry": "CN",
  "ad_cache_version": "1792575887",
  "base_url": "https://www.cdngwc.club",
  "main_web_host": "18comic.vip",
  "img_host": "https://cdn-msp3.jmdanjonproxy.vip",
  "is_cn": 1,
  "cn_base_url": "https://cn-appdata.jmapiproxy2.cc",
  "app_shunts": [{ "title": "图源1", "key": 1 }, { "title": "图源2", "key": 2 }],
  "is_hot_update": false
}
```

> 还有 `POST /setting`（`language=0/1`，切换语言时官方会打）—— ⚠ 未实测，且不影响内容接口。

---

## 3. 首页与推荐

### 3.1 `GET /promote` — 首页主推荐

无参数。返回**分组数组**，实测 **8 组**，多数每组 30 条。

```json
[
  {
    "id": "26",
    "title": "连载更新→右滑看更多→",
    "slug": "",
    "type": "promote",
    "filter_val": "26",
    "content": [
      {
        "id": "1482221",
        "author": "うつきのこ あかりさと",
        "name": "帝都清扫服务团…",
        "image": "",
        "category": { "id": "3", "title": "短篇" },
        "category_sub": { "id": null, "title": null },
        "liked": false,
        "is_favorite": false,
        "update_at": 1792570627
      }
    ]
  }
]
```

实测 8 组标题：`连载更新` / `C108&推荐本本` / `禁漫去码&全彩化` / `禁漫汉化组` / `韩漫更新` / `其他更新` /
`禁漫书库` / `禁漫小说`。

### 3.2 `GET /promote_list` — 某组「更多」

| 参数 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 组 id（即 `/promote` 的 `id` / `filter_val`） |
| `page` | 是 | 从 1 开始 |

```json
{
  "total": "133",
  "list": [
    {
      "id": "1116808",
      "author": "ゴ太郎",
      "name": "只有我的疫苗能拯救丧尸化的世界…",
      "category": { "id": "2", "title": "单本" },
      "category_sub": { "id": null, "title": null },
      "liked": false,
      "is_favorite": false,
      "update_at": 1791619632
    }
  ]
}
```

> 实测 `id=26&page=1` → 27 条（`total:"133"`）。

### 3.3 `GET /latest` — 最新

| 参数 | 必填 | 说明 |
|---|---|---|
| `page` | 是 | **从 0 开始**，实测每页 **80** 条 |

返回**数组**（不是对象）：

```json
[
  {
    "id": "1484488",
    "author": "Moon",
    "name": "【卡拉斯科学士个人机翻】[Moon] 皮奥怀上了小马【Chinese】",
    "image": "",
    "category": { "id": "1", "title": "同人" },
    "category_sub": { "id": "1", "title": "同人" },
    "liked": false,
    "is_favorite": false,
    "update_at": 1792568823
  }
]
```

### 3.4 `GET /serialization` — 连载

| 参数 | 必填 | 说明 |
|---|---|---|
| `type` | 是 | 分类 slug（如 `hanman`） |
| `date` | 否 | 日期串，空 = 全部 |
| `page` | 是 | 从 1 开始，实测 **40/页** |

```json
{
  "list": [
    {
      "id": "1033429",
      "author": "Park sin blapin",
      "description": null,
      "name": "财阀家的女婿 / 财阀家的女婿",
      "image": "/media/albums/1033429_3x4.jpg?u=1792468753",
      "category": { "id": "5", "title": "韩漫" },
      "category_sub": { "id": "0", "title": null },
      "liked": false,
      "favorite": false,
      "update_at": "1792468753"
    }
  ],
  "total": 120
}
```

> ⚠ 这个接口的列表项**与别处不一致**：用 `favorite` 而不是 `is_favorite`，`update_at` 是**字符串**，
> `image` 是**相对路径**且带 `_3x4.jpg?u=`。

### 3.5 `GET /week` — 每周必看：筛选项

无参数，返回"有哪几周 + 有哪几个分类"：

```json
{
  "categories": [
    { "id": "262", "title": "", "time": "2026第 261周 10.16 - 10.09" },
    { "id": "261", "title": "", "time": "2026第 260周 10.09 - 10.02" }
  ],
  "type": [
    { "id": "hanman", "title": "韩漫" },
    { "id": "another", "title": "其他" }
  ]
}
```

### 3.6 `GET /week/filter` — 每周必看：某周某分类的列表

| 参数 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 周 id（`/week` 的 `categories[].id`） |
| `type` | 是 | 分类 id；**`all` 实测可用**（一次拿全部，20 条） |
| `page` | 是 | 从 1 开始 |

```json
{
  "total": 1,
  "list": [
    {
      "id": "1480022",
      "author": "sonmohn 庞达 空古米",
      "description": "本以为她是朋友的姐姐才向她表白…",
      "name": "朋友妈妈我天菜",
      "image": "",
      "category": { "id": "5", "title": "韩漫" },
      "category_sub": { "id": null, "title": null },
      "liked": false,
      "is_favorite": false,
      "update_at": 1791992222
    }
  ]
}
```

> `type=hanman` → 1 条；`type=all` → 20 条。

### 3.7 `GET /random_recommend` — 随机推荐

无参数，返回数组，实测 **30** 条；列表项**带 `description`**（多数列表接口不带）。

---

## 4. 分类与搜索

### 4.1 `GET /categories` — 分类树

| 参数 | 必填 | 说明 |
|---|---|---|
| `lang` | 自动 | 分类名会跟着变（`漢化`/`汉化`） |

实测 **10 个主分类 + 4 组标签块**：

| id | name | slug | 子分类数 |
|---|---|---|---|
| 0 | 最新A漫 | `""` | 0 |
| 1 | 同人 | `doujin` | 5 |
| 2 | 单本 | `single` | 5 |
| 3 | 短篇 | `short` | 4 |
| 4 | 其他类 | `another` | 3 |
| 5 | 韩漫 | `hanman` | 1 |
| 6 | English Manga | `meiman` | 6 |
| 7 | Cosplay | `another_cosplay` | 0 |
| 8 | 3D | `3D` | 0 |
| 9 | 禁漫汉化组 | `禁漫汉化组` | 0 |

```json
{
  "categories": [
    { "id": 0, "name": "最新A漫", "slug": "", "total_albums": 0 },
    {
      "id": "1",
      "name": "同人",
      "slug": "doujin",
      "type": "slug",
      "total_albums": "354979",
      "sub_categories": [
        { "CID": "1", "name": "汉化", "slug": "chinese" },
        { "CID": "2", "name": "日语", "slug": "japanese" }
      ]
    }
  ],
  "blocks": [
    { "title": "主题A漫", "content": ["剧情向", "校园"] },
    { "title": "角色 / 扮演", "content": ["萝莉", "御姐"] }
  ]
}
```

- `blocks` 是**标签云**，点击后走 `/search?search_query=<标签>`（分类的 slug **不**传给搜索）。
- 第一个分类 `id=0 / slug=""` 是前端合成的"最新A漫"，请求 `/categories/filter` 时 `c` 传空。

### 4.2 `GET /categories/filter` — 分类内容列表

| 参数 | 必填 | 说明 |
|---|---|---|
| `page` | 是 | 从 1 开始，实测 **80/页** |
| `c` | 是 | `slug` 或 `slug_subSlug`（如 `doujin_chinese`）；「最新A漫」传空串 |
| `o` | 否 | 排序码，见下 |
| `lang` | 自动 | |

排序码：`""` 最新（默认）/ `tf` 最多爱心 / `mv` 总排行 / `mv_m` 月排行 / `mp_w` 周排行。

```json
{
  "search_query": "",
  "total": 10000,
  "content": [
    {
      "id": "1224005",
      "author": "zensori",
      "name": "108P！～一年后一定要108P的故事～…",
      "image": "",
      "category": { "id": "2", "title": "单本" },
      "category_sub": { "id": null, "title": null },
      "liked": false,
      "is_favorite": false,
      "update_at": 1792570155,
      "adddate": "2025-10-15"
    }
  ],
  "tags": ["阿妈我鸡鸡疼", "观帆"]
}
```

- **`total` 被截断到 10000**（前端按 `ceil(total/80)` 算页数，最多 125 页）。
- `tags` 是本页出现的热门标签（少量）。
- 列表项**没有** `description`（搜索接口才有）。

### 4.3 `GET /search` — 搜索

| 参数 | 必填 | 说明 |
|---|---|---|
| `search_query` | 是 | 关键字；**标签也走这里**（不要带 `#`） |
| `o` | 否 | `""`/`mv`/`mp`/`tf`/`old` |
| `page` | 否 | 从 1 开始，实测上限 80/页 |
| `search_type` | 否 | `site`(默认) / `work` / `author` / `tag` / `character` |
| `y` | 否 | 年份 `2017..今年`，空 = 全部 |
| `m` | 否 | 月份 `1..12` |
| `lang` | 自动 | |

```json
{
  "search_query": "YAOI",
  "search_type": "tag",
  "y": 0,
  "m": 0,
  "total": 10000,
  "content": [
    {
      "id": "1484567",
      "author": "Mon3tre",
      "description": null,
      "name": "[Mon3tre] 预川的烦恼 [中文] [檬同个人OC]",
      "image": "",
      "category": { "id": "1", "title": "同人" },
      "category_sub": { "id": "1", "title": "同人" },
      "liked": false,
      "is_favorite": false,
      "update_at": 1792568823,
      "adddate": "2026-10-21"
    }
  ]
}
```

- `y`/`m` 会**原样回显**（不传时回 `0`）。
- 实测 `search_query=""&y=2024&m=6` → `total: 0`（空关键字 + 年月过滤没结果，别用它做"按年月浏览"）。
- 某些关键字会命中"精确作品"，响应里带 **`redirect_aid`**，官方直接跳详情页。
- 搜索结果的 `description` 常为 `null`（不是所有条目都有简介）。

### 4.4 `GET /hot_tags` — 热门标签

无参数，返回 **10 个字符串**：

```json
["再有违法社群规范TAG一律斩帐号", "鱼与熊掌一定要兼得"]
```

---

## 5. 漫画详情与阅读

### 5.1 `GET /album` — 详情

| 参数 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 漫画 id |
| `lang` | 自动 | 影响 `tags`/`description` 的中文形态 |

```json
{
  "id": 350234,
  "name": "董卓 上+下",
  "images": [],
  "addtime": "1655254028",
  "description": "",
  "total_views": "1765514",
  "total_photos": 50,
  "likes": "92870",
  "series": [
    { "id": "350234", "name": "", "sort": "1" },
    { "id": "350235", "name": "下", "sort": "2" }
  ],
  "series_id": "350234",
  "real_link": "",
  "comment_total": "9793",
  "author": ["N/A", "孙笑川", "姜萍", "东雪莲", "牢大", "皮特·格里芬", "丁真"],
  "tags": ["YAOI", "中文", "欺诈", "骗子全家出演", "骗子死全家", "前面标签都是骗人，很治愈的本子"],
  "works": ["骗子死了马马", "鸡吧骗子"],
  "actors": ["推荐这部作品的死骗子", "骗子全家"],
  "related_list": [
    { "id": "10", "author": "明话ぉん", "name": "[村长个人汉化] (SC2017 Winter) …", "image": "" },
    { "id": "17", "author": "一ノ瀬ぽん", "name": "[无毒汉化组] (C92) …", "image": "" }
  ],
  "liked": false,
  "is_favorite": false,
  "is_aids": false,
  "price": "",
  "purchased": ""
}
```

要点：`id` 是 **number**；`addtime` 是**秒级时间戳字符串**；`series[].id` 就是章节 id；
`series` 里 **`name` 常为空串**（只有 `sort` 有值）；`images` 在 2.1.11 里恒为 `[]`（图在 `/comic_read` 或 `/chapter`）；
`author/tags/works/actors` 都是数组；`related_list` 实测 12 条。

### 5.2 `GET /comic_read` — 章节内容（2.1.11 主用）

| 参数 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | **章节 id**（= `/album` 的 `series[].id`） |
| `express` | 否 | 未知语义，官方不传 |

```json
{
  "id": 350234,
  "scramble_id": "220980",
  "name": "董卓 上+下",
  "total_page": 25,
  "images": [
    { "page": 1, "image": "https://cdn-msp3.jmdanjonproxy.vip/media/photos/350234/00001.webp?t=1772592330" },
    { "page": 2, "image": "https://cdn-msp3.jmdanjonproxy.vip/media/photos/350234/00002.webp?t=1772592330" }
  ],
  "addtime": "1655254028",
  "adddt": "2022-06-06 08:47:08",
  "series_id": "350234",
  "real_link": "",
  "is_favorite": false,
  "liked": false
}
```

- `images[].image` 是**完整 URL**（已指向 CDN，带 `?t=` 缓存串），不需要再拼 `img_host`。
- `scramble_id` 就在响应里（**不再需要** `/chapter_view_template`）。
- 解扰算法见 `docs/official-api-2.1.11.md` §8.3（`md5(aid + 文件名去扩展名)` 末位字符取模）。

### 5.3 `GET /chapter` — 章节清单（旧机制，2.1.11 未使用）

| 参数 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 章节 id |

```json
{
  "id": 350234,
  "series": [{ "id": "350234", "name": "", "sort": "1" }],
  "tags": "YAOI 中文 欺诈 骗子全家出演 …",
  "name": "董卓 上+下",
  "images": ["00001.webp", "00002.webp"],
  "addtime": "1655254028",
  "real_link": "",
  "series_id": "350234",
  "is_favorite": false,
  "liked": false
}
```

与 `/comic_read` 的差别：`images` 只有**文件名**、`tags` 是**空格分隔的字符串**、没有 `scramble_id`。

### 5.4 `GET /chapter_view_template` — 旧机制：`scramble_id`

| 参数 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 章节 id |

**返回 HTML**（实测 ~20 KB），不是 JSON 信封、不需要解密，里面有一段：

```js
var scramble_id = 220980;
```

用途：走 `/chapter` 旧机制时补 `scramble_id`。取不到时的兜底是 `220980`（`JmcomicService` 的行为）。

### 5.5 `GET /album_download_2/{id}` — 整本下载地址

**id 在路径里**，无业务 query 参数（本次调用自动带了 `lang=CN`，服务端接受）。
实测在**登录态**下调用；返回的 `download_url` 里带 `uid` 与 `expires`，推测需要登录（未验证匿名调用）。

```json
{
  "title": "董卓 上+下",
  "fileSize": "3.1 MB",
  "download_url": "https://dl2025.cdngwc.club/download_zip?md5=38FH1l8QfSWfcnOy0W2GbA&expires=1792578792&aid=350234&uid=6404735",
  "img_url": "https://cdn-msp3.jmdanjonproxy.vip/media/albums/350234.jpg?v=1772592330"
}
```

> `download_url` 带 `expires`（秒级）与 `uid`，**会过期**，不能缓存复用。

---

## 6. 评论

### 6.1 `GET /forum` — 评论列表

| 参数 | 必填 | 说明 |
|---|---|---|
| `mode` | 是 | `all`（全部）/ `manhua`（漫画留言）/ `chat`（大厅） |
| `page` | 是 | 从 1 开始，实测 **10/页** |
| `aid` | 视场景 | 漫画 id（详情页必传） |
| `uid` | 视场景 | 只看某用户（「我的评论」用 `uid=<自己>`） |
| `bid`/`nid`/`ncid` | 视场景 | 部落格 / 小说 |
| `lang` | 自动 | 影响 `addtime` 格式（带 lang → `2026-10-16 12:34`；不带 → `Oct 20, 2026`） |

```json
{
  "list": [
    {
      "AID": "350234",
      "BID": null,
      "CID": "11182932",
      "NID": null,
      "NCID": null,
      "UID": "12964198",
      "username": "chenguoyu",
      "nickname": "chenguoyu",
      "likes": "0",
      "gender": "Male",
      "update_at": "0",
      "addtime": "Oct 20, 2026",
      "parent_CID": "0",
      "expinfo": {
        "level_name": "奔跑的春风",
        "level": 5,
        "nextLevelExp": 4200,
        "exp": "3800",
        "expPercent": 90.47619047619048,
        "uid": "12964198",
        "badges": []
      },
      "name": "JM350234",
      "content": "<div style='flex-direction:row;flex-wrap:wrap;'>陵水中学高三黄德奥实名发射</div>",
      "photo": "nopic-Male.gif",
      "spoiler": "1"
    }
  ],
  "total": 5000
}
```

- `content` 是 **HTML 片段**（可能夹表情 `<img>`），渲染前要处理。
- `photo` 是头像**文件名**：拼 `{img_host}/media/users/{photo}`。
- `name` 是漫画展示名（`JM{aid}`），"我的评论"列表可以直接用它当来源标注。
- `parent_CID` 顶层是 `"0"`；`replys`（内嵌回复）**是否出现不稳定** —— 本次 `mode=manhua` 的样本里没有，
  早期对 `aid=114514` 的探测里见过，所以按"可选字段"处理，不要假设一定有。
- `total` 被截断到 **5000**；本次是 **number**（早前实测过字符串，**类型不稳定**）。
- 匿名可读（不带 `Authorization` 也 200）。

### 6.2 `POST /comment` — 发评论 / 回复（2026-10-22 实测）

| 参数 | 必填 | 说明 |
|---|---|---|
| `comment` | 是 | 正文，官方 UI 限 200 字 |
| `aid` | 是 | 漫画 id |
| `comment_id` | 否 | 回复时传父评论 `CID` |

```json
// 成功（HTTP 200，信封 code 200）
{ "msg": "評論成功發布！您已完成每日發表評論，獲得「5」經驗值<br>您已完成每日發表評論，獲得「3」金幣",
  "status": "ok", "aid": 1484920, "cid": 11191109, "spoiler": "1" }
```

- **成败只看 `data.status`**（`ok` / `fail`），HTTP 与信封 `code` 都是 200；
- `cid` / `aid` 是**数字**；`msg` 里可能带 `<br>`，展示前要清洗；
- 服务端会拒**含英文字母 / 数字**的正文：
  `抱歉，暂时不可输入英文和数字，请确认后重新送出。…`（`status=fail`）；
- 连发太快会回 `勿短時間重複留言…` / `請勿於短時間內連續發文。`（`status=fail`，与内容无关）；
  > 官方 App 在发送前弹的那个**数学题验证码只是前端门槛**：`NewTopicModal` 里点「发布」→
  > 弹题 → 答对才调 `FETCH_FORUM_SEND_THUNK({comment, aid[, comment_id]})`，**验证码不进请求**。
  > 所以它跟这条"重复留言"冷却提示无关（同一个请求隔几分钟再发就能成功）。
- 未登录：**HTTP 401**，信封带 `errorMsg:"請先登入會員"`，`data` 解出来是 `{"type":"auth_fail"}`；
- 正文实测会被服务端**统一成繁体**（请求带 `lang=ZH` 也一样），所以列表里的文字可能与刚输入的不同。

### 6.3 `POST /comment_delete` — 删评论（2026-10-22 实测）

`comment_id`（= `CID`）+ `aid`（官方 `ForumList.tsx` 还会带 `bid` / `nid`，本工程只用前两个）。

```json
// 成功
{ "msg": "評論成功刪除！", "status": "ok", "cid": 11191109, "aid": 1484920, "bid": 0, "nid": 0, "gid": 0 }
// 删别人的 / 已经删过
{ "msg": "無法刪除評論！ ？", "status": "fail", "cid": 11187248, "aid": 114514, "bid": 0, "nid": 0, "gid": 0 }
```

同样是 `HTTP 200 + 信封 code 200`，只看 `status`。**回复也能用同一个接口删**（`comment_id` 传回复的 `CID`）。

> 版面：`GET /forum?uid=` 的「我的留言」列表里**不会**出现回复——回复只挂在主楼的 `replys` 里，
> 所以那个列表实际上是"我发过的主留言"。

### 6.4 `POST /check_recommend_book` — 检查能否推荐书籍

`aid` → 实测返回：

```json
{ "status": "ok", "msg": "正确" }
```

（发带"推荐书籍"的评论前的前置检查。）

---

## 7. 账号

### 7.1 `POST /login`

| 参数 | 必填 |
|---|---|
| `username` | 是 |
| `password` | 是 |

成功：`code=200`，`data` = 会员资料（26 个字段）。失败：HTTP/信封 **401** + `errorMsg`。

```json
{
  "uid": "6404735",
  "username": "1527188922",
  "email": "<redacted>",
  "emailverified": "yes",
  "photo": "nopic-.gif?v=0",
  "fname": "",
  "gender": "",
  "message": "Welcome 1527188922!",
  "coin": "865",
  "album_favorites": 178,
  "album_favorites_max": 600,
  "s": "<redacted>",
  "level_name": "地上的月影",
  "level": 4,
  "nextLevelExp": 2100,
  "exp": "1490",
  "expPercent": 70.95238095238095,
  "badges": [],
  "ad_free": false,
  "ad_free_before": "0000-00-00 00:00:00",
  "charge": "0/30",
  "jar": "0/1",
  "invitation_qrcode": "https://comic18j-guta.quest/uiqrcode?uicode=3344178735",
  "invitation_url": "https://comic18j-guta.quest/signup?uicode=3344178735",
  "invited_cnt": "0",
  "jwttoken": "<redacted>"
}
```

- `jwttoken` → `Authorization: Bearer …`；`s` → `Cookie: AVS=…`。**两个都要存**。
- 类型注意：`level`/`album_favorites`/`album_favorites_max`/`nextLevelExp` 是 **number**，
  `exp`/`coin`/`charge`/`jar`/`invited_cnt` 是 **string**，`ad_free` 是 **bool**。
- `charge` = `"已充能/上限"`，`jar` = `"已兑换/上限"`；`ad_free_before` 未开通时 `0000-00-00 00:00:00`。
- **`album_favorites` 就是收藏总数**，`album_favorites_max` 是上限（本账号 600）。

### 7.2 `POST /logout` ⚠ 未实测

无参数。按源码返回 `code=200`；客户端无论如何都要清本地凭证。

### 7.3 `POST /register`（2026-10-23 实测：成功 + 失败形态）

| 参数 | 必填 | 说明 |
|---|---|---|
| `username` | 是 | 服务端上限实测 **15 个字符**（`用户名只能包含15个字符!`），不是官方前端写的 `maxLength=50` |
| `password` | 是 | |
| `password_confirm` | 是 | |
| `email` | 是 | |
| `gender` | 是 | `Male` / `Female` |

注册后**还要走邮箱验证码**（§7.4）才能登录。

**成功形态（2026-10-23 真机实测，用户完成了一次真实注册）**

```json
{
  "type": "",
  "msg": "您已注册。检查您的电子邮件中的确认链接!",
  "status": "ok",
  "errors": ""
}
```

两个**容易踩死的细节**：

1. **成功时 `errors` 是空字符串 `""`，不是空数组 `[]`**。把它声明成 `List<String>`
   会在反序列化阶段直接抛
   `Unexpected JSON token at offset …: Expected start of the array '[', but had '"' instead`，
   表现为"邮件已经发出、账号已经创建，但 App 报解析错误"（本工程首轮真机就踩了这个）。
   客户端必须对 `errors` 做**数组 / 字符串 / 空串 / null 的宽容解析**
   （本工程 `LooseStringListSerializer`）。
2. **`msg` 是给用户的通知文案**（"您已注册…"），不是错误；`status == "ok"` 时按成功处理。

**失败形态（2026-10-23 实测，用已占用用户名 / 非法邮箱发的请求，没有创建账号）**

- 与收藏类接口同构：**HTTP 200 + 信封 `code` 200**，真正结果在解密后的 `data` 里：

```json
{
  "type": "",
  "msg": "电子邮件是不是有效的电子邮件地址!1",
  "status": "fail",
  "errors": [
    "电子邮件是不是有效的电子邮件地址!1",
    "用户名已经存在!"
  ]
}
```

- **顶层还有一个明文 `errorMsg`**，值是 `errors` 的第一条；客户端按 `data` 判成败即可
  （本工程 `postDecrypted` 解密后读 `status` / `msg` / `errors`）。
- **`msg` 只给第一条错误，`errors` 才是全部**。实测一次能同时返回
  「邮箱格式 + 用户名已存在 / 超长 + 两次密码不一致」三四条，界面必须展示 `errors`。
- 邮箱校验比官方前端正则**更严**：`probe-only@example.com` 这种能过官方正则的地址，
  服务端仍会判 `电子邮件是不是有效的电子邮件地址!`（疑似校验域名可达性）。
  所以本地只做格式首筛，**服务端原文照旧展示**。
- 同一个用户名在**未验证**状态下再次 `POST /register` 会回「用户名已经存在」，
  所以「已有验证码」必须走 §7.4 的 `confirm`，而不是重新注册。

### 7.4 `POST /confirm`（2026-10-23 补测：失败形态）

| 用途 | 参数 |
|---|---|
| 提交验证码 | `username` + `numcode` |
| 重新发送验证码 | `username` + `password` |

返回 `{status,msg}`。

**失败形态（2026-10-23 实测）**

- 与 `/register` 完全同构：HTTP 200、信封 `code` 200、`data` 解密后
  `{status:"fail", msg:"…", errors:[…]}`，顶层再带一个明文 `errorMsg`。
- **成功时 `errors` 同样是空字符串**（与 §7.3 的实测一致），所以 `errors` 的类型
  在成功/失败之间不固定，客户端必须宽容解析。
- 实测样本（不存在的用户名 + 错误验证码）：
  `{"status":"fail","msg":"用户名已经存在!","errors":["用户名已经存在!"]}`
  —— 服务端的文案本身就不一定准确（这里用户名显然不存在），**客户端原样展示，不自造文案**。
- 重发（`username + password`，不存在的账号）实测：
  `无效的核查身份证!您确定此ID是正确的？ !2`（同样是服务端原文）。
- 官方 UI 的 **60 秒重发冷却只是前端限制**（`MemberModal.tsx` 的 `resendCooldown`），
  服务端没有这个状态；本工程也在 ViewModel 里做了同样的倒计时。


### 7.5 `POST /forgot` ⚠ 未实测

`email` → 返回 `{status,msg}`（发重置邮件）。

### 7.6 `GET /useredit/{uid}` — 读个人资料

**uid 在路径里**。返回 24 个字段：

```json
{
  "nickName": "",
  "password": "",
  "password_confirm": "",
  "firstName": "",
  "lastName": "",
  "gender": null,
  "birthday": "1970-01-01",
  "relations": "",
  "sexuality": "",
  "website": "",
  "birthPlace": "",
  "city": "",
  "country": "",
  "occupation": "",
  "company": "",
  "school": "",
  "aboutMe": "",
  "infoHere": "",
  "collections": "",
  "ideal": "",
  "erogenic": "",
  "favorite": "",
  "hate": "",
  "status": "0"
}
```

- `password`/`password_confirm` 返回**空串**（不是哈希）。
- `gender` 可能是 `null` 也可能是 `""`（类型不稳定）。
- `birthday` 是 `YYYY-MM-DD`；**空值/`0000-00-00` 会被服务端归一成 `1970-01-01`**（实测）。
- `relations`：`""`/`Single`/`Taken`/`Open`；`sexuality`：`""`/`Guys`/`Girls`/`Guys + Girls`。

### 7.7 `POST /useredit/{uid}` — 写个人资料

**整条记录覆盖**（实测，重要）：提交的字段才生效，**没提交的字段会被写成默认值**
（例：只发 `nickName` → `birthday` 从 `0000-00-00` 变成 `1970-01-01`）。

- 入参就是 §7.6 那一组字段（`password`/`password_confirm` 留空 = 不改密码；实测“空密码字段”不会改密码，**登录仍正常**）。
- 返回**更新后的整条资料对象**（不是 `{status,msg}`）。
- 想"只改昵称"也必须把 GET 到的**全部字段**原样回传——这也是官方 App 的做法。

**2026-10-23 本工程实现时补的两条防御**

1. **`GET` 必须发生在渲染表单之前**。官方 `InfoModal.tsx` 用
   `defaultValue={formData.nickName}`，而 `formData` 是 `GET /useredit` 回来后才 `setFormData` 的：
   首帧是空对象，`defaultValue` 之后不会再更新，于是**已有资料回显不出来**。
   本工程改成"加载完成才渲染表单 + Compose 受控状态"，并以**刚刚 GET 到的值**作为提交底稿
   （详见 `feature/ui/AccountViewModels.kt` 的 `AccountProfileEditViewModel`）。
2. **`POST` 的业务失败要能识别**。文档写"返回更新后的整条资料"，但为防服务端哪天改成
   `{status:"fail",msg}`，`UserEditDto` 也读了 `msg`；`AccountRepository.saveUserEdit`
   遇到 `status == "fail"` 或非空 `msg` 时抛业务错误，不会把失败当成功。
3. `birthday` 的空值 / `0000-00-00` 被归一成 `1970-01-01` 且**改不回去**，所以界面上把
   `1970-01-01` 也当成「未设置」展示（用户不可能真的选 1970 年当生日），提交时仍按原值/空串回传。


---

## 8. 收藏（本次实测最细的部分）

### 8.1 `GET /favorite` — 收藏列表

| 参数 | 必填 | 说明 |
|---|---|---|
| `page` | 是 | 从 1 开始，实测 **20/页**（响应里 `count:20`） |
| `folder_id` | 否 | 收藏夹 `FID`；**空 = 全部**（传 `0` 也等于全部，实测） |
| `o` | 否 | `mr` 收藏时间（默认，实测与不传一致）/ `mp` 更新时间 |

```json
{
  "list": [
    {
      "id": "1225147",
      "author": "CunnyFunky",
      "description": "",
      "name": "[CunnyFunky] Shigure Ui [AI Generated]",
      "latest_ep": null,
      "latest_ep_aid": null,
      "image": "",
      "category": { "id": "1", "title": "同人" },
      "category_sub": { "id": "3", "title": "短篇" }
    }
  ],
  "folder_list": [
    { "FID": "1780873", "UID": "6404735", "name": "本子" },
    { "FID": "4090037", "UID": "6404735", "name": "AI" }
  ],
  "total": "178",
  "count": 20
}
```

- **`total` 是字符串**；`count` 是每页条数。
- `folder_list` 只有 `{FID, UID, name}`：**没有数量、没有封面、没有排序字段、没有"默认收藏夹"**。
- 列表项**没有** `update_at` / `liked` / `is_favorite`（和分类/搜索的列表项不一样）。
- `FID` 是**字符串数字**（`"1780873"`）。

**`list` 的语义（容易误解，单独说清）**

`list` 是**"当前这次请求的筛选条件下的当前页"**，不是"全部收藏的前 20 条"：

| 请求 | `list` 是什么 | 实测 |
|---|---|---|
| `folder_id` 空（或不传 / 传 `0`） | **全部收藏**（所有夹内的 + 未归类的并集）的第 `page` 页 | `page=1` → 20 条，`total:"178"` |
| `folder_id=<FID>` | **该收藏夹**的第 `page` 页 | `本子` → 20 条 / `total:"173"`；空夹 `AI` → `[]` / `total:"0"` |
| `page` 超出范围 | `[]`，但 `total` 仍是真实总数 | 仍要按 `total` 算页数 |

由此三条推论（写客户端时很容易踩）：

1. **`"list": []` 只表示"这一页没有内容"**，不等于"没有收藏" —— 要看 `total`。空夹、越界页都会给 `[]`。
2. **列表项不带所属夹**（没有 `FID`/`folder` 字段）：想知道某本在哪个夹，只能按 `folder_id` 逐个夹翻页去找，
   而且它只保证出现在**那一页里**——所以本工程**不让 UI 标"当前在哪个夹"**（见设计文档 §4.4）。
3. `folder_list` 是**每次都返回全量**（与 `folder_id`、`page` 无关），所以"收藏夹卡片列表"只需第 1 次请求，
   而"每个夹的 N 本 + 封面"要**另按 `folder_id` 各请求一次**（本项目用 `page=1` 的 `total` 与 `list[0].id`）。

排序 `o`：`mr`（收藏时间，默认）/ `mp`（更新时间）——只影响 `list` 的顺序。


### 8.2 `POST /favorite` — 加入 / 移出（**切换式**）

| 参数 | 必填 | 说明 |
|---|---|---|
| `aid` | 是 | 漫画 id |

**行为是 toggle**：未收藏 → 加入；已收藏 → 取消收藏。响应用 `type` 告诉你是哪种：

```json
{ "status": "ok", "msg": "漫画添加到您最喜爱的清单!", "type": "add" }
{ "status": "ok", "msg": "已移除收藏", "type": "remove" }
```

⚠ **`folder_id` 参数会被忽略**（实测）：`POST /favorite {aid, folder_id:"4774908"}` 返回 `add`，
但漫画**没有**进那个夹（夹内 total 仍 0，只在「全部」里）。→ 归类只能走 §8.3 的 `move`。

⚠ **不要用逗号拼多个 aid**（实测不可靠）：`aid="114514,114515"` 在两条都未收藏时返回
`{"type":"remove"}`，在混合状态时也返回 `remove`，实际状态与响应不符。**一次只发一个 aid**。

### 8.3 `POST /favorite_folder` — 收藏夹增删改移

| `type` | 额外参数 | 说明 |
|---|---|---|
| `add` | `folder_name` | 新建 |
| `edit` | `folder_id`, `folder_name` | 改名 |
| `move` | `folder_id`, `aid`（`folder_name` 官方也带） | 把漫画**搬进**该夹 |
| `del` | `folder_id` | 删除夹 |

成功：`{"status":"ok","msg":"新增资料夹成功"}` / `"编辑资料夹成功"` / `"移动资料夹成功"` / `"删除资料夹成功"`。

**失败也是 HTTP 200 + 信封 200**，`data` 里带 `code`：

```json
{ "status": "fail", "msg": "该收藏夹裡还有漫画，请先删除漫画", "code": 400 }
{ "status": "fail", "msg": "请选择移动的资料夹" }
{ "status": "fail", "msg": "资料夹不存在" }
```

### 8.4 实测出来的六条硬规则（写客户端时必须遵守）

1. **单夹模型**：一本漫画在同一个账号里**只属于一个收藏夹**；`move` 是"搬家"，不是"多夹"。
2. **不能把漫画移出夹子**：`move` 的 `folder_id` 传空串 / `"0"` → `{"status":"fail","msg":"请选择移动的资料夹"}`；
   传不存在的 id → `"资料夹不存在"`。**想离开夹子只能取消收藏**（`POST /favorite` toggle 掉）。
3. **非空收藏夹不能删**：`del` 会对有漫画的夹返回 `"该收藏夹裡还有漫画，请先删除漫画"`。
   → 删夹前必须把夹内每一本**取消收藏**（夹随之变空才删得掉）。
4. **不能"加入时指定夹"**：`POST /favorite` 忽略 `folder_id`；也没发现"未分类"目标可 move。
   新收藏只进「全部」（不在任何夹里），归类必须再发一次 `move`。
5. **批量不可靠**：别用逗号拼 aid（§8.2）。
6. **读接口偶发"晚一步"**：本次 6 轮测试里有 1 次写完后立刻读（`/album` 的 `is_favorite` 与
   `/favorite` 的 `total`）拿到的是旧值，隔一拍再读就对了 → **不要用"写完立刻回读"判定成功**，
   以写接口自己的 `type`/`status` 为准。

### 8.5 一个完整的收藏流程（实测通过）

```
① POST /favorite {aid}                       → {status:"ok", type:"add"}
② POST /favorite_folder {type:"move", folder_id:"<FID>", aid}   → {"status":"ok","msg":"移动资料夹成功"}
③ GET  /favorite?page=1&folder_id=<FID>      → total=1，list[0].id=<aid>
   GET  /favorite?page=1&folder_id=          → total=179（仍算在全部里）
④ 取消：POST /favorite {aid}                 → {status:"ok", type:"remove"}
   GET  /favorite?page=1&folder_id=<FID>     → total=0
⑤ 删空夹：POST /favorite_folder {type:"del", folder_id:"<FID>"} → {"status":"ok","msg":"删除资料夹成功"}
```

---

## 9. 观看记录与标签

### 9.1 `GET /watch_list` — 观看记录

| 参数 | 必填 | 说明 |
|---|---|---|
| `page` | 是 | 从 1 开始，官方按 `ceil(total/20)` 算页数 |

```json
{
  "list": [
    {
      "id": "1258357",
      "author": "CunnyFunky",
      "description": "",
      "name": "[CunnyFunky] Nahida & Klee [AI Generated]",
      "image": "",
      "category": { "id": "1", "title": "同人" },
      "category_sub": { "id": null, "title": null }
    }
  ],
  "total": 19
}
```

- 列表项**没有** `update_at`（拿不到"什么时候看的"）。
- **谁在上报记录**：官方源码里没有任何"上报观看"的调用点，推测是服务端在带 `Authorization` 的
  `/comic_read`（或 `/album`）时自动记账。

### 9.2 `POST /watch_list` — 删一条记录（官方行为）

`id` → 返回 `{status, msg}`，官方判定 `status !== 1` 为失败。

> ⚠ **不是"上报观看"**。旧文档把它写成"上报观看"是错的；拿它去试上报会把用户记录删掉。
> 本工程**不使用**这个接口（最近浏览用本地数据）。

### 9.3 `GET /tags_favorite` — 收藏的标签

```json
{ "list": [{ "tag": "C104", "updated_at": "781 days ago" }, { "tag": "CunnyFunky", "updated_at": "363 days ago" }] }
```

`updated_at` 是服务端算好的相对时间**英文串**（与 `lang=CN` 无关）。

### 9.4 `POST /tags_favorite_update` ⚠ 未实测

`type` + `tags`（多个标签怎么拼未验证）。`GET` 同路径返回 `data:null`。

### 9.5 `GET /album_sertracking` — 连载追踪状态

`id` → 实测返回 **`false`**（布尔，不是对象）。`POST` 同路径用于开关追踪（⚠ 未实测）。

### 9.6 `POST /album_tracking` — 追踪列表 ⚠ 未实测

`page`。按源码返回 `{list, total}`。

### 9.7 `GET /tag_block` — 标签屏蔽 ⚠ 参数未知

不带参数 / `action=list|get|all` 都返回：

```json
{ "status": 0, "msg": "Invalid request" }
```

（HTTP 400 + 信封 400。`action` 的真实取值未找到，源码里 `GET` 带 `action`/`tag`。）

---

## 10. 未实测 / 无引用 / 非漫画接口

### 10.1 漫画相关但本次**未实测**（按源码推断）

| 接口 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `/comment` | POST | `comment`,`aid`,`comment_id?` | 发评论/回复（会产生公开数据） |
| `/comment_delete` | POST | `comment_id`,`aid` | 删评论 |
| `/like` | POST | `id`,`like_type?` | 点赞（`like_type=novel` 用于小说） |
| `/coin_buy_comics` | POST | `id` | 金币购买 AI 漫画 |
| `/tags_favorite_update` | POST | `type`,`tags` | 更新收藏标签 |
| `/album_tracking` | POST | `page` | 追踪列表 |
| `/album_sertracking` | POST | `id` | 开/关追踪 |
| `/creator_author` | GET | `page`,`search_query` | 书库-作者 |
| `/creator_work` | GET | `page`,`search_value`,`lang`,`source` | 书库-作品 |
| `/creator_author_work` | GET | `id`,`lang`,`source` | 作者作品 |
| `/creator_work_info` | GET | `id` | 作品资料 |
| `/creator_work_info_detail` | GET | `id` | 作品资料详情 |

### 10.2 声明但未使用

`/comment_vote`、`/error_log`、`/ad_content`、`/chapter`（2.1.11 已改用 `/comic_read`）。

### 10.3 非漫画（本文不收录）

`/novels` `/novel` `/novelchapters` `/search_novels` `/novel_favorites` `/novel_favorites_folder` `/coin_buy_nc`（小说）、
`/videos` `/latest_hanime` `/video` `/baitu-create-token`（影片）、`/allgames` `/game/{id}`（游戏）、
`/blogs` `/blog`（部落格）、`/ad_content_all` `/advertise` `/advertise_all`（广告）、
`/support_report`（客服）、`/daily*` `/tasks` `/coin*` `/ad_free` `/payment`（签到/任务/金币/支付）、
`/notifications*`（通知）、`/setting` 的 POST（语言）。

---

## 11. 字段字典与类型陷阱

### 11.1 通用列表项（分类 / 搜索 / 推荐 / 最新）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | **string** | 漫画 id（`/album` 里才是 number） |
| `author` | string | 作者（可能空） |
| `description` | string \| null | 简介（**只有** `/search`、`/random_recommend`、`/week/filter` 等少数接口有） |
| `name` | string | 标题 |
| `image` | string | 封面相对路径。**实测经常是空串**，只能按 `{img_host}/media/albums/{id}.jpg` 自己拼 |
| `category` | `{id,title}` | 主分类 |
| `category_sub` | `{id,title}` \| null | 子分类（`id` 可能是 `null` / `"0"`） |
| `liked` | bool | 当前用户是否点赞（匿名恒 false） |
| `is_favorite` | bool | 是否已收藏（匿名恒 false） |
| `update_at` | number | 更新时间戳（秒）—— **`/serialization` 是 string** |
| `adddate` | string | `YYYY-MM-DD`（只有分类/搜索有） |

### 11.2 类型不一致清单（实测）

| 字段 | 出现过哪些类型 | 位置 |
|---|---|---|
| `total` | `10000`(number) / `"178"`(string) / `5000`(number) / `"133"` / `"0"` | 分类、收藏、评论、推荐分组、我的评论 |
| `update_at` | number / **string** / `"0"` | 列表 / `/serialization` / 评论 |
| `favorite` vs `is_favorite` | 两种键名都存在 | `/serialization` 用 `favorite` |
| `image` | 空串 / 相对路径 / 绝对 URL | 列表 / `/serialization` / `/album_download_2` |
| `likes` | string | 评论、详情 |
| `spoiler` | string `"1"` | 评论 |
| `parent_CID` | string `"0"`（顶层） | 评论 |
| `album_favorites` | number | 登录 |
| `exp` | string，`level` number，`expPercent` float | 登录 / 评论 `expinfo` |
| `gender` | `null` / `""` / `"Male"` | `/useredit`、评论 |
| `addtime` | 秒级时间戳**字符串**；评论里是 `"Oct 20, 2026"` | 详情/阅读 vs 评论 |

**结论**：这些字段一律用"宽松解析"（能转 number 就转、拿不到就空），不要把类型写死。

---

## 12. 附录：实测脚本与样本

- 探测脚本（未提交仓库，`.work/` 已被 gitignore）：`api-probe.mjs`…`api-probe8.mjs`、`api-report.mjs`。
- 结构化原始样本：`.work/probe-out/api-probe*.json`（已脱敏：`jwttoken`/`s`/`password` 替换为占位符，`email` 打码，数组裁到 2 条）。
- 线路清单解密、AES 解密、`scramble` 解扰等算法细节：`docs/official-api-2.1.11.md` §3 / §8 / §11。
- 官方前端源码位置（sourcemap 还原）：`.work/src/main.9a45a3ff/actions/*.ts`（每个接口的入参都在这）。
