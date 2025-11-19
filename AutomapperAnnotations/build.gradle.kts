import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary) // Use androidLibrary for a library module
    alias(libs.plugins.mavenPublish)
    id("maven-publish")
    id("signing")
}



tasks.withType<PublishToMavenRepository> {
    val isMac = getCurrentOperatingSystem().isMacOsX
    onlyIf {
        isMac.also {
            if (!isMac) logger.error(
                """
                    Publishing the library requires macOS to be able to generate iOS artifacts.
                    Run the task on a mac or use the project GitHub workflows for publication and release.
                """
            )
        }
    }
}

extra["packageNameSpace"] = "io.github.tbib.automapper.automapperannotations"
extra["artifactId"] = "automapper-annotations"
extra["packageName"] = "Automapper Annotations"


mavenPublishing {
    coordinates(
        extra["groupId"].toString(),
        extra["artifactId"].toString(),
        extra["version"].toString()
    )

    publishToMavenCentral(true)
    signAllPublications()

    pom {
        name.set(extra["packageName"].toString())
        description.set(extra["packageDescription"].toString())
        url.set(extra["packageUrl"].toString())
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
            }
        }
        issueManagement {
            system.set(extra["system"].toString())
            url.set(extra["issueUrl"].toString())
        }
        scm {
            connection.set(extra["connectionGit"].toString())
            url.set(extra["packageUrl"].toString())
        }
        developers {
            developer {
                id.set(extra["developerNameId"].toString())
                name.set(extra["developerName"].toString())
                email.set(extra["developerEmail"].toString())
            }
        }
    }

}


signing {
    useGpgCmd()
    sign(publishing.publications)
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
    namespace = extra["packageNameSpace"].toString()
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        // You don't need to specify source/target compatibility for a KMP library
    }
}
