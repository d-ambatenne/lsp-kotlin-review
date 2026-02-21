fun greet(name: String): String {
    return "Hello, $name!"
}

fun main() {
    val x: String = 42  // ERROR: Type mismatch
    println(greet("World"))
}
