package dev.review.lsp.integration

import dev.review.lsp.compiler.Severity
import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
class CrossModuleIntegrationTest {

    private lateinit var facade: AnalysisApiCompilerFacade
    private lateinit var repositoryKt: Path
    private lateinit var mainKt: Path
    private lateinit var inMemoryRepoKt: Path

    @BeforeAll
    fun setUp() {
        val model = TestFixtures.multiModule()
        facade = AnalysisApiCompilerFacade(model)
        val coreDir = model.modules[0].sourceRoots[0]
        val appDir = model.modules[1].sourceRoots[0]
        repositoryKt = coreDir.resolve("com/example/core/Repository.kt")
        mainKt = appDir.resolve("com/example/app/Main.kt")
        inMemoryRepoKt = appDir.resolve("com/example/app/InMemoryRepository.kt")
    }

    @AfterAll
    fun tearDown() {
        facade.dispose()
    }

    @Test
    fun `cross-module types resolve without UNRESOLVED_REFERENCE for core types`() {
        // InMemoryRepository uses Repository and Entity from core module.
        // These should resolve (no UNRESOLVED_REFERENCE for "Repository" or "Entity").
        // Note: stdlib functions like mutableMapOf may be unresolved if stdlib is not on classpath.
        val diagnostics = facade.getDiagnostics(inMemoryRepoKt)
        val unresolvedCoreTypes = diagnostics.filter {
            it.severity == Severity.ERROR &&
            it.code == "UNRESOLVED_REFERENCE" &&
            (it.message.contains("Repository") || it.message.contains("Entity"))
        }
        assertTrue(unresolvedCoreTypes.isEmpty(),
            "Core module types should resolve in app module, got: ${unresolvedCoreTypes.map { it.message }}")
    }

    @Test
    fun `cross-module symbol resolution`() {
        // "import com.example.core.Entity" in Main.kt — line 2, col ~27
        val symbol = facade.resolveAtPosition(mainKt, 2, 27)
        assertNotNull(symbol, "Expected to resolve Entity import from core module")
        assertTrue(symbol.name == "Entity" || symbol.name == "core",
            "Expected Entity-related symbol, got: ${symbol.name}")
    }

    @Test
    fun `core module interface has file symbols`() {
        val symbols = facade.getFileSymbols(repositoryKt)
        val names = symbols.map { it.name }
        assertTrue("Repository" in names, "Expected Repository in core symbols, got: $names")
        assertTrue("Entity" in names, "Expected Entity in core symbols, got: $names")
    }
}
