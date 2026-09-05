// Pupurin° Loom (铃言织机°) — Android 版 app 模块
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pupurin.loom"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pupurin.loom"
        minSdk = 24
        targetSdk = 34
        versionCode = 42
        versionName = "0.4.2"
        setProperty("archivesBaseName", "PupurinLoom-$versionName")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 使用调试 keystore 签名，便于直接安装分发（正式发布请替换为自有签名）
            signingConfig = signingConfigs.getByName("debug")
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
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    // WebViewAssetLoader + addDocumentStartJavaScript（ES module 离线加载的关键）
    implementation("androidx.webkit:webkit:1.9.0")
    // JSON 序列化（JS Bridge 数据交换）
    implementation("com.google.code.gson:gson:2.10.1")
    // SAF（Storage Access Framework）文件访问
    implementation("androidx.documentfile:documentfile:1.0.1")
}