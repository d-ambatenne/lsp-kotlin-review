package com.example.app

import com.example.core.Entity
import com.example.core.Repository

class InMemoryRepository : Repository<Entity> {
    private val store = mutableMapOf<String, Entity>()

    override fun findById(id: String): Entity? = store[id]

    override fun findAll(): List<Entity> = store.values.toList()

    override fun save(item: Entity): Entity {
        store[item.id] = item
        return item
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}
