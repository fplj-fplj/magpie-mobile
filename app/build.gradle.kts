plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 仅测试构建可用 -PmagpieTestAbi=x86_64 限定 ABI；生产 APK 不设置该属性，保持默认打包。
val magpieTestAbi = providers.gradleProperty("magpieTestAbi").orNull

android {
    namespace = "cc.fplj.magpiemobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.fplj.magpiemobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 65
        versionName = "0.1.64-reasoning-aliases"
        buildConfigField("String", "CORE_VERSION", "\"0.1.3781-androidhost.3\"")
        buildConfigField("String", "GIT_SHA", "\"" + (System.getenv("GITHUB_SHA")?.take(8) ?: "local") + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add(magpieTestAbi ?: "arm64-v8a") }
    }

    // P0-2 关键：ELF PIE 可执行载荷以“提取式”native 库打包，
    // 安装后从 applicationInfo.nativeLibraryDir 以绝对路径 exec。
    // keepDebugSymbols 避免 AGP strip 改写载荷。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so")
        }
    }

    signingConfigs {
        create("fixedRelease") {
            storeFile = System.getenv("MAGPIE_SIGNING_STORE")?.let { file(it) }
            storePassword = System.getenv("MAGPIE_SIGNING_PASSWORD")
            keyAlias = "magpie"
            keyPassword = System.getenv("MAGPIE_SIGNING_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixedRelease")
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { abortOnError = false }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
