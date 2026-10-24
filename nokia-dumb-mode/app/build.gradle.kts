plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.philip.nokiamode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.philip.nokiamode"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // A fixed key committed with the project so every CI build can update the installed app.
    // It only protects a personal sideloaded app; don't reuse it for anything published.
    signingConfigs {
        create("sideload") {
            storeFile = file("nokia-mode.jks")
            storePassword = "nokiamode"
            keyAlias = "nokia"
            keyPassword = "nokiamode"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
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
        abortOnError = false
    }
}
