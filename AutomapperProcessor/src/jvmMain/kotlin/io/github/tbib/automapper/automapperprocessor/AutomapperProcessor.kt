package io.github.tbib.automapper.automapperprocessor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Nullability
import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.automapperprocessor.core.ImportHandler
import io.github.tbib.automapper.automapperprocessor.core.MapperConfig
import io.github.tbib.automapper.automapperprocessor.core.checkNullability
import io.github.tbib.automapper.automapperprocessor.core.getCollectionArgumentInfo
import io.github.tbib.automapper.automapperprocessor.core.validateNestedReverseSupport
import io.github.tbib.automapper.automapperprocessor.core.validatePropertyMatching
import io.github.tbib.automapper.automapperprocessor.core.validateReverseMappingWithCustom
import io.github.tbib.automapper.automapperprocessor.extensions.getCustomMapperAnnotation
import io.github.tbib.automapper.automapperprocessor.extensions.getMappedName
import io.github.tbib.automapper.automapperprocessor.extensions.getPrimaryConstructorProperties
import io.github.tbib.automapper.automapperprocessor.extensions.hasAnnotation
import io.github.tbib.automapper.automapperprocessor.extensions.isArray
import io.github.tbib.automapper.automapperprocessor.extensions.isCustomDataClass
import io.github.tbib.automapper.automapperprocessor.extensions.isList
import io.github.tbib.automapper.automapperprocessor.extensions.isMap
import io.github.tbib.automapper.automapperprocessor.extensions.isSameTypeAs
import java.io.OutputStream

/**
 * A KSP processor that generates extension functions for mapping between data classes,
 * triggered by the `@AutoMapper` annotation.
 */
