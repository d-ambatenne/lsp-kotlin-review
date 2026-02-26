package dev.review.lsp.integration

import dev.review.lsp.buildsystem.KmpPlatform
import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(Lifecycle.PER_CLASS)
class KmpIntegrationTest {

    private lateinit var facade: AnalysisApiCompilerFacade

    @BeforeAll
    fun setUp() {
        facade = AnalysisApiCompilerFacade(TestFixtures.kmpModule())
    }

    @AfterAll
    fun tearDown() {
        facade.dispose()
    }

    @Test
    fun `common file is resolvable`() {
        val commonFile = TestFixtures.kmpModule().modules[0].sourceRoots[0]
            .resolve("com/example/common/Platform.kt")
        val symbols = facade.getFileSymbols(commonFile)
        assertTrue(symbols.isNotEmpty(), "Expected symbols in common Platform.kt")
        assertTrue(symbols.any { it.name == "greet" }, "Expected greet function in symbols, got: ${symbols.map { it.name }}")
    }

    @Test
    fun `jvm file is resolvable`() {
        val model = TestFixtures.kmpModule()
        val jvmRoot = model.modules[0].targets.first { it.platform == KmpPlatform.JVM }.sourceRoots[0]
        val jvmFile = jvmRoot.resolve("com/example/common/JvmPlatform.kt")
        val symbols = facade.getFileSymbols(jvmFile)
        assertTrue(symbols.isNotEmpty(), "Expected symbols in JvmPlatform.kt")
        assertTrue(symbols.any { it.name == "platformName" }, "Expected platformName function in symbols, got: ${symbols.map { it.name }}")
    }

    @Test
    fun `platformForFile routes jvm files correctly`() {
        val model = TestFixtures.kmpModule()
        val jvmRoot = model.modules[0].targets.first { it.platform == KmpPlatform.JVM }.sourceRoots[0]
        val jvmFile = jvmRoot.resolve("com/example/common/JvmPlatform.kt")
        assertEquals("JVM", facade.platformForFile(jvmFile))
    }

    @Test
    fun `platformForFile routes js files correctly`() {
        val model = TestFixtures.kmpModule()
        val jsRoot = model.modules[0].targets.first { it.platform == KmpPlatform.JS }.sourceRoots[0]
        val jsFile = jsRoot.resolve("com/example/common/JsPlatform.kt")
        assertEquals("JS", facade.platformForFile(jsFile))
    }

    @Test
    fun `common files route to primary session`() {
        val commonFile = TestFixtures.kmpModule().modules[0].sourceRoots[0]
            .resolve("com/example/common/Platform.kt")
        val platform = facade.platformForFile(commonFile)
        // Common files should route to JVM (preferred primary session)
        assertEquals("JVM", platform)
    }

    @Test
    fun `findExpectActualCounterparts finds actuals for expect`() {
        val commonFile = TestFixtures.kmpModule().modules[0].sourceRoots[0]
            .resolve("com/example/common/Platform.kt")
        // "expect fun platformName()" is on line 2, "platformName" starts at column 11
        val counterparts = facade.findExpectActualCounterparts(commonFile, 2, 11)
        assertTrue(counterparts.isNotEmpty(), "Expected actual counterparts for expect fun platformName()")
        assertTrue(counterparts.any { it.name == "platformName" },
            "Expected counterpart named platformName, got: ${counterparts.map { it.name }}")
    }

    @Test
    fun `findExpectActualCounterparts finds expect for actual`() {
        val model = TestFixtures.kmpModule()
        val jvmRoot = model.modules[0].targets.first { it.platform == KmpPlatform.JVM }.sourceRoots[0]
        val jvmFile = jvmRoot.resolve("com/example/common/JvmPlatform.kt")
        // "actual fun platformName()" is on line 2, "platformName" starts at column 11
        val counterparts = facade.findExpectActualCounterparts(jvmFile, 2, 11)
        assertTrue(counterparts.isNotEmpty(), "Expected expect counterpart for actual fun platformName()")
        assertTrue(counterparts.any { it.name == "platformName" },
            "Expected counterpart named platformName, got: ${counterparts.map { it.name }}")
    }

    @Test
    fun `findExpectActualCounterparts returns empty for non-expect-actual`() {
        val commonFile = TestFixtures.kmpModule().modules[0].sourceRoots[0]
            .resolve("com/example/common/Platform.kt")
        // "fun greet()" is on line 4, "greet" starts at column 4
        val counterparts = facade.findExpectActualCounterparts(commonFile, 4, 4)
        assertTrue(counterparts.isEmpty(), "Expected no counterparts for non-expect/actual function greet()")
    }
}
