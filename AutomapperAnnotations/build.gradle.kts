plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary) // Use androidLibrary for a library module
}

kotlin {
    // 1. Android Target
    androidTarget()

    // 2. Apple Targets (iOS, macOS, watchOS, tvOS)
    val xcfName = "AutomapperAnnotationsKit"
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        macosX64(),
        macosArm64(),
        tvosX64(),
        tvosArm64(),
        tvosSimulatorArm64(),
        watchosX64(),
        watchosArm64(),
        watchosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = xcfName
        }
    }

    // 3. JVM (for server-side or desktop)
    jvm()

    // 4. JavaScript (for web browsers and NodeJS)
    js(IR) {
        browser()
        nodejs()
    }

    // 5. Linux Native Targets
    linuxX64()
    linuxArm64()

    // Source Set Configuration
    sourceSets {
        commonMain.dependencies {
            // No dependencies needed for a pure annotation library
            // implementation(libs.kotlin.stdlib) is added automatically
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    // Minimal Android configuration needed for a library
    namespace = "io.github.tbib.automapper.automapperannotations"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        // You don't need to specify source/target compatibility for a KMP library
    }
}
