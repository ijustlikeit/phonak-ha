plugins {
    id("com.android.application")
}

android {
    namespace = "app.phonakbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.phonakbridge"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal app: the debug key is fine.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
