plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningStoreFile = providers.environmentVariable("COLORS_SIGNING_STORE_FILE").orNull
val releaseSigningStorePassword = providers.environmentVariable("COLORS_SIGNING_STORE_PASSWORD").orNull
val releaseSigningKeyAlias = providers.environmentVariable("COLORS_SIGNING_KEY_ALIAS").orNull
val releaseSigningKeyPassword = providers.environmentVariable("COLORS_SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseSigningStoreFile,
    releaseSigningStorePassword,
    releaseSigningKeyAlias,
    releaseSigningKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.jaredwinick.colors.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaredwinick.colors.camera"
        minSdk = 29
        targetSdk = 29
        versionCode = 12
        versionName = "0.8.0"

        buildConfigField(
            "String",
            "INGEST_ENDPOINT",
            "\"https://colors-sky-archive.jaredwinick.workers.dev/api/ingest\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningStoreFile))
                storePassword = releaseSigningStorePassword
                keyAlias = releaseSigningKeyAlias
                keyPassword = releaseSigningKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_ENDPOINT_OVERRIDE", "true")
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "ALLOW_ENDPOINT_OVERRIDE", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // This is a dedicated, sideloaded Android 10 station. Google Play
        // publication is explicitly out of scope.
        disable += "ExpiredTargetSdkVersion"
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(releaseSigningConfigured) {
            "Release signing requires all COLORS_SIGNING_* environment variables."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
