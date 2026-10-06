// 「翎」浏览器 —— 顶层构建脚本
// 插件版本统一由 gradle/libs.versions.toml 版本目录管理。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
