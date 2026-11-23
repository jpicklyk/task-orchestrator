package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.RunToolServices
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.request.RequestDecoder
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.declaredServices
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.mockk.mockk
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.reflect.KClass
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The per-slice services holders the dispatcher hands every production select and op ([ToolServiceRegistry]). */
class ToolServiceRegistryTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    /** A services holder no registry serves. */
    private class UnregisteredServices

    /** Every select and op of [buildToolSpecs], with the holder type it declares. */
    private fun declared(): List<Pair<String, KClass<*>>> = buildToolSpecs().flatMap { it.declaredServices() }

    private fun sqliteContext(): ToolExecutionContext {
        val provider = sqlite.repositoryProvider()
        return ToolExecutionContext(provider, unitOfWork = sqlite.unitOfWork())
    }

    private fun specNamingUnregisteredHolder(): SelectToolSpec =
        SelectToolSpec(
            "fx_orphan",
            "A tool whose select names a holder nobody registered.",
            listOf(
                SelectSpec.Single<UnregisteredServices, FixtureTools.PeekReq, String>(
                    name = "orphan",
                    doc = "Orphan.",
                    profiles = emptySet(),
                    services = UnregisteredServices::class,
                    request = serializer<FixtureTools.PeekReq>(),
                    handler = { _, _, _ -> Outcome.Ok("never") },
                    data = { null }
                )
            )
        )

    @Test
    fun `every production select and op names a registered holder, none takes the whole context, and a holder is built once`() {
        val registry = ToolServiceRegistry(sqliteContext())
        val members = declared()
        assertTrue(members.isNotEmpty(), "buildToolSpecs() declares selects and ops")
        members.forEach { (member, type) ->
            assertNotEquals<KClass<*>>(ToolExecutionContext::class, type, "$member takes the whole ToolExecutionContext")
            assertTrue(registry.isRegistered(type), "$member names ${type.simpleName}, which the registry does not serve")
            assertSame(registry.get(type), registry.get(type), "$member: a second lookup of ${type.simpleName} rebuilt the holder")
        }
    }

    @Test
    fun `a holder carries the context's own members`() {
        val context = sqliteContext()
        val run = ToolServiceRegistry(context).get(RunToolServices::class)
        assertSame(context.unitOfWork, run.unitOfWork, "the run holder carries the context's unit of work")
        assertSame(context.runService, run.runService, "the run holder carries the context's run service")
        assertSame(context.seatService, run.seatService, "the run holder carries the context's seat service")
    }

    @Test
    fun `one registry shares one decision service and run limits and a second registry builds its own`() {
        val context = sqliteContext()
        val registry = ToolServiceRegistry(context)
        assertSame(
            registry.get(RunToolServices::class).decisionService,
            registry.get(RunToolServices::class).decisionService,
            "one registry shares one DecisionService"
        )
        assertSame(
            registry.get(RunToolServices::class).runLimits,
            registry.get(RunToolServices::class).runLimits,
            "one registry shares one RunLimits"
        )
        assertNotSame(
            registry.get(RunToolServices::class).decisionService,
            ToolServiceRegistry(context).get(RunToolServices::class).decisionService,
            "a second registry builds its own DecisionService"
        )
    }

    @Test
    fun `building every holder touches no store`() {
        // No stubs: any call on the provider throws, so a holder that reads a store when built fails here.
        val strict = mockk<RepositoryProvider>()
        val context = ToolExecutionContext(strict, nextItemRecommender = NextItemRecommender(mockk<WorkItemRepository>()))
        val registry = ToolServiceRegistry(context)
        declared().map { it.second }.distinct().forEach { type ->
            assertSame(registry.get(type), registry.get(type), "${type.simpleName} builds once, without a store call")
        }
    }

    @Test
    fun `a holder nobody registered is not served and a lookup of it throws`() {
        val registry = ToolServiceRegistry(sqliteContext())
        assertFalse(registry.isRegistered(UnregisteredServices::class))
        val failure = assertFailsWith<IllegalStateException> { registry.get(UnregisteredServices::class) }
        assertContains(failure.message.orEmpty(), UnregisteredServices::class.qualifiedName.orEmpty())
    }

    @Test
    fun `a dispatcher refuses at construction a spec naming an unregistered holder`() {
        val env = sqliteContext()
        val specs = buildToolSpecs() + specNamingUnregisteredHolder()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ToolDispatcher(RequestDecoder(), BatchRunner(env.unitOfWork, env.idempotency), env, specs)
            }
        assertContains(failure.message.orEmpty(), "fx_orphan.orphan")
        assertContains(failure.message.orEmpty(), UnregisteredServices::class.qualifiedName.orEmpty())
    }

    @Test
    fun `a dispatcher over the production specs constructs`() {
        val env = sqliteContext()
        ToolDispatcher(RequestDecoder(), BatchRunner(env.unitOfWork, env.idempotency), env, buildToolSpecs())
    }
}
