package io.github.tbib.automapper.automapperprocessor.core

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import io.github.tbib.automapper.automapperprocessor.extensions.getArgument
import io.github.tbib.automapper.automapperprocessor.extensions.getMappedName
import io.github.tbib.automapper.automapperprocessor.extensions.hasAnnotation
import io.github.tbib.automapper.automapperprocessor.extensions.isCustomDataClass
import io.github.tbib.automapper.automapperprocessor.extensions.isMap

/**
 * Validates that reverse mapping is not enabled when @AutoMapperCustom is used.
 */
fun validateReverseMappingWithCustom(
    sourceProps: List<KSPropertyDeclaration>,
    isReverseEnabled: Boolean
) {
    if (!isReverseEnabled) return

    if (sourceProps.any { it.hasAnnotation("AutoMapperCustom") }) {
        throw IllegalArgumentException(
            "@AutoMapper(reverse=true) does not support properties with @AutoMapperCustom. " +
                    "Please disable reverse mapping or remove the custom mapper annotation."
        )
    }
}

/**
 * Performs validation checks between source and target properties.
 */
internal fun validatePropertyMatching(
    sourceClass: KSClassDeclaration,
    targetClass: KSClassDeclaration,
    sourceProps: List<KSPropertyDeclaration>,
    targetProps: List<KSPropertyDeclaration>,
    config: MapperConfig,
    logger: KSPLogger
) {
    val sourceName = sourceClass.simpleName.asString()
    val targetName = targetClass.simpleName.asString()

    // Get the names of properties as they will be after mapping.
    val sourcePropNames = sourceProps.map { it.getMappedName() }.toSet()

    // Get the names of the properties required by the target constructor.
    val targetPropNames = targetProps.map { it.simpleName.asString() }.toSet()

    // --- FIX IS HERE ---
    // Check for target properties that are not satisfied by the source.
    // A target property is satisfied if its name:
    // 1. Exists in the source property names (after @AutoMapperName is applied).
    // 2. Is explicitly ignored via 'ignoreKeys'.
    // 3. Is provided with a 'defaultValue'.
    val missingKeys = targetPropNames.filter { targetPropName ->
        targetPropName !in sourcePropNames &&
                targetPropName !in config.ignoreKeys &&
                targetPropName !in config.defaultValues.keys
    }

    if (missingKeys.isNotEmpty()) {
        throw IllegalArgumentException(
            "Source class '$sourceName' is missing properties for '$targetName': ${missingKeys.joinToString()}. " +
                    "You can ignore them via 'ignoreKeys' or provide a default value via 'defaultValues'."
        )
    }

    // Warn about source properties that are not used in the target.
    (sourcePropNames - targetPropNames).forEach { propName ->
        // This warning is only useful if the property isn't explicitly ignored.
        if (propName !in config.ignoreKeys) {
            logger.warn(
                "Source property '$propName' in '$sourceName' has no matching property in target '$targetName' and will be ignored."
            )
        }
    }
}

/**
 * Gets information about a collection's generic argument type.
 */
internal fun getCollectionArgumentInfo(collectionType: KSType): Pair<KSType?, Boolean> {
    // For Maps, we check the value type (argument 1)
    val argumentIndex = if (collectionType.isMap()) 1 else 0
    val argType = collectionType.arguments.getOrNull(argumentIndex)?.type?.resolve()
    val argDecl = argType?.declaration as? KSClassDeclaration
    val isCustom = argDecl?.isCustomDataClass() == true
    return Pair(argType, isCustom)
}


/**
 * Validates that nested objects in a reverse-mapped class also support reverse mapping.
 */
internal fun validateNestedReverseSupport(
    sourceProps: List<KSPropertyDeclaration>,
    sourceClassName: String
) {
    sourceProps.forEach { prop ->
        val propType = prop.type.resolve()
        val typeDecl = propType.declaration as? KSClassDeclaration

        if (typeDecl != null && typeDecl.isCustomDataClass()) {
            val nestedMapper =
                typeDecl.annotations.firstOrNull { it.shortName.asString() == "AutoMapper" }
            if (nestedMapper == null) {
                throw IllegalArgumentException(
                    "Property '${prop.simpleName.asString()}' type '${typeDecl.simpleName.asString()}' " +
                            "is a custom class but is missing the @AutoMapper annotation, " +
                            "which is required for reverse mapping in '$sourceClassName'."
                )
            }
            if (!nestedMapper.getArgument("reverse", false)) {
                throw IllegalArgumentException(
                    "Property '${prop.simpleName.asString()}' maps to a type ('${typeDecl.simpleName.asString()}') that " +
                            "does not have @AutoMapper(reverse=true). This is required for reverse mapping in '$sourceClassName'."
                )
            }
        }
    }
}

/**
 * Checks for nullable-to-non-nullable mismatches.
 */
internal fun checkNullability(
    sourceProp: KSPropertyDeclaration,
    targetProp: KSPropertyDeclaration,
    hasDefaultValue: Boolean
) {
    val sourceNullable = sourceProp.type.resolve().nullability == Nullability.NULLABLE
    val targetNullable = targetProp.type.resolve().nullability == Nullability.NULLABLE
    if (sourceNullable && !targetNullable && !hasDefaultValue) {
        throw IllegalArgumentException(
            "Nullable property '${sourceProp.simpleName.asString()}' cannot be mapped to non-nullable " +
                    "property '${targetProp.simpleName.asString()}' without a default value."
        )
    }
}

