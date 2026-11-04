plugins {
    id("com.android.application")
}

// The web app lives in the repo root; copy it into the APK as the offline fallback.
val webRoot = rootProject.projectDir.parentFile
val copyWebAssets by tasks.registering(Copy::class) {
    from(webRoot) {
        include("index.html", "app.js", "converter.js", "style.css", "manifest.webmanifest", "icons/**")
    }
    into(layout.buildDirectory.dir("webassets/web"))
}

android {
    namespace = "com.nickcross.manaboxtomoxfield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nickcross.manaboxtomoxfield"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "WEB_URL", "\"https://nickcross-79.github.io/manabox-to-moxfield/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("webassets"))

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK can be sideloaded without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") { dependsOn(copyWebAssets) }
