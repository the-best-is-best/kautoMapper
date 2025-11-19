package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.demo.model.PhoneNumberModel

@AutoMapper(to = PhoneNumberModel::class, reverse = true)
data class PhoneNumberDto(
    val id: Long,
    val number: String,
    val cityCode: String,
    val countryCode: String,


)
