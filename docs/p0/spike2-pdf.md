# P0 spike 2：JM PDF 生成方案选型

> 计划 §7 把这个 spike 定为「本任务最大难点」：必须先验证
> `com.tom-roush:pdfbox-android` 的**加密 + 书签 + 页面**三件套是否可用，
> 通过就走 B1，不通过就退到 B2（手写 PDF 写入器）。

## 结论

**采用 B1：`com.tom-roush:pdfbox-android:2.0.27.0`。**
三件套全部可用，不需要走 B2（手写 PDF 写入器，约 400～700 行）。

用户已就加密与权限做出决策（2026-10-08），见下文第 3 节。

## 1. 验证结果

| 能力 | 结果 | 验收方式 |
|---|---|---|
| 每张图一页，页面尺寸 = 图片像素尺寸 | ✅ | `doc.getPage(i).mediaBox` 逐页断言 |
| 章节书签（outline），根书签 `目录`，按 `第N话` 序号排序 | ✅ | 重新加载 PDF 遍历 outline，断言标题顺序 |
| `PageMode = USE_OUTLINES` | ✅ | `documentCatalog.pageMode.name == "USE_OUTLINES"` |
| 「不设密码」= 完全不加密 | ✅ | `doc.isEncrypted == false` |
| 密码保护：错误密码打不开 | ✅ | `PDDocument.load(out, "9999")` 抛 `InvalidPasswordException` |
| 加密强度可做到 AES-128 | ✅ | 断言 `V=4 / R=4 / Length=128 / StdCF=AESV2` |

## 2. 实测数据：加密强度取决于**两个**设置，缺一即静默降级

这一节的数据来自两处探测（探测代码见本文末尾），
**桌面版 PDFBox 3.0.2（参考实现在用的版本）与 pdfbox-android 2.0.27.0 行为一致**：

| `StandardProtectionPolicy` 的设置 | 产出 |
|---|---|
| 什么都不设 | `V=1 R=3 Length=40 StdCF=(none)` ← **40 位 RC4** |
| **只设 `encryptionKeyLength = 128`** | `V=1 R=3 Length=40 StdCF=(none)` ← **长度被忽略！** |
| 只设 `preferAES = true` | `V=1 R=3 Length=40 StdCF=(none)` |
| `encryptionKeyLength = 128` **且** `preferAES = true` | `V=4 R=4 Length=128 StdCF=AESV2` ← **AES-128** |

### ⚠ 由此推翻之前的一处判断（本文档 2026-10-07 版本的错误）

早先这一节写的是：「Android 端口没有 `setEncryptionKeyLength`，所以退化成 RC4-128；
参考实现产出的是 AES-128」。**两句都错**，原因是我当时读的 `javap` 输出不完整，
漏掉了 `setEncryptionKeyLength` 与 `setPreferAES` 这两个方法（它们确实存在，
用运行期反射 `Class.getMethods()` 才能看全）。

事实是：

- **参考实现（`JmcomicService` 第 684～693 行）只设了 `setEncryptionKeyLength(128)`，
  没有设 `preferAES`，所以它实际产出的是 40 位 RC4** —— 这是当前 PDF 加密里最弱的档位之一；
- Android 端口与桌面版行为**一致**，同样只设 keyLength 也是 RC4-40。

所以「保持现状」并不等于"对齐参考实现 + 实现成本最低"，
而是"沿用参考实现中的一个无效调用 + 最弱的加密"。

## 3. 用户决策（2026-10-08）

### 决策 1：加密算法改为 **AES-128**

- 实现：`PdfWriter.applyProtection` 中同时设置
  `encryptionKeyLength = 128` 与 `isPreferAES = true`；
- 性质：这是**有意的安全强化**，**不是**与参考实现产物对齐（参考实现是 RC4-40）；
- 回归防线：`PdfEncryptionInstrumentedTest.albumPdfWithPasswordUsesAes128`
  断言 `V/R/Length/StdCF` 四项 —— 漏设任一项都会静默降级且不报错，所以必须逐项断言。

### 决策 2：访问控制只看「能否打开」

用户原话：「两者同密码即可，因为只需要管控能否打开/阅读 pdf 就行，
能打开就不管其他权限了」。

