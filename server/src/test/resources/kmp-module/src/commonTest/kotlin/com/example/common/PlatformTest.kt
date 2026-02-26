package com.example.common

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun greetContainsPlatform() {
        assertTrue(greet().startsWith("Hello"))
    }
}
