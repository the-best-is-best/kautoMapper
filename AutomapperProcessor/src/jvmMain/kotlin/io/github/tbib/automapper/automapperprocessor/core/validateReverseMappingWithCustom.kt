package io.github.tbib.automapper.automapperprocessor.core

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import io.github.tbib.automapper.automapperprocessor.extensions.getArgument
import io.github.tbib.automapper.automapperprocessor.extensions.getMappedName
import io.github.tbib.automapper.automapperprocessor.extensions.hasAnnotation
import io.github.tbib.automapper.automapperprocessor.extensions.isCustomDataClass
import io.github.tbib.automapper.automapperprocessor.extensions.isMap
import io.github.tbib.automapper.automapperprocessor.extensions.isSameTypeAs

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
        val (typeToCheck, _) = if (propType.isMap() || propType.declaration.qualifiedName?.asString()
                ?.startsWith("kotlin.collections") == true
        ) {
            val genericType = propType.arguments.firstOrNull()?.type?.resolve()
            Pair(genericType, true)
        } else {
            Pair(propType, false)
        }

        val typeDecl = typeToCheck?.declaration as? KSClassDeclaration

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
    config: MapperConfig
) {
    val sourcePropType = sourceProp.type.resolve()
    val targetPropType = targetProp.type.resolve()

    val sourceNullable = sourcePropType.nullability == Nullability.NULLABLE
    val targetNullable = targetPropType.nullability == Nullability.NULLABLE

    // The only dangerous condition is mapping a nullable source to a non-nullable target.
    val isUnsafeMapping = sourceNullable && !targetNullable
    if (!isUnsafeMapping) {
        return // All other combinations are safe.
    }

    // If the mapping is unsafe, we must check for a fallback mechanism.
    val targetPropName = targetProp.simpleName.asString()
    val hasDefaultValue = config.defaultValues.containsKey(targetPropName)
    val hasCustomMapper =
        sourceProp.hasAnnotation("AutoMapperCustom") || sourceProp.hasAnnotation("AutoMapperCustomFromParent")

    // A fallback makes the mapping safe.
    val hasSafeFallback = hasDefaultValue || hasCustomMapper

    if (!hasSafeFallback) {
        // If there's no fallback, we must throw a clear, actionable error.
        throw IllegalArgumentException(
            """
            Nullability Mismatch on property '${targetPropName}':
            - Source: '${sourceProp.simpleName.asString()}' in '${config.sourceClass.simpleName.asString()}' is NULLABLE.
            - Target: '${targetPropName}' in '${config.targetClass.simpleName.asString()}' is NON-NULLABLE.
            
            Mapping a nullable type to a non-nullable type can cause a NullPointerException at runtime.
            
            How to fix this:
            1. Make the target property nullable:
               'val ${targetPropName}: ${targetPropType.declaration.simpleName.asString()}?'

            2. Provide a default value in the @AutoMapper annotation:
               defaultValues = [DefaultValue(key = "$targetPropName", value = "YourDefaultValue")]

            3. Use @AutoMapperCustom to handle the null case manually.

            4. Make the source property '${sourceProp.simpleName.asString()}' non-nullable.
            """.trimIndent()
        )
    }
}


