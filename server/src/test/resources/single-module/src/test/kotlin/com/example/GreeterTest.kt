package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class GreeterTest {
    @Test
    fun `simple greeter says hello`() {
        val greeter = SimpleGreeter()
        assertEquals("Hello, World!", greeter.greet("World"))
    }
}
