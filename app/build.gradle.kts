import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Release signing is driven by keystore.properties, which is gitignored and absent
// on fresh clones. Its absence must not break assembleDebug.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val appVersionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
val appVersionParts = appVersionName.substringBefore('-').split('.')
    .map { it.toIntOrNull() ?: 0 }
val appVersionCode =
    appVersionParts.getOrElse(0) { 0 } * 1_000_000 +
        appVersionParts.getOrElse(1) { 0 } * 1_000 +
        appVersionParts.getOrElse(2) { 0 }

android {
    namespace = "com.drugme.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drugme.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.coerceAtLeast(1)
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the real android.jar resources rather than the stubbed one,
            // otherwise Room's SQLite and any resource lookup fail at runtime.
            isIncludeAndroidResources = true
        }
    }

    // MigrationTestHelper loads the exported schema JSON from *assets* to rebuild the old
    // database. Room's ksp writes those to $projectDir/schemas.
    //
    // They must go on the DEBUG source set, not "test". Robolectric reads whatever the
    // generated test_config.properties points at, and that is
    // `android_merged_assets=…/assets/debug/mergeDebugAssets` — the debug variant's merged
    // assets. Assets added to the "test" source set never reach it, and every migration
    // test fails with "Cannot find the schema file", which reads like a missing export
    // rather than a wiring mistake.
    //
    // On "main", so BOTH variants get them. Putting them on "debug" alone passes
    // testDebugUnitTest and then fails testReleaseUnitTest — which is exactly what the
    // release workflow runs, so the failure only appears when cutting a release. The ~30KB
    // of schema JSON in the release APK is worth not having that asymmetry.
    sourceSets {
        getByName("main") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // App Check. Play Integrity ships in every build; the debug provider is debug-only so it
    // never reaches release. Which one is installed is chosen per variant in AppCheckInstaller.
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // Google sign-in goes through Credential Manager; the legacy GoogleSignIn SDK is
    // deprecated. googleid supplies the GetGoogleIdOption request type.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    // Crypto: Tink for AEAD, BouncyCastle for Argon2id (pure JVM, no NDK).
    implementation(libs.tink.android)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// synonyms.json is curated in tools/ (alongside its validator) and consumed as an asset.
// Copying at build time keeps one source of truth: editing the asset by hand would drift
// from the file validate-synonyms.mjs actually checks.
val copySynonyms by tasks.registering(Copy::class) {
    from(rootProject.file("tools/synonyms.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copySynonyms) }
