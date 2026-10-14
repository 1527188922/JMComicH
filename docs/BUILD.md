# 构建与安装

## 快速命令

```powershell
cd D:\Project\JMComicH

# 日常验证（提交前必跑）
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# 装到真机（debug 包）
.\gradlew.bat :app:installDebug

# 生成可侧载安装的 release 包
.\gradlew.bat :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 直接安装 release 包
adb install -r app\build\outputs\apk\release\app-release.apk

# 设备上的 instrumented test（PDF 加密等）
.\gradlew.bat :app:runDeviceTests
# 只跑某个测试类
.\gradlew.bat :app:runDeviceTests "-PclassFilter=com.haruhi.jmcomic.core.codec.PdfEncryptionInstrumentedTest"
```

> **不要用 `:app:connectedDebugAndroidTest`**（在小米机型上）：
> AGP 安装测试 APK 时硬编码带 `-g`，MIUI / HyperOS 会拒绝，详见
> 「小米机型上跑设备测试」一节。`:app:runDeviceTests` 是本工程自带的替代品。

## 构建环境（P0 固定，见 `docs/p0/README.md`）

| 项 | 值 |
|---|---|
| 构建 JDK | 本机 JDK 21，由 `gradle.properties` 的 `org.gradle.java.home` 指定 |
| Gradle / AGP / Kotlin | 9.6.0 / 9.4.0 / 2.2.10（AGP 内置） |
| compileSdk / minSdk / targetSdk | 37 / 26 / 37 |

> `gradle.properties` 里的 `org.gradle.java.home` 是本机绝对路径。
> CI 上由 workflow 里的 `sed` 删掉这一行，改用 `JAVA_HOME`。

## 两种包可以同时安装

| | debug | release |
|---|---|---|
| applicationId | `com.haruhi.jmcomic.debug` | `com.haruhi.jmcomic` |
| 体积 | ~33 MB | ~4.4 MB |
| 混淆/裁剪 | 关闭 | R8 minify + resource shrink |
| 私有目录 | 各自的 `filesDir`（applicationId 不同，天然隔离） | 同左 |
| 签名 | Android 调试密钥 | `haruhibox-release.jks`（本地，文件名沿用改造前的名字） |

因为 applicationId 不同，**两个包可以同时装在一台设备上、互不覆盖**，
不需要每次切换都卸载。代价是桌面出现两个图标、数据不互通。

> 历史：`applicationIdSuffix = ".debug"` 是 2026-10-08 加的。
> 在那之前两者同名，切换构建类型必须 `adb uninstall`，会清掉 App 数据。

## release 签名

`app/build.gradle.kts` 会读工程根目录的 `keystore.properties`：

```properties
storeFile=haruhibox-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> 文件名与别名沿用了工程改名前的 `haruhibox`：那个 `.jks` 是**本机已有的、不在版本库里的**
> 资产，改名只会让签名配置失效。给新环境重新生成时用 `jmcomic-release.jks` 之类的名字也完全可以
> ——只要 `keystore.properties` 里的四项和实际文件对得上。

- 文件与 `*.jks` 都在 `.gitignore` 里，**不会进版本库**；
- 没有这个文件时 release 包**不签名**（CI 与只跑单测不受影响）；
- **务必备份 keystore**：丢了就无法对已安装的应用做覆盖升级。

重新生成一个本地 keystore：

```powershell
& "D:\Environment\jdk-21.0.4\bin\keytool.exe" -genkeypair -v `
  -keystore jmcomic-release.jks -alias jmcomic `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <口令> -keypass <口令> `
  -dname "CN=JMComicH, OU=Personal, O=JMComicH, L=Local, ST=Local, C=CN"
```

## 体积优化记录（33 MB → 4.4 MB）

安装时遇到的 `INSTALL_FAILED_INSUFFICIENT_STORAGE`（"Requested internal only,
but not enough space"）直接原因是**设备存储满**，但 APK 也确实虚胖。清理项：

