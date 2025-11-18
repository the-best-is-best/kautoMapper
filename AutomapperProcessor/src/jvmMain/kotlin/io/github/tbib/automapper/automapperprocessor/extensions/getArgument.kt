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

internal fun KSType.isSameTypeAs(other: KSType): Boolean {
    // Check if the main declaration is the same
    if (this.declaration.qualifiedName != other.declaration.qualifiedName) return false

    // Check if the number of generic arguments is the same
    if (this.arguments.size != other.arguments.size) return false

    // Recursively check if the generic arguments are the same type
    return this.arguments.zip(other.arguments).all { (arg1, arg2) ->
        val type1 = arg1.type?.resolve()
        val type2 = arg2.type?.resolve()

        if (type1 != null && type2 != null) {
            type1.isSameTypeAs(type2)
        } else {
            type1 == null && type2 == null
        }
    }
}
