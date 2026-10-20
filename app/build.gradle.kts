import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.rennsteig.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.rennsteig.offline"
        minSdk = 23
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        ndk {
            // Handys: ARM. Für den Emulator-Test baut die CI zusätzlich mit -Pabis=x86_64.
            val abis = (project.findProperty("abis") as String?)?.split(",")
                ?: listOf("arm64-v8a", "armeabi-v7a")
            abiFilters += abis
        }
    }

    signingConfigs {
        create("release") {
            // Eigener Schlüssel über Umgebungsvariablen (z. B. GitHub-Secrets), sonst der
            // mitgelieferte Projektschlüssel, damit Updates sich über alte Versionen installieren lassen.
            val ownKeystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }
            if (ownKeystore != null && ownKeystore.exists()) {
                storeFile = ownKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/rennsteig-offline.jks")
                storePassword = "rennsteig"
                keyAlias = "rennsteig"
                keyPassword = "rennsteig"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
}
