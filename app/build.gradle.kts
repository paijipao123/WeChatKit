plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wechathook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wechathook"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 注意：LSPosed 模块通常不开混淆，以保证 DexKit 反射所需的类名完整。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        // DexKit 2.0.7 以 JVM target 11 编译，其 inline 方法要求我们的 bytecode 不低于 11。
        jvmTarget = "17"
    }

    buildFeatures {
        // 模块 UI 使用纯 View 构建，无需 compose
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/**",
                "**.kotlin_module",
                "kotlin/**",
                "kotlin-tooling-metadata.json"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 编译期依赖：Xposed API（不打包进 APK）
    compileOnly(libs.xposed)

    // DexKit：动态定位微信内部类/方法（按字符串特征，跨版本适配）。
    // 必须以 implementation 打包进 APK，在宿主线程运行时由模块类加载器加载其类，
    // 用宿主(微信)的 ClassLoader 构建 DexKitBridge 来搜索宿主 dex。
    implementation(libs.dexkit)

    // 已读回执服务的 HTTP 客户端
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
}
