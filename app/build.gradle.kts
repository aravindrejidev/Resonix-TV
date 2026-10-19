import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself (built-in Kotlin): no kotlin-android plugin needed.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.resonix.tv"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.resonix.tv"
        minSdk = 26 // Android 8.0 - AAudio is available for Oboe
        targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1 // newer CI build = higher code
        versionName = "0.2.0"

        ndk {
            // ABIs come from gradle.properties (resonix.abis) so CI can build a single ABI quickly.
            abiFilters += providers.gradleProperty("resonix.abis")
                .orElse("arm64-v8a,armeabi-v7a")
                .get()
                .split(",")
                .map { it.trim() }
        }

        externalNativeBuild {
            cmake {
                // Oboe's Prefab package is built against the shared C++ STL.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true // consume Oboe through Prefab
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Oboe, our own lib and (module 3) the FFmpeg decoder may each carry libc++_shared.so
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        // One fixed debug key (public, password "android") for every build, so a new CI build
        // installs over the previous one without uninstalling it first.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8 on, but signed with the debug key so the APK can be sideloaded directly.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // Sideload project: lint is advisory, it must never break the CI build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Jetpack Compose (versions come from the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)

    // Compose for TV
    implementation(libs.androidx.tv.material)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Media3 / ExoPlayer (the FFmpeg audio decoders are added in module 3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.common.ktx)

    // Native audio engine (C++): Oboe through Prefab
    implementation(libs.oboe)

    // Compile-time only: the Media3 FFmpeg extension sources (fetched in CI) use these annotations.
    compileOnly(libs.checker.qual)
}
