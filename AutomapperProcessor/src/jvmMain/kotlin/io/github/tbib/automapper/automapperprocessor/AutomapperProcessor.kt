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
import io.github.tbib.automapper.automapperprocessor.core.validateCustomMapper
import io.github.tbib.automapper.automapperprocessor.core.validateCustomReverseMapper
import io.github.tbib.automapper.automapperprocessor.core.validateNestedReverseSupport
import io.github.tbib.automapper.automapperprocessor.core.validatePropertyMatching
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

class AutoMapperProcessor(
    private val env: SymbolProcessorEnvironment,
    private val autoMapperVisibility: Boolean = false
) : SymbolProcessor {

    private val logger = env.logger
    private val codeGen = env.codeGenerator

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotatedClasses = resolver.getSymbolsWithAnnotation(AutoMapper::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()

        annotatedClasses.forEach { classDecl ->
            try {
                generateMapperFor(classDecl, resolver)
            } catch (e: Exception) {
                logger.error(
                    "Failed to generate mapper for ${classDecl.simpleName.asString()}: ${e.message}",
                    classDecl
                )
                throw e
            }
        }
        return emptyList()
    }

    private fun generateMapperFor(sourceClass: KSClassDeclaration, resolver: Resolver) {
        val config = MapperConfig.from(sourceClass, autoMapperVisibility)
        val targetClass = config.targetClass
        val sourceProps = sourceClass.getAllProperties().toList()
        val targetProps = targetClass.getPrimaryConstructorProperties()

        validatePropertyMatching(sourceClass, targetClass, sourceProps, targetProps, config, logger)

        val importHandler = ImportHandler(config, sourceClass)
        val forwardMappingLines =
            generateForwardMappingLines(sourceProps, targetProps, config, importHandler, resolver)
        val reverseMappingLines = if (config.isReverseEnabled) {
            generateReverseMappingLines(sourceProps, targetProps, config, importHandler, resolver)
        } else null

        val fileContent =
            buildFileContent(config, importHandler, forwardMappingLines, reverseMappingLines)
        writeFile(config.packageName, config.mapperName, sourceClass, fileContent)
    }

    private fun generateForwardMappingLines(
        sourceProps: List<KSPropertyDeclaration>,
        targetProps: List<KSPropertyDeclaration>,
        config: MapperConfig,
        importHandler: ImportHandler,
        resolver: Resolver
    ): List<String> {
        return targetProps.map { targetProp ->
            val targetPropName = targetProp.simpleName.asString()
            when {
                targetPropName in config.ignoreKeys -> "$targetPropName = null"
                targetPropName in config.defaultValues -> {
                    val defaultValue = config.defaultValues[targetPropName]!!
                    importHandler.addImportsFromDefaultValue(defaultValue, config.targetClass)
                    "$targetPropName = $defaultValue"
                }
                else -> {
                    val sourceProp = sourceProps.find { it.getMappedName() == targetPropName }
                        ?: throw IllegalStateException("Validated source property for '$targetPropName' not found.")
                    mapProperty(sourceProp, targetProp, config, importHandler, resolver)
                }
            }
        }
    }

    private fun mapProperty(
        sourceProp: KSPropertyDeclaration,
        targetProp: KSPropertyDeclaration,
        config: MapperConfig,
        importHandler: ImportHandler,
        resolver: Resolver
    ): String {
        val targetPropName = targetProp.simpleName.asString()
        val sourcePropName = sourceProp.simpleName.asString()
        val sourcePropType = sourceProp.type.resolve()
        val targetPropType = targetProp.type.resolve()

        checkNullability(sourceProp, targetProp, config)

        val customMapper = sourceProp.getCustomMapperAnnotation()
        if (customMapper != null) {
            val (annotationName, funcName, _) = customMapper
            validateCustomMapper(
                resolver,
                config.sourceClass,
                funcName,
                sourceProp,
                targetPropType,
                annotationName
            )
            val mapperCall = if (annotationName == "AutoMapperCustomFromParent") {
                "${config.sourceClass.simpleName.asString()}.$funcName(this)"
            } else {
                val qualifiedFunc =
                    if (funcName.contains('.')) funcName else "${config.sourceClass.simpleName.asString()}.$funcName"
                if (!funcName.contains('.')) importHandler.addImport(config.sourceClass.qualifiedName!!.asString())
                "$qualifiedFunc(this.$sourcePropName)"
            }
            return "$targetPropName = $mapperCall"
        }

        val sourceNullable = sourcePropType.nullability == Nullability.NULLABLE
        val accessPrefix = "this.$sourcePropName"
        val sourcePropClassDecl = sourcePropType.declaration as? KSClassDeclaration

        return when {
            sourcePropType.isList() || sourcePropType.isArray() -> {
                val argType = sourcePropType.arguments.first().type!!.resolve()
                val argClassDecl = argType.declaration as? KSClassDeclaration
                val needsItemMapping = argClassDecl?.hasAnnotation("AutoMapper") == true

                if (argClassDecl?.isCustomDataClass() == true && !needsItemMapping) {
                    throw IllegalArgumentException(
                        "Error on property '$sourcePropName': The list contains items of type '${argClassDecl.simpleName.asString()}', " +
                                "which is a custom class but is not annotated with @AutoMapper. Please annotate it or use a custom mapper."
                    )
                }

                if (!needsItemMapping) return "$targetPropName = $accessPrefix"

                val nullSafeOp = if (sourceNullable) "?." else "."
                val mapTransform = "map { it.toSource() }"
                val arraySuffix = if (sourcePropType.isArray()) ".toTypedArray()" else ""
                "$targetPropName = $accessPrefix$nullSafeOp$mapTransform$arraySuffix"
            }
            sourcePropClassDecl?.hasAnnotation("AutoMapper") == true -> {
                val nullSafeOp = if (sourceNullable) "?." else "."
                "$targetPropName = $accessPrefix${nullSafeOp}toSource()"
            }
            else -> {
                if (sourcePropClassDecl?.isCustomDataClass() == true) {
                    if (sourcePropType.isSameTypeAs(targetPropType)) return "$targetPropName = $accessPrefix"
                    throw IllegalArgumentException(
                        "Error on property '$sourcePropName': The type '${sourcePropClassDecl.simpleName.asString()}' is a custom class " +
                                "but is not annotated with @AutoMapper. The processor cannot map it automatically. " +
                                "Please add @AutoMapper to the class or use @AutoMapperCustom for this property."
                    )
                }

                if (!sourcePropType.isSameTypeAs(targetPropType)) {
                    throw IllegalArgumentException(
                        "Type Mismatch for property '$sourcePropName': " +
                                "Source type is '${sourcePropType.declaration.qualifiedName?.asString()}' but " +
                                "target type is '${targetPropType.declaration.qualifiedName?.asString()}'.\n" +
                                "To fix this, use @AutoMapperCustom to provide a manual conversion function."
                    )
                }
                "$targetPropName = $accessPrefix"
            }
        }
    }

    private fun generateReverseMappingLines(
        sourceProps: List<KSPropertyDeclaration>,
        targetProps: List<KSPropertyDeclaration>,
        config: MapperConfig,
        importHandler: ImportHandler,
        resolver: Resolver
    ): List<String> {
        validateNestedReverseSupport(sourceProps, config.sourceClass.simpleName.asString())

        return sourceProps.mapNotNull { sourceProp ->
            val sourcePropName = sourceProp.simpleName.asString()
            val mappedName = sourceProp.getMappedName()
            val targetProp = targetProps.firstOrNull { it.simpleName.asString() == mappedName }
                ?: return@mapNotNull null

            val sourcePropType = sourceProp.type.resolve()
            val targetPropType = targetProp.type.resolve()
            val isNullable = sourcePropType.nullability == Nullability.NULLABLE
            val nullSafeOp = if (isNullable) "?." else "."
            val accessPrefix = "this.${targetProp.simpleName.asString()}"

            val customMapper = sourceProp.getCustomMapperAnnotation()
            if (customMapper != null) {
                val (annotationName, _, reverseFuncName) = customMapper
                if (reverseFuncName.isNotEmpty()) {
                    validateCustomReverseMapper(
                        resolver,
                        config.sourceClass,
                        config.targetClass,
                        reverseFuncName,
                        targetProp,
                        sourcePropType,
                        annotationName
                    )
                    val mapperCall = if (annotationName == "AutoMapperCustomFromParent") {
                        "${config.sourceClass.simpleName.asString()}.$reverseFuncName(this)"
                    } else {
                        val qualifiedFunc =
                            if (reverseFuncName.contains('.')) reverseFuncName else "${config.sourceClass.simpleName.asString()}.$reverseFuncName"
                        if (!reverseFuncName.contains('.')) importHandler.addImport(config.sourceClass.qualifiedName!!.asString())
                        "$qualifiedFunc(this.${targetProp.simpleName.asString()})"
                    }
                    return@mapNotNull "$sourcePropName = $mapperCall"
                }
                if (!sourcePropType.isSameTypeAs(targetPropType)) {
                    throw IllegalArgumentException(
                        "@AutoMapperCustom error on '${config.sourceClass.simpleName.asString()}.${sourceProp.simpleName.asString()}': " +
                                "A custom forward mapper was provided, but the types are different. You must provide a 'reverseMapperFunction' for reverse mapping."
                    )
                }
            }

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
                    if (mapLogic.isNotEmpty()) "$accessPrefix$nullSafeOp$mapLogic$arraySuffix" else accessPrefix
                }
                (sourcePropType.declaration as? KSClassDeclaration)?.isCustomDataClass() == true -> "$accessPrefix${nullSafeOp}toOriginal()"
                else -> accessPrefix
            }
            "$sourcePropName = $assignment"
        }
    }

    private fun buildFileContent(
        config: MapperConfig,
        importHandler: ImportHandler,
        forwardLines: List<String>,
        reverseLines: List<String>?
    ): String {
        return buildString {
            if (config.optInAnnotationString.isNotBlank()) {
                appendLine(config.optInAnnotationString)
                appendLine()
            }
            appendLine("package ${config.packageName}")
            appendLine()
            appendLine(importHandler.getImportStatements())
            appendLine("// Auto-generated by AutoMapper KSP. Do not edit!")
            appendLine("${config.visibilityModifier} fun ${config.sourceClass.simpleName.asString()}.toSource(): ${config.targetClass.simpleName.asString()} {")
            appendLine("    return ${config.targetClass.simpleName.asString()}(")
            appendLine(forwardLines.joinToString(separator = ",\n") { "        $it" })
            appendLine("    )")
            appendLine("}")

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

    private fun writeFile(
        packageName: String,
        fileName: String,
        sourceClass: KSClassDeclaration,
        content: String
    ) {
        val file: OutputStream = codeGen.createNewFile(
            Dependencies(false, sourceClass.containingFile!!),
            packageName,
            fileName
        )
        file.write(content.toByteArray())
    }
}
