package io.github.tbib.automapper.demo.model

data class PhoneNumberModel(
    val id: Long,
    val number: String,
    val cityCode: String,
    val countryCode: String,
    val listAnotherNumber: Map<Int, PhoneNumberModel>
)
