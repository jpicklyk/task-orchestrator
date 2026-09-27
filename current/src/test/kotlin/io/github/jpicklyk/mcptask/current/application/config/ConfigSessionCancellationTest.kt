package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenario S8's cancellation addendum. Oracle: `task-scope`'s review addendum on
 * `application/config/ConfigSession.kt` ("in `memoized`, never memoise a
 * `kotlin.coroutines.cancellation.CancellationException`; rethrow it without storing. All other
 * failures stay memoised") and the declarations' verbatim restatement of that contract: a
 * `CancellationException` thrown by `fetch` is not stored, and the NEXT call for the same
 * `(source, rootId)` key re-invokes `fetch`; a non-cancellation failure IS memoized and rethrown
 * without a second read, exactly as
 * [io.github.jpicklyk.mcptask.current.application.config.ConfigSessionTest]'s existing
 * "a failure is memoized and rethrown" case already establishes for [PerRootConfigUnavailableException].
 *
 * NEW-SURFACE (test-plan S8): [ConfigSession]/`withConfigSession` are introduced by this feature.
 * No source revert can yield behavioral red. Substitute per the frozen plan: "keep ConfigSession,
 * bypass memo in layered()" — an orchestrator-run mutation. `resolveNoteLimitsMode` is used here
 * (rather than `layered` directly, as in `ConfigSessionTest`) per the S8 scenario wording, but both
 * route through the SAME `memoized` call per `EffectiveConfigResolver.layered`.
 */
class ConfigSessionCancellationTest {
    private val noOpGlobal = ServiceBackedGlobalLookup(NoOpNoteSchemaService, NoOpStatusLabelService)

    private fun layerWithMode(mode: String): ConfigLayer =
        ConfigLayer(ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), noteLimitsMode = mode), "fp-L", ConfigSource.PER_ROOT)

    /** Replays one scripted outcome per call, in order; each call increments [reads] unconditionally. */
    private class ScriptedPerRootConfigSource(
        private val steps: MutableList<suspend () -> ConfigLayer?>
    ) : PerRootConfigSource {
        var reads = 0

        override suspend fun layer(rootId: UUID): ConfigLayer? {
            reads++
            return steps.removeAt(0).invoke()
        }
    }

    @Test
    fun `S8 - a CancellationException is not memoized, so the next call for the same key performs a fresh fetch`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source =
                ScriptedPerRootConfigSource(
                    mutableListOf(
                        { throw CancellationException("cancelled") },
                        { layerWithMode("reject") },
                    ),
                )
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                assertFailsWith<CancellationException> { resolver.resolveNoteLimitsMode(root) }
                val second = resolver.resolveNoteLimitsMode(root)
                assertEquals("reject", second, "the second call, after the cancelled first, must observe the fresh fetch's result")
            }

            assertEquals(2, source.reads, "a cancelled read must not be memoized; the next call for the same key must re-fetch")
        }

    @Test
    fun `S8 control - a non-cancellation failure IS memoized, with exactly one underlying read`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val source =
                object : PerRootConfigSource {
                    var reads = 0

                    override suspend fun layer(rootId: UUID): ConfigLayer? {
                        reads++
                        throw PerRootConfigUnavailableException(rootId, "boom for $rootId")
                    }
                }
            val resolver = EffectiveConfigResolver(noOpGlobal, source)

            withConfigSession {
                assertFailsWith<PerRootConfigUnavailableException> { resolver.resolveNoteLimitsMode(root) }
                assertFailsWith<PerRootConfigUnavailableException> { resolver.resolveNoteLimitsMode(root) }
            }

            assertEquals(1, source.reads, "a non-cancellation failure must be memoized and rethrown without a second read")
        }
}