- 实现：`StandardProtectionPolicy(password, password, permission)`，owner 与 user 同密码；
- 后果（**已确认接受，但必须说清楚**）：打开者会被判定为 owner 身份，
  因此 `canModify` / `canExtractContent` 等权限位**不生效**
  （实测 `isOwnerPermission == true`）。P 位本身写对了（实测 `-540`），
  但对任何拿到密码的人都不起作用；
- 因此**本 App 的 PDF 只提供「打不开」这一层保护，不提供「能看不能改」**；
- 回归防线：`PdfEncryptionInstrumentedTest.permissionBitsAreWrittenButOwnerIdentityBypassesThem`
  把这一事实固定成断言，避免以后误以为权限生效。

## 4. 加密用例为什么不在单元测试里跑

`PdfWriterSpikeTest`（Robolectric，跑在开发机上）只覆盖**不涉及密码学**的部分：
页面数、页面尺寸、书签、`PageMode`、「不设密码」确实不加密。

加密相关用例放在 `app/src/androidTest/.../PdfEncryptionInstrumentedTest.kt`，
**必须在设备上跑**，原因是 Robolectric 沙箱的一个环境限制：

```
providers = Conscrypt, SUN, SunRsaSign, SunEC, SunJSSE, SunJCE, ..., BC
AES via SunJCE = ok(16)
AES via BC     = FAIL: NoClassDefFoundError: Could not initialize class javax.crypto.JarVerifier
```

Conscrypt 排在 provider 首位，JDK 初始化 `JarVerifier` 时用它验签 provider 的 JAR，
失败链是：

```
NoSuchAlgorithmException: error:0c00006f:ASN.1 encoding routines:
  OPENSSL_internal:DIGEST_AND_KEY_TYPE_NOT_SUPPORTED
  at org.conscrypt.OpenSSLX509Certificate.verifyOpenSSL(...)
```

PDFBox 的 AES 走 BouncyCastle，于是**任何需要 AES 的路径在 Robolectric 下都跑不了**——
这既包括生成加密 PDF，也包括用密码打开加密 PDF。真机上不存在这个问题
（APK 里的 BC 类不需要 JAR 验签）。

> 这与 P0 决策 D-6（Robolectric 泄漏 Conscrypt 到同一 JVM）是同一个根源的两种表现。

## 5. 其它实现约束（P4 用）

1. **WebP 不能直接内嵌 PDF**，必须解码后重新编码：`PdfWriter` 走
   `LosslessFactory.createFromImage(document, bitmap)`，即无损 Flate 内嵌，
   与参考实现一致（文件会明显偏大）。
2. **PDF 单页尺寸上限 14400×14400**：JM 长图接近上限时要处理，
   参考实现没有处理，P4 需要加校验并给出明确错误。
3. **必须流式写**：`PdfWriter` 直接 `document.save(out)`，不在内存里拼 ByteArray；
   P4 要把生成放进前台服务，并支持取消（取消时删除半成品）。
4. **`BitmapFactory` 依赖真实解码器**：P0 的测试用 PNG 字节流配 `.webp` 后缀，
   只验证 PDF 能力；真机上的真实 WebP 解码是 P4 的验收项。

## 6. 复现方式

```powershell
cd D:\Project\HaruhiBox

# 结构类用例（开发机，Robolectric）
.\gradlew.bat :app:testDebugUnitTest --tests "com.haruhi.haruhibox.core.codec.PdfWriterSpikeTest"

# 加密类用例（需要在设备上；先确认 adb devices 能看到设备）
.\gradlew.bat :app:connectedDebugAndroidTest
```

### 探测加密强度的独立方法

桌面版（对照参考实现）：

```powershell
$cp = (Get-Content reference-classpath.txt -Raw).Trim()
& javac -cp $cp -d out tools/pdf-probe/PdfBoxReferenceProbe.java
& java -cp "out;$cp" PdfBoxReferenceProbe
```

Android 侧同理，把 `PdfEncryptionInstrumentedTest` 里的四种设置组合各跑一次即可
（探测代码在 git 历史里，用完就删了，避免留无意义的测试）。
