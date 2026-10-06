plugins {
    // AGP 9.x 内置了 Kotlin 支持（自带 `kotlin` 扩展），
    // 因此不能再单独 apply org.jetbrains.kotlin.android，否则会报
    // "Cannot add extension with name 'kotlin'"。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ling.browser"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ling.browser"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    androidResources {
        // 只打包中英文资源，减小体积（替代已废弃的 resConfigs）
        localeFilters += listOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            // 密钥库优先取 keystore/ling-release.jks（本地开发），
            // 其次可用环境变量 LING_KEYSTORE_PATH 指定（CI）。
            //
            // 注意必须过滤空字符串：CI 在未配置签名 Secrets 时会把
            // LING_KEYSTORE_PATH 设成 ""，而 "" 不是 null，
            // `?.let { file(it) }` 会走进来并抛出
            //   IllegalArgumentException: Cannot convert '' to File
            // 导致 Gradle 在**配置阶段**就失败（连测试都跑不到）。
            val keystoreFile = System.getenv("LING_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { file(it) }
                ?: rootProject.file("keystore/ling-release.jks")

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                // 口令支持三种来源，按优先级：Gradle 属性 → 环境变量 → 本地默认值。
                // CI 用环境变量（Secrets 注入），本地直接跑也不用额外配置。
                storePassword = providers.gradleProperty("LING_STORE_PASSWORD").orNull
                    ?: System.getenv("LING_STORE_PASSWORD")
                    ?: "ling123456"
                keyAlias = providers.gradleProperty("LING_KEY_ALIAS").orNull
                    ?: System.getenv("LING_KEY_ALIAS")
                    ?: "ling"
                keyPassword = providers.gradleProperty("LING_KEY_PASSWORD").orNull
                    ?: System.getenv("LING_KEY_PASSWORD")
                    ?: "ling123456"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // 有密钥库就签名；没有则退化为 unsigned（CI 未配置 Secrets 时也能出包）。
            // 同样要过滤空字符串，理由见上面 signingConfigs 的注释。
            val hasKeystore = System.getenv("LING_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { file(it).exists() }
                ?: rootProject.file("keystore/ling-release.jks").exists()
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefresh)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 偏好设置持久化
    implementation(libs.androidx.datastore.preferences)

    // 书签 / 历史 / 标签页持久化：直接用平台自带的 SQLiteOpenHelper，零额外依赖

    // Compose：版本由 BOM 决定
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // 注意：刻意不使用 material-icons-extended —— 它会把上万个图标类编进 dex，
    // 仅此一项就让 debug 包膨胀到 18 MB。所有图标见 ui/theme/LingIcons.kt。

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 单元测试：UrlUtils 这类纯逻辑必须有用例覆盖
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
