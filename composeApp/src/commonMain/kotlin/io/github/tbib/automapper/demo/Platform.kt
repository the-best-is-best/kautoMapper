package io.github.tbib.automapper.demo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform