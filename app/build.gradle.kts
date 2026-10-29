plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manu.iconrecents"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manu.iconrecents"
        minSdk = 30          // Android 11+ (safe for LineageOS 22.2 / Android 15)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            // ⚠️  No applicationIdSuffix — Xposed hooks reference the package name
            // at runtime. Debug + release must share "com.manu.iconrecents" so the
            // hook's startActivity() resolves correctly on the device.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // XposedBridge API — compileOnly (provided by LSPosed/VirtualXposed at runtime)
    // isTransitive=false avoids pulling in broken transitive deps from api.xposed.info
    compileOnly("de.robv.android.xposed:api:82") { isTransitive = false }
    // Fallback: place api-82.jar in app/libs/ — CI downloads it automatically
    compileOnly(fileTree("libs") { include("*.jar") })
}
