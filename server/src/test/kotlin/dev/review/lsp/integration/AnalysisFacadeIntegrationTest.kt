package dev.review.lsp.integration

import dev.review.lsp.compiler.Severity
import dev.review.lsp.compiler.SymbolKind
import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
class AnalysisFacadeIntegrationTest {

    private lateinit var facade: AnalysisApiCompilerFacade
    private lateinit var appKt: Path
    private lateinit var greeterKt: Path
    private lateinit var dataModelKt: Path

    @BeforeAll
    fun setUp() {
        val model = TestFixtures.singleModule()
        facade = AnalysisApiCompilerFacade(model)
        val srcDir = model.modules[0].sourceRoots[0]
        appKt = srcDir.resolve("com/example/App.kt")
        greeterKt = srcDir.resolve("com/example/Greeter.kt")
        dataModelKt = srcDir.resolve("com/example/DataModel.kt")
    }

    @AfterAll
    fun tearDown() {
        facade.dispose()
    }

    @Test
    fun `getDiagnostics returns type mismatch error`() {
        val diagnostics = facade.getDiagnostics(appKt)
        assertTrue(diagnostics.isNotEmpty(), "Expected diagnostics for App.kt (has type mismatch)")
        val typeMismatch = diagnostics.find { it.code == "TYPE_MISMATCH" || it.code == "INITIALIZER_TYPE_MISMATCH" }
        assertNotNull(typeMismatch, "Expected TYPE_MISMATCH diagnostic, got: ${diagnostics.map { it.code }}")
        assertEquals(Severity.ERROR, typeMismatch.severity)
    }

    @Test
    fun `getDiagnostics returns no errors for clean file`() {
        val diagnostics = facade.getDiagnostics(greeterKt)
        val errors = diagnostics.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "Expected no errors for Greeter.kt, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun `resolveAtPosition finds class declaration`() {
        // "class SimpleGreeter" — line 6 (0-based), column ~6
        val symbol = facade.resolveAtPosition(greeterKt, 6, 6)
        assertNotNull(symbol, "Expected to resolve SimpleGreeter")
        assertEquals("SimpleGreeter", symbol.name)
        assertEquals(SymbolKind.CLASS, symbol.kind)
    }

    @Test
    fun `resolveAtPosition finds interface`() {
        // "interface Greeter" — line 2 (0-based), column ~10
        val symbol = facade.resolveAtPosition(greeterKt, 2, 10)
        assertNotNull(symbol, "Expected to resolve Greeter interface")
        assertEquals("Greeter", symbol.name)
        assertEquals(SymbolKind.INTERFACE, symbol.kind)
    }

    @Test
    fun `getFileSymbols lists all declarations`() {
        val symbols = facade.getFileSymbols(greeterKt)
        val names = symbols.map { it.name }
        assertTrue("Greeter" in names, "Expected Greeter in symbols, got: $names")
        assertTrue("SimpleGreeter" in names, "Expected SimpleGreeter in symbols, got: $names")
        assertTrue("FormalGreeter" in names, "Expected FormalGreeter in symbols, got: $names")
    }

    @Test
    fun `getFileSymbols includes enum and data class`() {
        val symbols = facade.getFileSymbols(dataModelKt)
        val names = symbols.map { it.name }
        assertTrue("User" in names, "Expected User in symbols")
        assertTrue("Role" in names, "Expected Role in symbols")
    }

    @Test
    fun `getType returns inferred type for val`() {
        // In App.kt: "val greeter: Greeter = SimpleGreeter()" — line 3 (0-based), col ~8
        val typeInfo = facade.getType(appKt, 3, 8)
        assertNotNull(typeInfo, "Expected type info for 'greeter'")
        assertTrue(
            typeInfo.shortName.contains("Greeter") || typeInfo.shortName.contains("SimpleGreeter"),
            "Expected Greeter-related type, got: ${typeInfo.shortName}"
        )
    }

    @Test
    fun `findImplementations finds concrete classes`() {
        // Resolve the Greeter interface, then find implementations
        val symbol = facade.resolveAtPosition(greeterKt, 2, 10) ?: return
        val impls = facade.findImplementations(symbol)
        val names = impls.map { facade.resolveAtPosition(it.path, it.line, it.column)?.name }
        assertTrue(names.any { it == "SimpleGreeter" }, "Expected SimpleGreeter in implementations, got: $names")
        assertTrue(names.any { it == "FormalGreeter" }, "Expected FormalGreeter in implementations, got: $names")
    }
}
