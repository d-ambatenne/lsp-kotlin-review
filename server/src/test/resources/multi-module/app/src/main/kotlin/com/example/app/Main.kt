package com.example.app

import com.example.core.Entity

fun main() {
    val repo = InMemoryRepository()
    repo.save(Entity("1", "test", 100))

    // Intentional error: wrong type for value parameter
    val broken: String = repo.findAll()
}
