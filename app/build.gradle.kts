// Explicit import: inside a Gradle Kotlin script `java` resolves to the Java
// plugin extension, not the package, so `java.util.Properties` does not compile.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val expectedReleaseCertificates = providers.gradleProperty("RECIBOZEN_RELEASE_CERT_SHA256")
    .orElse("")
val entitlementPublicKey = providers.gradleProperty("RECIBOZEN_ENTITLEMENT_PUBLIC_KEY_B64")
    .orElse("")
val apiBaseUrl = providers.gradleProperty("RECIBOZEN_API_BASE_URL")
    .orElse("https://api-recibozen.simapp.uk")
val playCloudProjectNumber = providers.gradleProperty("RECIBOZEN_PLAY_CLOUD_PROJECT_NUMBER")
    .orElse("0")

// Upload-key material for release builds. It lives in keystore.properties, which
// is git-ignored: neither the .jks nor its passwords may ever reach the
// repository, and losing that key means never being able to update the app on
// Play again. When the file is absent the release build still assembles
// unsigned, so the project keeps building on a machine that does not hold it.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasUploadKey = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile").isNullOrBlank().not()

android {
    namespace = "app.recibozen.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.recibozen.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public verification material only. Private signing keys and Play API
        // credentials must never be placed in Gradle properties or the APK.
        buildConfigField(
            "String",
            "EXPECTED_RELEASE_CERT_SHA256",
            expectedReleaseCertificates.get().asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "ENTITLEMENT_PUBLIC_KEY_B64",
            entitlementPublicKey.get().asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "API_BASE_URL",
            apiBaseUrl.get().asBuildConfigString(),
        )
        buildConfigField(
            "long",
            "PLAY_CLOUD_PROJECT_NUMBER",
            "${playCloudProjectNumber.get().toLongOrNull() ?: 0L}L",
        )
    }

    signingConfigs {
        if (hasUploadKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // Play requires v2 at minimum; v1 stays on for sideloaded APKs
                // on the oldest devices this app still supports.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PREMIUM_SECURITY_ENFORCED", "false")
            buildConfigField("boolean", "OFFICIAL_INSTALL_REQUIRED", "false")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "PREMIUM_SECURITY_ENFORCED", "true")
            buildConfigField("boolean", "OFFICIAL_INSTALL_REQUIRED", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.mlkit.text.recognition)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.play.billing)
    implementation(libs.google.play.integrity)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.json.java)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
