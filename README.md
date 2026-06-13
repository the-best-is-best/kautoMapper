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
implementation("io.github.the-best-is-best:automapper-annotations:2.0.0-rc.3")
```

Add KSP processor:

```kotlin
ksp("io.github.the-best-is-best:automapper-processor:2.0.0-rc.3")
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

### 2. Type-Based Mapping (Readable)

You can define mappers based on the **Target Type**. This is useful for reusing logic across
multiple properties of the same type.

```kotlin
// Forward: maps any property targeting LocalDateTime
fun mapToLocalDateTime(value: String): LocalDateTime

// OR include source type for more clarity
fun mapFromStringToLocalDateTime(value: String): LocalDateTime

// Reverse: maps any property targeting String from LocalDateTime
fun reverseMapFromLocalDateTime(value: LocalDateTime): String
```

### 3. Cross-Type Mapping

Explicitly define mappers between two specific types for maximum clarity.

```kotlin
// Forward: String -> LocalDateTime
fun mapStringToLocalDateTime(value: String): LocalDateTime

// OR
fun mapFromStringToLocalDateTime(value: String): LocalDateTime

// Reverse: LocalDateTime -> String
fun reverseMapLocalDateTimeToString(value: LocalDateTime): String

// OR
fun reverseMapFromLocalDateTimeToString(value: LocalDateTime): String
```

### 4. Collection Mapping

For `List<T>` or `Array<T>`, you can define a mapper for the list itself using
`mapFromList[ArgTypeName]ToList[TargetArgTypeName]` or `mapList[ArgTypeName]`.

```kotlin
// Forward: List<LookupResponse> -> List<Int>
fun mapFromListLookupResponseToListInt(data: List<LookupResponse>?): List<Int>?

// OR simpler
fun mapListLookupResponse(data: List<LookupResponse>?): List<Int>?
```

### 5. Mapping from Parent (Advanced)
If the function takes the **Source Class** itself as a parameter, it acts as a "from parent" mapper.

```kotlin
fun mapEmails(data: UserDto): List<String>
```

### 6. Target-Specific Mapping

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
        fun mapToLocalDateTime(value: String): LocalDateTime
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
    val phoneNumbers: List<PhoneNumberDto>,
    val role: Roles,
    val status: Status
) {
    companion object {
        // 1. mapTo[Type] convention - Used for any property mapping to LocalDateTime
        fun mapToLocalDateTime(date: String): LocalDateTime = LocalDateTime.parse(date)

        // 2. Target-Specific - Used for 'joinDate' specifically when mapping to UserEntity
        fun mapJoinDateToUserEntity(date: String): Long =
            LocalDateTime.parse(date).toInstant(TimeZone.UTC).toEpochMilliseconds()

        // 3. reverseMapFrom[Type] - Used when mapping back from UserModel
        fun reverseMapFromLocalDateTime(date: LocalDateTime): String = date.toString()

        // 4. Custom logic using the whole parent object
        fun mapEmails(data: UserDto): List<String> = data.emails.filter { it.isNotBlank() }
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
