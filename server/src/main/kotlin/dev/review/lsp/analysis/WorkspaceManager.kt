package dev.review.lsp.analysis

import dev.review.lsp.buildsystem.BuildSystemProvider
import dev.review.lsp.buildsystem.BuildSystemResolver
import dev.review.lsp.buildsystem.ProjectModel
import dev.review.lsp.buildsystem.ProjectModelCache
import dev.review.lsp.compiler.CompilerFacade
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple build roots within a workspace, each with its own
 * [AnalysisSession] and [CompilerFacade]. Supports both single-root
 * workspaces (majority case, identical to prior behavior) and multi-root
 * workspaces (e.g. compose-multiplatform with independent Gradle builds).
 *
 * Build roots are discovered eagerly but resolved lazily — a root's
 * AnalysisSession is only created when a file from that root is first opened.
 */
class WorkspaceManager(
    private val workspaceRoot: Path,
    private val variant: String
) {
    data class BuildRoot(
        val provider: BuildSystemProvider,
        val rootDir: Path,
        @Volatile var session: AnalysisSession? = null,
        @Volatile var model: ProjectModel? = null
    )

    private val buildRoots = ConcurrentHashMap<Path, BuildRoot>()
    private val fileToRootCache = ConcurrentHashMap<Path, Path>()

    /**
     * Eagerly discover all build roots in the workspace. Does NOT resolve
     * any of them (no Gradle calls). Call [resolveRoot] to lazily resolve.
     */
    fun discover() {
        val resolver = BuildSystemResolver()
        val discovered = resolver.discoverBuildRoots(workspaceRoot)
        for ((provider, rootDir) in discovered) {
            buildRoots[rootDir.normalize()] = BuildRoot(provider, rootDir)
        }
        System.err.println("[workspace] Discovered ${buildRoots.size} build root(s): ${buildRoots.keys.map { it.fileName }}")
    }

    /**
     * Lazily resolve a single build root: run provider.resolve(), create
     * AnalysisSession, cache the result. Returns the facade, or null on failure.
     */
    suspend fun resolveRoot(rootDir: Path): CompilerFacade? {
        val normalizedRoot = rootDir.normalize()
        val root = buildRoots[normalizedRoot] ?: return null

        // Already resolved
        root.session?.let { return it.facade }

        return try {
            val resolver = BuildSystemResolver()
            // Use the resolver's cache-aware resolve, scoped to this root
            val (_, model) = resolver.resolve(normalizedRoot, variant)
            val session = AnalysisSession(model)
            root.model = model
            root.session = session
            System.err.println("[workspace] Resolved build root: ${normalizedRoot.fileName} (${model.modules.size} modules)")
            session.facade
        } catch (e: Exception) {
            System.err.println("[workspace] Failed to resolve build root ${normalizedRoot.fileName}: ${e.message}")
            null
        }
    }

    /**
     * Find the deepest containing build root for a file path.
     * Uses a cache for repeated lookups.
     */
    fun buildRootForFile(file: Path): Path? {
        val normalizedFile = file.normalize()

        fileToRootCache[normalizedFile]?.let { return it }

        // Find the deepest build root that contains this file
        val match = buildRoots.keys
            .filter { normalizedFile.startsWith(it) }
            .maxByOrNull { it.nameCount }

        if (match != null) {
            fileToRootCache[normalizedFile] = match
        }
        return match
    }

    /**
     * Get the facade for a file, triggering lazy resolution if needed.
     * Returns null if the file doesn't belong to any known build root
     * or if resolution fails.
     */
    suspend fun facadeForFile(file: Path): CompilerFacade? {
        val rootDir = buildRootForFile(file) ?: return null
        val root = buildRoots[rootDir] ?: return null

        // Fast path: already resolved
        root.session?.let { return it.facade }

        // Lazy resolve
        return resolveRoot(rootDir)
    }

    /**
     * Get the ProjectModel for a file's build root.
     */
    fun modelForFile(file: Path): ProjectModel? {
        val rootDir = buildRootForFile(file) ?: return null
        return buildRoots[rootDir]?.model
    }

    /**
     * Rebuild a specific build root's session (e.g. after build file changes).
     */
    suspend fun rebuildRoot(rootDir: Path): CompilerFacade? {
        val normalizedRoot = rootDir.normalize()
        val root = buildRoots[normalizedRoot] ?: return null
        val existingSession = root.session ?: return resolveRoot(rootDir)

        return try {
            val resolver = BuildSystemResolver()
            val (_, model) = resolver.resolve(normalizedRoot, variant)
            val newFacade = existingSession.rebuild(model)
            root.model = model
            System.err.println("[workspace] Rebuilt build root: ${normalizedRoot.fileName} (${model.modules.size} modules)")
            newFacade
        } catch (e: Exception) {
            System.err.println("[workspace] Failed to rebuild build root ${normalizedRoot.fileName}: ${e.message}")
            null
        }
    }

    /**
     * Check if a build root has been resolved (session created).
     */
    fun isRootResolved(rootDir: Path): Boolean {
        return buildRoots[rootDir.normalize()]?.session != null
    }

    /**
     * True if the workspace has exactly one build root (the common case).
     * When true, callers can use the single-root fast path.
     */
    fun isSingleRoot(): Boolean = buildRoots.size == 1

    /**
     * Get all discovered build root directories.
     */
    fun allRoots(): Set<Path> = buildRoots.keys.toSet()

    /**
     * Get the single build root (convenience for single-root workspaces).
     * Throws if there are 0 or >1 roots.
     */
    fun singleRoot(): BuildRoot {
        check(buildRoots.size == 1) { "Expected single root, found ${buildRoots.size}" }
        return buildRoots.values.first()
    }

    /**
     * Dispose all sessions and clear caches.
     */
    fun dispose() {
        for ((_, root) in buildRoots) {
            try {
                root.session?.dispose()
            } catch (e: Exception) {
                System.err.println("[workspace] Error disposing session for ${root.rootDir}: ${e.message}")
            }
        }
        buildRoots.clear()
        fileToRootCache.clear()
    }
}
