package io.github.tbib.automapper.automapperannotations


import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapper(
    val to: KClass<*>,
    val optIns: Array<String> = []
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapperName(
    val to: String
)


@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AddOptIns(
    val value: Array<String>
)