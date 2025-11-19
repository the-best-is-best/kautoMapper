package io.github.tbib.automapper.demo.model

import io.github.tbib.automapper.demo.Roles
import io.github.tbib.automapper.demo.Status
import kotlinx.datetime.LocalDateTime
import kotlin.time.ExperimentalTime

data class UserModel @OptIn(ExperimentalTime::class) constructor(
    val id: Int,
    val name: String,
    val joinDate: LocalDateTime,
    val addres: AddressModel,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberModel>,
    val role: Roles,
    val status: Status
)