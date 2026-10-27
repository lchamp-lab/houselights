import java.util.Properties
import java.util.zip.ZipFile

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

// Trakt app keys: trakt.clientId (without it, Settings says the build has no Trakt), trakt.clientSecret only
// for an app that has one (built into the APK, it can be read there), and trakt.redirectUri for an app declared
// with another redirect URI than the project's, from local.properties or TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET
// and TRAKT_REDIRECT_URI (CI). Never commit them.
val traktClientId: String = localProps.getProperty("trakt.clientId") ?: System.getenv("TRAKT_CLIENT_ID") ?: ""
val traktClientSecret: String = localProps.getProperty("trakt.clientSecret") ?: System.getenv("TRAKT_CLIENT_SECRET") ?: ""
val traktRedirectUri: String = localProps.getProperty("trakt.redirectUri") ?: System.getenv("TRAKT_REDIRECT_URI") ?: ""

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

// The release's full version name, a beta's included (0.4.0-beta.1), from the release workflow: -PversionName, which
// must be versionName below or start with it and "-". Without it, the release is named versionName.
val fullVersionName: String? = providers.gradleProperty("versionName").orNull

// The in-app updater (README, Install): built into the release only, its permission with it (src/updater);
// -Phouselights.updater=false builds a release without it (F-Droid). Debug and bench never have it.
val updaterBuilt: Boolean = providers.gradleProperty("houselights.updater").orNull?.toBooleanStrict() ?: true

// The project's signing certificate (gradle.properties, its only copy): the updater installs an APK signed with it
// alone, and runs only in an app signed with it.
val releaseCertSha256: String = providers.gradleProperty("houselights.releaseCertSha256").get().also {
    require(Regex("[0-9a-f]{64}").matches(it)) { "houselights.releaseCertSha256 must be 64 lowercase hex digits" }
}

// Where the release's updater looks for releases: the project's repository, or for a dry run before a release a test
// one, -Phouselights.updateRepo=owner/repo (CONTRIBUTING, Releases).
val updateRepo: String = (providers.gradleProperty("houselights.updateRepo").orNull ?: "lchamp-lab/houselights").also {
    require(Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(it)) { "-Phouselights.updateRepo must read owner/repo, not \"$it\"" }
}

