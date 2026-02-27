package dev.review.lsp.integration

import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
class HoverIntegrationTest {

    private lateinit var facade: AnalysisApiCompilerFacade
    private lateinit var dataModelKt: Path
    private lateinit var greeterKt: Path

    @BeforeAll
    fun setUp() {
        val model = TestFixtures.singleModule()
        facade = AnalysisApiCompilerFacade(model)
        val srcDir = model.modules[0].sourceRoots[0]
        dataModelKt = srcDir.resolve("com/example/DataModel.kt")
        greeterKt = srcDir.resolve("com/example/Greeter.kt")
    }

    @AfterAll
    fun tearDown() {
        facade.dispose()
    }

    @Test
    fun `hover on class declaration shows name`() {
        // "data class User(val name: String, val age: Int)" — line 7 (0-based)
        // The word "User" starts at col 11
        val symbol = facade.resolveAtPosition(dataModelKt, 7, 14)
        assertNotNull(symbol, "Expected to resolve User data class")
        assertTrue(symbol.name == "User", "Expected name 'User', got: ${symbol.name}")
    }

    @Test
    fun `hover on function shows name`() {
        // "fun isAdult(): Boolean" in User class — line 8 (0-based), col ~8
        val symbol = facade.resolveAtPosition(dataModelKt, 8, 10)
        assertNotNull(symbol, "Expected to resolve isAdult function")
        assertTrue(symbol.name == "isAdult", "Expected name 'isAdult', got: ${symbol.name}")
    }

    @Test
    fun `getType for declared variable returns type`() {
        // In App.kt: "val greeter: Greeter = SimpleGreeter()" — line 3, col ~8
        val appKt = dataModelKt.parent.resolve("App.kt")
        val typeInfo = facade.getType(appKt, 3, 8)
        assertNotNull(typeInfo, "Expected type info for 'greeter'")
        assertTrue(
            typeInfo.shortName.contains("Greeter") || typeInfo.shortName.contains("SimpleGreeter"),
            "Expected Greeter-related type, got: ${typeInfo.shortName}"
        )
    }

    @Test
    fun `getDocumentation returns KDoc for documented class`() {
        // User class has KDoc starting at line 1 — the class itself is at line 7
        val symbol = facade.resolveAtPosition(dataModelKt, 7, 14)
        assertNotNull(symbol, "Expected to resolve User")
        val docs = facade.getDocumentation(symbol)
        assertNotNull(docs, "Expected KDoc for User class")
        assertTrue(docs.contains("user") || docs.contains("User") || docs.contains("property"),
            "KDoc should describe the class, got: $docs")
    }

    @Test
    fun `resolveAtPosition on interface returns correct kind`() {
        // "interface Greeter" in Greeter.kt — line 2, col ~10
        val symbol = facade.resolveAtPosition(greeterKt, 2, 10)
        assertNotNull(symbol, "Expected to resolve Greeter interface")
        assertTrue(symbol.name == "Greeter", "Expected Greeter, got: ${symbol.name}")
    }
}
