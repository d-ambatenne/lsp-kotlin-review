sealed class Shape {
    abstract fun area(): Double
}

data class Circle(val radius: Double) : Shape() {
    override fun area(): Double = Math.PI * radius * radius
}

data class Rectangle(val width: Double, val height: Double) : Shape() {
    override fun area(): Double = width * height
}

fun describeShape(shape: Shape): String = when (shape) {
    is Circle -> "Circle with radius ${shape.radius}"
    is Rectangle -> "Rectangle ${shape.width}x${shape.height}"
}
