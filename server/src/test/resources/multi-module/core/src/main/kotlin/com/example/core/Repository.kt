package com.example.core

interface Repository<T> {
    fun findById(id: String): T?
    fun findAll(): List<T>
    fun save(item: T): T
    fun delete(id: String)
}

data class Entity(
    val id: String,
    val name: String,
    val value: Int
)
