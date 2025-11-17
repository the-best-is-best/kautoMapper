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
    private val env: SymbolProcessorEnvironment,
    private val autoMapperVisibility: Boolean = false
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
        val visibilityModifier = if (autoMapperVisibility) "public" else "internal"
        val autoMapperAnnotation =
            sourceClass.annotations.first { it.shortName.asString() == "AutoMapper" }

        val reverseEnabled =
            autoMapperAnnotation.arguments.find { it.name?.asString() == "reverse" }?.value as? Boolean
                ?: false

        // نبحث عن أي خاصية تحمل @AutoMapperCustom
        val hasCustomMapping = sourceClass.getAllProperties().any { prop ->
            prop.annotations.any { it.shortName.asString() == "AutoMapperCustom" }
        }

        // إذا وجدنا كلا الشرطين، نرمي استثناء
        if (reverseEnabled && hasCustomMapping) {
            throw IllegalArgumentException(
                "reverse=true with @AutoMapper does not support properties annotated with @AutoMapperCustom. Please disable reverse or remove @AutoMapperCustom."
            )

        }

        val pkg = "io.github.tbib.automapper"
        val sourceName = sourceClass.simpleName.asString()


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
        val primaryConstructorValProps = targetClassDecl.getPrimaryConstructorValProperties()

