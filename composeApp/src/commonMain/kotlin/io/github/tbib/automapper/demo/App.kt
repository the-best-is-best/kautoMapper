package io.github.tbib.automapper.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import automapper.composeapp.generated.resources.Res
import automapper.composeapp.generated.resources.compose_multiplatform
import io.github.tbib.automapper.demo.dto.AddressDto
import io.github.tbib.automapper.demo.dto.PhoneNumberDto
import io.github.tbib.automapper.demo.dto.UserDto
import io.github.tbib.automapper.demo.dto.UserDtoMapper
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
@Preview
fun App() {
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = {
                showContent = !showContent
                val userModel = UserDtoMapper.map(
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
                                listAnotherNumber = mapOf(
                                    1 to
                                            PhoneNumberDto(
                                                id = 2,
                                                number = "123456789",
                                                cityCode = "123456789",
                                                countryCode = "123456789",
                                                listAnotherNumber = mapOf()
                                            )
                                )
                            )
                        ),
                        joinDate = (Clock.System.now() - (300 * 12).days).toString()
                    )
                )
                println("user data: ${userModel.joinDate}")

            }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
        }
    }
}