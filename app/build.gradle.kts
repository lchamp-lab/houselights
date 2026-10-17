import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.detekt)
    alias(libs.plugins.baselineprofile)
}

// TMDB key: from local.properties (tmdb.apiKey) or the TMDB_API_KEY environment variable (CI).
// Never commit it: users can also enter their own key in the app settings.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val tmdbApiKey: String = localProps.getProperty("tmdb.apiKey") ?: System.getenv("TMDB_API_KEY") ?: ""

// Release signing: keystore.properties (never committed) gives storeFile, storePassword, keyAlias and
// keyPassword. Android only updates an app signed with the same key, so a release signed with the debug
// key of whatever machine built it could not be updated by one signed with yours: without the file,
// packaging the release fails, unless -Phouselights.debugSignedRelease=true asks for the debug key (CI,
// a build that will not be installed). bench and the builds :baselineprofile makes are always signed
// with the debug key, whatever the file says (see androidComponents, below).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun keystoreProp(name: String): String =
    requireNotNull(keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }) { "keystore.properties: $name is missing" }
val debugSignedRelease = providers.gradleProperty("houselights.debugSignedRelease").orNull.toBoolean()

// The release's versionCode grows with every commit, so Android refuses to install a release over a
// newer one, whose database it could not open: -PversionCode when given, else the number of commits
// (CI checks out the whole history), else 1. Read through providers.exec, which the configuration
// cache tracks. The other builds stay at 1 (androidComponents, below).
val commitCount: Int? = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
}.getOrNull()
val appVersionCode: Int = providers.gradleProperty("versionCode").orNull
    ?.let { requireNotNull(it.toIntOrNull()) { "-PversionCode must be a whole number, not \"$it\"" } }
    ?: commitCount
    ?: 1

android {
    namespace = "app.houselights"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.houselights"
        minSdk = 28 // Zidoo Z1000 Pro : Android 9
        targetSdk = 35
        // The release's own is set with its variant (androidComponents, below).
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // The languages of the interface: the libraries' strings in 80 others would only be shipped.
        resourceConfigurations += listOf("en", "fr")
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProp("storeFile"))
                storePassword = keystoreProp("storePassword")
                keyAlias = keystoreProp("keyAlias")
                keyPassword = keystoreProp("keyPassword")
                // v3 as well as v2: a v3 signature can name the key that follows this one (key rotation,
                // Android 9 and up), should the project ever have to change keys without uninstalling.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // Debug builds install beside the release one, so trying a change never costs a library.
        debug {
            applicationIdSuffix = ".debug"
            // Only the launcher says so: the interface keeps its wordmark.
            manifestPlaceholders["appLabel"] = "Houselights debug"
        }
        release {
            isMinifyEnabled = true
            // Drawables and strings no code or resource names (nothing looks one up by its name).
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a key, packageRelease refuses the debug key unless asked (below).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Optimised like a release, beside it and signed with the debug key: what the player really
        // runs, to measure frames and start-up without installing the release package.
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            manifestPlaceholders["appLabel"] = "Houselights bench"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // Files of the libraries that nothing the app runs reads: BouncyCastle's post-quantum
            // tables (Picnic, 1.2 MB) and the messages of its certificate path reviewer, the agent of
            // the coroutines debugger (a JVM tool). META-INF/license/ stays: the licences shipped.
            excludes += setOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties", "DebugProbesKt.bin")
        }
    }
    testOptions {
        // Robolectric tests need the merged resources. Migration tests read app/schemas as files
        // (SchemaHistory), every version since the first.
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }
}

// The builds :baselineprofile makes from the release (nonMinifiedRelease generates the profile,
// benchmarkRelease measures) keep its package name, and the plugin hands them the release's key. On
// a player that holds the real release, they would replace it, then be uninstalled when the run ends,
// the library and watch history with them. Signed with the debug key, their installation fails there
// instead. Registered after the plugin (applied above), so this runs once it has set its own.
androidComponents {
    finalizeDsl { android ->
        listOf("nonMinifiedRelease", "benchmarkRelease").forEach { name ->
            android.buildTypes.findByName(name)?.signingConfig = android.signingConfigs.getByName("debug")
        }
    }
    // Only the release grows with each commit. Each change is tried on its own branch, and branches
    // count different numbers of commits: debug and bench stay at 1, so that one installs over another
    // (Android refuses a lower versionCode, and only uninstalling would get past it).
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { it.versionCode.set(appVersionCode) }
    }
}

// Without a key, the release stops before its APK is written, and says why (see keystoreProps).
if (keystoreProps.isEmpty && !debugSignedRelease) {
    tasks.matching { it.name == "packageRelease" }.configureEach {
        doFirst {
            throw GradleException(
                "No keystore.properties: this release would be signed with this machine's debug key, and a later " +
                    "one signed with yours could only be installed after uninstalling it, which loses the library " +
                    "and watch history kept on the player. Create your key first (README, Signing), or pass " +
                    "-Phouselights.debugSignedRelease=true for a build that will not be installed; " +
                    "scripts/install.sh bench installs an optimised build beside the release."
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The code of the main journeys (start-up, browsing home and a library, a detail page, settings),
// compiled ahead of time from the first launch instead of interpreted until the player compiles it
// overnight. Generated on a device by :baselineprofile (README, Performance) and checked in, so a
// build never needs a device: src/main/generated/baselineProfiles.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    // Startup classes laid out first in the dex file of minified builds.
    dexLayoutOptimization = true
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

// detekt 1.23 is built against Kotlin 2.0; keep its own compiler instead of the project's.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)

    implementation(libs.androidx.core.ktx)
    // The launch screen (the mark alone on black while home gets ready), Android 9 included.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Whether the app is on screen: scans wait while it is away.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    // Installs the baseline profile on players that do not get apps from Google Play.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.smbj)
    implementation(libs.nfs.client)
    constraints {
        implementation(libs.bouncycastle.prov) {
            because("CVE-2024-29857, CVE-2024-30171, CVE-2024-30172, CVE-2024-34447, CVE-2025-8916; pulled in by smbj")
        }
        implementation(libs.commons.lang3) {
            because("CVE-2025-48924; pulled in by nfs-client")
        }
    }

    testImplementation(libs.junit)
    // XmlPullParser implementation for JVM tests (Android provides its own).
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Compose tests on Robolectric: focus, keys and sheets as the remote drives them. The test
    // manifest declares the activity they run in, in debug builds only.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
