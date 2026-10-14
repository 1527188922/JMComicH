package com.haruhi.haruhibox.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `t_bilibili_video`：B站视频记录。
 *
 * 字段对应参考实现 `BilibiliVideoSqlite`。两个 Android 侧的注意点：
 * 1. `desc` 是 SQL 保留字，列在 SQL 里要用反引号（参考实现就是这么做的），
 *    这里映射成 `desc` 保持一致，DAO 的 `@Query` 会写 `` `desc` ``。
 * 2. 唯一索引是 `(bvid, cid)`，不是单独的 `bvid`：多 P 视频同一个 bvid 有多个 cid，
 *    各自是独立记录（计划 §9 第 11 条：多 P 提供分集选择，按选定 CID 入库/下载）。
 *    `cid` 允许为空（详情接口异常时），此时唯一性退化为 bvid——
 *    SQLite 的唯一索引把 NULL 视为互不相等，所以需要业务层额外保证"cid 为空的记录只有一条"。
 */
@Entity(
    tableName = "t_bilibili_video",
    indices = [
        Index(value = ["bvid", "cid"], unique = true),
        Index(value = ["bvid"]),
        Index(value = ["create_time"]),
    ],
)
data class BilibiliVideoEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "bvid") val bvid: String,
    @ColumnInfo(name = "avid") val avid: Long? = null,
    @ColumnInfo(name = "cid") val cid: Long? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "tag") val tag: String? = null,
    /** 列名是保留字，SQL 里必须用反引号包起来。 */
    @ColumnInfo(name = "desc") val desc: String? = null,
    @ColumnInfo(name = "pic") val pic: String? = null,
    @ColumnInfo(name = "duration") val duration: Long? = null,
    @ColumnInfo(name = "owner_mid") val ownerMid: Long? = null,
    @ColumnInfo(name = "owner_name") val ownerName: String? = null,
    @ColumnInfo(name = "owner_face") val ownerFace: String? = null,
    @ColumnInfo(name = "video_detail_raw") val videoDetailRaw: String? = null,
    @ColumnInfo(name = "play_url_raw") val playUrlRaw: String? = null,
    @ColumnInfo(name = "pubdate") val pubdate: Long? = null,
    @ColumnInfo(name = "ctime") val ctime: Long? = null,
    @ColumnInfo(name = "create_time") val createTime: String? = null,
    @ColumnInfo(name = "update_time") val updateTime: String? = null,
)