// نأخذ فقط خصائص الهدف اللي موجودة في الـ constructor
        val targetProps = targetClassDecl.getAllProperties()
            .filter { it.simpleName.asString() in primaryConstructorValProps }
            .toList()

        val targetPropNames = targetProps.map { it.simpleName.asString() }.toSet()
        val sourcePropNames = sourceProps.map { prop ->
            val ann = prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
            ann?.arguments?.firstOrNull()?.value as? String ?: prop.simpleName.asString()
        }.toSet()


        val missingKeys = targetPropNames.filter { it !in sourcePropNames }
        if (missingKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Source class '$sourceName' is missing the following properties required by target class '$targetName': ${
                    missingKeys.joinToString(", ")
                }"
            )
        }

        // تحذير عن خصائص في المصدر غير موجودة في الهدف
        sourcePropNames.filter { it !in targetPropNames }.forEach { propName ->
            logger.warn(
                "Source property '$propName' in class '$sourceName' does not have a matching property in target class '$targetName'. This property will be ignored."
            )
        }

        val mapperName = "${sourceName}Mapper"
        val importsSet = mutableSetOf<String>()

        // إذا الصنف الهدف في package مختلف نضيفه كاستيراد (ممكن يحتاجه المولد)
        if (pkg != targetPackage) {
            targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
        }
        if (pkg != sourceClass.packageName.asString()) {
            sourceClass.qualifiedName?.asString()?.let { importsSet.add(it) }
        }

        // OptIns
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

        // -----------------------
        // GENERATE FORWARD MAPPING
        // -----------------------
        val forwardLines = sourceProps.mapNotNull { prop ->
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

            // custom mapper annotation
            val customMapperAnnotation =
                prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperCustom" }
            val customMapperFuncNameRaw =
                customMapperAnnotation?.arguments?.firstOrNull()?.value as? String

            val customMapperFuncQualified = customMapperFuncNameRaw?.let { raw ->
                if (raw.contains('.')) raw else "$sourceName.$raw"
            }

            if (customMapperFuncQualified != null) {
                // import source if needed (to allow qualified companion call)
                val sourceQualifiedName = sourceClass.qualifiedName?.asString()
                val sourcePackage = sourceClass.packageName.asString()
                if (sourceQualifiedName != null && sourcePackage != pkg) {
                    importsSet.add(sourceQualifiedName)
                }
                "$targetPropName = $customMapperFuncQualified(this.$propName)"
            } else {
                // default mapping handling (primitive, list, array, map, custom classes)
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
                                ?: throw IllegalArgumentException("...")
                            val listArgDecl = listArgType.declaration
                            val listArgQualifiedName = listArgDecl.qualifiedName?.asString()
                            val isCustomListItem = listArgDecl is KSClassDeclaration &&
                                    !listArgQualifiedName.isNullOrBlank() &&
                                    !listArgQualifiedName.startsWith("kotlin.") &&
                                    !listArgQualifiedName.startsWith("java.")

                            if (isCustomListItem) {
                                if (isNullable) {
                                    "$targetPropName = this.$propName?.map { it.map() }"
                                } else {
                                    "$targetPropName = this.$propName.map { it.map() }"
                                }
                            } else {
                                "$targetPropName = this.$propName"
                            }
                        }
                        propType.isArrayType() -> {
                            val arrayArgType = propType.arguments.firstOrNull()?.type?.resolve()
                                ?: throw IllegalArgumentException("...")
                            val arrayArgDecl = arrayArgType.declaration
                            val arrayArgQualifiedName = arrayArgDecl.qualifiedName?.asString()
                            val isCustomArrayItem = arrayArgDecl is KSClassDeclaration &&
                                    !arrayArgQualifiedName.isNullOrBlank() &&
                                    !arrayArgQualifiedName.startsWith("kotlin.") &&
                                    !arrayArgQualifiedName.startsWith("java.")

                            if (isCustomArrayItem) {
                                if (isNullable) {
                                    "$targetPropName = this.$propName?.map { it.map() }?.toTypedArray()"
                                } else {
                                    "$targetPropName = this.$propName.map { it.map() }.toTypedArray()"
                                }
                            } else {
                                "$targetPropName = this.$propName"
                            }
                        }
                        propType.isMapType() -> {
                            val valueType = propType.arguments.getOrNull(1)?.type?.resolve()
                                ?: throw IllegalArgumentException("...")
                            val valDecl = valueType.declaration
                            val valQualified = valDecl.qualifiedName?.asString()
                            val isCustomValue = valDecl is KSClassDeclaration &&
                                    !(valQualified?.startsWith("kotlin.") ?: true) &&
                                    !(valQualified?.startsWith("java.") ?: true)

                            if (isCustomValue) {
                                if (isNullable) {
                                    "$targetPropName = this.$propName?.mapValues { it.value.map() }"
                                } else {
                                    "$targetPropName = this.$propName.mapValues { it.value.map() }"
                                }
                            } else {
                                "$targetPropName = this.$propName"
                            }
                        }
                        else -> {
                            val isCustomClass = typeDeclaration is KSClassDeclaration &&
                                    !qualifiedName.isNullOrBlank() &&
                                    !qualifiedName.startsWith("kotlin.") &&
                                    !qualifiedName.startsWith("java.")

                            if (isCustomClass) {
                                if (isNullable) {
                                    "$targetPropName = this.$propName?.map()"
                                } else {
                                    "$targetPropName = this.$propName.map()"
                                }
                            } else {
                                "$targetPropName = this.$propName"
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
        }

        // -----------------------
        // GENERATE REVERSE MAPPING (if requested)
        // -----------------------
        val reverseLines = if (reverseEnabled) {
            // iterate over targetProps (we map from target -> source)
            targetProps.mapNotNull { targetProp ->
                val targetPropName = targetProp.simpleName.asString()

                // find source property that maps to this targetProp (consider AutoMapperName on source)
                val sourceProp = sourceProps.firstOrNull { sp ->
                    val ann =
                        sp.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
                    val mapped =
                        ann?.arguments?.firstOrNull()?.value as? String ?: sp.simpleName.asString()
                    mapped == targetPropName
                } ?: return@mapNotNull null

                val sourcePropName = sourceProp.simpleName.asString()

                // check if this sourceProp had a custom mapper
                val customMapperAnnotation =
                    sourceProp.annotations.firstOrNull { it.shortName.asString() == "AutoMapperCustom" }
                val customMapperFuncNameRaw =
                    customMapperAnnotation?.arguments?.firstOrNull()?.value as? String

                if (customMapperFuncNameRaw != null) {
                    // produce reverse call: try to call targetClass.reverse<Capitalize(funcNameWithoutSuffixMapper)>(...)
                    val withoutSuffix = if (customMapperFuncNameRaw.endsWith("Mapper")) {
                        customMapperFuncNameRaw.removeSuffix("Mapper")
                    } else customMapperFuncNameRaw
                    val reverseFunc =
                        "reverse" + withoutSuffix.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    // ensure import for target class if in different package
                    if (targetPackage != pkg) {
                        targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
                    }
                    // call target's reverse helper (user must provide it in target class or companion)
                    "$sourcePropName = $targetName.$reverseFunc(this.$targetPropName)"
                } else {
                    // default reverse behaviour — BUT use the same mapper names as forward: base on SOURCE property declaration
                    val sourcePropType = sourceProp.type.resolve()
                    val isNullable = sourcePropType.nullability == Nullability.NULLABLE
                    val typeDecl = sourcePropType.declaration
                    val qualifiedName = typeDecl.qualifiedName?.asString()

                    when {
                        sourcePropType.isListType() -> {
                            val argType = sourcePropType.arguments.firstOrNull()?.type?.resolve()
                                ?: return@mapNotNull "$sourcePropName = this.$targetPropName"
                            val argDecl = argType.declaration
                            val isCustom = argDecl is KSClassDeclaration &&
                                    !(argDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true)
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?" else ""}.map { $mapperName.mapReverse(it) }"
                            } else {
                                "$sourcePropName = this.$targetPropName"
                            }
                        }

                        sourcePropType.isArrayType() -> {
                            val argType = sourcePropType.arguments.firstOrNull()?.type?.resolve()
                                ?: return@mapNotNull "$sourcePropName = this.$targetPropName"
                            val argDecl = argType.declaration
                            val isCustom = argDecl is KSClassDeclaration &&
                                    !(argDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true)
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?" else ""}.map { $mapperName.mapReverse(it) }.toTypedArray()"
                            } else {
                                "$sourcePropName = this.$targetPropName"
                            }
                        }

                        sourcePropType.isMapType() -> {
                            val valType = sourcePropType.arguments.getOrNull(1)?.type?.resolve()
                                ?: return@mapNotNull "$sourcePropName = this.$targetPropName"
                            val valDecl = valType.declaration
                            val isCustom = valDecl is KSClassDeclaration &&
                                    !(valDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true)
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?" else ""}.mapValues { $mapperName.mapReverse(it.value) }"
                            } else {
                                "$sourcePropName = this.$targetPropName"
                            }
                        }

                        else -> {
                            val isCustom = typeDecl is KSClassDeclaration &&
                                    !qualifiedName.isNullOrBlank() &&
                                    !qualifiedName.startsWith("kotlin.") &&
                                    !qualifiedName.startsWith("java.")
                            if (isCustom) {
                                // IMPORTANT: use the mapper named after the SOURCE property type (so it matches forward mapper)
                                "$sourcePropName = ${if (isNullable) "this.$targetPropName?.let { $mapperName.mapReverse(it) }" else "$mapperName.mapReverse(this.$targetPropName)"}"
                            } else {
                                "$sourcePropName = this.$targetPropName"
                            }
                        }
                    }
                }
            }.joinToString()
        } else {
            ""
        }

        // build imports string
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

            // Generate forward extension function
            appendLine("$visibilityModifier fun $sourceName.map(): $targetName {")
            appendLine("    return $targetName(")
            forwardLines.forEachIndexed { idx, l ->
                val comma = if (idx < forwardLines.lastIndex) "," else ""
                appendLine("        $l$comma")
            }
            appendLine("    )")
            appendLine("}")

            // Generate reverse extension function (if enabled)
            if (reverseEnabled) {
                appendLine()
                appendLine("$visibilityModifier fun $targetName.mapReverse(): $sourceName {")
                appendLine("    return $sourceName(")
                if (reverseLines.isNotEmpty()) {
                    val revLines = reverseLines.lines()
                    revLines.forEachIndexed { idx, l ->
                        val comma = if (idx < revLines.lastIndex) "," else ""
                        appendLine("        $l$comma")
                    }
                }
                appendLine("    )")
                appendLine("}")
            }
        }


        file.write(content.toByteArray())
    }

    private fun KSType.isListType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.collections.List" || declName == "kotlin.collections.MutableList" || declName == "java.util.List"
    }

    private fun KSType.isArrayType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.Array"
    }

    private fun KSType.isMapType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.collections.Map" || declName == "kotlin.collections.MutableMap" || declName == "java.util.Map"
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

    // دالة مساعدة للحصول على أسماء خصائص val الموجودة في primary constructor
    private fun KSClassDeclaration.getPrimaryConstructorValProperties(): Set<String> {
        val ctor = this.primaryConstructor ?: return emptySet()
        return ctor.parameters
            .filter { it.isVal } // فقط val، ليس var (عادة في data class كل parameters هي val)
            .mapNotNull { it.name?.asString() }
            .toSet()
    }

}
