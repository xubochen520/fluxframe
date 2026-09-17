// FluxFrame 原生 Android 客户端 —— 根构建脚本
// 版本组合已在 JDK 21 + Gradle 8.7 上验证：AGP 8.6.1 / Kotlin 2.0.21 / Compose BOM 2024.10.01
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
