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
            sourceClass.annotations.firstOrNull { it.shortName.asString() == "AddOptIns" }

        // قراءة النوع الهدف (to)
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

        // أسماء خصائص المصدر بعد إعادة التسمية (بمراعاة AutoMapperName)
        val sourcePropNames = sourceProps.map { prop ->
            val ann = prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
            ann?.arguments?.firstOrNull()?.value as? String ?: prop.simpleName.asString()
        }.toSet()

        val targetPropNames = targetProps.map { it.simpleName.asString() }.toSet()

        // تحقق الحقول المفقودة دفعة واحدة
        val missingKeys = mutableListOf<String>()
        targetPropNames.forEach { targetPropName ->
            if (!sourcePropNames.contains(targetPropName)) {
                missingKeys.add(targetPropName)
            }
        }
        if (missingKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Source class '$sourceName' is missing the following properties required by target class '$targetName': ${
                    missingKeys.joinToString(", ")
                }"
            )
        }

        // تحقق الحقول الزائدة في المصدر (تحذير فقط)
        sourcePropNames.forEach { sourcePropName ->
            if (!targetPropNames.contains(sourcePropName)) {
                logger.warn(
                    "Source property '$sourcePropName' in class '$sourceName' does not have a matching property in target class '$targetName'. This property will be ignored."
                )
            }
        }

        val mapperName = "${sourceName}Mapper"

        val importsSet = mutableSetOf<String>()

        // أضف import الهدف إذا مختلف الحزمة
        if (pkg != targetPackage) {
            targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
        }
        // أضف import المصدر لو مختلف الحزمة (عادة نفس الحزمة)
        if (pkg != sourceClass.packageName.asString()) {
            sourceClass.qualifiedName?.asString()?.let { importsSet.add(it) }
        }

        // استخراج optIns مع دعم AddOptIns بشكل آمن
        val optInsFromAutoMapper = extractStringsFromAnnotationValue(
            autoMapperAnnotation.arguments.find { it.name?.asString() == "optIns" }?.value
        )
        val optInsFromAddOptIns = extractStringsFromAnnotationValue(
            addOptInsAnnotation?.arguments?.firstOrNull()?.value
        )

        val combinedOptIns = (optInsFromAutoMapper + optInsFromAddOptIns).distinct()

        val optInAnnotations = mutableListOf<String>()
        combinedOptIns.forEach { annFullName ->
            if (annFullName.isNotBlank()) {
                optInAnnotations.add(annFullName.substringAfterLast('.')) // اسم الـ Annotation فقط
                importsSet.add(annFullName) // أضف import كامل
            }
        }

        val optInString = if (optInAnnotations.isNotEmpty()) {
            "@OptIn(" + optInAnnotations.joinToString(", ") { "$it::class" } + ")"
        } else ""

        // توليد المابنج لكل خاصية مع دعم أنواع مختلفة (List, Array, Map, Classes, nullable ...)
        val mappings = sourceProps.mapNotNull { prop ->
            val propName = prop.simpleName.asString()
            val autoMapperNameAnnotation =
                prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
            val targetPropName =
                autoMapperNameAnnotation?.arguments?.firstOrNull()?.value as? String ?: propName

            val targetProp = targetClassDecl.getAllProperties()
                .firstOrNull { it.simpleName.asString() == targetPropName }
            if (targetProp == null) {
                // تحذير وتجاهل
                logger.warn(
                    "Property '$targetPropName' in source class '$sourceName' does not exist in target class '$targetName', skipping mapping for this property."
                )
                return@mapNotNull null
            }

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
        }.joinToString()

        val imports = importsSet
            .sorted()
            .joinToString("\n") { "import $it" }

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
            // لتجنب الفواصل الزائدة: أضف فاصلة فقط بين العناصر
            val lines = mappings.lines()
            lines.forEachIndexed { index, line ->
                val comma = if (index < lines.lastIndex) "," else ""
                appendLine("            $line$comma")
            }
            appendLine("        )")
            appendLine("    }")
            appendLine("}")
        }

        file.write(content.toByteArray())
    }

    private fun extractStringsFromAnnotationValue(value: Any?): List<String> {
        return when (value) {
            is Array<*> -> value.mapNotNull { it?.toString() }
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> emptyList()
        }
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
