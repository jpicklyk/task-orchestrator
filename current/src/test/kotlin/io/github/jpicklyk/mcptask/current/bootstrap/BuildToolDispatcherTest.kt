package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The dispatcher the composition builds validates the very specs its [ToolRegistry] serves, never a second list. */
class BuildToolDispatcherTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    @Test
    fun `the dispatcher validates the same spec instances its registry serves`() {
        val env = ToolExecutionContext(sqlite.repositoryProvider(), unitOfWork = sqlite.unitOfWork())
        val registry = ToolRegistry(buildToolSpecs())
        val dispatcher = buildToolDispatcher(env, registry)
        assertEquals(registry.tools.map { it.name }, dispatcher.specs.map { it.name }, "same tools in the same order")
        registry.tools.zip(dispatcher.specs).forEach { (served, validated) ->
            assertSame(served, validated, "${served.name}: the dispatcher validated a different spec instance than the registry serves")
        }
    }
}
