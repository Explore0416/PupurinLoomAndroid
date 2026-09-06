// Pupurin° Loom (铃言织机°) — Android 版 app 模块
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取固定签名密钥配置（keystore/pupurin-release.jks + key.properties）。
// 保证本地与 GitHub Actions 每次构建的 Release APK 签名一致，可直接覆盖升级。
val keystoreProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.pupurin.loom"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pupurin.loom"
        minSdk = 24
        targetSdk = 34
        versionCode = 49
        versionName = "0.4.9"
        setProperty("archivesBaseName", "PupurinLoom-$versionName")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 使用固定密钥签名，保证各版本签名一致，支持直接覆盖升级
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // 缺 key.properties 时回退到 debug 签名，本地开发仍可安装
                signingConfig = signingConfigs.getByName("debug")
            }
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