android {
    namespace = "app.houselights"
    // Android 16's SDK, which core and activity need; the app keeps Android 15's behaviour (targetSdk).
    compileSdk = 36

    defaultConfig {
        applicationId = "app.houselights"
        minSdk = 28 // Zidoo Z1000 Pro : Android 9
        // Android 16's behaviour, once the app targets it, is a change of its own, tried on players first.
        //noinspection OldTargetApi
        targetSdk = 35
        // The release's own is set with its variant (androidComponents, below).
        versionCode = 1
        versionName = "0.3.0"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${traktClientId.trim()}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${traktClientSecret.trim()}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${traktRedirectUri.trim()}\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
        // The updater, in the release alone (below); the repository it reads.
        buildConfigField("boolean", "UPDATER", "false")
        buildConfigField("String", "UPDATE_REPO", "\"lchamp-lab/houselights\"")
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    androidResources {
        // The languages of the interface (InterfaceLanguage.LANGUAGES): the libraries' strings in 80 others would only be
        // shipped. The libraries write Chinese by region.
        localeFilters +=
            listOf("de", "en", "es", "fr", "it", "ja", "ko", "nl", "pl", "pt", "b+zh+Hans", "b+zh+Hant", "zh-rCN", "zh-rTW", "zh-rHK")
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
            // A release signed with another key than the project's never runs it (UpdaterAvailability).
            buildConfigField("boolean", "UPDATER", "$updaterBuilt")
            buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        }
        // Optimised like a release, beside it and signed with the debug key: what the player really
        // runs, to measure frames and start-up without installing the release package.
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            manifestPlaceholders["appLabel"] = "Houselights bench"
            // initWith copied the release's: bench never updates itself.
            buildConfigField("boolean", "UPDATER", "false")
            buildConfigField("String", "UPDATE_REPO", "\"lchamp-lab/houselights\"")
        }
    }
    // The permission to install packages and the updater's receivers, in the release alone and only when it has the
    // updater: debug, bench and the builds :baselineprofile makes never ask for it.
    if (updaterBuilt) sourceSets.getByName("release").manifest.srcFile("src/updater/AndroidManifest.xml")
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
        // A warning fails the build as an error does, and no baseline keeps old ones (CONTRIBUTING, Checks).
        warningsAsErrors = true
        // Whether a dependency has a newer version is Dependabot's to say, in a pull request of its own: these checks
        // read what was published the day they run, so a build that changed nothing would fail the day one comes out.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        // Google Play's SDK Index checks read the index of the day too: a version it marks outdated, risky or out of
        // policy without blocking it is reported at a warning, so an error here. Houselights is not on Google Play
        // (README, Install), and a library's security fixes come from Dependabot's security updates.
        disable += setOf(
            "OutdatedLibrary", "RiskyLibrary", "PlaySdkIndexDeprecated", "PlaySdkIndexNonCompliant",
            "PlaySdkIndexVulnerability", "PlaySdkIndexGenericIssues",
        )
        // The app's language is the default locale (InterfaceLanguage.applyDefault), and a change of it, the app's or
        // the device's, makes the activity again: a composition that reads it is never left behind.
        disable += "NonObservableLocale"
        // Compose's rule for a library's modifiers: the app's helpers that hand a Modifier made of something else, a
        // screen's focus keys (FocusMemory), a raise or a frame remembered, are no modifier factories to chain.
        disable += "ModifierFactoryExtensionFunction"
    }
    // A bundle, should one be built, keeps every language in its APK: the interface's is chosen in the app.
    bundle {
        language {
            enableSplit = false
        }
    }
}

// A compiler warning fails every compilation of the app, its release's and its unit tests' included, so none is left
// behind (CONTRIBUTING, Checks). The unit tests opt in once to the coroutines' test APIs still experimental
// (advanceUntilIdle, currentTime, setMain…); the app's own code says so where it uses one.
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
    target.compilations.matching { it.name.endsWith("UnitTest") }.configureEach {
        compileTaskProvider.configure { compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi") }
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
        // A beta is named as its tag (0.4.0-beta.1): the updater tells it from its final by that name.
        if (fullVersionName != null) {
            val base = requireNotNull(android.defaultConfig.versionName)
            require(fullVersionName == base || fullVersionName.startsWith("$base-")) {
                "-PversionName=$fullVersionName must be versionName ($base) or start with \"$base-\""
            }
            variant.outputs.forEach { it.versionName.set(fullVersionName) }
        }
    }
}

// Without a key, the release stops before its APK is written, and says why (see keystoreProps).
if (keystoreProps.isEmpty && !debugSignedRelease) {
    tasks.matching { it.name == "packageRelease" }.configureEach {
        doFirst {
            throw GradleException(
                "No keystore.properties: this release would be signed with this machine's debug key, and a later " +
                    "one signed with yours could only be installed after uninstalling it, which loses the library " +
                    "and watch history kept on the player. Create your key first (CONTRIBUTING, Signing), or pass " +
                    "-Phouselights.debugSignedRelease=true for a build that will not be installed; " +
                    "scripts/install.sh bench installs an optimised build beside the release."
            )
        }
    }
}

