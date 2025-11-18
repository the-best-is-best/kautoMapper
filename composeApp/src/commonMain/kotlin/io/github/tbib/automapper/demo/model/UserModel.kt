package io.github.tbib.automapper.demo.model

import kotlin.time.ExperimentalTime

data class UserModel @OptIn(ExperimentalTime::class) constructor(
    val id: Int,
    val name: String,
    val joinDate: String,
    val addres: AddressModel,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberModel>
)