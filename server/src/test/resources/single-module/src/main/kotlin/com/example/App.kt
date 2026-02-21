package com.example

fun main() {
    val greeter: Greeter = SimpleGreeter()
    println(greeter.greet("World"))

    // Intentional error: type mismatch
    val x: String = 42
}
