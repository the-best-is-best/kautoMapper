package io.github.tbib.automapper.demo.dto

import io.github.tbib.automapper.automapperannotations.AutoMapper
import io.github.tbib.automapper.automapperannotations.AutoMapperAddOptIns
import io.github.tbib.automapper.automapperannotations.AutoMapperName
import io.github.tbib.automapper.automapperannotations.AutoMapperRequired
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
    @AutoMapperRequired
    val joinDate: String?,
    @AutoMapperName("addres")
    val address: AddressDto,
    val emails: List<String>,
    val phoneNumbers: List<PhoneNumberDto>,
    @AutoMapperName("roless")
    @AutoMapperName("roles", mapTo = UserEntity::class)
    val role: Roles,
    val status: Status
) {
    @OptIn(ExperimentalTime::class)
    companion object {

        // 1. mapTo[Type] convention - Used for any property mapping to LocalDateTime
        @OptIn(ExperimentalTime::class)
        fun mapFromStringToLocalDateTime(joinDate: String): LocalDateTime {
            return try {
                LocalDateTime.parse(joinDate)
            } catch (e: Exception) {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
        }


        // 2. mapTo[Type] convention - Used for AddressDto -> AddressModel
        @OptIn(ExperimentalTime::class)
        fun mapToAddressModel(data: AddressDto): AddressModel {
            return AddressModel(
                id = data.id,
                streets = data.street
            )
        }

        // 3. reverseMapFrom[Type] convention - Used for LocalDateTime -> String
        fun reverseMapFromLocalDateTimeToString(joinDate: LocalDateTime): String {
            return joinDate.toString()
        }

        // 4. Target-specific property mapping - Used for 'joinDate' when mapping to UserEntity
        fun mapJoinDateToUserEntity(joinDate: String?): Long? {
            return try {
                if (joinDate == null) return null
                LocalDateTime.parse(joinDate).toInstant(TimeZone.UTC).toEpochMilliseconds()
            } catch (e: Exception) {
                0L
            }
        }

        // 5. Target-specific reverse mapping - Used for mapping UserEntity back to UserDto
        fun reverseMapJoinDateToUserEntity(joinDate: Long): String {
            return try {
                ""
            } catch (e: Exception) {
                ""
            }
        }

        // 6. Parent-object mapping - Used for 'emails'
        fun mapEmails(data: UserDto): List<String> {
            return listOf("email1", "email2")
        }
    }
}
