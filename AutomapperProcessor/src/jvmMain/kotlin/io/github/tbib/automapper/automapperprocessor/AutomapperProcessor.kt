package io.github.tbib.automapper.automapperprocessor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
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
        val annotated = resolver.getSymbolsWithAnnotation(AutoMapper::class.qualifiedName ?: "")
            .filterIsInstance<KSClassDeclaration>()

        annotated.forEach { classDecl ->
            try {
                generateMapperFor(classDecl)
            } catch (e: Exception) {
                logger.error(
                    "Failed to generate mapper for ${classDecl.simpleName.asString()}: ${e.message}",
                    classDecl
                )
                e.printStackTrace()
                throw e  // تعيد رمي الاستثناء لتتوقف العملية مع ظهور الخطأ الكامل
            }
        }

        return emptyList()
    }

    private fun generateMapperFor(sourceClass: KSClassDeclaration) {
        val autoMapperAnnotation =
            sourceClass.annotations.firstOrNull { it.shortName.asString() == "AutoMapper" }
                ?: throw IllegalArgumentException("@AutoMapper annotation not found on class ${sourceClass.simpleName.asString()}")

        val forcePublic = autoMapperAnnotation.arguments
            .firstOrNull { it.name?.asString() == "forcePublic" }
            ?.value as? Boolean ?: false

        val visibilityModifier = when {
            forcePublic -> "public"
            autoMapperVisibility -> "public"
            else -> "internal"
        }

        val defaultValuesArg = autoMapperAnnotation.arguments
            .firstOrNull { it.name?.asString() == "defaultValues" }
            ?.value as? List<*>

        val defaultValuesMap: Map<String, String> = defaultValuesArg
            ?.mapNotNull { anno ->
                val defValAnno = anno as? KSAnnotation
                val key =
                    defValAnno?.arguments?.firstOrNull { it.name?.asString() == "key" }?.value as? String
                val value =
                    defValAnno?.arguments?.firstOrNull { it.name?.asString() == "value" }?.value?.toString()
                if (key != null && value != null) key to value else null
            }
            ?.toMap() ?: emptyMap()

        val reverseEnabled = autoMapperAnnotation.arguments
            .firstOrNull { it.name?.asString() == "reverse" }
            ?.value as? Boolean ?: false

        val ignoreKeys = (autoMapperAnnotation.arguments
            .firstOrNull { it.name?.asString() == "ignoreKeys" }?.value as? List<String>)
            ?.toSet() ?: emptySet()

        val hasCustomMapping = sourceClass.getAllProperties().any { prop ->
            val found = prop.annotations.any { it.shortName.asString() == "AutoMapperCustom" }
            if (found) {
                logger.warn("Property ${prop.simpleName.asString()} has @AutoMapperCustom annotation")
            }
            found
        }

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

        val targetProps = targetClassDecl.getAllProperties()
            .filter { it.simpleName.asString() in primaryConstructorValProps }
            .toList()

        val targetPropNames = targetProps.map { it.simpleName.asString() }.toSet()
        val sourcePropNames = sourceProps.map { prop ->
            val ann = prop.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
            ann?.arguments?.firstOrNull()?.value as? String ?: prop.simpleName.asString()
        }.toSet()

        val missingKeys = targetPropNames.filter { it !in sourcePropNames && it !in ignoreKeys }
        val missingKeysWithoutDefault = missingKeys.filter { it !in defaultValuesMap.keys }

        if (missingKeysWithoutDefault.isNotEmpty()) {
            throw IllegalArgumentException(
                "Source class '$sourceName' is missing the following properties required by target class '$targetName': ${
                    missingKeysWithoutDefault.joinToString(", ")
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

        // إضافة imports من defaultValuesMap إذا كانت القيمة تحتوي على اسم كلاس
        defaultValuesMap.values.forEach { defVal ->
            val regex = """([A-Za-z_][A-Za-z0-9_]*)\(""".toRegex()
            val match = regex.find(defVal)
            if (match != null) {
                val className = match.groupValues[1]
                // ابحث في targetClassDecl أو sourceClass عن Declaration بهذا الاسم
                val classDecl = targetClassDecl.getAllProperties()
                    .mapNotNull { it.type.resolve().declaration as? KSClassDeclaration }
                    .firstOrNull { it.simpleName.asString() == className }
                if (classDecl != null) {
                    val qualified = classDecl.qualifiedName?.asString()
                    if (qualified != null) importsSet.add(qualified)
                }
            }
        }

        // إضافة import للـ classes الرئيسية
        if (pkg != targetPackage) {
            targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
        }
        if (pkg != sourceClass.packageName.asString()) {
            sourceClass.qualifiedName?.asString()?.let { importsSet.add(it) }
        }

        val optInsFromAutoMapper = autoMapperAnnotation.arguments
            .firstOrNull { it.name?.asString() == "optIns" }?.value as? List<String> ?: emptyList()
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

        // ======================
        // توليد forwardLines من خصائص targetProps
        // ======================
        val forwardLines = targetProps.map { targetProp ->
            val targetPropName = targetProp.simpleName.asString()

            if (targetPropName in ignoreKeys) {
                // Property is ignored, assign null
                "$targetPropName = null"
            } else {
                val sourceProp = sourceProps.firstOrNull { sp ->
                    val ann =
                        sp.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
                    val mappedName =
                        ann?.arguments?.firstOrNull()?.value as? String ?: sp.simpleName.asString()
                    mappedName == targetPropName
                }

                if (sourceProp != null) {
                    val sourcePropName = sourceProp.simpleName.asString()

                    val customMapperAnnotation = sourceProp.annotations.firstOrNull {
                        val name = it.shortName.asString()
                        name == "AutoMapperCustom" || name == "AutoMapperCustomFromParent"
                    }



                    val customMapperFuncNameRaw =
                        customMapperAnnotation?.arguments?.firstOrNull()?.value as? String

                    if (customMapperFuncNameRaw != null) {
                        val annotationName = customMapperAnnotation.shortName.asString()

                        val mapperCall = if (annotationName == "AutoMapperCustomFromParent") {
                            // الدالة في الكلاس الأب (this) تأخذ الخاصية كوسيط
                            "$sourceClass.$customMapperFuncNameRaw(this)"
                        } else {
                            // الدالة static داخل الكلاس المصدر
                            val customMapperFuncQualified =
                                if (customMapperFuncNameRaw.contains('.')) {
                                    customMapperFuncNameRaw
                                } else {
                                    "$sourceName.$customMapperFuncNameRaw"
                                }
                            "$customMapperFuncQualified(this.$sourcePropName)"
                        }

                        // أضف import للكلاس المصدر لو لازم
                        val sourceQualifiedName = sourceClass.qualifiedName?.asString()
                        val sourcePackage = sourceClass.packageName.asString()
                        if (sourceQualifiedName != null && sourcePackage != pkg) {
                            importsSet.add(sourceQualifiedName)
                        }
                        "$targetPropName = $mapperCall"
                    } else {
                        try {
                            val sourcePropType = sourceProp.type.resolve()
                            val sourceNullable = sourcePropType.nullability == Nullability.NULLABLE

                            val targetPropType = targetProp.type.resolve()
                            val targetNullable = targetPropType.nullability == Nullability.NULLABLE

                            val hasDefaultValue = defaultValuesMap.containsKey(targetPropName)

                            checkNullabilityCompatibility(
                                sourceName,
                                sourcePropName,
                                sourceNullable,
                                targetNullable,
                                hasDefaultValue
                            )

                            when {
                                sourcePropType.isListType() -> {
                                    // تحقق وجود @AutoMapper على عنصر القائمة
                                    val argType =
                                        sourcePropType.arguments.firstOrNull()?.type?.resolve()
                                            ?: throw IllegalStateException("Cannot resolve generic type argument for property '$sourcePropName' in class '$sourceName'")
                                    val argDecl = argType.declaration as? KSClassDeclaration
                                        ?: throw IllegalStateException("Generic argument of property '$sourcePropName' is not a class in '$sourceName'")

                                    val hasAutoMapper =
                                        if (isPrimitiveOrStandardType(argDecl.qualifiedName?.asString())) {
                                            true
                                        } else {
                                            argDecl.annotations.any { it.shortName.asString() == "AutoMapper" }
                                        }



                                    if (!hasAutoMapper) {
                                        throw IllegalArgumentException("Property '$sourcePropName' generic type '${argDecl.simpleName.asString()}' in class '$sourceName' is missing @AutoMapper annotation")
                                    }

                                    val mapTransform =
                                        if (hasAutoMapper && !isPrimitiveOrStandardType(argDecl.qualifiedName?.asString())) "it.toSource()" else "it"

                                    when {
                                        sourceNullable && hasDefaultValue -> {
                                            val defaultVal = defaultValuesMap[targetPropName]
                                            "$targetPropName = this.$sourcePropName?.map { $mapTransform } ?: $defaultVal"
                                        }

                                        sourceNullable -> {
                                            "$targetPropName = this.$sourcePropName?.map { $mapTransform }"
                                        }

                                        else -> {
                                            "$targetPropName = this.$sourcePropName.map { $mapTransform }"
                                        }
                                    }

                                }

                                sourcePropType.isArrayType() -> {
                                    // تحقق وجود @AutoMapper على عناصر المصفوفة
                                    val argType =
                                        sourcePropType.arguments.firstOrNull()?.type?.resolve()
                                            ?: throw IllegalStateException("Cannot resolve generic type argument for array property '$sourcePropName' in class '$sourceName'")
                                    val argDecl = argType.declaration as? KSClassDeclaration
                                        ?: throw IllegalStateException("Generic argument of array property '$sourcePropName' is not a class in '$sourceName'")
                                    val hasAutoMapper =
                                        argDecl.annotations.any { it.shortName.asString() == "AutoMapper" }
                                    if (!hasAutoMapper) {
                                        throw IllegalArgumentException("Array property '$sourcePropName' generic type '${argDecl.simpleName.asString()}' in class '$sourceName' is missing @AutoMapper annotation")
                                    }

                                    if (sourceNullable) {
                                        if (hasDefaultValue) {
                                            val defaultVal = defaultValuesMap[targetPropName]
                                            "$targetPropName = this.$sourcePropName?.map { it.toSource() }?.toTypedArray() ?: $defaultVal"
                                        } else {
                                            "$targetPropName = this.$sourcePropName?.map { it.toSource() }?.toTypedArray()"
                                        }
                                    } else {
                                        "$targetPropName = this.$sourcePropName.map { it.toSource() }.toTypedArray()"
                                    }
                                }

                                else -> {
                                    val typeDecl = sourcePropType.declaration as? KSClassDeclaration
                                    val qualifiedName = typeDecl?.qualifiedName?.asString()
                                    val hasAutoMapper =
                                        typeDecl?.annotations?.any { it.shortName.asString() == "AutoMapper" }
                                            ?: false
                                    val isPrimitiveOrStandard =
                                        isPrimitiveOrStandardType(qualifiedName)
                                    val isEnum = sourcePropType.isEnumType()

                                    if (hasAutoMapper) {
                                        if (sourceNullable && !targetNullable && hasDefaultValue) {
                                            val defaultVal = defaultValuesMap[targetPropName]
                                            "$targetPropName = this.$sourcePropName?.toSource() ?: $defaultVal"
                                        } else if (sourceNullable) {
                                            "$targetPropName = this.$sourcePropName?.toSource()"
                                        } else {
                                            "$targetPropName = this.$sourcePropName.toSource()"
                                        }
                                    } else if (isPrimitiveOrStandard || isEnum) {
                                        // نمرر مباشرة
                                        // تحقق تطابق النوع هنا أيضًا (اختياري إذا تريد صارم)
                                        if (!isSameTypeIgnoringNullability(
                                                sourcePropType,
                                                targetPropType
                                            )
                                        ) {
                                            throw IllegalArgumentException("Type mismatch for property '$sourcePropName' in class '$sourceName': source type '$qualifiedName' does not match target type '${targetPropType.declaration.qualifiedName?.asString()}'")
                                        }
                                        "$targetPropName = this.$sourcePropName"
                                    } else {
                                        // حالة كلاس مباشر بدون annotation ولا generic (يسمح بتمرير مباشر)
                                        if (typeDecl != null && sourcePropType.arguments.isEmpty()) {
                                            // تحقق صارم لنفس النوع
                                            if (sourcePropType != targetPropType) {
                                                throw IllegalArgumentException("Type mismatch for property '$sourcePropName' in class '$sourceName': source type '$qualifiedName' does not match target type '${targetPropType.declaration.qualifiedName?.asString()}'")
                                            }
                                            "$targetPropName = this.$sourcePropName"
                                        } else {
                                            throw IllegalArgumentException("Property '$sourcePropName' in class '$sourceName' is missing @AutoMapper annotation and is not primitive or enum")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            val message =
                                "Error processing property '$sourcePropName' in class '$sourceName': ${e.message}"
                            logger.error(message, sourceClass)
                            throw IllegalStateException(message, e)
                        }
                    }
                } else {
                    // إذا لم توجد الخاصية في المصدر، استخدم القيمة الافتراضية إن وجدت أو null
                    if (targetPropName in defaultValuesMap) {
                        val defVal = defaultValuesMap[targetPropName]
                        "$targetPropName = $defVal"
                    } else {
                        "$targetPropName = null"
                    }
                }
            }
        }


        val reverseLines = if (reverseEnabled) {
            // تحقق من أن كل النوعيات المتداخلة تحمل @AutoMapper(reverse = true)
            sourceClass.getAllProperties().forEach { prop ->
                val propName = prop.simpleName.asString()
                val propType = prop.type.resolve()
                val typeDecl = propType.declaration
                val qualifiedName = (typeDecl as? KSClassDeclaration)?.qualifiedName?.asString()

                if (qualifiedName != null &&
                    !qualifiedName.startsWith("kotlin.") &&
                    !qualifiedName.startsWith("java.")
                ) {
                    val autoMapperAnn =
                        (typeDecl as? KSClassDeclaration)?.annotations?.firstOrNull {
                            it.shortName.asString() == "AutoMapper"
                        }

                    if (autoMapperAnn == null) {
                        throw IllegalArgumentException("Property '$propName' type '$qualifiedName' is nested class without @AutoMapper annotation, required for reverse mapping.")
                    } else {
                        val nestedReverse =
                            autoMapperAnn.arguments.firstOrNull { it.name?.asString() == "reverse" }?.value as? Boolean
                                ?: false
                        if (!nestedReverse) {
                            throw IllegalArgumentException("Property '$propName' type '$qualifiedName' must have @AutoMapper(reverse = true) to support reverse mapping in parent class '${sourceClass.simpleName.asString()}'.")
                        }
                    }
                }
            }

            targetProps.mapNotNull { targetProp ->
                val targetPropName = targetProp.simpleName.asString()

                val sourceProp = sourceProps.firstOrNull { sp ->
                    val ann =
                        sp.annotations.firstOrNull { it.shortName.asString() == "AutoMapperName" }
                    val mapped =
                        ann?.arguments?.firstOrNull()?.value as? String ?: sp.simpleName.asString()
                    mapped == targetPropName
                } ?: return@mapNotNull null

                val sourcePropName = sourceProp.simpleName.asString()

                val customMapperAnnotation =
                    sourceProp.annotations.firstOrNull { it.shortName.asString() == "AutoMapperCustom" }
                val customMapperFuncNameRaw =
                    customMapperAnnotation?.arguments?.firstOrNull()?.value as? String

                if (customMapperFuncNameRaw != null) {
                    val withoutSuffix = if (customMapperFuncNameRaw.endsWith("Mapper")) {
                        customMapperFuncNameRaw.removeSuffix("Mapper")
                    } else customMapperFuncNameRaw
                    val reverseFunc =
                        "reverse" + withoutSuffix.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                    if (targetPackage != pkg) {
                        targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
                    }

                    "$sourcePropName = $targetName.$reverseFunc(this.$targetPropName)"
                } else {
                    val sourcePropType = sourceProp.type.resolve()
                    val isNullable = sourcePropType.nullability == Nullability.NULLABLE
                    val typeDecl = sourcePropType.declaration
                    val qualifiedName = typeDecl.qualifiedName?.asString()

                    if (sourcePropType.isEnumType()) {
                        "$sourcePropName = this.$targetPropName"
                    } else when {
                        sourcePropType.isListType() -> {
                            val argType = sourcePropType.arguments.firstOrNull()?.type?.resolve()
                                ?: return@mapNotNull "$sourcePropName = this.$targetPropName"
                            val argDecl = argType.declaration
                            val isCustom = argDecl is KSClassDeclaration &&
                                    !(argDecl.qualifiedName?.asString()?.startsWith("kotlin.")
                                        ?: true) &&
                                    !argType.isEnumType()
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?." else "."}map { it.toOriginal() }"
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
                                        ?: true) &&
                                    !argType.isEnumType()
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?." else "."}map { it.toOriginal() }.toTypedArray()"
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
                                        ?: true) &&
                                    !valType.isEnumType()
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?." else "."}mapValues { it.value.toOriginal() }"
                            } else {
                                "$sourcePropName = this.$targetPropName"
                            }
                        }

                        else -> {
                            val isCustom = typeDecl is KSClassDeclaration &&
                                    !qualifiedName.isNullOrBlank() &&
                                    !qualifiedName.startsWith("kotlin.") &&
                                    !qualifiedName.startsWith("java.") &&
                                    !sourcePropType.isEnumType()
                            if (isCustom) {
                                "$sourcePropName = this.$targetPropName${if (isNullable) "?." else "."}toOriginal()"
                            } else {
                                "$sourcePropName = this.$targetPropName"
                            }
                        }
                    }
                }
            }.joinToString("\n")
        } else {
            ""
        }

        val imports = importsSet.sorted().joinToString("\n") { "import $it" }

        val containingFile = sourceClass.containingFile
            ?: throw IllegalStateException("Source class ${sourceName} does not have a containing file.")

        val file = codeGen.createNewFile(
            Dependencies(false, containingFile),
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
                appendLine()
            }
            appendLine("$visibilityModifier fun $sourceName.toSource(): $targetName {")
            appendLine("    return $targetName(")
            forwardLines.forEachIndexed { idx, l ->
                val comma = if (idx < forwardLines.lastIndex) "," else ""
                appendLine("        $l$comma")
            }
            appendLine("    )")
            appendLine("}")

            if (reverseEnabled) {
                appendLine()
                appendLine("$visibilityModifier fun $targetName.toOriginal(): $sourceName {")
                appendLine("    return $sourceName(")
                if (reverseLines.isNotEmpty()) {
                    reverseLines.lines().forEachIndexed { idx, l ->
                        val comma = if (idx < reverseLines.lines().lastIndex) "," else ""
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
        return declName == "kotlin.collections.List" ||
                declName == "kotlin.collections.MutableList" ||
                declName == "java.util.List"
    }

    private fun KSType.isArrayType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.Array"
    }

    private fun KSType.isMapType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.collections.Map" ||
                declName == "kotlin.collections.MutableMap" ||
                declName == "java.util.Map"
    }

    private fun KSType.isEnumType(): Boolean {
        val decl = this.declaration
        return decl is KSClassDeclaration && decl.classKind.name == "ENUM_CLASS"
    }

    private fun checkNullabilityCompatibility(
        className: String,
        propName: String,
        sourceNullable: Boolean,
        targetNullable: Boolean,
        hasDefaultValue: Boolean
    ) {
        if (sourceNullable && !targetNullable && !hasDefaultValue) {
            throw IllegalArgumentException(
                "Property '$propName' in source class '$className' is nullable but the corresponding property in target is non-nullable and no default value is provided."
            )
        }
    }

    private fun KSClassDeclaration.getPrimaryConstructorValProperties(): Set<String> {
        val ctor = this.primaryConstructor ?: return emptySet()
        return ctor.parameters.filter { it.isVal }.mapNotNull { it.name?.asString() }.toSet()
    }

    private fun isPrimitiveOrStandardType(qualifiedName: String?): Boolean {
        val standardTypes = setOf(
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Boolean",
            "kotlin.String",
            "kotlin.Int",
            "kotlin.Boolean",
        )
        return qualifiedName?.startsWith("kotlin.") == true || qualifiedName in standardTypes
    }


    private fun isSameTypeIgnoringNullability(type1: KSType, type2: KSType): Boolean {
        val decl1 = type1.declaration.qualifiedName?.asString()
        val decl2 = type2.declaration.qualifiedName?.asString()
        if (decl1 != decl2) return false

        // تحقق من عدد المعاملات النوعية
        if (type1.arguments.size != type2.arguments.size) return false

        for (i in type1.arguments.indices) {
            val arg1 = type1.arguments[i].type?.resolve() ?: return false
            val arg2 = type2.arguments[i].type?.resolve() ?: return false
            if (!isSameTypeIgnoringNullability(arg1, arg2)) return false
        }
        return true
    }

    private fun KSClassDeclaration.getConstructorProperties(): List<KSPropertyDeclaration> {
        val ctorParams = this.primaryConstructor?.parameters ?: return emptyList()
        val props = this.getAllProperties().toList()
        return ctorParams.mapNotNull { param ->
            if (param.isVal || param.isVar) {
                props.firstOrNull { it.simpleName.asString() == param.name?.asString() }
            } else null
        }
    }
}
