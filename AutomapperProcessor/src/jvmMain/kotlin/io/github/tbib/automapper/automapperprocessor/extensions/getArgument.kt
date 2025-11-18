package io.github.tbib.automapper.automapperprocessor.extensions

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

// General Annotation & Class extensions
internal fun <T> KSAnnotation.getArgument(name: String, defaultValue: T): T {
    @Suppress("UNCHECKED_CAST")
    return this.arguments.firstOrNull { it.name?.asString() == name }?.value as? T ?: defaultValue
}

internal fun KSAnnotation.getTargetClass(): KSClassDeclaration {
    val targetType = this.arguments.first().value as? KSType
        ?: throw IllegalArgumentException("Invalid 'to' parameter in @AutoMapper.")
    return targetType.declaration as? KSClassDeclaration
        ?: throw IllegalArgumentException("Target type must be a class.")
}

internal fun KSDeclaration.hasAnnotation(name: String): Boolean {
    return this.annotations.any { it.shortName.asString() == name }
}

internal fun KSClassDeclaration.getPrimaryConstructorProperties(): List<KSPropertyDeclaration> {
    val constructorParams =
        this.primaryConstructor?.parameters?.mapNotNull { it.name?.asString() }?.toSet()
            ?: emptySet()
    return this.getAllProperties().filter { it.simpleName.asString() in constructorParams }.toList()
}

internal fun KSClassDeclaration.isCustomDataClass(): Boolean {
    val fqn = this.qualifiedName?.asString()
    return fqn != null && !fqn.startsWith("kotlin.") && !fqn.startsWith("java.")
}

internal fun List<String>.toOptInString(): String {
    if (this.isEmpty()) return ""
    return "@OptIn(" + this.joinToString { "${it.substringAfterLast('.')}::class" } + ")"
}

// Property-specific extensions
internal fun KSPropertyDeclaration.getMappedName(): String {
    val nameAnnotation =
        this.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }

    // --- THIS IS THE CORRECTED LOGIC ---
    // The annotation's argument is 'value', not 'name'. We get the first argument's value.
    return nameAnnotation?.arguments?.firstOrNull()?.value as? String ?: this.simpleName.asString()
}

internal fun KSPropertyDeclaration.getCustomMapperAnnotation(): Pair<String, String>? {
    val annotation = this.annotations.firstOrNull {
        val name = it.shortName.asString()
        name == "AutoMapperCustom" || name == "AutoMapperCustomFromParent"
    } ?: return null

    val funcName = annotation.arguments.first().value as? String
    return if (funcName != null) annotation.shortName.asString() to funcName else null
}

// Type-specific extensions
internal fun KSType.isList(): Boolean = declaration.qualifiedName?.asString() in listOf(
    "kotlin.collections.List",
    "kotlin.collections.MutableList"
)

internal fun KSType.isArray(): Boolean = declaration.qualifiedName?.asString() == "kotlin.Array"
internal fun KSType.isMap(): Boolean = declaration.qualifiedName?.asString() in listOf(
    "kotlin.collections.Map",
    "kotlin.collections.MutableMap"
)

/**
 * A robust function to deeply compare two KSType objects for equality,
 * ignoring only the top-level nullability. It compares the qualified name,
 * number of arguments, and recursively checks each type argument's variance and type.
 *
 * @param other The KSType to compare against.
 * @return `true` if the types are structurally identical (ignoring nullability), otherwise `false`.
 */
internal fun KSType.isSameTypeAs(other: KSType): Boolean {
    // 1. Compare the fully qualified names of the base declarations.
    if (this.declaration.qualifiedName?.asString() != other.declaration.qualifiedName?.asString()) {
        return false
    }

    // 2. Ensure they have the same number of generic type arguments.
    if (this.arguments.size != other.arguments.size) {
        return false
    }

    // 3. Recursively compare each type argument.
    return this.arguments.zip(other.arguments).all { (thisArg, otherArg) ->
        // Compare variance (in, out, or invariant). They must match.
        if (thisArg.variance != otherArg.variance) {
            return@all false
        }

        val thisArgType = thisArg.type?.resolve()
        val otherArgType = otherArg.type?.resolve()

        // Handle star projections (*), where the type is null.
        if (thisArgType == null && otherArgType == null) {
            true // Both are star projections, so they match.
        } else if (thisArgType != null && otherArgType != null) {
            // If both types are resolvable, recurse.
            thisArgType.isSameTypeAs(otherArgType)
        } else {
            // One is a star projection and the other is not, so they don't match.
            false
        }
    }
}