class AutoMapperProcessor(
    private val env: SymbolProcessorEnvironment,
    private val autoMapperVisibility: Boolean = false
) : SymbolProcessor {

    private val logger = env.logger
    private val codeGen = env.codeGenerator

    /**
     * Main processing function that KSP invokes to start the code generation.
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotatedClasses = resolver.getSymbolsWithAnnotation(AutoMapper::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()

        annotatedClasses.forEach { classDecl ->
            try {
                generateMapperFor(classDecl)
            } catch (e: Exception) {
                logger.error(
                    "Failed to generate mapper for ${classDecl.simpleName.asString()}: ${e.message}",
                    classDecl
                )
                // Re-throwing the exception halts the build and provides a full stack trace.
                throw e
            }
        }
        return emptyList()
    }

    /**
     * Orchestrates the entire mapper generation process for a single annotated class.
     */
    private fun generateMapperFor(sourceClass: KSClassDeclaration) {
        // 1. Extract and validate configuration from annotations.
        val config = MapperConfig.from(sourceClass, autoMapperVisibility)
        val targetClass = config.targetClass

        // 2. Gather property information from source and target classes.
        val sourceProps = sourceClass.getAllProperties().toList()
        val targetProps = targetClass.getPrimaryConstructorProperties()

        // 3. Perform validation checks.
        validateReverseMappingWithCustom(sourceProps, config.isReverseEnabled)
        validatePropertyMatching(sourceClass, targetClass, sourceProps, targetProps, config, logger)

        // 4. Generate the mapping logic.
        val importHandler = ImportHandler(config, sourceClass)
        val forwardMappingLines =
            generateForwardMappingLines(sourceProps, targetProps, config, importHandler)
        val reverseMappingLines = if (config.isReverseEnabled) {
            generateReverseMappingLines(sourceProps, targetProps, config, importHandler)
        } else null

        // 5. Write the generated file.
        val fileContent =
            buildFileContent(config, importHandler, forwardMappingLines, reverseMappingLines)
        writeFile(config.packageName, config.mapperName, sourceClass, fileContent)
    }

    /**
     * Generates the lines for the forward mapping function (Source -> Target).
     */
    private fun generateForwardMappingLines(
        sourceProps: List<KSPropertyDeclaration>,
        targetProps: List<KSPropertyDeclaration>,
        config: MapperConfig,
        importHandler: ImportHandler
    ): List<String> {
        return targetProps.map { targetProp ->
            val targetPropName = targetProp.simpleName.asString()

            when {
                // Case 1: Property is explicitly ignored.
                targetPropName in config.ignoreKeys -> "$targetPropName = null"

                // Case 2: A default value is provided.
                targetPropName in config.defaultValues -> {
                    val defaultValue = config.defaultValues[targetPropName]!!
                    importHandler.addImportsFromDefaultValue(defaultValue, config.targetClass)
                    "$targetPropName = $defaultValue"
                }

                // Case 3: Find a matching source property and generate mapping logic.
                else -> {
                    val sourceProp = sourceProps.find { it.getMappedName() == targetPropName }
                        ?: throw IllegalStateException("Validated source property for '$targetPropName' not found.")

                    mapProperty(sourceProp, targetProp, config, importHandler)
                }
            }
        }
    }

    /**
     * Generates the code for a single property-to-property mapping (forward).
     */
    private fun mapProperty(
        sourceProp: KSPropertyDeclaration,
        targetProp: KSPropertyDeclaration,
        config: MapperConfig,
        importHandler: ImportHandler
    ): String {
        val targetPropName = targetProp.simpleName.asString()
        val sourcePropName = sourceProp.simpleName.asString()
        val sourcePropType = sourceProp.type.resolve()
        val targetPropType = targetProp.type.resolve()

        checkNullability(sourceProp, targetProp, config.defaultValues.containsKey(targetPropName))

        val customMapper = sourceProp.getCustomMapperAnnotation()
        if (customMapper != null) {
            val (annotationName, funcName) = customMapper
            val mapperCall = if (annotationName == "AutoMapperCustomFromParent") {
                "${config.sourceClass.simpleName.asString()}.$funcName(this)"
            } else {
                val qualifiedFunc =
                    if (funcName.contains('.')) funcName else "${config.sourceClass.simpleName.asString()}.$funcName"
                importHandler.addImport(config.sourceClass.qualifiedName!!.asString())
                "$qualifiedFunc(this.$sourcePropName)"
            }
            return "$targetPropName = $mapperCall"
        }

        val sourceNullable = sourcePropType.nullability == Nullability.NULLABLE
        val accessPrefix = "this.$sourcePropName"
        val sourcePropClassDecl = sourcePropType.declaration as? KSClassDeclaration

        // --- FIX IS HERE ---
        return when {
            // Collection types (List, Array)
            sourcePropType.isList() || sourcePropType.isArray() -> {
                val argType = sourcePropType.arguments.first().type!!.resolve()
                val argClassDecl = argType.declaration as? KSClassDeclaration
                val needsMapping = argClassDecl?.hasAnnotation("AutoMapper") == true

                // If the item type is a custom class but has no @AutoMapper, throw an exception.
                if (argClassDecl?.isCustomDataClass() == true && !needsMapping) {
                    throw IllegalArgumentException(
                        "Property '$sourcePropName' is a collection of '${argClassDecl.simpleName.asString()}', " +
                                "which is a custom class but is not annotated with @AutoMapper. " +
                                "Please add the annotation to '${argClassDecl.simpleName.asString()}' or ignore the property."
                    )
                }

                if (!needsMapping) {
                    return "$targetPropName = $accessPrefix" // Direct assignment
                }

                // If mapping is needed, build the transformation string.
                val nullSafeOp = if (sourceNullable) "?." else "."
                val mapTransform = "map { it.toSource() }"
                val arraySuffix = if (sourcePropType.isArray()) ".toTypedArray()" else ""
                "$targetPropName = $accessPrefix$nullSafeOp$mapTransform$arraySuffix"
            }

            // A nested object that has its own @AutoMapper
            sourcePropClassDecl?.hasAnnotation("AutoMapper") == true -> {
                val nullSafeOp = if (sourceNullable) "?." else "."
                "$targetPropName = $accessPrefix${nullSafeOp}toSource()"
            }

            // A nested object that is a custom class but WITHOUT @AutoMapper
            sourcePropClassDecl?.isCustomDataClass() == true -> {
                // If types are the same, direct assignment is safe. Don't throw an error.
                if (sourcePropType.isSameTypeAs(targetPropType)) {
                    "$targetPropName = $accessPrefix"
                } else {
                    // Only throw an error if the types are different and no mapping strategy is defined.
                    throw IllegalArgumentException(
                        "Property '$sourcePropName' of type '${sourcePropClassDecl.simpleName.asString()}' is a custom class " +
                                "but is not annotated with @AutoMapper. Please add the annotation, use @AutoMapperCustom, or ignore the property."
                    )
                }
            }

            // Primitives, Enums, or simple objects that don't need recursive mapping.
            else -> {
                if (!sourcePropType.isSameTypeAs(targetPropType)) {
                    logger.warn(
                        "Type mismatch for property '$sourcePropName'. " +
                                "Source: ${sourcePropType.declaration.qualifiedName?.asString()}, " +
                                "Target: ${targetPropType.declaration.qualifiedName?.asString()}. " +
                                "Direct assignment will be attempted.", sourceProp
                    )
                }
                "$targetPropName = $accessPrefix"
            }
        }
    }

    /**
     * Generates the lines for the reverse mapping function (Target -> Source).
     */
    private fun generateReverseMappingLines(
        sourceProps: List<KSPropertyDeclaration>,
        targetProps: List<KSPropertyDeclaration>,
        config: MapperConfig,
        importHandler: ImportHandler
    ): List<String> {
        // Validate that all nested types for reverse mapping are correctly annotated.
        validateNestedReverseSupport(sourceProps, config.sourceClass.simpleName.asString())

        return sourceProps.mapNotNull { sourceProp ->
            val sourcePropName = sourceProp.simpleName.asString()
            val mappedName = sourceProp.getMappedName()

            // Find the corresponding property in the target class.
            val targetProp = targetProps.firstOrNull { it.simpleName.asString() == mappedName }
                ?: return@mapNotNull null // Ignored if no match in target.

            val sourcePropType = sourceProp.type.resolve()
            val isNullable = sourcePropType.nullability == Nullability.NULLABLE
            val nullSafeOp = if (isNullable) "?." else "."
            val accessPrefix = "this.${targetProp.simpleName.asString()}"

            val assignment = when {
                sourcePropType.isList() || sourcePropType.isArray() || sourcePropType.isMap() -> {
                    val (_, isCustom) = getCollectionArgumentInfo(sourcePropType)
                    val mapLogic = if (isCustom) {
                        when {
                            sourcePropType.isMap() -> "mapValues { it.value.toOriginal() }"
                            else -> "map { it.toOriginal() }"
                        }
                    } else ""
                    val arraySuffix = if (sourcePropType.isArray()) ".toTypedArray()" else ""
                    "$accessPrefix$nullSafeOp$mapLogic$arraySuffix"
                }

                (sourcePropType.declaration as? KSClassDeclaration)?.isCustomDataClass() == true -> {
                    "$accessPrefix${nullSafeOp}toOriginal()"
                }

                else -> accessPrefix // Direct assignment for primitives/enums
            }
            "$sourcePropName = $assignment"
        }
    }

    /**
     * Constructs the entire content of the generated Kotlin file as a string.
     */
    private fun buildFileContent(
        config: MapperConfig,
        importHandler: ImportHandler,
        forwardLines: List<String>,
        reverseLines: List<String>?
    ): String {
        return buildString {
            appendLine("package ${config.packageName}")
            appendLine()
            appendLine(importHandler.getImportStatements())
            appendLine("// Auto-generated by AutoMapper KSP. Do not edit!")
            if (config.optInAnnotationString.isNotBlank()) {
                appendLine(config.optInAnnotationString)
                appendLine()
            }
            // Forward mapping function
            appendLine("${config.visibilityModifier} fun ${config.sourceClass.simpleName.asString()}.toSource(): ${config.targetClass.simpleName.asString()} {")
            appendLine("    return ${config.targetClass.simpleName.asString()}(")
            appendLine(forwardLines.joinToString(separator = ",\n") { "        $it" })
            appendLine("    )")
            appendLine("}")

            // Reverse mapping function (if enabled)
            if (reverseLines != null) {
                appendLine()
                appendLine("${config.visibilityModifier} fun ${config.targetClass.simpleName.asString()}.toOriginal(): ${config.sourceClass.simpleName.asString()} {")
                appendLine("    return ${config.sourceClass.simpleName.asString()}(")
                appendLine(reverseLines.joinToString(separator = ",\n") { "        $it" })
                appendLine("    )")
                appendLine("}")
            }
        }
    }

    /**
     * Writes the provided content to a new file using the KSP code generator.
     */
    private fun writeFile(
        packageName: String,
        fileName: String,
        sourceClass: KSClassDeclaration,
        content: String
    ) {
        val file: OutputStream = codeGen.createNewFile(
            dependencies = Dependencies(false, sourceClass.containingFile!!),
            packageName = packageName,
            fileName = fileName
        )
        file.write(content.toByteArray())
    }
}
