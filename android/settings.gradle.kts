// ---------------------------------------------------------------------------
// android-mcp-hub —— Android 端（手机链路）构建入口
//
// 依赖仓库集中在这里声明，并通过 RepositoriesMode.FAIL_ON_PROJECT_REPOS
// 禁止各模块自己写 repositories {}：版本来源只有一个地方，评审时好核对。
// ---------------------------------------------------------------------------

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
    }
}

dependencyResolutionManagement {
    // 任何模块私自声明仓库都会直接构建失败
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-mcp-hub"
include(":app")