| 措施 | 效果 |
|---|---|
| release 开启 R8 minify + resource shrink | 主要收益来源 |
| 排除 `org/bouncycastle/pqc/**`（后量子密码查找表，纯数据文件，R8 不删） | 约 −8 MB（未压缩） |
| 不再引入 `material-icons-extended` | 该模块含几千个矢量图标 |
| 移除 AGP 9 的 `optimization.packageScope`（见下） | 7.5 MB → 4.4 MB，且修掉启动崩溃 |

### ⚠ 不要配 `optimization { packageScope = ... }`

脚手架里原本有：

```kotlin
optimization {
    enable = true
    packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
}
```

开启后 release 包**启动即闪退**：

```
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider:
  androidx.startup.StartupException: java.lang.IllegalAccessError:
  Class kotlin.text.k extended by class v02 is inaccessible
```

原因：`packageScope` 让 R8 把这些包**重打包**到别的包名。Kotlin 的 `internal`
在字节码里是 `public`，R8 按"只在包内使用"的判断把它们收窄成包私有；
重打包改变了"包内"的边界，于是任何继承者（包括 R8 自己合成的类 `v02`）
在运行期就撞上 `IllegalAccessError`。

**结论**：本工程不使用 `packageScope`。它既导致崩溃，又让包更大。
`app/proguard-rules.pro` 里也不要加 `-repackageclasses`。

### 排除 BouncyCastle 后量子密码表是安全的吗？

是的。`pdfbox-android` → `bcprov-jdk15to18`，PDFBox 只用它做标准 CMS/证书解析。
`org/bouncycastle/pqc/**` 是后量子算法（Picnic/BIKE/SIKE）的参数表，
与 PDF 加密（RC4/AES + 标准安全字典）无关。
排掉的是**数据文件**，不是把某个安全功能关掉。

## 设备测试的运行方式

**不要用 `connectedAndroidTest`**：AGP 给测试 APK 硬编码加 `-g`，MIUI/HyperOS
拒绝该 flag（`SecurityException: ...INSTALL_GRANT_RUNTIME_PERMISSIONS...`），
安装阶段就失败。`-g` 是 AGP 测试引擎内部的默认值、不是 DSL 的 `installOptions`，
所以改不到它（已实测确认，`app/build.gradle.kts` 里那段配置留作记录）。

用本工程自带的任务（自己 `adb install` 不带 `-g`，再 `am instrument`）：

```powershell
.\gradlew.bat :app:runDeviceTests
.\gradlew.bat :app:runDeviceTests "-PclassFilter=<完全限定类名>"
```

实现：`app/build.gradle.kts` 的 `runDeviceTests` + `tools/device-tests/run-instrumented-tests.ps1`。
**`am instrument` 有用例失败时退出码仍是 0**，所以脚本从输出里找
`FAILURES!!!` / `INSTRUMENTATION_FAILED` / `OK (N tests)` 来判断成败。

### 排查卡住：用 `run-one.ps1`（带硬超时）

`:app:runDeviceTests` **没有超时保护**——某个用例挂住就会一直等，
而 Gradle 输出是缓冲的，看起来像"完全没反应"（CPU 也不会高）。
排查时用这个脚本，它直接调 adb、超时后杀掉并打印部分输出：

```powershell
powershell -File tools\device-tests\run-one.ps1 `
  -Class com.haruhi.jmcomic.core.codec.PdfEncryptionInstrumentedTest -TimeoutSec 90
