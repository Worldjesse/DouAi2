plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("douaiReleaseStoreFile")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("douaiReleaseStorePassword")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("douaiReleaseKeyAlias")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("douaiReleaseKeyPassword")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_KEY_PASSWORD"))
val releaseSigningPresent = listOf(releaseStoreFile, releaseStorePassword,
    releaseKeyAlias, releaseKeyPassword).map { it.isPresent }
require(releaseSigningPresent.none { it } || releaseSigningPresent.all { it }) {
    "Provide all four DouAi release signing properties or environment variables."
}

android {
    namespace = "com.douai.module"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.yudigaga.douai"
        minSdk = 28
        targetSdk = 35
        versionCode = 77
        versionName = "0.1.76"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    if (releaseSigningPresent.all { it }) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-release.pro")
            if (releaseSigningPresent.all { it }) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("r8Experiment") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.constraintlayout:constraintlayout:2.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4-android:1.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.10.1")
}
