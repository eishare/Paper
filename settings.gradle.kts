// =============================
// ✅ 精简版 settings.gradle.kts
// 适用于自定义 Paper fork（仅构建 paper-server）
// =============================

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
        mavenCentral()
    }
}

plugins {
    // 自动管理 JDK 版本
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// 设置项目名称
rootProject.name = "Paper"

// 只包含服务端模块
include("paper-server")

// ===== 可选：保持 Paper 官方的构建缓存逻辑（不会影响正常编译） =====
if (providers.gradleProperty("paperBuildCacheEnabled").orNull.toBoolean()) {
    val buildCacheUsername = providers.gradleProperty("paperBuildCacheUsername").orElse("").get()
    val buildCachePassword = providers.gradleProperty("paperBuildCachePassword").orElse("").get()
    if (buildCacheUsername.isBlank() || buildCachePassword.isBlank()) {
        println("The Paper remote build cache is enabled, but no credentials were provided. Remote build cache will not be used.")
    } else {
        val buildCacheUrl = providers.gradleProperty("paperBuildCacheUrl")
            .orElse("https://gradle-build-cache.papermc.io/")
            .get()
        val buildCachePush = providers.gradleProperty("paperBuildCachePush").orNull?.toBoolean()
            ?: System.getProperty("CI").toBoolean()
        buildCache {
            remote<HttpBuildCache> {
                url = uri(buildCacheUrl)
                isPush = buildCachePush
                credentials {
                    username = buildCacheUsername
                    password = buildCachePassword
                }
            }
        }
    }
}