private fun validateCustomMapper(
    resolver: Resolver,
    sourceClass: KSClassDeclaration,
    funcName: String,
    sourceProp: KSPropertyDeclaration,
    targetPropType: KSType
) {
    var func: KSFunctionDeclaration? = null

    // Strategy 1: Look in the companion object of the source class
    val companionObject = sourceClass.declarations
        .filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.isCompanionObject }

    if (companionObject != null) {
        func = companionObject.getDeclaredFunctions()
            .firstOrNull { it.simpleName.asString() == funcName }
    }

    // Strategy 2: If not in companion, look for a top-level function.
    if (func == null) {
        val qualifiedFuncName = if ('.' in funcName) {
            funcName
        } else {
            "${sourceClass.packageName.asString()}.$funcName"
        }

        func = resolver.getFunctionDeclarationsByName(
            resolver.getKSNameFromString(qualifiedFuncName),
            includeTopLevel = true
        ).firstOrNull()
    }

    if (func == null) {
        throw IllegalArgumentException("@AutoMapperCustom error on '${sourceClass.simpleName.asString()}.${sourceProp.simpleName.asString()}': Function '$funcName' not found in companion object or as a top-level function.")
    }

    // --- Validation Logic ---
    // The function must take the source property's type as input.
    val expectedInputType = sourceProp.type.resolve()
    val actualInputType = func.parameters.firstOrNull()?.type?.resolve()

    // The function must return the target property's type.
    val expectedOutputType = targetPropType
    val actualOutputType = func.returnType?.resolve()

    val errorLocation = "'${sourceClass.simpleName.asString()}.${sourceProp.simpleName.asString()}'"
    val funcSignatureForError =
        "fun ${func.simpleName.asString()}(${actualInputType?.declaration?.simpleName?.asString() ?: ""})" +
                ": ${actualOutputType?.declaration?.simpleName?.asString() ?: "Unit"}"

    // Validate the input parameter
    if (func.parameters.size != 1 || actualInputType == null || !actualInputType.isSameTypeAs(
            expectedInputType
        )
    ) {
        val expectedInputTypeName = expectedInputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustom error on $errorLocation: " +
                    "Function '$funcName' has an invalid parameter. Expected input type '($expectedInputTypeName)', but found signature '$funcSignatureForError'."
        )
    }

    // Validate the return type
    if (actualOutputType == null || !actualOutputType.isSameTypeAs(expectedOutputType)) {
        val expectedOutputTypeName = expectedOutputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustom error on $errorLocation: " +
                    "Function '$funcName' has an invalid return type. Expected return type '$expectedOutputTypeName', but found signature '$funcSignatureForError'."
        )
    }
}

internal fun validateCustomReverseMapper(
    resolver: Resolver,
    sourceClass: KSClassDeclaration,
    funcName: String,
    targetProp: KSPropertyDeclaration,
    sourcePropType: KSType
) {
    var func: KSFunctionDeclaration? = null

    // Strategy 1: Look in the companion object of the source class
    val companionObject = sourceClass.declarations
        .filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.isCompanionObject }

    if (companionObject != null) {
        func = companionObject.getDeclaredFunctions()
            .firstOrNull { it.simpleName.asString() == funcName }
    }

    // Strategy 2: If not in companion, look for a top-level function.
    if (func == null) {
        val qualifiedFuncName = if ('.' in funcName) {
            funcName
        } else {
            "${sourceClass.packageName.asString()}.$funcName"
        }

        func = resolver.getFunctionDeclarationsByName(
            resolver.getKSNameFromString(qualifiedFuncName),
            includeTopLevel = true
        ).firstOrNull()
    }

    if (func == null) {
        throw IllegalArgumentException("@AutoMapperCustom error on '${sourceClass.simpleName.asString()}.${targetProp.simpleName.asString()}': Function '$funcName' not found in companion object or as a top-level function.")
    }

    // --- Validation Logic ---
    val expectedInputType = targetProp.type.resolve()
    val actualInputType = func.parameters.firstOrNull()?.type?.resolve()

    val expectedOutputType = sourcePropType
    val actualOutputType = func.returnType?.resolve()

    val errorLocation = "'${sourceClass.simpleName.asString()}.${targetProp.simpleName.asString()}'"
    val funcSignatureForError =
        "fun ${func.simpleName.asString()}(${actualInputType?.declaration?.simpleName?.asString() ?: ""})" +
                ": ${actualOutputType?.declaration?.simpleName?.asString() ?: "Unit"}"

    // Validate the input parameter
    if (func.parameters.size != 1 || actualInputType == null || !actualInputType.isSameTypeAs(
            expectedInputType
        )
    ) {
        val expectedInputTypeName = expectedInputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustom error on $errorLocation: " +
                    "Function '$funcName' has an invalid parameter. Expected input type '($expectedInputTypeName)', but found signature '$funcSignatureForError'."
        )
    }

    // Validate the return type
    if (actualOutputType == null || !actualOutputType.isSameTypeAs(expectedOutputType)) {
        val expectedOutputTypeName = expectedOutputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustom error on $errorLocation: " +
                    "Function '$funcName' has an invalid return type. Expected return type '$expectedOutputTypeName', but found signature '$funcSignatureForError'."
        )
    }
}
