import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从 local.properties（不入库）读取阶跃 API Key，注入 BuildConfig，避免把密钥写死在源码里。
// 缺失时为空串：代码里 API_KEY 为空会自动退回手机本地 TTS，不会崩。
val stepApiKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("STEP_API_KEY", "")

android {
    namespace = "com.formocracy.glassbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.formocracy.glassbridge"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "STEP_API_KEY", "\"$stepApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // === Rokid CXR-L SDK（眼镜 IO：显示/TTS/指令通道，经 Rokid AI App 连接眼镜）===
    // 备注：1.1.0 依赖自包含最干净；若要严格对照官方文档示例(按 1.0.4 编写)可改成 1.0.4
    implementation("com.rokid.cxr:client-l:1.1.0")

    // === 接收 Godot 游戏事件的本地 WebSocket 服务 ===
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // === 基础库 ===
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
