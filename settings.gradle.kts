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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView 只放在 Mozilla 自己的 Maven，其他東西不從這裡拿
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "stockdesk"
// core：純 Kotlin（資料來源、解析、快取規則、自選股），沒有 Android；app：畫面與 Android
include(":core", ":app")
