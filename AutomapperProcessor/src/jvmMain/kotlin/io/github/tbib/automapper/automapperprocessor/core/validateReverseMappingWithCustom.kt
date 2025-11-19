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
    val sourcePropNames = sourceProps.map { it.getMappedName() }.toSet()
    val targetPropNames = targetProps.map { it.simpleName.asString() }.toSet()

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

    (sourcePropNames - targetPropNames).forEach { propName ->
        if (propName !in config.ignoreKeys) {
            logger.warn(
                "Source property '$propName' in '$sourceName' has no matching property in target '$targetName' and will be ignored."
            )
        }
    }
}

internal fun getCollectionArgumentInfo(collectionType: KSType): Pair<KSType?, Boolean> {
    val argumentIndex = if (collectionType.isMap()) 1 else 0
    val argType = collectionType.arguments.getOrNull(argumentIndex)?.type?.resolve()
    val argDecl = argType?.declaration as? KSClassDeclaration
    val isCustom = argDecl?.isCustomDataClass() == true
    return Pair(argType, isCustom)
}

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

internal fun checkNullability(
    sourceProp: KSPropertyDeclaration,
    targetProp: KSPropertyDeclaration,
    config: MapperConfig
) {
    val sourcePropType = sourceProp.type.resolve()
    val targetPropType = targetProp.type.resolve()
    val sourceNullable = sourcePropType.nullability == Nullability.NULLABLE
    val targetNullable = targetPropType.nullability == Nullability.NULLABLE
    val isUnsafeMapping = sourceNullable && !targetNullable
    if (!isUnsafeMapping) return

    val targetPropName = targetProp.simpleName.asString()
    val hasDefaultValue = config.defaultValues.containsKey(targetPropName)
    val hasCustomMapper =
        sourceProp.hasAnnotation("AutoMapperCustom") || sourceProp.hasAnnotation("AutoMapperCustomFromParent")
    val hasSafeFallback = hasDefaultValue || hasCustomMapper

    if (!hasSafeFallback) {
        throw IllegalArgumentException(
            """
            Nullability Mismatch on property '${targetPropName}':
            - Source: '${sourceProp.simpleName.asString()}' in '${config.sourceClass.simpleName.asString()}' is NULLABLE.
            - Target: '${targetPropName}' in '${config.targetClass.simpleName.asString()}' is NON-NULLABLE.
            
            How to fix this:
            1. Make the target property nullable: 'val ${targetPropName}: ${targetPropType.declaration.simpleName.asString()}?'
            2. Provide a default value: defaultValues = [DefaultValue(key = "$targetPropName", value = "YourDefaultValue")]
            3. Use @AutoMapperCustom to handle the null case manually.
            4. Make the source property '${sourceProp.simpleName.asString()}' non-nullable.
            """.trimIndent()
        )
    }
}

internal fun findFunction(
    resolver: Resolver,
    sourceClass: KSClassDeclaration,
    funcName: String
): KSFunctionDeclaration? {
    sourceClass.declarations.filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.isCompanionObject }?.let { companion ->
        companion.getDeclaredFunctions().firstOrNull { it.simpleName.asString() == funcName }
            ?.let { return it }
    }
    val qualifiedFuncName =
        if ('.' in funcName) funcName else "${sourceClass.packageName.asString()}.$funcName"
    return resolver.getFunctionDeclarationsByName(
        resolver.getKSNameFromString(qualifiedFuncName),
        true
    ).firstOrNull()
}

internal fun validateCustomMapper(
    resolver: Resolver, sourceClass: KSClassDeclaration, funcName: String,
    sourceProp: KSPropertyDeclaration, targetPropType: KSType, annotationName: String
) {
    val func = findFunction(resolver, sourceClass, funcName) ?: throw IllegalArgumentException(
        "@AutoMapperCustom error on '${sourceClass.simpleName.asString()}.${sourceProp.simpleName.asString()}': Function '$funcName' not found."
    )

    val expectedInputType =
        if (annotationName == "AutoMapperCustomFromParent") sourceClass.asStarProjectedType() else sourceProp.type.resolve()
    val actualInputType = func.parameters.firstOrNull()?.type?.resolve()
    val actualOutputType = func.returnType?.resolve()

    val errorLocation = "'${sourceClass.simpleName.asString()}.${sourceProp.simpleName.asString()}'"
    val funcSignature =
        "fun ${func.simpleName.asString()}(${actualInputType?.declaration?.simpleName?.asString() ?: ""}): ${actualOutputType?.declaration?.simpleName?.asString() ?: "Unit"}"

    if (func.parameters.size != 1 || actualInputType == null || !actualInputType.isSameTypeAs(
            expectedInputType
        )
    ) {
        val expectedInputTypeName = expectedInputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustom error on $errorLocation: Function '$funcName' has an invalid parameter. Expected '($expectedInputTypeName)', found '$funcSignature'."
        )
    }

    if (actualOutputType == null || !actualOutputType.isSameTypeAs(targetPropType)) {
        val expectedOutputTypeName = targetPropType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustom error on $errorLocation: Function '$funcName' has an invalid return type. Expected '$expectedOutputTypeName', found '$funcSignature'."
        )
    }
}

internal fun validateCustomReverseMapper(
    resolver: Resolver,
    sourceClass: KSClassDeclaration,
    targetClass: KSClassDeclaration,
    funcName: String,
    targetProp: KSPropertyDeclaration,
    sourcePropType: KSType,
    annotationName: String
) {
    val func = findFunction(resolver, sourceClass, funcName) ?: throw IllegalArgumentException(
        "@AutoMapperCustomReverse error on '${sourceClass.simpleName.asString()}.${targetProp.simpleName.asString()}': Function '$funcName' not found."
    )

    val expectedInputType =
        if (annotationName == "AutoMapperCustomFromParent") targetClass.asStarProjectedType() else targetProp.type.resolve()
    val actualInputType = func.parameters.firstOrNull()?.type?.resolve()
    val expectedOutputType = sourcePropType
    val actualOutputType = func.returnType?.resolve()

    val errorLocation = "'${sourceClass.simpleName.asString()}.${targetProp.simpleName.asString()}'"
    val funcSignature =
        "fun ${func.simpleName.asString()}(${actualInputType?.declaration?.simpleName?.asString() ?: ""}): ${actualOutputType?.declaration?.simpleName?.asString() ?: "Unit"}"

    if (func.parameters.size != 1 || actualInputType == null || !actualInputType.isSameTypeAs(
            expectedInputType
        )
    ) {
        val expectedInputTypeName = expectedInputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustomReverse error on $errorLocation: Function '$funcName' has an invalid parameter. Expected '($expectedInputTypeName)', found '$funcSignature'."
        )
    }

    if (actualOutputType == null || !actualOutputType.isSameTypeAs(expectedOutputType)) {
        val expectedOutputTypeName = expectedOutputType.declaration.simpleName.asString()
        throw IllegalArgumentException(
            "@AutoMapperCustomReverse error on $errorLocation: Function '$funcName' has an invalid return type. Expected '$expectedOutputTypeName', found '$funcSignature'."
        )
    }
}
