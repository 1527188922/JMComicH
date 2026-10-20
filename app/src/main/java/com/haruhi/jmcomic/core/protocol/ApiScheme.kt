package com.haruhi.jmcomic.core.protocol

/**
 * JM 两代协议并存时的**方案开关**。
 *
 * 官方 2.1.11 换了签名密钥与版本号，并把"章节图片清单 + `scramble_id`"合并到
 * `/comic_read` 一次返回（见 `docs/official-api-2.1.11.md` §2.2 / §7.5 / §8.3）。
 * 本工程**两套都留着**，默认走新的，需要时可以切回旧的（改下面两个 `DEFAULT` 即可，
 * 不做设置页开关）。
 *
 * 两个枚举刻意分开：**签名**和**取章节数据的接口**是两件独立的事
 * （文档 §11 也明确"两套签名/接口是不同代际的协议面，能同时存在"），
 * 合成一个枚举会让"新签名 + 旧章节接口"这种组合没法表达。
 */

/**
 * 请求签名用哪一套：影响 `Token` 与 `Tokenparam` 两个 header。
 *
 * | 方案 | `Token` | `Tokenparam` |
 * |---|---|---|
 * | [LEGACY] | `md5(ts + "18comicAPP")` | `"{ts},2.0.13"` |
 * | [OFFICIAL_211] | `md5(ts + "185Hcomic3PAPP7R")` | `"{ts},2.1.11"` |
 *
 * 密钥与版本号常量放在 [Crypto] 里（签名相关只在那一个文件定义），这个枚举只表达"选哪一套"。
 */
enum class SignatureScheme {
    /** 旧机制：`18comicAPP` / `2.0.13`。 */
    LEGACY,

    /** 官方 2.1.11：`185Hcomic3PAPP7R` / `2.1.11`。 */
    OFFICIAL_211,
    ;

    companion object {
        /** 默认走新机制（官方 2.1.11）。 */
        val DEFAULT = OFFICIAL_211
    }
}

/**
 * 章节图片清单（+ `scramble_id`）用哪一套接口。
 *
 * - [LEGACY_CHAPTER]：`/chapter` 取清单，另外再打一次 `/chapter_view_template` 取 `scramble_id`；
 * - [COMIC_READ]：`/comic_read` **一次**拿到清单与 `scramble_id`（不必再调
 *   `/chapter_view_template`）。
 *
 * 两种情况下图片 URL 都由**用户选中的图片线路**拼接（`Defaults.buildImgUrl`），
 * 接口返回的完整 URL 只用来取文件名 —— 与"图片线路"这个设置项保持一致。
 */
enum class ChapterSource {
    /** 旧机制：`/chapter` + `/chapter_view_template`。 */
    LEGACY_CHAPTER,

    /** 官方 2.1.11：`/comic_read`。 */
    COMIC_READ,
    ;

    companion object {
        /** 默认走新机制。 */
        val DEFAULT = COMIC_READ
    }
}
