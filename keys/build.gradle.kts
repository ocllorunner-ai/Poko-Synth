plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.poko.keys"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.poko.keys"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // A fixed key, so a newer build installs over the older one.
    signingConfigs {
        create("poko") {
            storeFile = rootProject.file("poko.keystore")
            storePassword = "pokopoko"
            keyAlias = "poko"
            keyPassword = "pokopoko"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("poko")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("poko")
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
}

dependencies {
    implementation(project(":audio"))
}
