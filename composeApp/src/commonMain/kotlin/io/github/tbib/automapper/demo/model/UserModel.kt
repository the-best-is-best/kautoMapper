package io.github.tbib.automapper.demo.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class UserModel @OptIn(ExperimentalTime::class) constructor(
    val id: Int,
    val name: String,
    val joinDate: Instant,
    val address: AddressModel,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberModel>
)