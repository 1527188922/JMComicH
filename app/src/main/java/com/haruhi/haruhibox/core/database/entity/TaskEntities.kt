package com.haruhi.haruhibox.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 下载任务类型。 */
enum class DownloadType {
    JM_ALBUM,
    BILI_VIDEO,
    JM_EXPORT_ZIP,
    JM_EXPORT_PDF,
    ;

    /**
     * 是否是"导出"类任务（zip/pdf 打包）。
     *
     * 导出与下载有两个本质区别，恢复 / 退出收尾 / 界面文案都要按它分流：
     * - **不能续做**：中断了就整份重来，所以退出应用时是**删除**（任务行 + 中转文件），
     *   而不是像下载那样暂停等用户继续；
     * - **进度口径不同**：它的 `doneImages` 是"已写入的条目数"，不是下载的图片数。
     */
    val isExport: Boolean
        get() = this == JM_EXPORT_ZIP || this == JM_EXPORT_PDF
}

/**
 * 下载任务状态。
 *
 * 计划 §4.5 定义的六态，另加 [WAITING_NETWORK]：
 * §9 第 9 条要求任务页"明确区分排队、执行、暂停、等待网络及待处理失败，
 * **不将失败算作正在运行**"，所以"等待网络"必须是一个独立状态而不是 RUNNING 的注解。
 *
 * 恢复规则（§D.3 故障验收矩阵，用户拍板的行为）：
 * - 进程被杀后，`RUNNING` 任务按磁盘现状重算进度后回到 `PAUSED`（**不自动续跑**，
 *   等用户点"继续"），因为退出应用时的语义就是"下载暂停、导出删除"；
 * - 导出任务（zip/pdf）**直接删除**：不能续做，留着的任务行只会占住去重名额；
 * - `CANCELLED` 任务**不复活**；
 * - `PAUSED` 保持 `PAUSED`，等用户点恢复。
 */
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_NETWORK,
    SUCCESS,
    FAIL,
    CANCELLED,
    ;

    /** 是否算"活跃"（占用 bizKey 去重名额、需要恢复、进任务页"进行中"分组）。 */
    val isActive: Boolean
        get() = this == QUEUED || this == RUNNING || this == PAUSED || this == WAITING_NETWORK

    /**
     * 是否允许执行器接着跑。
     *
     * 与 [isActive] 的差别只在 [PAUSED]：暂停之后即使网络刚好恢复、
     * 或者界面又调了一次 `start`，也不能把状态自己改回运行中。
     */
    val isRunnable: Boolean
        get() = this == QUEUED || this == RUNNING || this == WAITING_NETWORK

    /** 是否已完成（任务页"已完成"分组；失败不算完成）。 */
    val isFinished: Boolean
        get() = this == SUCCESS

    /** 是否失败（单独用提示点，不计入角标，见 §9 第 9 条）。 */
    val isFailed: Boolean
        get() = this == FAIL
}

/**
 * `t_download_task`：**持久化**下载任务主表（计划 §4.5，用户已确认必须做）。
 *
 * 为什么要持久化：参考实现的任务只在内存里，进程重启即清空（它作为服务端可以这样取舍），
 * 手机上不行——用户下载到一半杀进程，进度全丢是不可接受的。
 *
 * 去重规则（照抄参考实现）：**同一个 `(type, bizKey)` 只允许一个"活跃"任务**。
 * JM 的 bizKey = aid；B站的 bizKey = `bvid_cid`。
 *
 * 进度语义：
 * - 章节级：[totalChapters] / [doneChapters]
 * - 图片级：[totalImages] / [doneImages]（B站视频用 [totalBytes] / [downloadedBytes]）
 *
 * **进度不回退也不算错**：恢复时按磁盘现状重算（见 §4.5 与 §D.1），
 * 已存在的文件计入 done，缺失的文件才需要重新下载。
 */
@Entity(
    tableName = "t_download_task",
    indices = [
        // 同一 bizKey 只允许一个进行中任务 —— 用部分唯一索引无法在 Room 里表达，
        // 所以由 Dao 的 activeTask(type, bizKey) 查询 + 仓库层串行化来保证。
        Index(value = ["type", "bizKey"]),
        Index(value = ["status"]),
        Index(value = ["updateTime"]),
    ],
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "type") val type: DownloadType,
    /** JM：aid；B站：`bvid_cid`；导出：本子 folderName。 */
    @ColumnInfo(name = "bizKey") val bizKey: String,
    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.QUEUED,

    @ColumnInfo(name = "totalChapters") val totalChapters: Int = 0,
    @ColumnInfo(name = "doneChapters") val doneChapters: Int = 0,
    @ColumnInfo(name = "totalImages") val totalImages: Int = 0,
    @ColumnInfo(name = "doneImages") val doneImages: Int = 0,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long = 0,
    @ColumnInfo(name = "downloadedBytes") val downloadedBytes: Long = 0,

    /** 当前阶段文案（"下载漫画图片"、"打包zip"…），对应参考实现的进度阶段上报。 */
    @ColumnInfo(name = "stageText") val stageText: String? = null,
    /** 失败原因（可理解文案，不是异常栈）。 */
    @ColumnInfo(name = "message") val message: String? = null,
    /** 展示名（本子标题 / 视频标题），任务页不需要回表就能显示。 */
    @ColumnInfo(name = "displayName") val displayName: String? = null,

    /**
     * 本次任务的参数快照（JSON）。恢复时用它重建上下文：
     * JM 存 aid + 章节 id 列表 + 起始章节；B站存 bvid/avid/cid；导出存目标与是否加密。
     *
     * **绝不能放凭据**（Cookie / 密码）：任务参数会进数据库与日志（计划 §D.2 第 6 条）。
     */
    @ColumnInfo(name = "payload") val payload: String? = null,

    /** 调度关联：WorkManager 的 request id，便于取消时精确停止。 */
    @ColumnInfo(name = "workRequestId") val workRequestId: String? = null,

    @ColumnInfo(name = "retryCount") val retryCount: Int = 0,
    @ColumnInfo(name = "createTime") val createTime: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "startTime") val startTime: Long? = null,
    @ColumnInfo(name = "endTime") val endTime: Long? = null,
    @ColumnInfo(name = "updateTime") val updateTime: Long = System.currentTimeMillis(),
)

