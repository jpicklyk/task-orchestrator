package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertSame

/** The dispatcher the composition builds validates the very list its [ToolRegistry] serves, never a second list. */
class BuildToolDispatcherTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private fun env() = ToolExecutionContext(sqlite.repositoryProvider(), unitOfWork = sqlite.unitOfWork())

    @Test
    fun `the dispatcher validates the same spec list instance its registry serves`() {
        val registry = ToolRegistry(buildToolSpecs())
        val dispatcher = buildToolDispatcher(env(), registry)
        assertSame(registry.tools, dispatcher.specs, "the dispatcher holds a different spec list than the registry serves")
    }

    @Test
    fun `the served tools the composition uses hand the dispatcher the registry's spec list`() {
        val served = buildServedTools(env())
        assertSame(served.registry.tools, served.dispatcher.specs, "the composition's dispatcher validates a second spec list")
    }
}
