package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * S8 — per-request memoization via [ConfigSession]/[withConfigSession], counted through a
 * hand-written fake [PerRootConfigSource] (no shared test fake exists for this port; declarations
 * confirm zero pre-existing hits for `: PerRootConfigSource` under `src/test`, so this file
 * defines its own). Oracle: `task-scope`'s Build section O2/session prose ("inside one request,
 * each (source, rootId) is read at most once, and that includes a failed read... the memo is
 * keyed by (PerRootConfigSource instance, rootId). It stores `kotlin.Result<ConfigLayer?>`, which
 * covers success, null and failure; a failure is rethrown as the same exception... a nested call
 * reuses the outer session") and `test-plan` S8's enumerated cases verbatim.
 *
 * The global side is irrelevant to every scenario here, so [ServiceBackedGlobalLookup] is wired
 * with the NoOp global services purely to satisfy [EffectiveConfigResolver]'s constructor; no
 * assertion in this file depends on global lookup behavior.
 *
 * NEW-SURFACE (test-plan S8): [ConfigSession]/`withConfigSession` are introduced by this feature.
 * No source revert can yield behavioral red. Substitute per the frozen plan: "keep ConfigSession,
 * bypass memo in layered()" — an orchestrator-run mutation.
 */
class ConfigSessionTest {
    private val noOpGlobal = ServiceBackedGlobalLookup(NoOpNoteSchemaService, NoOpStatusLabelService)

    private fun emptyLayer(): ConfigLayer =
        ConfigLayer(ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap()), "fp", ConfigSource.PER_ROOT)

    private class CountingPerRootConfigSource(
        private val provide: suspend (UUID) -> ConfigLayer?
    ) : PerRootConfigSource {
        val reads = mutableMapOf<UUID, Int>()

        override suspend fun layer(rootId: UUID): ConfigLayer? {
            reads[rootId] = (reads[rootId] ?: 0) + 1
            return provide(rootId)
        }
    }

    private class FailingPerRootConfigSource(
        private val message: (UUID) -> String
    ) : PerRootConfigSource {
        var reads: Int = 0

        override suspend fun layer(rootId: UUID): ConfigLayer? {
            reads++
            throw PerRootConfigUnavailableException(rootId, message(rootId))
        }
    }

    @Test
    fun `S8 - one session, 3 item-lookups over 2 distinct roots yields exactly 2 underlying reads`(): Unit =
        runBlocking {
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()
            val source = CountingPerRootConfigSource { emptyLayer() }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                // 3 "items", 2 of which share root1 and one at root2 — mirrors the plan's
                // "3 items x 2 roots = 2 reads".
                resolver.layered(root1)
                resolver.layered(root1)
                resolver.layered(root2)
            }

            assertEquals(1, source.reads[root1])
            assertEquals(1, source.reads[root2])
            assertEquals(2, source.reads.values.sum())
        }

    @Test
    fun `S8 - with no session, every call reads fresh`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source = CountingPerRootConfigSource { emptyLayer() }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            resolver.layered(root)
            resolver.layered(root)
            resolver.layered(root)

            assertEquals(3, source.reads[root])
        }

    @Test
    fun `S8 - a source change mid-session is unseen, but is seen by a fresh session`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            var mode = "warn"
            val source =
                CountingPerRootConfigSource { _ ->
                    ConfigLayer(
                        ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), noteLimitsMode = mode),
                        "fp",
                        ConfigSource.PER_ROOT
                    )
                }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                val first = resolver.layered(root).noteLimitsMode()
                mode = "reject"
                val second = resolver.layered(root).noteLimitsMode()
                assertEquals(first, second, "the session must not observe the underlying change")
                assertEquals("warn", second)
            }
            assertEquals(1, source.reads[root], "both reads within the session must collapse to one underlying read")

            val afterNewSession = withConfigSession { resolver.layered(root).noteLimitsMode() }
            assertEquals("reject", afterNewSession, "a fresh session must see the now-current value")
            assertEquals(2, source.reads[root], "the new session performs its own, separate read")
        }

    @Test
    fun `S8 - a failure is memoized and rethrown, with exactly one underlying read`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source = FailingPerRootConfigSource { "boom for $it" }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                assertFailsWith<PerRootConfigUnavailableException> { resolver.layered(root) }
                assertFailsWith<PerRootConfigUnavailableException> { resolver.layered(root) }
            }

            assertEquals(1, source.reads, "a memoized failure must be rethrown on the second call without a second read")
        }

    @Test
    fun `S8 - a null layer is memoized, with exactly one underlying read`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source = CountingPerRootConfigSource { null }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                assertNull(resolver.layered(root).perRoot)
                assertNull(resolver.layered(root).perRoot)
            }

            assertEquals(1, source.reads[root])
        }

    @Test
    fun `S8 - a nested session reuses the outer session's memo`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source = CountingPerRootConfigSource { emptyLayer() }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                resolver.layered(root)
                withConfigSession {
                    resolver.layered(root)
                }
            }

            assertEquals(1, source.reads[root], "the nested withConfigSession must not start a fresh session")
        }

    @Test
    fun `S8 - two distinct PerRootConfigSource instances are keyed apart within the same session`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source1 = CountingPerRootConfigSource { emptyLayer() }
            val source2 = CountingPerRootConfigSource { emptyLayer() }
            val resolver1 = EffectiveConfigResolver(noOpGlobal, source1)
            val resolver2 = EffectiveConfigResolver(noOpGlobal, source2)

            withConfigSession {
                resolver1.layered(root)
                resolver2.layered(root)
                resolver1.layered(root)
                resolver2.layered(root)
            }

            assertEquals(1, source1.reads[root], "source1's memo must not be shared with source2's")
            assertEquals(1, source2.reads[root])
        }
}