/**
 * `t_download_task_chapter`：JM 任务的章节明细，用于"进度到章节"。
 *
 * 计划 §4.5 的设计取舍：**不建"一张图一行"的表**（几百章 × 每章几十张图会写出几万行，
 * 收益极低）。图片级进度用「每章的 done/total 计数器 + 以磁盘文件为准」表达：
 * - 断点续传的判定 = 「该图片文件是否已存在于本地」（与参考实现
 *   `downloadChapter` 里 `.filter(e -> !e.getImgFile().exists())` 完全一致）；
 * - 恢复时按磁盘重算该章的 done，保证进度不回退也不重复累加。
 *
 * 将来若真需要"单张图的状态/失败原因"，再加一张 `t_download_task_image`
 * 只记录失败/待重试的图片（稀疏表），**不要记录成功的**。
 */
@Entity(
    tableName = "t_download_task_chapter",
    indices = [Index(value = ["taskId", "chapterId"], unique = true)],
)
data class DownloadTaskChapterEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "taskId") val taskId: Long,
    @ColumnInfo(name = "chapterId") val chapterId: Long,
    @ColumnInfo(name = "chapterTitle") val chapterTitle: String? = null,
    @ColumnInfo(name = "sort") val sort: Int = 0,
    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.QUEUED,
    @ColumnInfo(name = "totalImages") val totalImages: Int = 0,
    @ColumnInfo(name = "doneImages") val doneImages: Int = 0,
    @ColumnInfo(name = "message") val message: String? = null,
    @ColumnInfo(name = "updateTime") val updateTime: Long = System.currentTimeMillis(),
)

/**
 * 阅读/播放位置（计划 §4.5 末条、§9 第 8/13 条）。
 *
 * 漫画用 `albumId + chapterId + imageFile + 页内位置` 定位；
 * 视频用 `bvid + cid + 毫秒位置`。**文件缺失时保留位置并提示补下载**，
 * 所以这里不存"文件是否存在"，那是查询时按磁盘判断的。
 */
@Entity(
    tableName = "t_playback_position",
    indices = [Index(value = ["kind", "bizKey"], unique = true)],
)
data class PlaybackPositionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** `JM` 或 `BILI`。 */
    @ColumnInfo(name = "kind") val kind: String,
    /** JM：aid；B站：`bvid_cid`。 */
    @ColumnInfo(name = "bizKey") val bizKey: String,
    /** JM：章节 id。 */
    @ColumnInfo(name = "chapterId") val chapterId: Long? = null,
    /** JM：图片文件名（比页码更稳，章节增删页也不会错位）。 */
    @ColumnInfo(name = "imageFile") val imageFile: String? = null,
    /** JM：页内滚动位置（0f～1f）；B站：播放进度毫秒。 */
    @ColumnInfo(name = "position") val position: Long = 0,
    /** JM：页内偏移比例，长图恢复时用。 */
    @ColumnInfo(name = "offsetRatio") val offsetRatio: Float = 0f,
    @ColumnInfo(name = "updateTime") val updateTime: Long = System.currentTimeMillis(),
)

/** 在线搜索历史（计划 §2.4 第 1 条：`jm.online_search.history` 的存储语义要保留）。 */
@Entity(
    tableName = "t_jm_search_history",
    indices = [Index(value = ["updateTime"])],
)
data class JmSearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "keyword") val keyword: String,
    @ColumnInfo(name = "sort") val sort: String,
    @ColumnInfo(name = "page") val page: Int = 1,
    @ColumnInfo(name = "total") val total: Long = 0,
    @ColumnInfo(name = "resultCount") val resultCount: Int = 0,
    /** 结果快照 JSON：点历史记录直接回显，不再请求 JM（参考实现就是这么做的）。 */
    @ColumnInfo(name = "items") val items: String? = null,
    @ColumnInfo(name = "createTime") val createTime: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updateTime") val updateTime: Long = System.currentTimeMillis(),
)
