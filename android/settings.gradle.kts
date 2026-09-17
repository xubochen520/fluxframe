pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 注意：本机 ~/.gradle/init.gradle 里有阿里云镜像的全局注入，
    // 用 FAIL_ON_PROJECT_REPOS 会直接构建失败，因此这里用 PREFER_SETTINGS：
    // 以本文件声明的 google()/mavenCentral() 为准，忽略初始化脚本注入的仓库。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FluxFrame"
include(":app")
