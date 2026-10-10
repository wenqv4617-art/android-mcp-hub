import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ---------------------------------------------------------------------------
// :app —— 唯一模块（骨架阶段：一屏 UI + 一条长连接）
// ---------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.androidmcphub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androidmcphub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 骨架阶段不开混淆；规则文件已就位，将来开启时直接生效。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    // 骨架阶段不让 lint 阻断构建。
    //
    // ⚠️ Gate 0.2 的审计发现，这里必须写清楚，免得下次又踩：
    // lint **本来就能**抓到 Gate 0.1 的启动闪退 —— 它的 MissingClass 规则会报
    //
    //   AndroidManifest.xml:21: Error: Class referenced in the manifest,
    //   com.androidmcphub.app.HubApplication, was not found in the project
    //   or the libraries [MissingClass]
    //
    // 但 abortOnError = false 把当时唯一的 Error 一起吞了，构建照样 SUCCESSFUL。
    // 试过只把 MissingClass 提升为 fatal（fatal += "MissingClass"）：**无效** ——
    // abortOnError = false 的优先级更高，实测把错误包名打回去之后
    // :app:lintDebug 依然 BUILD SUCCESSFUL。
    //
    // 所以硬门禁没有放在 lint 里，而是放在文件末尾的 `verifyManifestClasses` 任务上：
    // 它解析真实 APK 的二进制 Manifest + dex 的 class_defs 表，不依赖 lint 的严重级别配置。
    // 没改成 abortOnError = true 是因为那会连带要求处理 PhoneLinkService.kt:321 的
    // MissingPermission（当前树上唯一的 Error）—— 那是另一件事，不属于本次最小修复。
    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            // org.json 由 testImplementation("org.json:json") 提供真实现，
            // 因此**不**打开 returnDefaultValues —— 那会让 JSON 解析静默返回空值，
            // 单测就变成假绿。
            isReturnDefaultValues = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- AndroidX 基础 ----------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // ---- Compose（版本由 BOM 统一）----------------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- 协程 / 网络 -------------------------------------------------------
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // ---- JVM 单元测试 ------------------------------------------------------
    testImplementation(libs.junit)
    // Android 自带的 org.json 在 JVM 单测里只是抛异常的 stub，补一份真实现。
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // 在 JVM 上起假 WebSocket 服务端，验证真实链路而不依赖模拟器。
    testImplementation(libs.okhttp.mockwebserver)
}

// ---------------------------------------------------------------------------
// Gate 0.2：用**真实产物**校验 AndroidManifest 的组件类名
//
// 起因是一次真实的闪退：Gate 0.1 的 APK 点图标立刻回桌面、界面完全不出现。
// `HubApplication.kt` 里写的是 `package com.androidmcphub.app.ui`（文件却在 app/ 目录下），
// 编译出来是 `com.androidmcphub.app.ui.HubApplication`，而 Manifest 要的是
// `com.androidmcphub.app.HubApplication` —— 系统建进程时找不到类，直接 ClassNotFoundException。
//
// 这类错误编译期全绿：Kotlin 不要求目录与包名一致，`assembleDebug` 和那 132 个 JVM 单测都不报错。
// 所以这里刻意**不做源码文本匹配**，而是先构建，再解析
//   * APK 里编译过的二进制 AndroidManifest.xml（脚本自己解析 AXML，不依赖 aapt2）
//   * APK 里所有 classes*.dex 的 class_defs 表（"定义了的类"，不是"引用到的类型"）
// 两者对不上就非零退出。它也覆盖依赖库合并进来的组件（AndroidX 的 provider/receiver）。
//
// 单独跑：./gradlew :app:verifyManifestClasses
// ---------------------------------------------------------------------------
tasks.register<Exec>("verifyManifestClasses") {
    group = "verification"
    description = "解析 APK 的 Manifest 与 dex，确认每个组件类都真实存在"
    dependsOn("assembleDebug")
    workingDir = rootProject.projectDir
    commandLine(
        "python3",
        "tools/verify_manifest_classes.py",
        "--apk",
        layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath,
    )
}

// 挂到 check 上，所以 `./gradlew check` 会跑（注意方向：verify 依赖 assemble，不能反过来）
tasks.named("check") {
    dependsOn("verifyManifestClasses")
}
