package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files

/**
 * The writer behind the `regenerateApiReference` Gradle task: rewrites, in place, every generated block of
 * `docs/api-reference.md` from the production registry. It runs only when the task sets
 * `REGENERATE_API_REFERENCE=true`, so a normal `test` run never rewrites the document. Prose outside the
 * blocks is never touched; a missing marker pair fails the run (add the empty pair by hand first).
 */
class ApiReferenceGenerator {
    @Test
    @EnabledIfEnvironmentVariable(named = ENV, matches = "true")
    fun regenerate() {
        val registry = ToolRegistry(buildToolSpecs())
        var text = ApiReferenceDocs.read()
        val names = ApiReferenceDocs.names(registry)
        for (name in names) text = ApiReferenceDocs.replace(text, name, registry)
        Files.writeString(ApiReferenceDocs.FILE, text, Charsets.UTF_8)
        println("api-reference.md: rewrote ${names.size} generated blocks $names")
    }

    companion object {
        const val ENV = "REGENERATE_API_REFERENCE"
    }
}
