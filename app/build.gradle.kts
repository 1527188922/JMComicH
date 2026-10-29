import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 本地 release 签名。
// 没有 keystore.properties 时 release 包不签名（CI 与只跑单测的场景不需要它）。
// 详见 docs/BUILD.md。
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.haruhi.jmcomic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.haruhi.jmcomic"
        // minSdk 26：JM 阅读器需要 BitmapRegionDecoder + 现代 WebP 解码，
        // 且 Android 8 起前台服务/通知渠道语义稳定。
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "1.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // 侧载自用：开启 v1+v2 以兼容旧机型
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // debug 与 release 用不同的 applicationId，才能同时安装、互不覆盖。
            // 否则每次切换构建类型都要先卸载（会清掉 App 数据），
            // 等 P2 有真实书库之后代价太大。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // ⚠ 不要在这里配 optimization.packageScope。
            // AGP 9 的 `optimization { packageScope = setOf("kotlin.**", ...) }` 会让 R8 把
            // Kotlin 标准库类重打包到别的包，而 Kotlin 的 internal 类在字节码里是 public，
            // 重打包后 R8 会把它们收窄成包私有 —— 结果任何继承它们的类（包括 R8 自己合成的类）
            // 都会在运行期抛：
            //   IllegalAccessError: Class kotlin.text.k extended by class v02 is inaccessible
            // 表现为 androidx.startup.InitializationProvider 初始化失败、应用启动即闪退。
            // 这条配置曾出现在 P0 的脚手架上，P1 加签名/混淆时被证实有害，已移除。
            // 详见 docs/BUILD.md。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Room 的 MigrationTestHelper 从**测试 assets** 里读导出的 schema JSON
    // （`app/schemas/<DatabaseClass>/<version>.json`），必须显式挂进去，
    // 否则迁移测试会报 "Cannot find the schema file in the assets folder"。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                // ---- 体积优化（见 docs/BUILD.md）----
                // pdfbox-android 传递引入 org.bouncycastle:bcprov-jdk15to18，
                // 而 bcprov 体积最大的部分是**后量子密码**的查找表，纯数据文件、
                // 不是类，R8 不会删：
                //   lowmc.properties 3.6MB、sike/p751 1.9MB、p610 1.2MB、p503 765KB…
                // PDFBox 只用 BouncyCastle 做标准 CMS/证书解析，完全用不到 PQC。
                // 注意：这不是安全功能开关，被排除的只是 PQC 算法的参数表。
                "org/bouncycastle/pqc/**",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 保持 false：设为 true 会把 `android.jar` 换成打桩版本（见 docs/p0/README.md 的 D-5）。
            isReturnDefaultValues = false

            all {
                // 每个测试类单独一个 JVM。
                // 原因（见 docs/p0/README.md 的 D-6）：Robolectric 的沙箱会把 Conscrypt 的
                // OpenSSL provider 泄漏到后续测试，导致 JDK 的 javax.crypto.JarVerifier
                // 初始化失败，纯 JVM 的 AES 用例集体报 NoClassDefFoundError。
                // 目前测试量很小（~50 个），这个代价可以接受；测试变多后应改为
                // 把 Robolectric 用例拆到独立的 test source set。
                it.forkEvery = 1
            }
        }

        // AGP 安装 APK 时会带上 `-g`（一次性授予全部运行时权限）。
        // **MIUI / HyperOS 拒绝这个 flag**：普通 `adb install -g` 没有
        // `INSTALL_GRANT_RUNTIME_PERMISSIONS` 特权，安装直接失败：
        //   SecurityException: You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
        //   permission to use the PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS flag
        // 结果是 `connectedAndroidTest` 在小米机型上永远卡在安装阶段。
        // 测试不需要自动授权，所以把 `-g` 替换成空串去掉它（AOSP/Pixel 上同样安全）。
        // 详见 docs/BUILD.md。
        installation {
            // AGP 安装测试 APK 时**硬编码**加了 `-g`（一次性授予全部运行时权限），
            // installOptions 管不到它。MIUI / HyperOS 拒绝这个 flag：
            //   SecurityException: You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
            //   permission to use the PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS flag
            // 只有小米机型上会这样，AOSP / Pixel 正常。
            //
            // `-g` 是 AdbApkInstaller 内部的默认值，不是 DSL 里的 installOptions，
            // 所以这里改不动它 —— 保留本块作为记录，实际规避办法见 docs/BUILD.md
            // （手动 adb install + am instrument）。
            installOptions.removeAll { it == "-g" }
        }
    }

    lint {
        // P0 起把 lint 当 CI 门禁：错误必须修。
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += setOf(
            "MissingTranslation",
            "ExtraTranslation",
            // 依赖版本是我们刻意固定的（见 libs.versions.toml 顶部说明），
            // 让 lint 每次联网报"有更新版本"只会制造噪声
            "NewerVersionAvailable",
            "GradleDependency",
            "AndroidGradlePluginVersion",
        )
    }
}

