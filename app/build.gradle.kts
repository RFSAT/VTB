plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rfsat.vtb"
    compileSdk = 36

    defaultConfig {
        // Play listing identity. NOTE: this is the applicationId only — the
        // Kotlin/resource namespace stays com.rfsat.vtb (above), so no source
        // file moves. An applicationId is PERMANENT once published.
        applicationId = "com.VTBC"
        minSdk = 26
        targetSdk = 36
        versionCode = 150
        versionName = "1.20.35" // scheme: <brand>.<major>.<minor>; brand 1 = current VTB
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // v1.20.32: R8 ON, per Play's optimisation recommendation. It
            // strips unreachable library code (including the Material sheet
            // classes this app never uses, which were the source of Play's
            // deprecated setStatusBarColor/setNavigationBarColor notice) and
            // produces the mapping file that Play's other warning asked for —
            // AGP packages that into the bundle automatically.
            //
            // SAFETY: proguard-rules.pro keeps ALL com.rfsat.vtb classes and
            // members. Every persisted format in this app is Gson reflection
            // over field NAMES, so renaming a field silently changes a stored
            // JSON key — no crash, no build error, just vanished profiles.
            // Resource shrinking is left OFF for this first optimised build
            // to keep the number of new variables down.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // v1.20.31: answers Play's second warning — the bundle carries
            // native code (CameraX ships libimage_processing_util_jni.so) with
            // no debug symbols. This packages the symbol table into the AAB's
            // metadata, where Play picks it up automatically; it is metadata
            // only and is not shipped to devices, so the installed app is
            // unchanged. Requires an NDK on the build machine to extract the
            // symbols (GitHub's ubuntu runners include one).
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (System.getenv("ANDROID_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX — video capture of the vapor trail
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Profile persistence
    implementation("com.google.code.gson:gson:2.11.0")

}
