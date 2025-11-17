package io.github.tbib.automapper.automapperprocessor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import io.github.tbib.automapper.automapperannotations.AutoMapper

class AutoMapperProcessor(
    private val env: SymbolProcessorEnvironment
) : SymbolProcessor {

    private val logger = env.logger
    private val codeGen = env.codeGenerator

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotated =
            resolver.getSymbolsWithAnnotation(AutoMapper::class.qualifiedName!!)
                .filterIsInstance<KSClassDeclaration>()

        annotated.forEach { classDecl ->
            generateMapperFor(classDecl)
        }

        return emptyList()
    }

    private fun generateMapperFor(sourceClass: KSClassDeclaration) {
        val pkg = sourceClass.packageName.asString()
        val sourceName = sourceClass.simpleName.asString()

        val autoMapperAnnotation =
            sourceClass.annotations.first { it.shortName.asString() == "AutoMapper" }
        val addOptInsAnnotation =
            sourceClass.annotations.firstOrNull { it.shortName.asString() == "AutoMapperAddOptIns" }

        val targetType = try {
            autoMapperAnnotation.arguments.first().value as? KSType
                ?: throw IllegalArgumentException("Invalid 'to' parameter in @AutoMapper on class $sourceName")
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to read target type in @AutoMapper on class $sourceName: ${e.message}")
        }

        val targetClassDecl = targetType.declaration as? KSClassDeclaration
            ?: throw IllegalArgumentException("Target type in @AutoMapper on class $sourceName is not a class")

        val targetName = targetClassDecl.simpleName.asString()
        val targetPackage = targetClassDecl.packageName.asString()

        val sourceProps = sourceClass.getAllProperties().toList()
        val targetProps = targetClassDecl.getAllProperties().toList()

        val sourcePropNames = sourceProps.map { prop ->
            val ann = prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
            ann?.arguments?.firstOrNull()?.value as? String ?: prop.simpleName.asString()
        }.toSet()

        val targetPropNames = targetProps.map { it.simpleName.asString() }.toSet()

        val missingKeys = targetPropNames.filter { it !in sourcePropNames }
        if (missingKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Source class '$sourceName' is missing the following properties required by target class '$targetName': ${
                    missingKeys.joinToString(", ")
                }"
            )
        }

        sourcePropNames.filter { it !in targetPropNames }.forEach { propName ->
            logger.warn(
                "Source property '$propName' in class '$sourceName' does not have a matching property in target class '$targetName'. This property will be ignored."
            )
        }

        val mapperName = "${sourceName}Mapper"

        val importsSet = mutableSetOf<String>()

        if (pkg != targetPackage) {
            targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
        }
        if (pkg != sourceClass.packageName.asString()) {
            sourceClass.qualifiedName?.asString()?.let { importsSet.add(it) }
        }

        val optInsFromAutoMapper =
            autoMapperAnnotation.arguments.find { it.name?.asString() == "optIns" }?.value as? List<String>
                ?: emptyList()
        val optInsFromAddOptIns =
            addOptInsAnnotation?.arguments?.firstOrNull()?.value as? List<String> ?: emptyList()

        val combinedOptIns = (optInsFromAutoMapper + optInsFromAddOptIns).distinct()

        val optInAnnotations = mutableListOf<String>()
        combinedOptIns.forEach { annFullName ->
            if (annFullName.isNotBlank()) {
                optInAnnotations.add(annFullName.substringAfterLast('.'))
                importsSet.add(annFullName)
            }
        }

        val optInString = if (optInAnnotations.isNotEmpty()) {
            "@OptIn(" + optInAnnotations.joinToString(", ") { "$it::class" } + ")"
        } else ""

        val mappings = sourceProps.mapNotNull { prop ->
            val propName = prop.simpleName.asString()
            val autoMapperNameAnnotation =
                prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
            val targetPropName =
                autoMapperNameAnnotation?.arguments?.firstOrNull()?.value as? String ?: propName

            val targetProp = targetClassDecl.getAllProperties()
                .firstOrNull { it.simpleName.asString() == targetPropName }
            if (targetProp == null) {
                logger.warn("Property '$targetPropName' in source class '$sourceName' does not exist in target class '$targetName', skipping.")
                return@mapNotNull null
            }

            val customMapperAnnotation =
                prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperCustom" }
            val customMapperFuncNameRaw =
                customMapperAnnotation?.arguments?.firstOrNull()?.value as? String

            val customMapperFuncName = if (customMapperFuncNameRaw != null) {
                if (customMapperFuncNameRaw.contains('.')) {
                    // الدالة مكتوبة مع qualifier كامل، استخدمها كما هي
                    customMapperFuncNameRaw
                } else {
                    // الدالة بدون qualifier، أضف اسم الكلاس المصدر
                    "${sourceName}.${customMapperFuncNameRaw}"
                }
            } else null

            if (customMapperFuncName != null) {
                val sourceQualifiedName = sourceClass.qualifiedName?.asString()
                val sourcePackage = sourceClass.packageName.asString()
                if (sourceQualifiedName != null && sourcePackage != pkg) {
                    importsSet.add(sourceQualifiedName)
                }
                // generate code with qualifier
                "$targetPropName = $customMapperFuncName(source.$propName)"
            } else {
                // توليد المابنج الافتراضي (شبيه بالكود الأصلي مع دعم List, Array, Map, nullable...)
                try {
                    val sourcePropType = prop.type.resolve()
                    val sourceNullable = sourcePropType.nullability == Nullability.NULLABLE

                    val targetPropType = targetProp.type.resolve()
                    val targetNullable = targetPropType.nullability == Nullability.NULLABLE

                    checkNullabilityCompatibility(
                        sourceName,
                        propName,
                        sourceNullable,
                        targetNullable
                    )

                    val propType = sourcePropType
                    val isNullable = sourceNullable
                    val typeDeclaration = propType.declaration
                    val qualifiedName = typeDeclaration.qualifiedName?.asString()

                    when {
                        propType.isListType() -> {
                            val listArgType = propType.arguments.firstOrNull()?.type?.resolve()
                                ?: throw IllegalArgumentException("Property '$propName' in class $sourceName is List with unknown generic type")
                            val listArgDecl = listArgType.declaration
                            val listArgQualifiedName = listArgDecl.qualifiedName?.asString()

                            val isCustomListItem = listArgDecl is KSClassDeclaration &&
                                    !listArgQualifiedName.isNullOrBlank() &&
                                    !listArgQualifiedName.startsWith("kotlin.") &&
                                    !listArgQualifiedName.startsWith("java.")

                            if (isCustomListItem) {
                                val itemMapperName = "${listArgDecl.simpleName.asString()}Mapper"
                                val itemMapperQualifiedName =
                                    "${listArgDecl.packageName.asString()}.$itemMapperName"
                                if (listArgDecl.packageName.asString() != pkg) {
                                    importsSet.add(itemMapperQualifiedName)
                                }
                                if (isNullable) {
                                    "$targetPropName = source.$propName?.map { $itemMapperName.map(it) }"
                                } else {
                                    "$targetPropName = source.$propName.map { $itemMapperName.map(it) }"
                                }
                            } else {
                                "$targetPropName = source.$propName"
                            }
                        }

                        propType.isArrayType() -> {
                            val arrayArgType = propType.arguments.firstOrNull()?.type?.resolve()
                                ?: throw IllegalArgumentException("Property '$propName' in class $sourceName is Array with unknown generic type")
                            val arrayArgDecl = arrayArgType.declaration
                            val arrayArgQualifiedName = arrayArgDecl.qualifiedName?.asString()

                            val isCustomArrayItem = arrayArgDecl is KSClassDeclaration &&
                                    !arrayArgQualifiedName.isNullOrBlank() &&
                                    !arrayArgQualifiedName.startsWith("kotlin.") &&
                                    !arrayArgQualifiedName.startsWith("java.")

                            if (isCustomArrayItem) {
                                val itemMapperName = "${arrayArgDecl.simpleName.asString()}Mapper"
                                val itemMapperQualifiedName =
                                    "${arrayArgDecl.packageName.asString()}.$itemMapperName"
                                if (arrayArgDecl.packageName.asString() != pkg) {
                                    importsSet.add(itemMapperQualifiedName)
                                }
                                if (isNullable) {
                                    "$targetPropName = source.$propName?.map { $itemMapperName.map(it) }?.toTypedArray()"
                                } else {
                                    "$targetPropName = source.$propName.map { $itemMapperName.map(it) }.toTypedArray()"
                                }
                            } else {
                                "$targetPropName = source.$propName"
                            }
                        }

                        propType.isMapType() -> {
                            val valueType = propType.arguments.getOrNull(1)?.type?.resolve()
                                ?: throw IllegalArgumentException("Property '$propName' in class $sourceName is Map with unknown value type")

                            val valueQualifiedName = valueType.declaration.qualifiedName?.asString()

                            val isCustomValue = valueType.declaration is KSClassDeclaration &&
                                    !(valueQualifiedName?.startsWith("kotlin.") ?: true) &&
                                    !(valueQualifiedName?.startsWith("java.") ?: true)

                            if (isCustomValue) {
                                val valueDecl = valueType.declaration as KSClassDeclaration
                                val valueMapperName = "${valueDecl.simpleName.asString()}Mapper"
                                val valueMapperQualifiedName =
                                    "${valueDecl.packageName.asString()}.$valueMapperName"
                                if (valueDecl.packageName.asString() != pkg) {
                                    importsSet.add(valueMapperQualifiedName)
                                }
                                if (isNullable) {
                                    "$targetPropName = source.$propName?.mapValues { $valueMapperName.map(it.value) }"
                                } else {
                                    "$targetPropName = source.$propName.mapValues { $valueMapperName.map(it.value) }"
                                }
                            } else {
                                "$targetPropName = source.$propName"
                            }
                        }

                        else -> {
                            val isCustomClass = typeDeclaration is KSClassDeclaration &&
                                    !qualifiedName.isNullOrBlank() &&
                                    !qualifiedName.startsWith("kotlin.") &&
                                    !qualifiedName.startsWith("java.")

                            if (isCustomClass) {
                                val propertyMapperName =
                                    "${typeDeclaration.simpleName.asString()}Mapper"
                                val propertyPackage = typeDeclaration.packageName.asString()
                                if (propertyPackage != pkg) {
                                    importsSet.add("$propertyPackage.$propertyMapperName")
                                }
                                if (isNullable) {
                                    "$targetPropName = source.$propName?.let { $propertyMapperName.map(it) }"
                                } else {
                                    "$targetPropName = $propertyMapperName.map(source.$propName)"
                                }
                            } else {
                                "$targetPropName = source.$propName"
                            }
                        }
                    }
                } catch (e: Exception) {
                    val message =
                        "Error processing property '${prop.simpleName.asString()}' in class '$sourceName': ${e.message}"
                    logger.error(message, sourceClass)
                    throw IllegalStateException(message, e)
                }
            }
        }.joinToString()

        val imports = importsSet.sorted().joinToString("\n") { "import $it" }

        val file = codeGen.createNewFile(
            Dependencies(false, sourceClass.containingFile!!),
            pkg,
            mapperName
        )

        val content = buildString {
            appendLine("package $pkg")
            appendLine()
            if (imports.isNotEmpty()) {
                appendLine(imports)
                appendLine()
            }
            appendLine("// Auto-generated by AutoMapper KSP")
            if (optInString.isNotBlank()) {
                appendLine(optInString)
            }
            appendLine("object $mapperName {")
            appendLine("    fun map(source: $sourceName): $targetName {")
            appendLine("        return $targetName(")
            val lines = mappings.lines()
            lines.forEachIndexed { index, line ->
                val comma = if (index < lines.lastIndex) "," else ""
                appendLine("            $line$comma")
            }
            appendLine("        )")
            appendLine("    }")

            // =====================
//   REVERSE MAPPING
// =====================
            val reverseEnabled =
                autoMapperAnnotation.arguments
                    .find { it.name?.asString() == "reverse" }
                    ?.value as? Boolean ?: false

            if (reverseEnabled) {

                val reverseMappings = targetProps.mapNotNull { prop ->
                    val propName = prop.simpleName.asString()

                    val sourceProp = sourceProps.firstOrNull {
                        val ann =
                            it.annotations.firstOrNull { a -> a.shortName.asString() == "AutoMapperName" }
                        val mappedName = ann?.arguments?.firstOrNull()?.value as? String
                            ?: it.simpleName.asString()
                        mappedName == propName
                    } ?: return@mapNotNull null

                    val sourcePropName = sourceProp.simpleName.asString()
                    val sourcePropType = sourceProp.type.resolve()
                    val typeDecl = sourcePropType.declaration
                    val qualifiedName = typeDecl.qualifiedName?.asString()

                    when {
                        sourcePropType.isListType() -> {
                            val argType = sourcePropType.arguments.first().type!!.resolve()
                            val argDecl = argType.declaration
                            val isCustom = argDecl is KSClassDeclaration &&
                                    !(argDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true)

                            if (isCustom) {
                                val mapperName = "${argDecl.simpleName.asString()}Mapper"
                                "$sourcePropName = source.$propName.map { $mapperName.mapReverse(it) }"
                            } else {
                                "$sourcePropName = source.$propName"
                            }
                        }

                        sourcePropType.isArrayType() -> {
                            val argType = sourcePropType.arguments.first().type!!.resolve()
                            val argDecl = argType.declaration
                            val isCustom = argDecl is KSClassDeclaration &&
                                    !(argDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true)

                            if (isCustom) {
                                val mapperName = "${argDecl.simpleName.asString()}Mapper"
                                "$sourcePropName = source.$propName.map { $mapperName.mapReverse(it) }.toTypedArray()"
                            } else {
                                "$sourcePropName = source.$propName"
                            }
                        }

                        sourcePropType.isMapType() -> {
                            val valType = sourcePropType.arguments[1].type!!.resolve()
                            val valDecl = valType.declaration
                            val isCustom = valDecl is KSClassDeclaration &&
                                    !(valDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true)

                            if (isCustom) {
                                val mapperName = "${valDecl.simpleName.asString()}Mapper"
                                "$sourcePropName = source.$propName.mapValues { $mapperName.mapReverse(it.value) }"
                            } else {
                                "$sourcePropName = source.$propName"
                            }
                        }

                        else -> {
                            val isCustom = typeDecl is KSClassDeclaration &&
                                    !qualifiedName.isNullOrBlank() &&
                                    !qualifiedName.startsWith("kotlin.") &&
                                    !qualifiedName.startsWith("java.")

                            if (isCustom) {
                                val mapperName = "${typeDecl.simpleName.asString()}Mapper"
                                "$sourcePropName = $mapperName.mapReverse(source.$propName)"
                            } else {
                                "$sourcePropName = source.$propName"
                            }
                        }
                    }
                }.joinToString(",")

                appendLine()
                appendLine("    fun mapReverse(source: $targetName): $sourceName {")
                appendLine("        return $sourceName(")
                appendLine("            $reverseMappings")
                appendLine("        )")
                appendLine("    }")
            }

            appendLine("}")


        }



        file.write(content.toByteArray())
    }

    private fun KSType.isListType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.collections.List" || declName == "kotlin.collections.MutableList"
    }

    private fun KSType.isArrayType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.Array"
    }

    private fun KSType.isMapType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.collections.Map" || declName == "kotlin.collections.MutableMap"
    }

    private fun checkNullabilityCompatibility(
        className: String,
        propName: String,
        sourceNullable: Boolean,
        targetNullable: Boolean
    ) {
        if (sourceNullable && !targetNullable) {
            throw IllegalArgumentException(
                "Property '$propName' in source class '$className' is nullable but the corresponding property in target is non-nullable. This will cause runtime exceptions."
            )
        }
    }
}