# 退出码：0 通过 / 1 失败 / 2 超时（卡住）/ 3 结果未知
```

> `run-one.ps1` 与 `run-instrumented-tests.ps1` 都刻意写成**纯 ASCII**：
> Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI 读，中文注释会直接
> 导致语法解析失败（踩过两次）。

### ⚠ 这台小米设备上 Compose UI 测试跑不起来

已实测确认（不是猜测）：

- `createAndroidComposeRule<ComponentActivity>()` 启动的测试 Activity
  **拿不到焦点**——`dumpsys activity` 显示 `topResumedActivity` 始终是 launcher，
  Compose 测试框架于是在首次同步处**永久阻塞**（90 秒零进度，不是慢）；
- `adb shell input tap/swipe` 被拒：
  `SecurityException: Injecting input events requires ... INJECT_EVENTS permission`，
  所以也没法用脚本点界面做端到端验证。

因此**仓库里不保留 Compose UI 测试**：留一个跑不了的测试比没有更糟，
它会让人以为界面有回归保护。界面验证改为：

1. 界面背后的**展示逻辑**用不依赖屏幕的单测覆盖（`DisplayLogicTest`：
   状态文案、章节状态判定、体积格式、错误文案）；
2. 交互观感由人工在设备上确认。

要在 CI 上跑 Compose UI 测试，需要标准 AOSP 镜像的模拟器（AVD），不能用这台 MIUI 真机。

## 安装失败排查

| 报错 | 原因 | 处理 |
|---|---|---|
| `Requested internal only, but not enough space` | 设备 `/data` 空间不足（实测可用 226 MB 时失败） | 清理设备存储，或用 4.4 MB 的 release 包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` | 已装的是另一种签名的包 | `adb uninstall <包名>` 后重装 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（切换到 `.debug` 之前装的旧包） | 旧包 applicationId 没有 `.debug` 后缀 | `adb uninstall com.haruhi.jmcomic` |
| `SecurityException: ...INSTALL_GRANT_RUNTIME_PERMISSIONS...` | 小米机型 + `adb install -g` | 见上一节 |
| 安装成功但启动即退 | R8 规则问题，看 `adb logcat -d \| Select-String FATAL` | 见 `packageScope` 一节 |

查看设备剩余空间：

```powershell
adb shell df -h /data
```

## ⚠ 不要用 PowerShell 重写含中文的源文件

**症状**：用 `Get-Content -Raw` + `Set-Content`（哪怕显式写了 `-Encoding utf8`）改一个含中文的
`.kt` / `.md`，文件里的中文会全部变成 `锛? 鐨? 涓?` 这种乱码，而且**换行会被吞掉**
（多行注释和下一行代码挤成一行）。曾经这样弄坏过一次 `ModeRoot.kt`，
只能用 `git checkout` 回滚重做。

**原因**：Windows PowerShell 5.1 的 `Get-Content` 默认按 ANSI（本机是 GBK）解码，
UTF-8 字节在**读**的那一步就已经错了，`Set-Content -Encoding utf8` 只能把错误结果固化。
（PowerShell 7+ 默认 UTF-8，不会有这个问题；但脚本可能被 5.1 执行，别赌。）

**正确做法**：

- 改文件一律用编辑工具（或 IDE），不要用 shell 文本重写；
- 确实要用 shell 批量替换时，走 .NET API 并显式指定两端编码（**`pwsh` 下这样是安全的**，
  已用于本工程的大规模改名）：

  ```powershell
  $utf8 = New-Object System.Text.UTF8Encoding($false)   # $false = 不写 BOM
  $raw  = [System.IO.File]::ReadAllText($path)          # .NET 默认按 UTF-8 读
  $new  = $raw.Replace('old', 'new')
  [System.IO.File]::WriteAllText($path, $new, $utf8)    # 保留原有 LF
  ```

  注意 `WriteAllText` 不会动行尾，所以 LF 仓库不会被改成 CRLF。

- 写完立刻自查（0 表示干净）：

  ```powershell
  $t = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
  ([regex]::Matches($t, "[锛鐨涓鍒鏄鎴鏂涔寰鍥鏃鐢]")).Count
  ```

- `git diff` 输出里的中文乱码**不代表文件坏了**——那是控制台解码的问题，
  用编辑器打开文件确认才算数。
