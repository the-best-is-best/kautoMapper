package io.github.tbib.automapper.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.tbib.automapper.demo.dto.AddressDto
import io.github.tbib.automapper.demo.dto.PhoneNumberDto
import io.github.tbib.automapper.demo.dto.UserDto
import io.github.tbib.automapper.toOriginal
import io.github.tbib.automapper.toSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Composable
@Preview
fun App() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = {
                val userModel =
                    UserDto(
                        id = 1,
                        name = "test",
                        address = AddressDto(1, "test"),
                        emails = listOf("test", "test 2"),
                        phoneNumbers = listOf(
                            PhoneNumberDto(
                                id = 1,
                                number = "123456789",
                                cityCode = "123456789",
                                countryCode = "123456789",
                            )
                        ),
                        joinDate = ((Clock.System.now() - (300 * 12).days).toString()),
                        role = Roles.USER,
                        status = Status.ACTIVE
                    ).toSource()
                println("user data: ${userModel.joinDate}")
                println("user data reverse ${userModel.toOriginal()}")

            }) {
                Text("Click me!")
            }
        }
    }
}