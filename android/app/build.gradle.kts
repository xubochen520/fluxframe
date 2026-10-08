import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.fluxframe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fluxframe.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 53
        versionName = "2.8.0"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 内网自用：release 直接用调试签名，方便本机 / 手机直接安装；
            // 若需上架或正式分发，请替换为自己的 keystore。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            // Robolectric 需要真实资源与 manifest 才能把 Compose 渲染起来
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

// 本工程位于中文路径下。Gradle 的测试 worker 是独立 JVM，默认用系统 ANSI 代码页
// 解析 classpath，中文目录会被解码错，表现为莫名其妙的 ClassNotFoundException。
// 显式把 worker 的编码固定成 UTF-8。若仍失败，请从 ASCII 路径（例如 C:\ff-android
// 建立的目录联接）执行测试。
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    systemProperty("file.encoding", "UTF-8")
    maxParallelForks = 1

    // 把 -Dfluxframe.* 透传给测试 JVM。
    // 注意：gradlew 的 -D 只作用于 Gradle daemon，不会传给 test worker，
    // 不转发的话 ApiContractTest 会静默走 assumeTrue 跳过（表现为 tests=12 skipped=12）。
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("fluxframe.")) systemProperty(name, value.toString())
    }
    // 同时也支持环境变量写法：FLUXFRAME_BASE_URL / FLUXFRAME_USER / FLUXFRAME_PASS
    mapOf(
        "FLUXFRAME_BASE_URL" to "fluxframe.baseUrl",
        "FLUXFRAME_USER" to "fluxframe.user",
        "FLUXFRAME_PASS" to "fluxframe.pass",
    ).forEach { (envName, propName) ->
        val value = providers.environmentVariable(envName).orNull
        if (!value.isNullOrBlank()) systemProperty(propName, value)
    }
}

dependencies {
    // ---- 基础 ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ---- Compose ----
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- 生命周期 / ViewModel ----
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // ---- 导航 ----
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // ---- 网络 ----
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ---- 图片 / 视频 ----
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // ---- 本地存储 ----
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- 调色（液态玻璃取色）----
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ---- OPPO 流体云 ----
    // 本项目走「意图共享（端侧）」链路，通过 ContentProvider 下发 IntelligentIntent，
    // 不需要把任何 SDK 打进包，因此这里默认不引入 OPPO 的 SeedlingSupportSDK。
    //
    // 若日后要走「泛在卡片」链路 B（需要在 Pantanal DevStudio 开发 upk 卡片包并
    // 发布到 OPPO 服务库），官方 aar 已公开在 Maven Central，取消下面一行注释即可：
    //   implementation("com.oplus.pantanal.card:seedling-support-external:3.0.7")
    // 届时还需在 Manifest 里自声明 SeedlingCardWidgetProvider
    // （action: com.oplus.seedling.action.SEEDLING_CARD）。

    // ---- 测试 ----
    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // 让 Compose 布局能在 JVM 上跑（无需真机/模拟器），用于回归"界面一片空白"这类布局 bug
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
