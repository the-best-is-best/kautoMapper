package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AddOptIns
import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.demo.model.UserModel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@AutoMapper(to = UserModel::class)
@AddOptIns(["kotlin.time.ExperimentalTime"])
data class UserDto @OptIn(ExperimentalTime::class) constructor(
    val id: Int,
    val name: String,
    val joinDate: Instant,
    val address: AddressDto,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberDto>
)
