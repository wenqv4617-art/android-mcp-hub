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

    // ── Lint 门禁（Gate 0.2 发现，Gate 0.3 收口）────────────────────────────
    //
    // 事实经过：
    //
    //  Gate 0.1 交付的 APK 装上后启动即闪退，根因是 Manifest 里的类名与真实类名不一致。
    //  lint 的 MissingClass 规则**本来就能抓到它**，报的是：
    //
    //    AndroidManifest.xml:21: Error: Class referenced in the manifest,
    //    com.androidmcphub.app.HubApplication, was not found in the project
    //    or the libraries [MissingClass]
    //
    //  但当时这里写的是 abortOnError = false，这条 Error 被一起吞了，构建照样 SUCCESSFUL。
    //  中途试过只把 MissingClass 提升为 fatal（fatal += "MissingClass"）：**无效** ——
    //  abortOnError = false 的优先级更高，把错误包名打回去之后 :app:lintDebug 依然成功。
    //
    // Gate 0.3 的决定：**不再让 lint 沉默**。
    //
    //  之前不改 true 的唯一原因是那会连带要求处理 PhoneLinkService.kt 的 MissingPermission
    //  （当时树上唯一的 Error）。那个权限守卫已经补上（见 updateNotification /
    //  canPostNotifications），所以这里改回 true，任何新的 lint Error 都会让 :app:lintDebug
    //  失败，从而让 :app:check 失败。
    //
    //  这是"用门禁兜住"而不是"用 ignore 规则绕开"：没有加任何
    //  lint { disable += … } / baseline / @SuppressLint。
    //
    //  另外，Manifest 类名这一类问题仍然由文件末尾的 verifyManifestClasses 任务独立把守 ——
    //  它解析真实 APK 的二进制 Manifest + dex 的 class_defs 表，不依赖 lint 的严重级别配置。
    //  两条防线互相独立：lint 管"宽"，verify 管"准"。
    lint {
        abortOnError = true
    }

    testOptions {
        unitTests {
            // org.json 由 testImplementation("org.json:json") 提供真实现，
            // 因此**不**打开 returnDefaultValues —— 那会让 JSON 解析静默返回空值，
            // 单测就变成假绿。
            isReturnDefaultValues = false
            // Robolectric 需要真实的资源/Manifest 才能起 Android 环境
            // （它同时是 Compose UI 测试在 JVM 上运行的前提）。
            isIncludeAndroidResources = true
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

    // ---- UI 导航（改版新增）-------------------------------------------------
    // 只加这一条依赖：四页底部导航 + 子页路由 + 系统返回键行为都由它提供。
    // 刻意不引 Hilt/Koin —— 一个 ViewModel、四个页面还不值得上依赖注入框架。
    implementation(libs.androidx.navigation.compose)

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

    // ---- Compose UI 测试（JVM + Robolectric，不需要模拟器）------------------
    // 存在的理由：Stage 1 真机上的导航故障（点首页进了工具页）是**返回栈记账**
    // 问题，纯逻辑单测测不到。这里用真实 NavHostController + 真实 HubApp 跑一遍
    // 标签切换与返回键，把这条路径钉住。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
