package io.github.tbib.automapper.automapperannotations


import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class AutoMapper(
    val to: KClass<*>,
    val useClassNameInMapperFunc: Boolean = false,
    val optIns: Array<String> = [],
    val ignoreKeys: Array<String> = [],
    val forcePublic: Boolean = false,
    val defaultValues: Array<DefaultValue> = [],
    val reverse: Boolean = false

)

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DefaultValue(
    val key: String,
    val value: String
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class AutoMapperName(
    val to: String,
    val mapTo: KClass<*> = Any::class
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class AutoMapperRequired(
    val mapTo: KClass<*> = Any::class
)


@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapperAddOptIns(
    val value: Array<String>
)
