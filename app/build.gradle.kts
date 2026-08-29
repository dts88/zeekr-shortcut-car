plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kooo.evcam"
    compileSdk = 36

    // 签名配置。
    // 默认沿用仓库内的公开测试密钥（AOSP 测试签名，口令公开），方便任何人自行构建、
    // 并保证不同版本之间可以覆盖安装。正式发布可用环境变量覆盖为自己的密钥：
    //   ZEEKR_KEYSTORE / ZEEKR_KEYSTORE_PASSWORD / ZEEKR_KEY_ALIAS / ZEEKR_KEY_PASSWORD
    signingConfigs {
        create("release") {
            val customStore = System.getenv("ZEEKR_KEYSTORE")
            if (!customStore.isNullOrBlank() && file(customStore).exists()) {
                storeFile = file(customStore)
                storePassword = System.getenv("ZEEKR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZEEKR_KEY_ALIAS")
                keyPassword = System.getenv("ZEEKR_KEY_PASSWORD")
            } else {
                storeFile = file("../keystore/release.jks")
                storePassword = "android"
                keyAlias = "apkeasytool"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.dts88.zeekrshortcut"
        minSdk = 28
        targetSdk = 36
        // 每次发布 tag 前同步更新这两个值：versionCode 决定能否覆盖安装，
        // versionName 会成为 Release 名称与 APK 文件名
        versionCode = 28
        // 版本号从 0.1.0 重新起算；代码基座为 EVCam 1.6.6 (0876b97)
        versionName = "0.8.2-alpha"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 使用签名配置
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("../assets")
        }
    }

}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.cardview)

    // 飞书：使用轻量级 OkHttp WebSocket 实现，不再依赖官方 SDK

    // 网络请求和 WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON 解析
    implementation("com.google.code.gson:gson:2.10.1")

    // Glide 图片加载库（用于缓存和优化缩略图加载）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // WorkManager 定时任务（用于保活）
    implementation("androidx.work:work-runtime:2.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
