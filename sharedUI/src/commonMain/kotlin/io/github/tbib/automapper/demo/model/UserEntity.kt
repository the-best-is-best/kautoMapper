package io.github.tbib.automapper.demo.model

import io.github.tbib.automapper.demo.Roles
import io.github.tbib.automapper.demo.Status

data class UserEntity(
    val id: Int,
    val name: String,
    val joinDate: Long?,
    val roles: Roles,
    val status: Status
)
