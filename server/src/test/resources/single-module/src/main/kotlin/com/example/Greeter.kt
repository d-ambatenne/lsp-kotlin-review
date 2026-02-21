package com.example

interface Greeter {
    fun greet(name: String): String
}

class SimpleGreeter : Greeter {
    override fun greet(name: String): String {
        return "Hello, $name!"
    }
}

class FormalGreeter : Greeter {
    override fun greet(name: String): String {
        return "Good day, $name."
    }
}
