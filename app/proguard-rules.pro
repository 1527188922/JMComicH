# HaruhiBox 的 R8 / ProGuard 规则。
#
# release 构建开启了 minify + shrinkResources（见 app/build.gradle.kts）。
# 这里只写**确实必要**的规则；能靠 R8 自动推断的一律不写。

# ---------------------------------------------------------------------------
# 通用
# ---------------------------------------------------------------------------
# 保留行号信息，崩溃栈才可读（不保留局部变量名以省体积）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# kotlinx.serialization
# 说明：官方推荐用 @Serializable 的类自动生成 serializer，
# 只要有 kotlinx-serialization 的 consumer rules 就够了；
# 这里显式保留 Companion 与 serializer()，因为反射查找会用到。
# ---------------------------------------------------------------------------
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# OkHttp / Okio
# okhttp 5.x 自带 consumer rules；这里只额外保留 TLS 相关可选类
# ---------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# BouncyCastle（pdfbox-android 的传递依赖）
# ---------------------------------------------------------------------------
# JCE provider 通过类名反射加载，必须整体保留
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
# 只按需保留 provider 的注册表（体积远小于全量保留）
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-dontwarn javax.naming.**

# ---------------------------------------------------------------------------
# PDFBox-Android
# ---------------------------------------------------------------------------
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ---------------------------------------------------------------------------
# zip4j
# ---------------------------------------------------------------------------
-keep class net.lingala.zip4j.** { *; }

# ---------------------------------------------------------------------------
# Room：实体与 Dao 由生成的实现类引用，R8 能正确推断，无需额外规则。
# Timber：DebugTree 在 release 里不会被引用，会被自动移除。
# ---------------------------------------------------------------------------

# 保留崩溃日志里的异常类名（默认已保留 Throwable 子类，这里是显式声明意图）
-keepnames class * extends java.lang.Exception
