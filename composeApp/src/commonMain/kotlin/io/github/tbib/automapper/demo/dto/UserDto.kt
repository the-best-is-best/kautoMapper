package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.demo.model.UserModel

@AutoMapper(to = UserModel::class)
data class UserDto(
    val id: Int,
    val name: String,
    val address: AddressDto,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberDto>
)
