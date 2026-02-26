package dev.review.lsp.integration

import dev.review.lsp.compiler.SymbolKind
import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression tests for annotation-related hover issues:
 *
 * 1. Hover on annotated property showed "@Inject" instead of the declaration signature
 * 2. Stacked annotations (multi-line) caused incorrect signature extraction
 * 3. Test source files were not included in the analysis session
 * 4. Hover on annotation usage showed constructor "HiltAndroidTest()" instead of class info
 */
@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
class AnnotationHoverIntegrationTest {

    private lateinit var facade: AnalysisApiCompilerFacade
    private lateinit var annotationsKt: Path
    private lateinit var testFileKt: Path

    @BeforeAll
    fun setUp() {
        val model = TestFixtures.singleModule()
        facade = AnalysisApiCompilerFacade(model)
        val srcDir = model.modules[0].sourceRoots[0]
        annotationsKt = srcDir.resolve("com/example/Annotations.kt")
        val testDir = model.modules[0].testSourceRoots[0]
        testFileKt = testDir.resolve("com/example/AnnotationTest.kt")
    }

    @AfterAll
    fun tearDown() {
        facade.dispose()
    }

    // ========================================================================
    // Regression: Hover on annotated property showed annotation, not declaration
    // ========================================================================

    @Test
    fun `hover on single-annotated property shows declaration, not annotation`() {
        // Annotations.kt (0-based lines):
        //   line 18: @Inject
        //   line 19: lateinit var greeter: Greeter   — "greeter" at col 17
        val symbol = facade.resolveAtPosition(annotationsKt, 19, 17)
        assertNotNull(symbol, "Expected to resolve 'greeter' property")
        assertEquals("greeter", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature")
        assertTrue(
            symbol.signature!!.contains("lateinit var greeter"),
            "Signature should contain 'lateinit var greeter', got: ${symbol.signature}"
        )
        assertFalse(
            symbol.signature!!.startsWith("@"),
            "Signature should NOT start with annotation '@', got: ${symbol.signature}"
        )
    }

    @Test
    fun `hover on stacked-annotated property shows declaration, not annotation`() {
        // Annotations.kt (0-based lines):
        //   line 24: @Named("formal")
        //   line 25: @Inject
        //   line 26: lateinit var greeter: Greeter   — "greeter" at col 17
        val symbol = facade.resolveAtPosition(annotationsKt, 26, 17)
        assertNotNull(symbol, "Expected to resolve 'greeter' in ServiceB")
        assertEquals("greeter", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature")
        assertTrue(
            symbol.signature!!.contains("lateinit var greeter"),
            "Signature should contain 'lateinit var greeter', got: ${symbol.signature}"
        )
        assertFalse(
            symbol.signature!!.startsWith("@"),
            "Signature should NOT start with annotation '@', got: ${symbol.signature}"
        )
    }

    @Test
    fun `hover on multi-line-annotated property shows declaration, not annotation args`() {
        // Annotations.kt (0-based lines):
        //   line 31: @Named(
        //   line 32:     value = "simple"
        //   line 33: )
        //   line 34: lateinit var greeter: Greeter   — "greeter" at col 17
        val symbol = facade.resolveAtPosition(annotationsKt, 34, 17)
        assertNotNull(symbol, "Expected to resolve 'greeter' in ServiceC")
        assertEquals("greeter", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature")
        assertTrue(
            symbol.signature!!.contains("lateinit var greeter"),
            "Signature should contain declaration, got: ${symbol.signature}"
        )
        assertFalse(
            symbol.signature!!.contains("value = "),
            "Signature should NOT contain annotation parameter, got: ${symbol.signature}"
        )
    }

    @Test
    fun `hover on stacked-annotated class shows class declaration`() {
        // Annotations.kt (0-based lines):
        //   line 38: @MyComponent
        //   line 39: @Deprecated(...)
        //   line 40: class ServiceD {   — "ServiceD" at col 6
        val symbol = facade.resolveAtPosition(annotationsKt, 40, 6)
        assertNotNull(symbol, "Expected to resolve ServiceD class")
        assertEquals("ServiceD", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature")
        assertFalse(
            symbol.signature!!.startsWith("@"),
            "Signature should NOT start with annotation, got: ${symbol.signature}"
        )
    }

    @Test
    fun `hover on annotated function shows function declaration`() {
        // Annotations.kt (0-based lines):
        //   line 46: @Deprecated("Use newGreet instead")
        //   line 47: fun oldGreet(): String = "hello"   — "oldGreet" at col 8
        val symbol = facade.resolveAtPosition(annotationsKt, 47, 8)
        assertNotNull(symbol, "Expected to resolve oldGreet function")
        assertEquals("oldGreet", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature")
        assertTrue(
            symbol.signature!!.contains("fun oldGreet"),
            "Signature should contain 'fun oldGreet', got: ${symbol.signature}"
        )
    }

    // ========================================================================
    // Regression: Hover on annotation should show annotation class, not constructor
    // ========================================================================

    @Test
    fun `hover on annotation usage resolves to annotation class`() {
        // @Inject on ServiceA.greeter — line 18 (0-based), col 5 is on "Inject"
        val symbol = facade.resolveAtPosition(annotationsKt, 18, 5)
        assertNotNull(symbol, "Expected to resolve @Inject annotation")
        assertEquals("Inject", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature for annotation")
        assertTrue(
            symbol.signature!!.contains("annotation class"),
            "Signature should contain 'annotation class', got: ${symbol.signature}"
        )
        assertFalse(
            symbol.signature!!.endsWith("()"),
            "Signature should NOT look like a constructor call, got: ${symbol.signature}"
        )
    }

    @Test
    fun `hover on annotation with args resolves to annotation class`() {
        // @Named("formal") on ServiceB.greeter — line 24 (0-based), col 5 is on "Named"
        val symbol = facade.resolveAtPosition(annotationsKt, 24, 5)
        assertNotNull(symbol, "Expected to resolve @Named annotation")
        assertEquals("Named", symbol.name)
        assertNotNull(symbol.signature, "Expected a signature for annotation")
        assertTrue(
            symbol.signature!!.contains("annotation class"),
            "Signature should contain 'annotation class', got: ${symbol.signature}"
        )
    }

    @Test
    fun `hover on stacked annotation definition shows annotation class`() {
        // annotation class Qualifier — line 53 (0-based), "Qualifier" at col 17
        val symbol = facade.resolveAtPosition(annotationsKt, 53, 17)
        assertNotNull(symbol, "Expected to resolve Qualifier annotation definition")
        assertEquals("Qualifier", symbol.name)
    }

    // ========================================================================
    // Regression: Test source files were not in the analysis session
    // ========================================================================

    @Test
    fun `test source file is resolvable`() {
        // AnnotationTest.kt is in src/test/kotlin — should be in the session
        val symbols = facade.getFileSymbols(testFileKt)
        assertTrue(symbols.isNotEmpty(), "Expected symbols from test source file")
        assertTrue(
            symbols.any { it.name == "AnnotationTest" },
            "Expected AnnotationTest class in symbols, got: ${symbols.map { it.name }}"
        )
    }

    @Test
    fun `hover on declaration in test file works`() {
        // class AnnotationTest — line 5 (0-based), "AnnotationTest" at col 6
        val symbol = facade.resolveAtPosition(testFileKt, 5, 6)
        assertNotNull(symbol, "Expected to resolve class in test source file")
        assertEquals("AnnotationTest", symbol.name)
    }

    @Test
    fun `cross-reference from test to main source works`() {
        // In AnnotationTest: val service = ServiceA() — line 8 (0-based), "ServiceA" at col 22
        val symbol = facade.resolveAtPosition(testFileKt, 8, 22)
        assertNotNull(symbol, "Expected to resolve ServiceA from test file")
        assertEquals("ServiceA", symbol.name)
    }
}
