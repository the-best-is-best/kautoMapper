package io.github.tbib.automapper.automapperprocessor.core

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.github.tbib.automapper.automapperprocessor.extensions.getArgument
import io.github.tbib.automapper.automapperprocessor.extensions.getTargetClass
import io.github.tbib.automapper.automapperprocessor.extensions.toOptInString

/**
 * A data class to hold all configuration extracted from the @AutoMapper annotation.
 */
internal data class MapperConfig(
    val sourceClass: KSClassDeclaration,
    val targetClass: KSClassDeclaration,
    val useClassNameInMapperFunc: Boolean,
    val isReverseEnabled: Boolean,
    val visibilityModifier: String,
    val defaultValues: Map<String, String>,
    val ignoreKeys: Set<String>,
    val optInClasses: List<String>, // Changed to hold the raw class names
    val manualImports: List<String>
) {
    val packageName: String = "io.github.tbib.automapper"
    val mapperName: String =
        "${sourceClass.simpleName.asString()}To${targetClass.simpleName.asString()}Mapper"
    val optInAnnotationString: String = optInClasses.toOptInString()

    val mapFunctionName: String =
        if (useClassNameInMapperFunc) "to${targetClass.simpleName.asString()}" else "toTarget"
    val reverseMapFunctionName: String =
        if (useClassNameInMapperFunc) "to${sourceClass.simpleName.asString()}" else "toSource"


    companion object {
        fun from(
            sourceClass: KSClassDeclaration,
            autoMapperAnnotation: KSAnnotation,
            autoMapperVisibility: Boolean,
            isMulti: Boolean = false
        ): MapperConfig {
            val targetClass = autoMapperAnnotation.getTargetClass()
            val forcePublic = autoMapperAnnotation.getArgument("forcePublic", false)
            val isReverseEnabled = autoMapperAnnotation.getArgument("reverse", false)
            val useClassNameInMapperFunc =
                autoMapperAnnotation.getArgument("useClassNameInMapperFunc", isMulti)
            val ignoreKeys =
                autoMapperAnnotation.getArgument<List<String>>("ignoreKeys", emptyList()).toSet()

            val visibility = when {
                forcePublic || autoMapperVisibility -> "public"
                else -> "internal"
            }

            val rawValue = autoMapperAnnotation.arguments
                .firstOrNull { it.name?.asString() == "defaultValues" }
                ?.value

            val defaultValuesList =
                if (rawValue is List<*> && rawValue.all { it is KSAnnotation }) {
                    @Suppress("UNCHECKED_CAST")
                    rawValue as List<KSAnnotation>
                } else {
                    emptyList()
                }

            val defaultValues = defaultValuesList.mapNotNull { anno ->
                val key = anno.getArgument<String?>("key", null)
                val value = anno.getArgument<String>("value", "")
                if (key != null) key to value else null
            }.toMap()

            val optInsClasses = getOptInClasses(sourceClass, autoMapperAnnotation)
            val manualImports = getManualImports(sourceClass)

            return MapperConfig(
                sourceClass = sourceClass,
                targetClass = targetClass,
                useClassNameInMapperFunc = useClassNameInMapperFunc,
                isReverseEnabled = isReverseEnabled,
                visibilityModifier = visibility,
                defaultValues = defaultValues,
                ignoreKeys = ignoreKeys,
                optInClasses = optInsClasses, // Store the raw list
                manualImports = manualImports
            )
        }

        private fun getManualImports(sourceClass: KSClassDeclaration): List<String> {
            val addImportAnnotation =
                sourceClass.annotations.firstOrNull { it.shortName.asString() == "AutoMapperAddImport" }
            return addImportAnnotation?.getArgument<List<String>>("value", emptyList())
                ?: emptyList()
        }

        private fun getOptInClasses(
            sourceClass: KSClassDeclaration,
            autoMapperAnnotation: KSAnnotation
        ): List<String> {
            val addOptInsAnnotation =
                sourceClass.annotations.firstOrNull { it.shortName.asString() == "AutoMapperAddOptIns" }

            val optInsFromAutoMapper =
                autoMapperAnnotation.getArgument<List<String>>("optIns", emptyList())
            val optInsFromAddOptIns =
                addOptInsAnnotation?.getArgument<List<String>>("value", emptyList()) ?: emptyList()

            return (optInsFromAutoMapper + optInsFromAddOptIns).distinct()
        }
    }
}

/**
 * Manages the collection and formatting of import statements for the generated file.
 */
internal class ImportHandler(
    private val config: MapperConfig,
    private val sourceClass: KSClassDeclaration,
) {
    private val imports = mutableSetOf<String>()

    init {
        if (config.packageName != config.targetClass.packageName.asString()) {
            addImport(config.targetClass.qualifiedName!!.asString())
        }
        if (config.packageName != sourceClass.packageName.asString()) {
            addImport(sourceClass.qualifiedName!!.asString())
        }
        // Directly add the fully qualified names from the config to the imports set.
        config.optInClasses.forEach { fqn ->
            addImport(fqn)
        }
        config.manualImports.forEach { fqn ->
            addImport(fqn)
        }
    }

    fun addImport(qualifiedName: String) {
        // Avoid importing from the same package
        if (qualifiedName.startsWith(config.packageName) && qualifiedName.count { it == '.' } == config.packageName.count { it == '.' }) {
            return
        }
        imports.add(qualifiedName)
    }

    fun addImportsFromDefaultValue(
        defaultValue: String,
        targetClass: KSClassDeclaration,
        targetPropName: String
    ) {
        val className = defaultValue.substringBefore("(").substringBefore(".")
        if (className.isNotBlank() && className[0].isUpperCase()) {
            // 1. Check if it matches the type of the target property itself
            val targetProp = targetClass.getAllProperties()
                .firstOrNull { it.simpleName.asString() == targetPropName }
            val targetPropType = targetProp?.type?.resolve()
            if (targetPropType?.declaration?.simpleName?.asString() == className) {
                val fqn = targetPropType.declaration.qualifiedName?.asString()
                if (fqn != null) {
                    addImport(fqn)
                    return
                }
            }

            // 2. Fallback to searching all properties in the target class
            val propTypeFqn = targetClass.getAllProperties()
                .firstOrNull { it.type.resolve().declaration.simpleName.asString() == className }
                ?.type?.resolve()?.declaration?.qualifiedName?.asString()

            if (propTypeFqn != null) {
                addImport(propTypeFqn)
            }
        }
    }

    fun getImportStatements(): String {
        return imports.sorted().joinToString("\n") { "import $it" }
    }
}
