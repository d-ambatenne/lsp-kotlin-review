package com.example

// Custom annotations for testing hover behavior

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MyComponent

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Named(val value: String)

// Single annotation on property
class ServiceA {
    @Inject
    lateinit var greeter: Greeter
}

// Stacked annotations on property
class ServiceB {
    @Named("formal")
    @Inject
    lateinit var greeter: Greeter
}

// Multi-line annotation on property
class ServiceC {
    @Named(
        value = "simple"
    )
    lateinit var greeter: Greeter
}

// Stacked annotations on class
@MyComponent
@Deprecated("Use ServiceB instead")
class ServiceD {
    lateinit var greeter: Greeter
}

// Annotation on function
class ServiceE {
    @Deprecated("Use newGreet instead")
    fun oldGreet(): String = "hello"
}

// Stacked annotation definitions
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Qualifier(val name: String)
