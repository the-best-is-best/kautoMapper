<div align="center">
 <h1> AutoMapper Annotations <h1>
</div>
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
- **Convention-based custom mapping (No annotations needed)**
- Multi-target mapping (Map one class to multiple entities)
- Add OptIns to generated class
- Public/Internal control for generated mapper

---

# Versions

** [![Maven Central](https://img.shields.io/maven-central/v/io.github.the-best-is-best/automapper-annotations)](https://search.maven.org/artifact/io.github.the-best-is-best/automapper-annotations)

# 📦 Maven Central

## Add to `commonMain`

```kotlin
implementation("io.github.the-best-is-best:automapper-annotations:2.0.0-rc.1")
```

Add KSP processor:

```kotlin
ksp("io.github.the-best-is-best:automapper-processor:2.0.0-rc.1")
```

## To start generator

```bash
./gradlew composeApp:kspCommonMainKotlinMetadata
```

---

# 🧩 Available Annotations

## `@AutoMapper`

### Attach to a CLASS to generate a mapper. This annotation is repeatable.

```kotlin
@Repeatable
annotation class AutoMapper(
    val to: KClass<*>,
    val useClassNameInMapperFunc: Boolean = false,
    val optIns: Array<String> = [],
    val ignoreKeys: Array<String> = [],
    val forcePublic: Boolean = false,
    val defaultValues: Array<DefaultValue> = [],
    val reverse: Boolean = false
)
```

---

## `@DefaultValue`

### Adds default value if missing

```kotlin
annotation class DefaultValue(
    val key: String,
    val value: String
)
```

---

## `@AutoMapperName`

### Renames property

```kotlin
annotation class AutoMapperName(val to: String)
```

---

## `@AutoMapperAddOptIns`

### Adds opt-ins to the generated class

```kotlin
annotation class AutoMapperAddOptIns(val value: Array<String>)
```

---

# 🛠️ Custom Mapping (Convention Based)

You can define custom mapping logic in your class or companion object without any property
annotations. The processor will automatically find these functions based on their names and types.

### 1. Simple Property Mapping

Use `map[PropertyName]` for forward and `reverseMap[PropertyName]` for reverse.

```kotlin
fun mapJoinDate(value: String): LocalDateTime
fun reverseMapJoinDate(value: LocalDateTime): String
```

### 2. Mapping from Parent (Advanced)

If the function takes the **Source Class** itself as a parameter, it acts as a "from parent" mapper.

```kotlin
fun mapEmails(data: UserDto): List<String>
```

### 3. Target-Specific Mapping

If you have multiple `@AutoMapper` targets, you can specify which target a function applies to using
`map[PropertyName]To[TargetClassName]`.

```kotlin
@AutoMapper(to = UserModel::class)
@AutoMapper(to = UserEntity::class)
data class UserDto(...) {
  companion object {
    // Only used when mapping to UserEntity
    fun mapJoinDateToUserEntity(value: String): Long

    // Used for UserModel (fallback)
    fun mapJoinDate(value: String): LocalDateTime
  }
}
```

---

# 🧪 Example Usage

### Multi-Target Mapping

```kotlin
@AutoMapper(to = UserModel::class, reverse = true, useClassNameInMapperFunc = true)
@AutoMapper(to = UserEntity::class, reverse = true)
@AutoMapperAddOptIns(["kotlin.time.ExperimentalTime"])
data class UserDto @OptIn(ExperimentalTime::class) constructor(
  val id: Int,
  val name: String,
  val joinDate: String,

  @AutoMapperName("addres")
  val address: AddressDto,

  val emails: List<String>,
  val role: Roles,
  val status: Status
) {
  companion object {
    // Forward: String -> LocalDateTime (Used by UserModel)
    fun mapJoinDate(joinDate: String): LocalDateTime = LocalDateTime.parse(joinDate)

    // Forward: String -> Long (Specifically for UserEntity)
    fun mapJoinDateToUserEntity(joinDate: String): Long =
      LocalDateTime.parse(joinDate).toInstant(TimeZone.UTC).toEpochMilliseconds()

    // Reverse: LocalDateTime -> String (Used by UserModel)
    fun reverseMapJoinDate(joinDate: LocalDateTime): String = joinDate.toString()

    // Reverse: Long -> String (Used by UserEntity)
    fun reverseMapJoinDateToUserEntity(joinDate: Long): String = ""

    // Custom logic using the whole parent object
    fun mapEmails(data: UserDto): List<String> = listOf("email1", "email2")
    }
}
```

### Generated Mapper Usage

```kotlin
val userDto = UserDto(...)

// Forward mapping
val model: UserModel = userDto.toUserModel()
val entity: UserEntity = userDto.toUserEntity()

// Reverse mapping
val dtoFromModel: UserDto = model.toUserDto()
val dtoFromEntity: UserDto = entity.toUserDto()
```

---

# ⚙️ Setup (Kotlin Multiplatform + KSP)

### Add plugin

```kotlin
id("com.google.devtools.ksp")
```

### Configure KSP + KMP

```kotlin
kotlin {
  // ... targets configuration (android, ios, etc.)

  sourceSets.named("commonMain").configure {
    // Ensure generated code is visible
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
    }
}

ksp {
  // Optional: make mappers internal by default
  arg("autoMapperVisibility", "false")
}
```

---

# 📍 Notes

- **Generated code path**: `build/generated/ksp/metadata/commonMain/kotlin`
- **Multi-Mapper Support**: Use `useClassNameInMapperFunc = true` to avoid name collisions in
  extension functions.
- **Zero Runtime Overhead**: Everything is generated at compile-time.

---

# 🎉 Done
