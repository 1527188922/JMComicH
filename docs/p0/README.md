# P0 技术验证记录（环境与基线）

> 目标：把「不确定的技术选型」在写业务代码之前钉死，并留下一份可复现的记录。
> 对应计划 `docs/android-app-plan.md` 第 10 节 P0。
>
> 记录日期：2026-10-07　工程：`D:\Project\JMComicH`

---

## 1. 构建环境（已固定）

| 项 | 值 | 说明 |
|---|---|---|
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties`，带 sha256 校验 |
| AGP | 9.4.0 | `gradle/libs.versions.toml` |
| Kotlin | **2.2.10**（AGP 内置） | 见下方 D-1，**不额外应用 KGP** |
| KSP | 2.3.12 | 见 D-1，唯一能与 AGP 9 内置 Kotlin 共存的版本线 |
| Compose BOM | 2026.09.00 | |
| 构建 JDK | **21**（`D:\Environment\jdk-21.0.4`） | `gradle.properties` 的 `org.gradle.java.home` |
| Java/Kotlin target | 21 | `compileOptions` + `kotlin.compilerOptions.jvmTarget` |
| compileSdk / targetSdk | 37 | 本机已装 `platforms/android-37.0` + `build-tools/36.0.0` |
| minSdk | 26 | Android 8.0；理由是 `BitmapRegionDecoder`、现代 WebP 解码、通知渠道语义稳定 |
| applicationId | `com.haruhi.jmcomic` | |

固定版本组合的升级门槛：**升级 AGP / Kotlin / KSP 任一版本前，先跑**
`gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。

---

## 2. 决策记录

### D-1　用 AGP 9 的内置 Kotlin，不引入 Kotlin Gradle Plugin（重要）

**现象与证据**

1. 应用 `org.jetbrains.kotlin.android` 2.2.10 → 失败：
   `Cannot add extension with name 'kotlin', as there is an extension already registered with that name.`
   （AGP 9 默认 `android.builtInKotlin=true`，已经注册了 `kotlin` 扩展。）
2. 设 `android.builtInKotlin=false` 再应用 KGP 2.2.10 → 失败：
   `ApplicationExtensionImpl$AgpDecorated_Decorated cannot be cast to com.android.build.gradle.BaseExtension`
   （AGP 9 移除了 `BaseExtension`，KGP 2.2.10 的 Android 集成仍依赖它。）
   同时 AGP 明确警告该开关「在 10.0 会被移除」。
3. 反过来只用内置 Kotlin 时，扩展形态是：
   `kotlin -> org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension`，
   `kotlin-core version = 2.2.10-release-430`，即**内置 Kotlin 就是 KGP 2.2.10 的 DSL**。

**结论**

- `plugins` 里**只应用** `com.android.application`、`org.jetbrains.kotlin.plugin.compose`、
  `org.jetbrains.kotlin.plugin.serialization`、`com.google.devtools.ksp`。
  **不要**再应用 `org.jetbrains.kotlin.android`，也不要设置 `android.builtInKotlin=false`。
- `kotlin { compilerOptions { ... } }` 块照旧可用（扩展类型就是 KGP 的
  `KotlinAndroidProjectExtension`）。

### D-2　KSP 必须用 2.3.x（新版本线），不能用 `<Kotlin 版本>-<KSP 版本>` 那种坐标

**现象**

- `com.google.devtools.ksp` `2.2.10-2.0.2` + 内置 Kotlin → 失败：
  `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.
   Kotlin source set 'debug' contains: [.../build/generated/ksp/debug/kotlin, .../java]`
   （旧 KSP 通过 `kotlin.sourceSets` 把生成目录塞给 Kotlin，被内置 Kotlin 拒绝。）
- `com.google.devtools.ksp` `2.3.12`（KSP 改用不绑定 Kotlin 版本的版本线）→ 通过，
  且 `:app:kspDebugKotlin`、`:app:compileDebugKotlin`、`:app:assembleDebug` 全部成功。

**结论**：KSP 固定 `2.3.12`。KSP 生成目录由 AGP 的 Variant API 接管，不需要手工 `srcDir`。

### D-3　不用 Hilt，改用手写依赖注入容器

**现象**：应用 `com.google.dagger.hilt.android` 2.60.1 + 内置 Kotlin → 失败：
`Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.`
（Hilt 的 Gradle 插件同样用旧 DSL 添加 `hilt` 生成目录。）

**结论**：本工程不引入 Hilt。

- 依赖注入用 `core/di/AppContainer.kt`：一个手写容器，持有 OkHttp、数据库、
  DataStore 设置、下载引擎等单例，由 `Application` 创建。
