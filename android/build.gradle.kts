plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bluehive.voippush"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    compileOnly(project(":tauri-android"))
    // FCM token acquisition. The Google Services plugin (and its
    // google-services.json) must be applied in the consuming APP module.
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    // NotificationCompat.CallStyle + Person for the incoming-call ring UI.
    implementation("androidx.core:core-ktx:1.13.1")
}
