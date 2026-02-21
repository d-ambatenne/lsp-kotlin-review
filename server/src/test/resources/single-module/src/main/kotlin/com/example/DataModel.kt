package com.example

/**
 * A user in the system.
 * @property name The user's display name
 * @property age The user's age in years
 */
data class User(val name: String, val age: Int) {
    fun isAdult(): Boolean = age >= 18
}

enum class Role {
    ADMIN,
    USER,
    GUEST
}

typealias UserList = List<User>
