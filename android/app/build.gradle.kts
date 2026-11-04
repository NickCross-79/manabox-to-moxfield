plugins {
    id("com.android.application")
}

// The web app lives in the repo root; a copy is bundled into the APK and used until
// (or unless) a newer one is downloaded from GitHub Releases.
val webRoot = rootProject.projectDir.parentFile
val copyWebAssets by tasks.registering(Copy::class) {
    from(webRoot) {
        include("index.html", "app.js", "converter.js", "style.css", "icons/**")
    }
    into(layout.buildDirectory.dir("webassets/web"))
}

// Build number = commit count (passed by CI and tools/build-apk.sh). The bundled web
// app has this build number, so the updater only downloads strictly newer builds.
val webBuild = (findProperty("webBuild") as String?)?.toInt() ?: 0
// Version of the window.Android bridge. Bump shell-api.txt when the page needs
// something new from the native side; older APKs then skip those updates.
val shellApi = File(webRoot, "shell-api.txt").readText().trim().toInt()
val repo = "NickCross-79/manabox-to-moxfield"

android {
    namespace = "com.nickcross.manaboxtomoxfield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nickcross.manaboxtomoxfield"
        minSdk = 29
        targetSdk = 35
        versionCode = maxOf(webBuild, 4)
        versionName = "2.0 (b$webBuild)"
        buildConfigField("int", "WEB_BUILD", "$webBuild")
        buildConfigField("int", "SHELL_API", "$shellApi")
        buildConfigField("String", "MANIFEST_URL",
            "\"https://github.com/$repo/releases/latest/download/manifest.json\"")
        buildConfigField("String", "UPDATE_URL_PREFIX",
            "\"https://github.com/$repo/releases/download/web-b\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("webassets"))

    // Every APK must be signed with the same key to install over the previous one.
    // CI passes the key from the APK_KEYSTORE_* secrets; otherwise the local debug key is used.
    val ksPath = findProperty("keystorePath") as String?
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = findProperty("keystorePassword") as String? ?: "android"
                keyAlias = findProperty("keyAlias") as String? ?: "androiddebugkey"
                keyPassword = findProperty("keyPassword") as String? ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(copyWebAssets) }
