package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.RunToolServices
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.reflect.KClass
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The per-slice services holders the dispatcher hands every production select and op ([ToolServiceRegistry]). */
class ToolServiceRegistryTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    /** Every select and op of [buildToolSpecs], with the holder type it declares. */
    private fun declared(): List<Pair<String, KClass<*>>> =
        buildToolSpecs().flatMap { spec ->
            when (spec) {
                is SelectToolSpec -> spec.selects.map { "${spec.name}.${it.name}" to it.services }
                is OpsToolSpec<*> -> spec.ops.map { "${spec.name}.${it.name}" to it.services }
            }
        }

    private fun sqliteContext(): ToolExecutionContext {
        val provider = sqlite.repositoryProvider()
        return ToolExecutionContext(provider, unitOfWork = sqlite.unitOfWork())
    }

    @Test
    fun `every production select and op resolves its declared holder and none takes the whole context`() {
        val registry = ToolServiceRegistry.of(sqliteContext())
        val members = declared()
        assertTrue(members.isNotEmpty(), "buildToolSpecs() declares selects and ops")
        members.forEach { (member, type) ->
            assertNotEquals<KClass<*>>(ToolExecutionContext::class, type, "$member takes the whole ToolExecutionContext")
            assertTrue(type.isInstance(registry.get(type)), "$member resolves an instance of ${type.simpleName}")
        }
    }

    @Test
    fun `one context gives one decision service and two contexts give two`() {
        val first = sqliteContext()
        val registry = ToolServiceRegistry.of(first)
        val again = ToolServiceRegistry.of(first)
        assertSame(
            registry.get(RunToolServices::class).decisionService,
            again.get(RunToolServices::class).decisionService,
            "one context shares one DecisionService"
        )

        val other = ToolServiceRegistry.of(sqliteContext())
        assertNotSame(
            registry.get(RunToolServices::class).decisionService,
            other.get(RunToolServices::class).decisionService,
            "a second context gets its own DecisionService"
        )
    }

    @Test
    fun `building every holder touches no store`() {
        // No stubs: any call on the provider throws, so a holder that reads a store when built fails here.
        val strict = mockk<RepositoryProvider>()
        val context = ToolExecutionContext(strict, nextItemRecommender = NextItemRecommender(mockk<WorkItemRepository>()))
        val registry = ToolServiceRegistry.of(context)
        declared().map { it.second }.distinct().forEach { type ->
            assertTrue(type.isInstance(registry.get(type)), "${type.simpleName} builds without a store call")
        }
    }
}
