package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.automapperannotations.AutoMapperAddOptIns
import io.github.tbib.automapper.automapperannotations.AutoMapperCustom
import io.github.tbib.automapper.automapperannotations.AutoMapperName
import io.github.tbib.automapper.demo.Roles
import io.github.tbib.automapper.demo.Status
import io.github.tbib.automapper.demo.model.UserModel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@AutoMapper(to = UserModel::class, reverse = true)
@AutoMapperAddOptIns(["kotlin.time.ExperimentalTime"])
data class UserDto @OptIn(ExperimentalTime::class) constructor(
    val id: Int,
    val name: String,
    @AutoMapperCustom("joinDateMapper", "joinDateReverseMapper")
    val joinDate: String,
    @AutoMapperName("addres")
    val address: AddressDto,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberDto>,
    val role: Roles,
    val status: Status
) {
    @OptIn(ExperimentalTime::class)
    companion object {
        @OptIn(ExperimentalTime::class)
        fun joinDateMapper(joinDate: String): LocalDateTime {
            return try {
                LocalDateTime.parse(joinDate)
            } catch (e: Exception) {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
        }

        fun joinDateReverseMapper(joinDate: LocalDateTime): String {
            return joinDate.toString()
        }
    }
}
