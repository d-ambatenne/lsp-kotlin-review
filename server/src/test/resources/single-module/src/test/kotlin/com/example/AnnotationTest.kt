package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationTest {
    @Test
    fun `service has greeter`() {
        val service = ServiceA()
        service.greeter = SimpleGreeter()
        assertEquals("Hello, World!", service.greeter.greet("World"))
    }
}
