package io.github.tbib.automapper.demo.model

data class UserModel(
    val id: Int,
    val name: String,
    val address: AddressModel,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberModel>
)