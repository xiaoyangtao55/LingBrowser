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
        // 版本号遵循 Semantic Versioning 2.0.0。
        //
        // 1.1.0 而不是 2.0.0：自 1.0.0 以来全是**向后兼容的新增**——
        // 阅读模式、下载管理、书签文件夹、会话恢复、标签页缩略图等，
        // 都只增加能力，没有移除任何用户可依赖的行为，
        // 数据库也只有加法迁移（VERSION 1→2→3，均为 ALTER TABLE ADD COLUMN）。
        // 因此按 SemVer「向后兼容的功能性新增」递增 MINOR。
        //
        // 唯一两处"用户能感知的行为变化"不构成 MAJOR：
        //  - 标签面板去掉 1/4 档：老值 "QUARTER" 走 runCatching 兜底为默认值，
        //    不会闪退，属于偏好项变更而非 API 破坏。
        //  - 默认搜索引擎 百度 → 必应：只影响未显式设置过的新用户。
        //
        // versionCode 是给系统判断"能否覆盖安装"的单调整数，与 SemVer 无关，
        // 每次发版 +1，绝不能回退（回退会导致无法覆盖安装）。
        versionCode = 2
        versionName = "1.1.0"
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
            // ⚠️ 路径必须统一用 rootProject.file(...) 解析。
            //
            // 这里踩过一个很隐蔽的坑：本文件是 **app 模块**的构建脚本，
            // 裸写 `file("keystore/ling-release.jks")` 会解析成
            //   <仓库根>/app/keystore/ling-release.jks
            // 而 CI 把密钥库还原到
            //   <仓库根>/keystore/ling-release.jks
            // 两者差一层目录，于是 exists() 恒为 false —— 构建成功，
            // 但产物是 app-release-unsigned.apk，而且**不报任何错**。
            //
            // 更阴的是：它只在设置了 LING_KEYSTORE_PATH 时才发作。
            // 本地不设这个变量，代码会走 `?:` 后面的 rootProject.file(...)
            // 分支从而正常工作，所以"本地能签名、CI 不能"——
            // 正是这个不对称让问题一直藏着。
            //
            // 还必须过滤空字符串：CI 在未配置签名 Secrets 时会把它设成 ""，
            // 而 "" 不是 null，`?.let { file(it) }` 会走进来并抛出
            //   IllegalArgumentException: Cannot convert '' to File
            // 导致 Gradle 在**配置阶段**就失败（连测试都跑不到）。
            val keystoreFile = System.getenv("LING_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { rootProject.file(it) }
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
            //
            // 判断逻辑必须与 signingConfigs 里**完全一致**（同样用
            // rootProject.file 解析）。两处若用了不同的基准目录，
            // 就会出现"signingConfig 认为有密钥、这里认为没有"（或反之）
            // 的分裂状态，产物行为将难以预测。
            val hasKeystore = System.getenv("LING_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { rootProject.file(it).exists() }
                ?: rootProject.file("keystore/ling-release.jks").exists()
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null

            // 关键：**明确说了要签名、却找不到密钥库**时必须让构建失败。
            //
            // 之前静默退化成 unsigned，CI 一路绿灯产出一个未签名包，
            // 从日志到退出码都看不出问题 —— 这种"成功但做错了事"最难查。
            // 只有"根本没配"（环境变量为空）才允许退化为 unsigned。
            val requested = System.getenv("LING_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
            if (requested != null && !hasKeystore) {
                throw GradleException(
                    "指定了 LING_KEYSTORE_PATH=$requested，但文件不存在：" +
                        "${rootProject.file(requested)}。" +
                        "若确实想产出未签名包，请不要设置该环境变量。",
                )
            }
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
