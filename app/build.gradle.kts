plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.musefloatx"
    compileSdk = 34

    defaultConfig {
        // 本地版单独装，不覆盖第 38 版：applicationId 不同，代码包名不变，
        // 这样 FloatingService 等文件不用重命名，改动最小。
        applicationId = "com.example.musefloatx.local"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-local"
    }
    // llama-server 以原生库形式打包：GitHub Actions 里 CMake + NDK 单独编出来，
    // 再放进 app/src/main/jniLibs/arm64-v8a/libllama_server.so，不用 externalNativeBuild。
    // 下面 packaging.jniLibs.useLegacyPackaging = true 是必须的，不要删：
    // targetSdk 34 下默认可能是不解压（extractNativeLibs=false），那安装后
    // nativeLibraryDir 里就不会有可执行文件，之前针对 W^X 做的原生库修复就白做了。

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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL,LICENSE,LGPL,NOTICE,INDEX.LIST,DEPENDENCIES}"
        }
        jniLibs {
            // 明确要求传统打包：原生库真正解压到 nativeLibraryDir，App 才能执行它。
            // 只靠默认行为不可靠，targetSdk 34 下默认可能是不解压。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
