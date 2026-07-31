// Optional Android Studio / Gradle build.
//
// The canonical, dependency-free build is ./mkapk.sh — this file exists so the
// same tree also opens and builds as a normal Android Studio project. It maps
// Gradle onto the project's FLAT layout (src/, res/, assets/, AndroidManifest
// at the root) rather than moving anything, so both builds compile exactly the
// same files.

plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// keystore/keystore.properties is the lowest-priority source of signing
// credentials; -P properties and environment variables override it. Missing file
// is fine — an unsigned release build still assembles.
val keystoreProperties: Map<String, String> = run {
    val file = rootProject.file("keystore/keystore.properties")
    if (!file.isFile) return@run emptyMap()
    val loaded = java.util.Properties().apply { file.inputStream().use(::load) }
    // Map the file's own key names onto the VEPRO_* names used everywhere else.
    mapOf(
        "VEPRO_KEYSTORE_PATH" to loaded.getProperty("storeFile"),
        "VEPRO_KEYSTORE_TYPE" to loaded.getProperty("storeType"),
        "VEPRO_KEYSTORE_PASSWORD" to loaded.getProperty("storePassword"),
        "VEPRO_KEY_ALIAS" to loaded.getProperty("keyAlias"),
        "VEPRO_KEY_PASSWORD" to loaded.getProperty("keyPassword"),
    ).filterValues { it != null }.mapValues { it.value as String }
}

android {
    namespace = "com.vepro.code"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vepro.code"
        // Android 6.0. The floor was 24 here while apksigner was already told
        // --min-sdk-version 23, so the APK was signed as Android-6-capable while
        // its dex was compiled for 24 — the manifest, the dexer and the signer
        // disagreed, and that inconsistency is what let API-24 calls
        // (stopForeground(int), Math.floorMod) ship unnoticed. All four now say 23:
        // this file, mkapk.sh's aapt2/d8/apksigner flags and AndroidManifest.xml.
        minSdk = 23
        targetSdk = 35
        // Version 1. The displayed version is "1" everywhere it appears — this
        // file, the build script, the About row and the CI workflow — which was
        // not true before: CI still built and labelled artifacts "v5" while the
        // app itself reported "1".
        //
        // versionCode is the separate, invisible number Android uses to order
        // installs. It must never go down, so it keeps rising independently of the
        // displayed name.
        //
        // The signing key lives OUTSIDE this repository and is supplied through
        // VEPRO_KEYSTORE_PATH. Android refuses an update signed by a different key
        // than the installed app, so keeping the same key is what lets a release
        // install over the last one without a reinstall. See
        // keystore/README-KEYSTORE.md.
        versionCode = 14
        versionName = "1"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            kotlin.setSrcDirs(listOf("src"))
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
            // this project has no JNI, aidl or renderscript
            jniLibs.setSrcDirs(emptyList<String>())
            aidl.setSrcDirs(emptyList<String>())
        }
    }

    // Release signing, wired to the same keystore ./mkapk.sh uses. Credentials
    // come from -P properties, the environment, or keystore/keystore.properties,
    // in that order — so nothing secret has to live in this file.
    //
    //   ./gradlew assembleRelease \
    //     -PVEPRO_KEYSTORE_PATH="$HOME/private/vega-release-v1.jks" \
    //     -PVEPRO_KEYSTORE_PASSWORD=… -PVEPRO_KEY_ALIAS=vepro
    signingConfigs {
        create("release") {
            fun setting(name: String, fallback: String? = null): String? =
                (project.findProperty(name) as String?)
                    ?: System.getenv(name)
                    ?: keystoreProperties[name]
                    ?: fallback

            // No default path any more. It used to fall back to a key inside the
            // tree, and there is deliberately no key inside the tree — a default
            // pointing at a file that must never exist only invites someone to
            // create it. Missing credentials produce an unsigned release build and
            // the message below, which is the honest outcome.
            val path = setting("VEPRO_KEYSTORE_PATH")
            val store = path?.let(::file)
            if (store != null && store.isFile) {
                storeFile = store
                storeType = setting("VEPRO_KEYSTORE_TYPE", "PKCS12")
                storePassword = setting("VEPRO_KEYSTORE_PASSWORD")
                keyAlias = setting("VEPRO_KEY_ALIAS", "vepro")
                keyPassword = setting("VEPRO_KEY_PASSWORD")
                    ?: setting("VEPRO_KEYSTORE_PASSWORD")
            } else {
                logger.lifecycle(
                    "no keystore at $path — release builds will be unsigned; see keystore/README-KEYSTORE.md"
                )
            }
        }
    }

    buildTypes {
        release {
            // The hand-rolled build ships un-minified; keep Gradle consistent so
            // both paths produce equivalent bytecode.
            isMinifyEnabled = false
            isShrinkResources = false
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null && release.storePassword != null) {
                signingConfig = release
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

    // Fonts must stay uncompressed, same as `aapt2 link -0 ttf`.
    androidResources {
        noCompress += listOf("ttf")
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
        }
    }

    lint {
        // The UI is built in code on purpose; the deprecation warnings on
        // systemUiVisibility / onBackPressed / stopForeground(boolean) are
        // deliberate — they are the pre-24 halves of the API-23 support branches.
        abortOnError = false
    }
}

// Zero third-party dependencies: only the Android framework, org.json (part of
// the platform) and the Kotlin standard library, which the Kotlin plugin adds.
dependencies {
}
