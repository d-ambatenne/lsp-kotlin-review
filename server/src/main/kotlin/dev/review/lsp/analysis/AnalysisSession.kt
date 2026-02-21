package dev.review.lsp.analysis

import dev.review.lsp.buildsystem.ProjectModel
import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.compiler.StubCompilerFacade
import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade

class AnalysisSession(
    projectModel: ProjectModel
) {
    @Volatile
    var projectModel: ProjectModel = projectModel
        private set

    @Volatile
    var facade: CompilerFacade = createFacade(projectModel)
        private set

    private fun createFacade(model: ProjectModel): CompilerFacade {
        return try {
            AnalysisApiCompilerFacade(model)
        } catch (e: Exception) {
            System.err.println("Failed to create Analysis API facade, falling back to stub: ${e.message}")
            StubCompilerFacade()
        }
    }

    /**
     * Rebuild the session with a new project model (e.g., after build file changes).
     * Disposes the old facade before creating the new one.
     */
    fun rebuild(newModel: ProjectModel): CompilerFacade {
        facade.dispose()
        this.projectModel = newModel
        val newFacade = createFacade(newModel)
        this.facade = newFacade
        return newFacade
    }

    fun dispose() {
        facade.dispose()
    }
}
