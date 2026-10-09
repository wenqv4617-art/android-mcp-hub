// ---------------------------------------------------------------------------
// 根构建脚本：只声明插件（不 apply），具体 apply 交给 :app 模块。
// ---------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
