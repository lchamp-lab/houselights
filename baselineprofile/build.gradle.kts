import java.util.Properties

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.detekt)
}

// The library the journeys browse, from local.properties (never committed): a fresh install of the
// app has none, so the generator adds the folders through the app itself, as a viewer would.
//   baselineprofile.host=192.168.1.10
//   baselineprofile.export=/volume1/media
//   baselineprofile.movies=films
//   baselineprofile.shows=series        (optional)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "app.houselights.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        listOf("host", "export", "movies", "shows").forEach { key ->
            localProps.getProperty("baselineprofile.$key")?.let { testInstrumentationRunnerArguments["library.$key"] = it }
        }
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Generated on the device connected (the emulator, rooted: `adb root`), not a managed one: the
// journeys need the NAS of the local network.
baselineProfile {
    useConnectedDevices = true
}

// The journeys run on the variant installed (release, benchmark): its application id, given to them.
androidComponents {
    onVariants { variant ->
        val loader = variant.artifacts.getBuiltArtifactsLoader()
        val appId = variant.testedApks.map { requireNotNull(loader.load(it)) { "No APK metadata in $it" }.applicationId }
        variant.instrumentationRunnerArguments.put("targetAppId", appId)
    }
}

// A compiler warning fails the journeys' compilation, as it does the app's (CONTRIBUTING, Checks).
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"), file("detekt.yml"))
}

// detekt 1.23 is built against Kotlin 2.0; keep its own compiler instead of the project's.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
