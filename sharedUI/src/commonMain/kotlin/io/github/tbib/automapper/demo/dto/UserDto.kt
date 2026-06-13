package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.automapperannotations.AutoMapperAddOptIns
import io.github.tbib.automapper.automapperannotations.AutoMapperName
import io.github.tbib.automapper.demo.Roles
import io.github.tbib.automapper.demo.Status
import io.github.tbib.automapper.demo.model.AddressModel
import io.github.tbib.automapper.demo.model.UserEntity
import io.github.tbib.automapper.demo.model.UserModel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@AutoMapper(to = UserModel::class, reverse = true, useClassNameInMapperFunc = true)
@AutoMapper(to = UserEntity::class, reverse = false)
@AutoMapperAddOptIns(["kotlin.time.ExperimentalTime"])
data class UserDto @OptIn(ExperimentalTime::class) constructor(
    val id: Int,
    val name: String,
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
        fun mapJoinDate(joinDate: String): LocalDateTime {
            return try {
                LocalDateTime.parse(joinDate)
            } catch (e: Exception) {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
        }

        @OptIn(ExperimentalTime::class)
        fun mapAddress(data: AddressDto): AddressModel {
            return AddressModel(
                id = data.id,
                streets = data.street
            )
        }

        fun reverseMapJoinDate(joinDate: LocalDateTime): String {
            return joinDate.toString()
        }

        fun mapJoinDateToUserEntity(joinDate: String): Long {
            return try {
                LocalDateTime.parse(joinDate).toInstant(TimeZone.UTC).toEpochMilliseconds()
            } catch (e: Exception) {
                0L
            }
        }

        fun reverseMapJoinDateToUserEntity(joinDate: Long): String {
            return try {
                ""
            } catch (e: Exception) {
                ""
            }
        }

        fun mapEmails(data: UserDto): List<String> {
            return listOf("email1", "email2")
        }
    }
}