// ---------------------------------------------------------------------------
// 在设备上跑 instrumented test（真机验证）。
//
// 为什么不用 `connectedAndroidTest`：AGP 安装测试 APK 时**硬编码**带 `-g`
// （一次性授予全部运行时权限）：
//     adb install --bypass-low-target-sdk-block -t -g --user 0 app-debug-androidTest.apk
// MIUI / HyperOS 拒绝这个 flag，因为普通 adb 没有
// `INSTALL_GRANT_RUNTIME_PERMISSIONS` 特权：
//     SecurityException: You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
//     permission to use the PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS flag
// 这是 ROM 侧限制，`installOptions` 改不到内部默认值。AOSP / Pixel 上
// `connectedAndroidTest` 本身是可用的，那个命令也保留着。
//
// 这个任务自己装 APK 自己跑 instrumentation，在任何 ROM 上都能用。
// 用法：
//     .\gradlew.bat :app:runDeviceTests
//     .\gradlew.bat :app:runDeviceTests -PclassFilter=com.haruhi.jmcomic.core.codec.PdfEncryptionInstrumentedTest
// ---------------------------------------------------------------------------
val instrumentationClassFilter: String? = providers.gradleProperty("classFilter").orNull

tasks.register<Exec>("runDeviceTests") {
    group = "verification"
    description = "在已连接设备上安装并运行 instrumented test（绕开 MIUI 对 adb install -g 的限制）"
    dependsOn("assembleDebug", "assembleDebugAndroidTest")

    val adbExecutable = androidComponents.sdkComponents.adb.get().asFile.absolutePath
    val appApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
    val testApk = layout.buildDirectory.file("outputs/apk/androidTest/debug/app-debug-androidTest.apk").get().asFile
    val testPackage = "com.haruhi.jmcomic.debug.test"
    val script = rootProject.file("tools/device-tests/run-instrumented-tests.ps1")

    commandLine(
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
        "-Adb", adbExecutable,
        "-AppApk", appApk.absolutePath,
        "-TestApk", testApk.absolutePath,
        "-TestPackage", testPackage,
        "-ClassFilter", instrumentationClassFilter.orEmpty(),
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    // --- AndroidX 基础 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 只引 icons-core。**不要**换成 icons-extended：那个模块含几千个矢量图标，
    // debug 包会全量保留、release 也要过 R8，明显增大体积（见 docs/BUILD.md）。
    // 需要 core 里没有的图标时，自己画 ImageVector。
    implementation(libs.androidx.compose.material.icons.core)
    // ⚠ 不要引 material-icons-extended：它是一个包含几千个矢量图标的模块，
    // 即使 R8 能删掉未使用的图标，也会显著拖慢构建、并且 debug 包里全量保留。
    // 需要图标时用 material-icons-core（随 material3 传递引入）里的常用集，
    // 或者自己画 ImageVector。
    implementation(libs.androidx.navigation.compose)

    // --- 数据库 ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- 后台任务 / 存储 ---
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // --- 媒体 ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.common)

    // --- 网络 / 序列化 ---
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // --- 图片 ---
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // --- 打包 / PDF ---
    implementation(libs.zip4j)
    implementation(libs.pdfbox.android)

    // --- 日志 ---
    implementation(libs.timber)

    // --- 单元测试 ---
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))

    // --- 仪器测试 ---
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    // Room 迁移测试：MigrationTestHelper 需要 room-testing + sqlite-framework
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.sqlite.framework)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
