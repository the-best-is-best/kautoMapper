import com.google.devtools.ksp.gradle.KspAATask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)


    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.runtime)
            implementation(libs.foundation)
            implementation(libs.material3)
            implementation(libs.ui)
            implementation(libs.components.resources)
            implementation(libs.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(projects.automapperAnnotations)

            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    sourceSets.named("commonMain").configure {
        kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
    }

    android {
        namespace = "io.github.tbib.automapper.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

    }



}
//
//dependencies {
//    ksp(projects.automapperProcessor)
//}



ksp {
    // auto mapper
    arg("autoMapperVisibility", "false")
}

dependencies {
    add("kspCommonMainMetadata", projects.automapperProcessor)
//    add("kspAndroid", projects.automapperProcessor)
//    add("kspIosArm64", projects.automapperProcessor)
//    add("kspIosSimulatorArm64", projects.automapperProcessor)

}

project.tasks.withType(KotlinCompilationTask::class.java).configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}


project.tasks.withType(KspAATask::class.java).configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        if (name == "kspDebugKotlinAndroid") {
            enabled = false
        }
        if (name == "kspReleaseKotlinAndroid") {
            enabled = false
        }
        if (name == "kspKotlinIosSimulatorArm64") {
            enabled = false
        }
        if (name == "kspKotlinIosX64") {
            enabled = false
        }
        if (name == "kspKotlinIosArm64") {
            enabled = false
        }
        dependsOn("kspCommonMainKotlinMetadata")
    }
}