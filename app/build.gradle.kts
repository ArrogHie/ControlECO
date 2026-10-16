plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.controleco.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.controleco.app"
        minSdk = 26
        targetSdk = 35
        // 每次带修改的构建把第三位 +1；beta 阶段版本名带 -beta 后缀
        versionCode = 31
        versionName = "0.6.0-beta"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // beta 分发用 debug 密钥签名：与侧载的 debug 包同一签名，可以直接覆盖安装、
            // 不清数据（换成正式密钥时要先卸载，用户账本会一起没，等正式发布再换）
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    testOptions {
        // 单元测试中 android.util.Log 等框架方法返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // 导出 Room schema，用于校验迁移 SQL（app/schemas/）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
