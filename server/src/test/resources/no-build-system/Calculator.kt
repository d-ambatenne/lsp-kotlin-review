interface Calculator {
    fun add(a: Int, b: Int): Int
    fun subtract(a: Int, b: Int): Int
}

class BasicCalculator : Calculator {
    override fun add(a: Int, b: Int): Int = a + b
    override fun subtract(a: Int, b: Int): Int = a - b
}

fun main() {
    val calc: Calculator = BasicCalculator()
    println(calc.add(2, 3))

    // Intentional error: unresolved reference
    println(calc.multiply(2, 3))
}
