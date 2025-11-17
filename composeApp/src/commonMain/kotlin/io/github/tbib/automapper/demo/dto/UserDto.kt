package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.automapperannotations.AutoMapperAddOptIns
import io.github.tbib.automapper.automapperannotations.AutoMapperCustom
import io.github.tbib.automapper.demo.model.UserModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@AutoMapper(to = UserModel::class)
@AutoMapperAddOptIns(["kotlin.time.ExperimentalTime"])
data class UserDto(
    val id: Int,
    val name: String,
    @AutoMapperCustom("joinDateMapper")
    val joinDate: String,
    val address: AddressDto,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberDto>
) {
    @OptIn(ExperimentalTime::class)
    companion object {
        @OptIn(ExperimentalTime::class)
        fun joinDateMapper(joinDate: String): Instant {
            return try {
                Instant.parse(joinDate)
            } catch (e: Exception) {
                Clock.System.now()
            }
        }
    }
}
