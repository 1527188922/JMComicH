pluginManagement {
    repositories {
        // 国内构建机（Gitee Go）访问 dl.google.com / plugins.gradle.org 会卡死，
        // 所以 CI 会设 JMCOMICH_USE_CN_MIRROR=1 优先走阿里云镜像（见 tools/ci/gitee-build.sh）。
        // 本地与 GitHub 不设这个变量，仍然只用官方仓库。
        if (System.getenv("JMCOMICH_USE_CN_MIRROR") == "1") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 说明：这里刻意不注册 org.gradle.toolchains.foojay-resolver-convention。
// 它会让 Gradle 在工具链不匹配时去 api.foojay.io 下载一整个 JDK（曾阻塞 P0 构建）。
// 本工程只用本机已安装的 JDK 21，见 gradle.properties 的 org.gradle.java.home。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：只有国内 CI 才把镜像排在前面，官方仓库留在后面兜底
        if (System.getenv("JMCOMICH_USE_CN_MIRROR") == "1") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "JMComicH"
include(":app")
