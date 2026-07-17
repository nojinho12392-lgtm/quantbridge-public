import io.gitlab.arturbosch.detekt.Detekt
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val defaultStagingApiBaseUrl = "https://qbjinho05082315-api.bravepond-eb5e2096.koreacentral.azurecontainerapps.io"
val defaultDebugApiBaseUrl = "http://10.0.2.2:8000"
val localApiBaseUrl = providers.gradleProperty("quantApiDebugBaseUrl")
    .orElse(providers.environmentVariable("QUANT_API_BASE_URL"))
    .orElse(localProperties.getProperty("quantApiDebugBaseUrl") ?: defaultDebugApiBaseUrl)
val stagingApiBaseUrl = providers.gradleProperty("quantApiReleaseBaseUrl")
    .orElse(providers.environmentVariable("QUANT_API_RELEASE_BASE_URL"))
    .orElse(localProperties.getProperty("quantApiReleaseBaseUrl") ?: defaultStagingApiBaseUrl)

fun deleteDuplicatedAndroidBuildArtifacts() {
    val duplicateSuffixes = listOf("?", "??", "???")
    val duplicateExtensions = listOf(
        "class",
        "dex",
        "jar",
        "kotlin_module",
        "xml",
        "png",
        "webp",
        "jpg",
        "jpeg"
    )
    delete(
        fileTree(layout.buildDirectory.get().asFile) {
            duplicateSuffixes.forEach { suffix ->
                duplicateExtensions.forEach { extension ->
                    include("**/* $suffix.$extension")
                }
                include("**/* ($suffix).*")
            }
        }
    )
}

android {
    namespace = "com.qubit.quantbridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.qubit.quantbridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Optional release signing via local.properties / env (never commit keystore secrets).
    // local.properties keys: QUANT_RELEASE_STORE_FILE, QUANT_RELEASE_STORE_PASSWORD,
    // QUANT_RELEASE_KEY_ALIAS, QUANT_RELEASE_KEY_PASSWORD
    // env fallbacks: QUANT_RELEASE_STORE_FILE, QUANT_RELEASE_STORE_PASSWORD,
    // QUANT_RELEASE_KEY_ALIAS, QUANT_RELEASE_KEY_PASSWORD
    val releaseStoreFilePath = providers.gradleProperty("QUANT_RELEASE_STORE_FILE")
        .orElse(providers.environmentVariable("QUANT_RELEASE_STORE_FILE"))
        .orElse(localProperties.getProperty("QUANT_RELEASE_STORE_FILE") ?: "")
        .orNull
        ?.trim()
        .orEmpty()
    val releaseStorePassword = providers.gradleProperty("QUANT_RELEASE_STORE_PASSWORD")
        .orElse(providers.environmentVariable("QUANT_RELEASE_STORE_PASSWORD"))
        .orElse(localProperties.getProperty("QUANT_RELEASE_STORE_PASSWORD") ?: "")
        .orNull
        ?.trim()
        .orEmpty()
    val releaseKeyAlias = providers.gradleProperty("QUANT_RELEASE_KEY_ALIAS")
        .orElse(providers.environmentVariable("QUANT_RELEASE_KEY_ALIAS"))
        .orElse(localProperties.getProperty("QUANT_RELEASE_KEY_ALIAS") ?: "")
        .orNull
        ?.trim()
        .orEmpty()
    val releaseKeyPassword = providers.gradleProperty("QUANT_RELEASE_KEY_PASSWORD")
        .orElse(providers.environmentVariable("QUANT_RELEASE_KEY_PASSWORD"))
        .orElse(localProperties.getProperty("QUANT_RELEASE_KEY_PASSWORD") ?: "")
        .orNull
        ?.trim()
        .orEmpty()
    val hasReleaseSigning = releaseStoreFilePath.isNotEmpty() &&
        releaseStorePassword.isNotEmpty() &&
        releaseKeyAlias.isNotEmpty() &&
        releaseKeyPassword.isNotEmpty() &&
        file(releaseStoreFilePath).isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_debug"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField("String", "QUANT_API_BASE_URL", "\"${localApiBaseUrl.get()}\"")
            buildConfigField("String", "QUANT_API_FALLBACK_BASE_URL", "\"${localApiBaseUrl.get()}\"")
        }
        release {
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_release"
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField("String", "QUANT_API_BASE_URL", "\"${stagingApiBaseUrl.get()}\"")
            buildConfigField("String", "QUANT_API_FALLBACK_BASE_URL", "\"${stagingApiBaseUrl.get()}\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        baseline = file("lint-baseline.xml")
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.withType<Detekt>().configureEach {
    // Avoid Gradle configuration-cache / ordering races when baseline is regenerated.
    mustRunAfter("detektBaseline")
    exclude("**/generated/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.register("cleanDuplicateAndroidBuildArtifacts") {
    group = "build"
    description = "Remove macOS/iCloud duplicate files from Android build intermediates."
    doLast {
        deleteDuplicatedAndroidBuildArtifacts()
    }
}

tasks.configureEach {
    if (
        name == "preBuild" ||
        name.contains("Kotlin") ||
        name.contains("DuplicateClasses") ||
        name.contains("Dex") ||
        name.contains("Manifest") ||
        name.contains("Resources") ||
        name.contains("Res") ||
        name.startsWith("package")
    ) {
        doFirst {
            deleteDuplicatedAndroidBuildArtifacts()
        }
    }
    if (name.contains("Resources") || name.contains("Res")) {
        doFirst {
            delete(
                fileTree(rootProject.projectDir) {
                    include("**/Icon*")
                }
            )
        }
    }
}
