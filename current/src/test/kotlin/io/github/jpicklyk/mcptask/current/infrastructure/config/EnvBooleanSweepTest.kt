package io.github.jpicklyk.mcptask.current.infrastructure.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Matrix tests for [EnvBoolean] across every call site the item 64f7b265-6bca-4a23-8809-449b6e94dae8
 * sweep unified: [AppConfig.fromEnv], [ApiAuthConfigLoader], and [AdvanceService]'s per-call
 * `RESOURCE_LEASES_ENFORCED` resolver.
 *
 * Oracle provenance: every expected value traces to the `test-plan` note frozen at queue phase
 * (algorithm: shared `true/1/yes` / `false/0/no` vocabulary; [ApiAuthConfigLoader] KDoc fail-fast
 * contract for `require`; CLAUDE.md's env-var table for documented defaults). Nothing here was
 * read off the call sites' implementations to decide correctness.
 *
 * Covers test-plan scenarios S1 (happy: the USE_FLYWAY=1 diagnosis repro), S4 (failure:
 * API_ENABLED fail-fast), S6 (failure: API_REDACT_ACTOR_PROOF fails safe toward redaction), S7
 * (edge: all 9 vars default correctly, silently, when unset), S9 (RESOURCE_LEASES_ENFORCED's
 * widened 0/no contract), S10 (RESOURCE_LEASES_ENFORCED stays per-call, never hoisted into
 * AppConfig), and S11 (the single-parser invariant, to the extent testable from this item's owned
 * files -- see that test's own scope note). S2/S3/S5/S8 and the adversarial probes live in
 * EnvBooleanTest.kt per the test-plan's file split.
 */
class EnvBooleanSweepTest {
    /** Builds a resolver over a fixed map; unset keys (or keys explicitly mapped to null) return null. */
    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    /** Captures WARN-level log records emitted by [EnvBoolean] during [block]. */
    private fun captureWarnLogs(block: () -> Unit): List<String> {
        val logbackLogger = LoggerFactory.getLogger(EnvBoolean::class.java.name) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.WARN
        try {
            block()
            return listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    // ------------------------------------------------------------------
    // S1 -- happy: the USE_FLYWAY=1 diagnosis repro
    // ------------------------------------------------------------------

    @Test
    fun `S1 USE_FLYWAY=1 parses to true, fixing the historical toBoolean bug`() {
        // Oracle: diagnosis repro + CLAUDE.md ("USE_FLYWAY -- default: true"). Before this item,
        // Kotlin's .toBoolean() only recognized the literal "true", so USE_FLYWAY=1 silently
        // resolved to false. This item's scope is the boolean value itself; which
        // DatabaseSchemaManager gets constructed from it is SchemaManagerFactory's concern, not an
        // owned file of this item and out of this test's scope.
        val c = AppConfig.fromEnv(env("USE_FLYWAY" to "1"))
        assertTrue(c.useFlyway)
    }

    // ------------------------------------------------------------------
    // S4 -- failure: ApiAuthConfigLoader fail-fast on API_ENABLED
    // ------------------------------------------------------------------

    @Test
    fun `S4 API_ENABLED=maybe fails fast naming the variable and the value`() {
        val loader = ApiAuthConfigLoader(envResolver = env("API_ENABLED" to "maybe"))
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_ENABLED"), "message: ${ex.message}")
        assertTrue(ex.message!!.contains("maybe"), "message: ${ex.message}")
    }

    // ------------------------------------------------------------------
    // S6 -- failure: API_REDACT_ACTOR_PROOF fails safe toward redaction
    // ------------------------------------------------------------------

    @Test
    fun `S6 API_REDACT_ACTOR_PROOF=maybe stays redacted (true) and warns, never silently un-redacts`() {
        var redacted: Boolean? = null
        val warnings =
            captureWarnLogs {
                redacted = AppConfig.fromEnv(env("API_REDACT_ACTOR_PROOF" to "maybe")).apiRedactActorProof
            }

        // Oracle: CLAUDE.md -- "API_REDACT_ACTOR_PROOF -- default true". An unrecognized value must
        // fail toward the safe (redacting) side, never silently disable redaction.
        assertEquals(true, redacted)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("API_REDACT_ACTOR_PROOF"), "WARN must name the variable")
    }

    // ------------------------------------------------------------------
    // S7 -- edge: all 9 boolean vars default correctly and silently when unset
    // ------------------------------------------------------------------

    @Test
    fun `S7 unset yields the documented default for all 9 boolean env vars, with no WARN`() {
        val warnings =
            captureWarnLogs {
                val c = AppConfig.fromEnv { null }
                // The 7 AppConfig-resident vars.
                assertTrue(c.useFlyway, "USE_FLYWAY default is true")
                assertFalse(c.databaseShowSql, "DATABASE_SHOW_SQL default is false")
                assertFalse(c.flywayRepair, "FLYWAY_REPAIR default is false")
                assertFalse(c.apiAllowQueryTokenForSse, "API_ALLOW_QUERY_TOKEN_FOR_SSE default is false")
                assertTrue(c.apiRedactNoteAttribution, "API_REDACT_NOTE_ATTRIBUTION default is true")
                assertTrue(c.apiRedactActorProof, "API_REDACT_ACTOR_PROOF default is true")
                assertTrue(c.apiWarnOnClaimedAdvance, "API_WARN_ON_CLAIMED_ADVANCE default is true")

                // API_ENABLED default is false: an unset loader must resolve to Disabled.
                val disabled = ApiAuthConfigLoader(envResolver = { null }).load()
                assertIs<ApiAuthConfig.Disabled>(disabled)

                // API_ALLOW_UNAUTHENTICATED default is false, observed indirectly through
                // ApiAuthConfigLoader's own public contract: with API_ENABLED=true and
                // API_AUTH_MODE=none but API_ALLOW_UNAUTHENTICATED left unset, the loader's
                // documented "API_AUTH_MODE=none requires API_ALLOW_UNAUTHENTICATED=true" fail-fast
                // fires -- which only fires when the resolved default is false, not true.
                val noneLoader = ApiAuthConfigLoader(envResolver = env("API_ENABLED" to "true", "API_AUTH_MODE" to "none"))
                val ex = assertThrows<IllegalArgumentException> { noneLoader.load() }
                assertTrue(ex.message!!.contains("API_ALLOW_UNAUTHENTICATED"), "message: ${ex.message}")
            }

        assertEquals(0, warnings.size, "an unset variable must never warn, across all 9 vars")
    }

    // ------------------------------------------------------------------
    // S9 -- RESOURCE_LEASES_ENFORCED's widened 0/no contract
    // ------------------------------------------------------------------

    @Test
    fun `S9 RESOURCE_LEASES_ENFORCED=0 and =no both now disable the kill switch`() {
        // Oracle: test-plan S9 -- the unification widens the pre-existing "only literal false
        // disables" contract to also cover 0/no (CLAUDE.md is amended by this item accordingly).
        assertFalse(AdvanceService.resourceLeasesEnforcedFromEnv { "0" })
        assertFalse(AdvanceService.resourceLeasesEnforcedFromEnv { "no" })
        assertFalse(AdvanceService.resourceLeasesEnforcedFromEnv { "No" })
        // The literal "false" spelling that pre-dates this item must still disable it.
        assertFalse(AdvanceService.resourceLeasesEnforcedFromEnv { "false" })
    }

    // ------------------------------------------------------------------
    // S10 -- RESOURCE_LEASES_ENFORCED stays per-call, never hoisted into AppConfig
    // ------------------------------------------------------------------

    @Test
    fun `S10 RESOURCE_LEASES_ENFORCED is read per-call and never hoisted into the AppConfig snapshot`() {
        // Structural: AppConfig's declared fields (its public signature) must not carry a
        // resourceLeasesEnforced property -- checked from the declaration itself, not from
        // AppConfig.fromEnv's body.
        val fieldNames = AppConfig::class.java.declaredFields.map { it.name }
        assertTrue(
            fieldNames.none { it.contains("resourceLeasesEnforced", ignoreCase = true) },
            "AppConfig must not hoist RESOURCE_LEASES_ENFORCED into its startup snapshot; found fields: $fieldNames",
        )

        // Behavioral: two independent calls against two different env snapshots return two
        // different results -- proving the value is resolved fresh at each call, per CLAUDE.md
        // ("read per advance_item/advance-route call ... not once at startup"), never memoized the
        // way a single AppConfig.fromEnv() startup snapshot would be.
        assertTrue(AdvanceService.resourceLeasesEnforcedFromEnv { "true" })
        assertFalse(AdvanceService.resourceLeasesEnforcedFromEnv { "false" })
    }

    // ------------------------------------------------------------------
    // S11 -- single-parser invariant
    // ------------------------------------------------------------------

    @Test
    fun `S11 FLYWAY_REPAIR and API_ALLOW_QUERY_TOKEN_FOR_SSE resolve through the identical shared parser`() {
        // Scope note (see test-manifest): this item's owned files give AppConfig.kt as the first
        // physical call site for both vars. The two SECOND call sites the test-plan names --
        // FlywayDatabaseSchemaManager's constructor-default read of real System.getenv, and
        // EventRoutes.kt's read (not yet wired to EnvBoolean as of this dispatch; that rewiring is
        // an orchestrator post-wave sweep item per plans/bugwave-2026-09.md) -- are not exercisable
        // here without either mutating the JVM environment (a pattern this codebase's tests
        // deliberately avoid, per AppConfigTest's own doc comment) or reading/depending on a file
        // outside this item's ownership. So this test verifies the invariant as fully as the owned
        // surface permits: for every value in the recognized/unrecognized/unset matrix, AppConfig's
        // resolved field for each var equals a direct, independent call to the shared parser with
        // the same (name, raw, default) triple -- i.e., there is exactly one parsing rule backing
        // both named vars, not two coincidentally-similar ones.
        val probeValues: List<String?> = listOf("true", "TRUE", "1", "yes", "false", "FALSE", "0", "no", "maybe", "", null)

        for (raw in probeValues) {
            val flywayViaAppConfig = AppConfig.fromEnv(env("FLYWAY_REPAIR" to raw)).flywayRepair
            val flywayViaSharedParser = EnvBoolean.parse("FLYWAY_REPAIR", raw, default = false)
            assertEquals(
                flywayViaSharedParser,
                flywayViaAppConfig,
                "FLYWAY_REPAIR raw='$raw': AppConfig's resolved value must match the shared parser",
            )

            val sseViaAppConfig = AppConfig.fromEnv(env("API_ALLOW_QUERY_TOKEN_FOR_SSE" to raw)).apiAllowQueryTokenForSse
            val sseViaSharedParser = EnvBoolean.parse("API_ALLOW_QUERY_TOKEN_FOR_SSE", raw, default = false)
            assertEquals(
                sseViaSharedParser,
                sseViaAppConfig,
                "API_ALLOW_QUERY_TOKEN_FOR_SSE raw='$raw': AppConfig's resolved value must match the shared parser",
            )
        }
    }
}