- ViewModel 用 `viewModelFactory { initializer { ... } }` 从容器取依赖，
  在 Compose 里用 `viewModel(factory = ...)`。
- 影响：没有编译期注入校验；代价换来的是**构建可用**与**依赖关系显式**。
  业务模块本来就少（JM / Bili / 下载 / 存储），手写容器的规模是可控的。
- 若日后 AGP/KSP/Hilt 组合恢复可用，可以局部迁移，不影响业务代码结构。

### D-4　构建 JDK 固定为本机 JDK 21，禁用工具链自动下载

**现象**：工程里原本有 `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`）与
`settings.gradle.kts` 里的 `org.gradle.toolchains.foojay-resolver-convention`，
首次构建卡在 `Downloading https://api.foojay.io/.../OpenJDK25U-...zip`，几分钟无进展。

**结论**

- 删除 `gradle/gradle-daemon-jvm.properties`；
- `settings.gradle.kts` 不再注册 foojay resolver；
- `gradle.properties` 固定 `org.gradle.java.home=D:\Environment\jdk-21.0.4`；
- CI 里用 `sed` 删掉这一行，让 Gradle 用 `JAVA_HOME`（见 `.github/workflows/android-ci.yml`）。

**2026-10-15 复发（Gitee Go）**：上面四条只做到一半——foojay resolver 确实从
`settings.gradle.kts` 去掉了，但 `gradle/gradle-daemon-jvm.properties`
（`toolchainVersion=25` + 10 条 `toolchainUrl.*`）从 init 提交起就一直留在仓库里。
本地不发作是两个原因叠加：`org.gradle.java.home` 优先，且本机
`GRADLE_USER_HOME/jdks` 里已经躺着当年下载下来的 `eclipse_adoptium-25`；CI 上
`sed` 删掉了前一行、容器里又没有 JDK 25，守护进程 JDK 就按这个文件要 25，而 Gradle 9
的工具链自动配置**不需要 foojay 插件**（内置默认仓库就是 api.foojay.io），于是又去拉
JDK 25 并失败：

```
Unable to download toolchain matching the requirements
({languageVersion=25, vendor=any vendor, ...}) from
'https://api.foojay.io/disco/v3.0/ids/fa1e318c.../redirect'
```

**修复（2026-10-15）**

1. `git rm gradle/gradle-daemon-jvm.properties` —— 真正的修复：把"要 JDK 25"这个要求本身去掉。
   不要再运行 `gradlew updateDaemonJvm`，它会把文件重新生成出来；
2. `gradle.properties` 加 `org.gradle.java.installations.auto-download=false` —— 安全网，不是修复：
   以后再有谁请求本机没有的 JDK，得到的是一条明确的
   `Cannot find a Java installation ... matching: {languageVersion=...}`（下面已验证），
   而不是静默去下载一整个 JDK 把 CI 卡死；
3. 两个 CI 脚本（`tools/ci/gitee-build.sh`、`.github/workflows/android-ci.yml`）在删
   `org.gradle.java.home` 之后顺手 `rm -f gradle/gradle-daemon-jvm.properties` 兜底；
4. `.gitignore` 忽略该文件，避免再次被提交上来。

**验证**（本机、干净 worktree、按 CI 的 `gradle.properties` 删掉固定 JDK 那一行）：把
`GRADLE_USER_HOME/jdks`（含 adoptium 25）暂时移开，复现 CI 的"没有 JDK 25"条件，
跑 `./gradlew --no-daemon help`：

- 文件在：`Cannot find a Java installation on your machine (...) matching:
  {languageVersion=25, vendor=any vendor, ...}. Toolchain auto-provisioning is not enabled.`
  —— 要 JDK 25 的正是这个文件；
- 文件删掉：`BUILD SUCCESSFUL`，全程没有任何工具链请求；
- 同配置下 `:app:assembleDebug` 也能出包（见 `tools/ci/gitee-build.sh` 的 CI 命令）。

### D-5　`unitTests.isReturnDefaultValues` 必须保持 `false`

AGP 的 `testOptions.unitTests.isReturnDefaultValues = true` 会把 `android.jar` 换成
**方法返回默认值的打桩版本**。副作用是 `javax.crypto.Cipher` 也会被打桩，
JM 的 AES 解密在单元测试里直接抛 `NoClassDefFoundError: javax.crypto.JarVerifier`。

结论：保持 `false`（默认值）。需要 Android 行为的测试（Bitmap / PDFBox）用 Robolectric，
不需要的（密码学、文件名、纯算法）用普通 JUnit，跑得更快。

### D-6　单元测试必须 `forkEvery = 1`

