package io.github.tbib.automapper.automapperannotations


import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapper(
    val to: KClass<*>,
    val optIns: Array<String> = [],
    val reverse: Boolean = false

)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapperName(
    val to: String
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapperCustom(
    val mapperFunction: String
)


@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapperAddOptIns(
    val value: Array<String>
)