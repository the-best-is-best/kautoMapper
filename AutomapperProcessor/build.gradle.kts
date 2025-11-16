plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.automapperAnnotations)
        }
        jvmMain.dependencies {
            implementation(libs.ksp.api)
        }
    }
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("src/jvmMain/resources")
}