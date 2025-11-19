# AutoMapper Annotations

<h1 align="center">AutoMapper Annotations</h1><br>

<div align="center">
<a href="https://opensource.org/licenses/Apache-2.0"><img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue.svg"/></a>
<img src="https://img.shields.io/badge/Platform-Kotlin%20Multiplatform-blueviolet.svg" />
<img src="https://img.shields.io/badge/KSP-Supported-brightgreen.svg" />
<a href="https://github.com/the-best-is-best/"><img alt="Profile" src="https://img.shields.io/badge/github-%23181717.svg?&style=for-the-badge&logo=github&logoColor=white" height="20"/></a>
</div>

---

This package provides annotation definitions for the **AutoMapper KSP Processor**, allowing
automatic mapping generation between Kotlin classes for **Kotlin Multiplatform**, **Android**, and *
*iOS**.

It includes:

- Auto mapping between data models
- Property renaming
- Default values
- Custom mapping functions
- Add OptIns to generated class
- Custom mapping from parent
- Public/ Internal control for generated mapper

---

# 📦 Maven Central

Add to `commonMain`:

```kotlin
implementation("io.github.tbib.automapper:automapperannotations:1.0.0-rc.1")
```

Add KSP processor:

```kotlin
ksp(io.github.tbib.automapper:automapperProcessor:1.0.0-rc.1)
```

To start generator

```terminal
./gradlew composeApp:kspCommonMainKotlinMetadata   
```
---

# 🧩 Available Annotations

## `@AutoMapper`

Attach to a CLASS to generate a mapper.

```kotlin
annotation class AutoMapper(
    val to: KClass<*>,
    val optIns: Array<String> = [],
    val ignoreKeys: Array<String> = [],
    val forcePublic: Boolean = false,
    val defaultValues: Array<DefaultValue> = []
)
```

---

## `@DefaultValue`

Adds default value if missing.

```kotlin
annotation class DefaultValue(
    val key: String,
    val value: String
)
```

---

## `@AutoMapperName`

Renames property.

```kotlin
annotation class AutoMapperName(val to: String)
```

---

## `@AutoMapperCustom`

Use custom mapping function.

```kotlin
annotation class AutoMapperCustom(val mapperFunction: String)
```

---

## `@AutoMapperCustomFromParent`

Use a custom method defined inside the parent generated mapper class.

```kotlin
annotation class AutoMapperCustomFromParent(val mapperFunction: String)
```

---

## `@AutoMapperAddOptIns`

Adds opt-ins to the generated class.

```kotlin
annotation class AutoMapperAddOptIns(val value: Array<String>)
```

---

# ⚙️ Setup (Kotlin Multiplatform + KSP)

Add plugin:

```kotlin
id("com.google.devtools.ksp")
```

---

## Configure KSP + KMP

```kotlin
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { ios ->
        ios.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets.named("commonMain").configure {
        kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
    }

    sourceSets.all {
        languageSettings.enableLanguageFeature("DisableCompatibilityModeForKotlinMetadata")
    }
}

dependencies {
    ksp(projects.automapperProcessor)
}
```

---

# 🔧 Force metadata generation before all KSP tasks

```kotlin
project.tasks.withType(KotlinCompilationTask::class.java).configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
```

---

# 🛑 Disable multiple KSP tasks (Required for KMM)

```kotlin
project.tasks.withType(KspAATask::class.java).configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        if (name == "kspDebugKotlinAndroid") enabled = false
        if (name == "kspReleaseKotlinAndroid") enabled = false
        if (name == "kspKotlinIosSimulatorArm64") enabled = false
        if (name == "kspKotlinIosX64") enabled = false
        if (name == "kspKotlinIosArm64") enabled = false
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
```

---

# 🧪 Example Usage

### Model → Entity Mapping

```kotlin
@AutoMapper(
    to = UserEntity::class,
    ignoreKeys = ["internalId"],
    forcePublic = true,
    optIns = ["kotlin.ExperimentalStdlibApi"],
    defaultValues = [
        DefaultValue("role", "Role()")
    ]
)
data class UserDto(
    val id: String,
    val name: String,

    @AutoMapperName(to = "createdAt")
    val joinDate: Long,

    @AutoMapperCustom(mapperFunction = "mapStatus")
    val status: Status,

    @AutoMapperCustomFromParent(mapperFunction = "mapRole")
    val role: Role

) {
    companion object {
        fun mapStatus(data: Status) {
            // do ur mapper need
        }

        fun mapRole(data: UserDto) {

        }
    }
}
```

Generated mapper:

```
UserDtoMapper.toSource(source: UserDto): UserEntity
```

---

# 📍 Notes

- Generated code path:  
  `build/generated/ksp/metadata/commonMain/kotlin`
- Works with: Android, iOS, Kotlin JVM & Native
- No runtime overhead — compile-time generated

---

# 🎉 Done!

A clean, ready-to-use README for `automapperannotations`.
