package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.automapperannotations.AutoMapperName
import io.github.tbib.automapper.demo.model.AddressModel

@AutoMapper(to = AddressModel::class)
data class AddressDto(
    val id: Int,
    @AutoMapperName("streets")
    val street: String?,
)
