import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 电视端 OAuth。设备码登录必须把这对值编进安装包。
// GitHub 推送保护不允许把 client secret 写进仓库，所以：
//   - Action 从仓库 Secrets 注入 DRIVE_CLIENT_ID / DRIVE_CLIENT_SECRET
//   - 本机从 gitignore 的 local.properties 读同名键
// 两边都空时，设置页里手填的一对仍然生效。
fun driveCredential(name: String): String {
    val fromEnv = System.getenv(name)?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.isFile) return ""
    val props = Properties()
    propsFile.inputStream().use { props.load(it) }
    return props.getProperty(name)?.trim().orEmpty()
}

fun gradleStringLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
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
        // 这一版是 fork：版本名用 2.11.1-beta，检查更新才认。加上 dim、远程、侧视、快捷键和保存瞬间。
        // 上游 versionCode 是 283（2.11.1-alpha）。本 fork 已经发出 2.11.0-alpha（310），装不上更小的号，所以号继续往上。
        versionCode = 311
        // 检查更新不推 alpha，所以这一版用 beta。
        versionName = "2.11.1-beta"

        buildConfigField("String", "DRIVE_CLIENT_ID", gradleStringLiteral(driveCredential("DRIVE_CLIENT_ID")))
        buildConfigField("String", "DRIVE_CLIENT_SECRET", gradleStringLiteral(driveCredential("DRIVE_CLIENT_SECRET")))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
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

}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.cardview)

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
    // 远程观看：车机拨出到 7xDash 的 /ws/car，有人看的时候才推 JPEG。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 设置界面（PreferenceScreen）
    implementation("androidx.preference:preference:1.2.1")
    // 两栏设置的左右分栏（androidx 自己的 PreferenceHeaderFragmentCompat 也用它）
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")
    // 车机没有无障碍设置页：借 Shizuku（shell 身份）给自己授 WRITE_SECURE_SETTINGS
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
