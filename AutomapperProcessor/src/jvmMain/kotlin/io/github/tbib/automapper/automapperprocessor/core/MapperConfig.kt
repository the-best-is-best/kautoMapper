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
    val isReverseEnabled: Boolean,
    val visibilityModifier: String,
    val defaultValues: Map<String, String>,
    val ignoreKeys: Set<String>,
    val optInClasses: List<String> // Changed to hold the raw class names
) {
    val packageName: String = "io.github.tbib.automapper"
    val mapperName: String = "${sourceClass.simpleName.asString()}Mapper"
    val optInAnnotationString: String = optInClasses.toOptInString()


    companion object {
        fun from(sourceClass: KSClassDeclaration, autoMapperVisibility: Boolean): MapperConfig {
            val autoMapperAnnotation =
                sourceClass.annotations.first { it.shortName.asString() == "AutoMapper" }

            val targetClass = autoMapperAnnotation.getTargetClass()
            val forcePublic = autoMapperAnnotation.getArgument("forcePublic", false)
            val isReverseEnabled = autoMapperAnnotation.getArgument("reverse", false)
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
                val value =
                    anno.arguments.firstOrNull { it.name?.asString() == "value" }?.value?.toString()
                if (key != null && value != null) key to value else null
            }.toMap()

            val optInsClasses = getOptInClasses(sourceClass, autoMapperAnnotation)

            return MapperConfig(
                sourceClass = sourceClass,
                targetClass = targetClass,
                isReverseEnabled = isReverseEnabled,
                visibilityModifier = visibility,
                defaultValues = defaultValues,
                ignoreKeys = ignoreKeys,
                optInClasses = optInsClasses // Store the raw list
            )
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
        // --- THIS IS THE CORRECTED LOGIC ---
        // Directly add the fully qualified names from the config to the imports set.
        config.optInClasses.forEach { fqn ->
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

    fun addImportsFromDefaultValue(defaultValue: String, targetClass: KSClassDeclaration) {
        val className = defaultValue.substringBefore("(").substringBefore(".")
        if (className.isNotBlank() && className[0].isUpperCase()) {
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
