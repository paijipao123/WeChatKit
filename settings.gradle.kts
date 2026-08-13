pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // DexKit (org.luckypray) 及其他第三方依赖
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API 仓库
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WeChatKit"
include(":app")
