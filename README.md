# JMComicH

纯漫画版 Android 客户端：JM 漫画的浏览、下载、阅读与导出（zip / pdf），单机运行、不需要任何服务器。

> 设计阶段原本规划了「漫画 + B站视频」双模式，**B站模式已整体移除**，本工程现在只做漫画。
> 历史计划见 `docs/android-app-plan.md`（该文档保留原设计原貌，双模式相关章节已不再适用）。

#### 软件架构

单个 `:app` Gradle 模块 + 按逻辑模块划分的包结构（计划 §3.1 建议的逻辑模块划分，
§D.3 明确「初期不必把每个逻辑目录都建成独立 Gradle 模块」）：

```
app/src/main/java/com/haruhi/jmcomic/
  JmComicApp.kt              进程入口（日志、崩溃留存、DI 容器）
  MainActivity.kt            唯一 Activity
  core/
    protocol/                JM 协议：签名、解密、块数、常量与 URL 规则
    codec/                   图片编解码与解扰、zip / pdf 导出
    network/                 OkHttp 工厂、错误分类、超时重试
    database/                Room 实体 / Dao / 数据库
    download/                下载引擎、任务仓库、前台服务
    settings/                设置（唯一的漫画作用域）+ Keystore 凭据存储
    storage/                 目录规划、文件名清洗与排序、导出交付
    di/                      手写依赖注入容器
  feature/
    api/                     JM 接口客户端与响应模型
    data/                    仓库、图片落盘、下载 / 导出编排、阅读位置
    ui/                      Compose 界面（书库 / 分类 / 最近 / 发现 / 收藏 / 任务 / 阅读器）
  ui/theme/                  Compose 主题
```

拆分 Gradle 模块的触发条件（先不做，但写下来避免争论）：
单一模块的编译时间明显变长（>2 分钟）时；或需要 feature-on-demand 分包时。

#### 安装教程

```powershell
# debug 包（与 release 包可同时安装，互不覆盖）
.\gradlew.bat :app:assembleDebug

# release 包（需要根目录的 keystore.properties + *.jks，见 docs/BUILD.md）
.\gradlew.bat :app:assembleRelease
```

安装：`adb install -r app\build\outputs\apk\debug\app-debug.apk`

#### 使用说明

| 入口 | 说明 |
|---|---|
| 书库 | 已入库的漫画，支持筛选、批量操作、继续阅读 |
| 分类 | 官方分类浏览：主分类 + 二级分类（左侧双层抽屉，含总数）、排序、下拉刷新、列表/卡片切换、手动加载更多 |
| 最近 | 进过详情页/阅读页的记录 |
| 发现 | 在线搜索（纯数字关键词会额外直查该本并置顶） |
| 收藏 | 收藏夹管理（默认夹不可删改） |
| 任务 | 下载与导出任务的进度、暂停/继续/取消/重试 |
| 阅读器 | 纵向连续 / 横向分页、双指缩放、章节切换、缺图补下 |

导出的 zip / pdf 落在 `下载/JMComicH/`（Android 10+，不需要存储权限）。

#### 开发

- 提交前必跑：`.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- 跨功能硬约束见 `docs/CONVENTIONS.md`，改动流水见 `docs/PROGRESS.md`，
  构建 / 签名 / 真机测试见 `docs/BUILD.md`。

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request
