plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "br.xok.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.xok.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("xok") {
            storeFile = rootProject.file("xok.jks")
            storePassword = "xokbrowser"
            keyAlias = "xok"
            keyPassword = "xokbrowser"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("xok")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:${providers.gradleProperty("geckoVersion").getOrElse("159.0.20261102144711")}")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
