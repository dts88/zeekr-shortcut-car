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
        //
        // 后缀默认就是 -alpha。只有项目拥有者明确说「这一版发 beta」时才用
        // -beta，而且<b>不会延续到下一版</b>——上一版是 beta 不是下一版也是 beta
        // 的理由。检查更新只推 beta 和正式版，标错等于把一个没验证过的版本
        // 推给一台正在用的车机。
        versionCode = 85
        // 版本号从 0.1.0 重新起算；代码基座为 EVCam 1.6.6 (0876b97)
        versionName = "0.36.4-alpha"


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

    // 「发送到手机」：局域网内起一个 HTTP 服务 + 生成二维码。
    // 两个都是上游 EVCam 用过的，许可证与 GPL-3.0 兼容：
    // NanoHTTPD 是 BSD-3-Clause，ZXing 是 Apache-2.0。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.1")

    // 设置界面（PreferenceScreen）
    implementation("androidx.preference:preference:1.2.1")
    // 两栏设置的左右分栏（androidx 自己的 PreferenceHeaderFragmentCompat 也用它）
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
