package io.github.tbib.automapper.automapperprocessor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
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

        val annotation =
            sourceClass.annotations.first { it.shortName.asString() == "AutoMapper" }
        val targetType = annotation.arguments.first().value as KSType
        val targetClassDecl = targetType.declaration as KSClassDeclaration
        val targetName = targetClassDecl.simpleName.asString()
        val targetPackage = targetClassDecl.packageName.asString()

        val props = sourceClass.getAllProperties().toList()

        val mapperName = "${sourceName}Mapper"

        val importsSet = mutableSetOf<String>()

        if (pkg != targetPackage) {
            targetClassDecl.qualifiedName?.asString()?.let { importsSet.add(it) }
        }
        if (pkg != sourceClass.packageName.asString()) {
            sourceClass.qualifiedName?.asString()?.let { importsSet.add(it) }
        }

        val mappings = props.joinToString(",\n            ") { prop ->
            val propName = prop.simpleName.asString()
            val propType = prop.type.resolve()
            val typeDeclaration = propType.declaration
            val qualifiedName = typeDeclaration.qualifiedName?.asString()

            // دعم List<T> - إذا النوع List أو MutableList
            if (propType.isListType()) {
                // استخرج النوع الداخلي للـ List
                val listArgType = propType.arguments.firstOrNull()?.type?.resolve()
                if (listArgType != null) {
                    val listArgDecl = listArgType.declaration
                    val listArgQualifiedName = listArgDecl.qualifiedName?.asString()

                    val isCustomListItem = listArgDecl is KSClassDeclaration &&
                            !listArgQualifiedName.isNullOrBlank() &&
                            !listArgQualifiedName.startsWith("kotlin.") &&
                            !listArgQualifiedName.startsWith("java.")

                    if (isCustomListItem) {
                        // استدعي المابير الخاص بعناصر القائمة
                        val itemMapperName = "${listArgDecl.simpleName.asString()}Mapper"
                        val itemMapperQualifiedName =
                            "${listArgDecl.packageName.asString()}.$itemMapperName"
                        if (listArgDecl.packageName.asString() != pkg) {
                            importsSet.add(itemMapperQualifiedName)
                        }
                        // عالج الخاصية كالتالي: قائمة من العناصر مابير لكل عنصر
                        "$propName = source.$propName.map { $itemMapperName.map(it) }"
                    } else {
                        // نوع داخلي بسيط داخل القائمة، انسخ القائمة مباشرة
                        "$propName = source.$propName"
                    }
                } else {
                    // قائمة بدون نوع محدد، انسخ مباشرة
                    "$propName = source.$propName"
                }
            } else {
                // نوع مباشر (مش قائمة)
                val isCustomClass = typeDeclaration is KSClassDeclaration &&
                        !qualifiedName.isNullOrBlank() &&
                        !qualifiedName.startsWith("kotlin.") &&
                        !qualifiedName.startsWith("java.")

                if (isCustomClass) {
                    val propertyMapperName = "${typeDeclaration.simpleName.asString()}Mapper"
                    val propertyPackage = typeDeclaration.packageName.asString()
                    if (propertyPackage != pkg) {
                        importsSet.add("$propertyPackage.$propertyMapperName")
                    }
                    "$propName = $propertyMapperName.map(source.$propName)"
                } else {
                    "$propName = source.$propName"
                }
            }
        }

        val imports = importsSet
            .sorted()
            .joinToString("\n") { "import $it" }

        val file = codeGen.createNewFile(
            Dependencies(false, sourceClass.containingFile!!),
            pkg,
            mapperName
        )

        val content = """
        package $pkg

        $imports

        // Auto-generated by AutoMapper KSP
        object $mapperName {
            fun map(source: $sourceName): $targetName {
                return $targetName(
                    $mappings
                )
            }
        }
    """.trimIndent()

        file.write(content.toByteArray())
    }

    // إضافة دالة مساعدة للتحقق من نوع List
    private fun KSType.isListType(): Boolean {
        val declName = this.declaration.qualifiedName?.asString() ?: return false
        return declName == "kotlin.collections.List" || declName == "kotlin.collections.MutableList"
    }

}