**现象**：Robolectric 的沙箱会把 Conscrypt 的 OpenSSL provider 泄漏到同一 JVM 里，
之后运行的纯 JVM 测试在初始化 `javax.crypto.JarVerifier` 时会失败：

```
Caused by: java.security.NoSuchAlgorithmException:
  error:0c00006f:ASN.1 encoding routines:OPENSSL_internal:DIGEST_AND_KEY_TYPE_NOT_SUPPORTED
  at org.conscrypt.OpenSSLX509Certificate.verifyOpenSSL(...)
```

表现为 `CryptoGoldenTest` 单独跑全绿、和 `DescramblerGoldenTest` 一起跑就集体失败。

结论：`testOptions.unitTests.all { it.forkEvery = 1 }`，每个测试类一个 JVM。
测试量级小（约 50 个用例）时代价可接受；用例变多后应把 Robolectric 用例拆到
独立的 test source set，或改用带 `forkEvery` 分组的方案。

### D-7　测试数据一律用 percent-encoding，不用 base64

黄金数据文件 `golden.txt` 是 `类型|字段…` 的行式文本。文本字段如果直接用原文，
空字段会被 Kotlin 的 `split` 丢掉（必须传 `limit`），中文与 `|` 也不安全；
base64 则无法表达"空串 vs 缺失"且对 1 字符长度有填充歧义。

结论：文本字段统一 percent-encoding（UTF-8，空格编码为 `%20`），
`-` 表示 null，解码用 `GoldenData.percentDecode`。

---

## 3. Spike 1：JM 签名 + AES 解密　✅ 通过

**验收方式**：与参考实现编译产物**逐值比对**，不是"能跑就行"。

黄金数据由 `tools/golden-gen/GoldenGen.java` 产出（反射调用参考实现，
**不修改参考工程源码**），生成脚本 `tools/golden-gen/regenerate.ps1`，
数据落在 `app/src/test/resources/golden/golden.txt`。

| 用例 | 数据量 | 结果 |
|---|---|---|
| G1 普通接口签名 `md5(ts + "18comicAPP")` | 3 组固定 ts | 与参考实现完全一致 |
| G1C 内容接口签名 `md5(ts + "18comicAPPContent")` | 3 组固定 ts | 一致，且与 G1 不同值 |
| G2KEY 解密密钥 = `md5(ts + "185Hcomic3PAPP7R")` 的 32 字符 hex 的 ASCII 字节 | 3 组 | 长度确认为 **32 字节（AES-256）** |
| G2 解密参考实现产出的密文 | 3 组（含中文/emoji/空串） | 明文完全一致 |
| G3 `calculateBlockNum` | 3 × 15 × 6 = 270 组，覆盖 268850 / 421926 两个阈值 | 全部一致 |

**关键坑（已用测试锁定，勿改）**

1. 解密密钥是 **32 个 ASCII 字符**（32 字节，AES-256），不是 md5 摘要的前 16 字节。
2. `tokenparam = "$ts,2.0.13"`，重试时必须沿用第一次请求的 `ts`，否则响应解不开。
3. `calculateBlockNum` 最后一步是 `md5(chapterId + 文件名去扩展名)` 末位字符的
   **字符码点** `% x`（`'a'`=97），不是把该字符当十六进制解析。
4. Base64 解码要按 commons-codec 的宽松模式（允许缺 `=` 填充），
   已用 `decryptData normalises base64 without padding` 用例锁定。

测试：`app/src/test/java/com/haruhi/jmcomic/core/protocol/CryptoGoldenTest.kt`

## 4. Spike 2：PDF 方案选型　✅ 通过（选 B1：pdfbox-android）

三件套（加密 / 书签 / 页面尺寸）全部验证通过，**不需要**走 B2 手写 PDF 写入器。
完整记录见 `docs/p0/spike2-pdf.md`。用户已于 2026-10-08 就两个行为差异做出决策：

1. **加密算法改为 AES-128**。
   > 更正：本文档 2026-10-07 版本曾写「Android 端口没有 `setEncryptionKeyLength`，
   > 参考实现产出 AES-128」——**两句都是错的**，当时读的 `javap` 输出不完整。
   > 实测（桌面版 PDFBox 3.0.2 与 pdfbox-android 2.0.27.0 行为一致）：
   > 单独设 `encryptionKeyLength` **被忽略**，参考实现实际产出 **40 位 RC4**；
   > 只有同时设 `preferAES = true` 才得到 AES-128。
   > 所以本 App 的 AES-128 是**有意的安全强化**，不是与参考实现对齐。
