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

    // 骨架阶段不让 lint 阻断构建
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
