package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The golden writer behind the `regenerateToolGoldens` Gradle task: rewrites the per-tool schema goldens of
 * the production registry ([buildToolSpecs]) and deletes stale ones. It runs only when the task sets
 * `REGENERATE_TOOL_GOLDENS=true`, so a normal `test` run never rewrites a golden.
 */
class ToolGoldenGenerator {
    @Test
    @EnabledIfEnvironmentVariable(named = ENV, matches = "true")
    fun regenerate() {
        val result = ToolGoldens.write(ToolRegistry(buildToolSpecs()))
        println("Tool goldens: wrote ${result.written.size} ${result.written}, deleted ${result.deleted.size} ${result.deleted}")
    }

    companion object {
        const val ENV = "REGENERATE_TOOL_GOLDENS"
    }
}
