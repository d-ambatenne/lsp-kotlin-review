package com.example.common

expect fun platformName(): String

fun greet(): String = "Hello from ${platformName()}"