2. **访问控制只看「能否打开」**：owner 与 user 同密码，权限位因此对打开者不生效
   （实测 `isOwnerPermission == true`）。用户明确接受。

两项都只影响 `PdfWriter.applyProtection` 一处。

### 加密用例为什么跑在真机上

Robolectric 沙箱里 Conscrypt 排在 JCE provider 首位，JDK 初始化 `JarVerifier` 时
用它验签 provider 的 JAR 会失败：

```
AES via SunJCE = ok        AES via BC = FAIL: NoClassDefFoundError: javax.crypto.JarVerifier
```

PDFBox 的 AES 走 BouncyCastle，因此**任何 AES 路径在 Robolectric 下都跑不了**
（与 D-6 同源）。所以：

- `PdfWriterSpikeTest`（开发机 / Robolectric）覆盖页面、书签、`PageMode`、不加密；
- `PdfEncryptionInstrumentedTest`（**设备**）覆盖 AES-128、错误密码、权限位事实。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 5. Spike 3：Android 解扰　✅ 通过

**验收方式**：与参考实现 AWT `WritableRaster` 逐行搬运的输出**逐像素比对**。

- 参考侧生成 5 种尺寸 × 5 种块数（含 `height % blockNum != 0`、块数大于高度等边界）的
  输入/输出 PNG，落在 `app/src/test/resources/golden/descramble/`。
- Android 侧用 Robolectric 跑 `Canvas.drawBitmap(src, srcRect, dstRect, paint)` 整行搬运，
  逐像素断言（`DescramblerGoldenTest`）。

**结论**

- 块布局公式（第 0 块吸收 `height % blockNum` 余数、块顺序倒置）在 Kotlin 侧独立实现并验证，
  纯逻辑测试不需要模拟器（`Descrambler.plan`）。
- 用 `Canvas` 整块搬运替代逐像素 `setPixel`，语义与 AWT 的
  `getDataElements/setDataElements` 一致（都是 ARGB int 拷贝），
  在 ARGB_8888 位图上不会引入颜色转换。
- 内存策略仍然必须做（`rgb565` 会掉色，只在阅读预览用；解扰与导出保持 ARGB_8888
  并串行执行），这是 P3 的实现约束，不是 P0 的结论。

测试：`app/src/test/java/com/haruhi/jmcomic/core/codec/DescramblerGoldenTest.kt`

## 6. P0 验收清单

- [x] Kotlin + Jetpack Compose 空工程可构建（`:app:assembleDebug` 成功）
- [x] 构建工具与依赖版本固定，写入 `gradle/libs.versions.toml` 并有升级门槛说明
- [x] 计划文档已复制进工程：`docs/android-app-plan.md`
- [x] CI 配置：`.github/workflows/android-ci.yml`（unit test + lint + assembleDebug）
- [x] 日志与 DI 基线：Timber + 手写 `AppContainer`（见 D-3）
- [x] spike 1 结论（签名 + AES + 块数，全部与参考实现逐值一致）
- [x] spike 2 结论（PDF 走 B1 pdfbox-android，加密/书签/页面三项均通过）
- [x] spike 3 结论（解扰逐像素一致）

## 7. 依赖版本一览（P0 固定）

| 依赖 | 版本 | 说明 |
|---|---|---|
| AGP / Gradle | 9.4.0 / 9.6.0 | |
| Kotlin（AGP 内置） | 2.2.10 | 见 D-1 |
| KSP | 2.3.12 | 见 D-2 |
| Compose BOM | 2026.09.00 | |
| Room | 2.8.5 | Kotlin 3.x 尚未稳定，用 2.8.x |
| WorkManager | 2.11.2 | 持久化下载任务的执行者 |
| DataStore Preferences | 1.3.0-alpha11 | **仅此一处用 alpha**，原因是 1.1.x 缺少本项目需要的部分能力；P1 若遇到问题回退 1.1.7 |
| Media3 | 1.11.1 | ExoPlayer + media3-ui-compose |
| Navigation Compose | 2.10.2 | |
| OkHttp | 5.5.0 | MockWebServer 同版本 |
| kotlinx-serialization-json | 1.9.0 | |
| kotlinx-coroutines | 1.10.2 | |
| Coil | 3.2.0 | coil-compose + coil-network-okhttp |
| zip4j | 2.11.5 | AES zip |
| pdfbox-android | 2.0.27.0 | 见 spike 2 |
| Timber | 5.0.1 | 日志 |
| Robolectric | 4.17 | 测试 SDK 固定 `@Config(sdk = [34])` |
| JUnit / Truth / Turbine | 4.13.2 / 1.4.4 / 1.2.1 | |

**没有引入 Hilt**，见 D-3。
