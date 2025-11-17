package io.github.tbib.automapper.automapperannotations


import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapper(
    val to: KClass<*>
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoMapperName(
    val to: String
)