// The servers' tests (app.houselights.servers, tools/test-servers) run with HOUSELIGHTS_TEST_SERVERS=1 alone, against
// servers outside the build. That variable, HOUSELIGHTS_TEST_SERVERS_HOST and HOUSELIGHTS_TEST_SERVERS_FRAGMENTS_HOST
// are inputs of the tests, so setting one runs them again, rather than taking the run without it, all skipped, as up
// to date; and with the servers up, the tests run each time, never up to date nor from a cache: what the servers serve
// is no input of theirs.
tasks.withType<Test>().configureEach {
    val servers = providers.environmentVariable("HOUSELIGHTS_TEST_SERVERS").orElse("")
    inputs.property("testServers", servers)
    inputs.property("testServersHost", providers.environmentVariable("HOUSELIGHTS_TEST_SERVERS_HOST").orElse(""))
    inputs.property(
        "testServersFragmentsHost",
        providers.environmentVariable("HOUSELIGHTS_TEST_SERVERS_FRAGMENTS_HOST").orElse(""),
    )
    outputs.upToDateWhen { servers.get() != "1" }
    outputs.doNotCacheIf("The servers' tests read servers outside the build") { servers.get() == "1" }
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
    // The build scripts too, which the pre-commit hook's ktlint checks: this one and the root project's.
    source.from(files("build.gradle.kts"), rootProject.files("build.gradle.kts", "settings.gradle.kts"))
}

// nfs-client 1.1.0 cannot read an NFS reply sent in several record fragments, as NFS servers on libtirpc (unfs3 among
// them) send every reply above 64 KB (issue #88). Its RPCRecordDecoder is replaced by the app's patched copy, in the
// same package (src/main/java/com/emc/ecs/nfsclient/network), which every connection's pipeline then builds: the app
// takes nfs-client's jar as written here, without its own, so that the app's is the only class of that name, the one
// dexed (R8 and D8 refuse a class defined twice) and the one tested; nfs-client's dependencies are the app's (below).
// A jar without RPCRecordDecoder fails the build, for whoever updates nfs-client to check whether the patch is needed.
val nfsClient: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val nfsClientWithoutDecoder = tasks.register<Jar>("nfsClientWithoutDecoder") {
    val decoder = "com/emc/ecs/nfsclient/network/RPCRecordDecoder.class"
    val upstream = nfsClient
    // Its classes and files but that decoder, and its manifest, which the task writes anew.
    from(provider { zipTree(upstream.singleFile) }) { exclude(decoder, "META-INF/MANIFEST.MF") }
    archiveFileName.set("nfs-client-${libs.versions.nfs.client.get()}-without-RPCRecordDecoder.jar")
    destinationDirectory.set(layout.buildDirectory.dir("nfs-client"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        val jar = upstream.singleFile
        check(ZipFile(jar).use { it.getEntry(decoder) != null }) {
            "${jar.name} has no $decoder: does the app still need its own (issue #88)?"
        }
    }
}

// detekt 1.23 is built against Kotlin 2.0; keep its own compiler instead of the project's, for its plugins' rules too.
configurations.matching { it.name == "detekt" || it.name == "detektPlugins" }.configureEach {
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
    // nfs-client's jar without its RPCRecordDecoder (above), then what nfs-client runs on, which that jar alone does not
    // bring: Netty 3 at nfs-client's version, which the app's RPCRecordDecoder is built on too, then Commons Lang and
    // SLF4J at run time alone, as nfs-client's own metadata gives them, so that no code of the app comes to use them.
    nfsClient(libs.nfs.client)
    implementation(files(nfsClientWithoutDecoder))
    implementation(libs.netty)
    runtimeOnly(libs.commons.lang3) {
        because("CVE-2025-48924: above nfs-client's 3.12.0")
    }
    runtimeOnly(libs.slf4j.api)
    constraints {
        implementation(libs.bouncycastle.prov) {
            because("CVE-2024-29857, CVE-2024-30171, CVE-2024-30172, CVE-2024-34447, CVE-2025-8916; pulled in by smbj")
        }
    }

    // Compose's own rules for detekt: naming, modifiers, state, CompositionLocals (config/detekt.yml, Compose).
    detektPlugins(libs.compose.rules.detekt)
    // ktlint's rules, the code's style (config/detekt.yml, formatting; .editorconfig for the pre-commit hook's ktlint).
    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.junit)
    // XmlPullParser implementation for JVM tests (Android provides its own).
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // A real server where a test needs what OkHttp does below the interceptors: redirects.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    // Compose tests on Robolectric: focus, keys and sheets as the remote drives them. The test
    // manifest declares the activity they run in, in debug builds only.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
