package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.CfgYaml
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `config` slice of [ProductionParity] (item bfdc296b / Q23): one HAPPY and one SCOPE_FORBIDDEN scenario per op (get,
 * history, push, validate, rollback, reset, effective, rules) plus OTHER scenarios for failures whose status and shape the
 * envelope spec fixes. The expectations are literals from the envelope spec and the task-scope contract: a failed element of a
 * non-atomic batch is a 207 `partial_failure` whose element carries the error; a forbidden element carries `{scope, required}`;
 * an atomic failure answers with the code's own status.
 *
 * Fixture: R1 "Alpha root" (tag alpha) with config versions 1 and 2 (revisions 1 and 2) and the rule `alpha-rule`; R2 "Beta
 * root" (tag beta) with one config version (revision 7) and the rule `beta-rule`. Seeded identically on both legs.
 */
internal object ConfigParityScenarios {
    val r1: UUID = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    val r2: UUID = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val ghost: UUID = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")

    private val reader = ParityToken("cfg-parity-reader", setOf(ApiCapability.READ))
    private val writer = ParityToken("cfg-parity-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_CONFIG))
    private val rootScoped = ParityToken("cfg-parity-root", setOf(ApiCapability.READ, ApiCapability.WRITE_CONFIG), rootIds = setOf(r1))

    val seed: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(r2, "Beta root", tags = "beta")
        config(r1, CfgYaml.rev(1))
        config(r1, CfgYaml.rev(2))
        config(r2, CfgYaml.rev(7))
        rule(r1, "alpha-rule", "alpha rule body\n")
        rule(r2, "beta-rule", "beta rule body\n")
    }

    private fun obj(vararg fields: Pair<String, Any>): JsonObject =
        buildJsonObject {
            fields.forEach { (k, v) ->
                when (v) {
                    is Int -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

    private fun ops(
        vararg elements: JsonObject,
        atomic: Boolean = false
    ): String =
        buildJsonObject {
            if (atomic) put("atomic", true)
            put("ops", JsonArray(elements.toList()))
        }.toString()

    private fun scenario(
        name: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList(),
        expectDbChanged: Boolean? = false
    ) = ParityScenario(
        name = "config $name",
        tool = "config",
        member = member,
        kind = kind,
        token = token,
        arguments = arguments,
        expectStatus = expectStatus,
        expectCode = expectCode,
        expectContains = expectContains,
        expectAbsent = expectAbsent,
        expectDbChanged = expectDbChanged,
        seed = seed
    )

    private val forbiddenRoot = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$r1\"")

    private fun foreign(
        name: String,
        member: String,
        element: JsonObject,
        absent: List<String> = listOf("spec-7", "beta-rule")
    ) = scenario(
        "$name scope-forbidden foreign root",
        member,
        ParityKind.SCOPE_FORBIDDEN,
        rootScoped,
        ops(element),
        207,
        "partial_failure",
        forbiddenRoot,
        absent
    )

    private fun op(
        name: String,
        vararg fields: Pair<String, Any>
    ) = obj("op" to name, *fields)

    val happy: List<ParityScenario> =
        listOf(
            scenario(
                "get happy",
                "get",
                ParityKind.HAPPY,
                reader,
                ops(op("get", "rootId" to r1)),
                200,
                expectContains = listOf("\"version\":2", "\"source\":\"push\"", "spec-2")
            ),
            scenario(
                "history happy",
                "history",
                ParityKind.HAPPY,
                reader,
                ops(op("history", "rootId" to r1)),
                200,
                expectContains = listOf("\"headVersion\":2", "\"canonical\":true")
            ),
            scenario(
                "validate happy",
                "validate",
                ParityKind.HAPPY,
                reader,
                ops(obj("op" to "validate", "rootId" to r1, "configYaml" to CfgYaml.rev(3))),
                200,
                expectContains = listOf("\"valid\":true", "\"diff\"")
            ),
            scenario(
                "effective happy",
                "effective",
                ParityKind.HAPPY,
                reader,
                ops(op("effective", "rootId" to r1)),
                200,
                expectContains = listOf("\"rootId\":\"$r1\"", "\"perRootFingerprint\"", "feature-task")
            ),
            scenario(
                "rules happy get by key",
                "rules",
                ParityKind.HAPPY,
                reader,
                ops(op("rules", "rootId" to r1, "key" to "alpha-rule")),
                200,
                expectContains = listOf("alpha rule body", "\"slug\":\"rule/alpha-rule\"", "\"rulesVersion\""),
                expectAbsent = listOf("beta-rule")
            )
        )

    /** The writing HAPPY scenarios: they append a version and an event on both legs. */
    val writing: List<ParityScenario> =
        listOf(
            scenario(
                "push happy",
                "push",
                ParityKind.HAPPY,
                writer,
                ops(obj("op" to "push", "rootId" to r1, "configYaml" to CfgYaml.rev(3))),
                200,
                expectContains = listOf("\"appended\":true", "\"version\":3"),
                expectDbChanged = true
            ),
            scenario(
                "rollback happy",
                "rollback",
                ParityKind.HAPPY,
                writer,
                ops(op("rollback", "rootId" to r1, "version" to 1)),
                200,
                expectContains = listOf("\"appended\":true", "\"version\":3", "\"source\":\"rollback\""),
                expectDbChanged = true
            ),
            scenario(
                "reset happy",
                "reset",
                ParityKind.HAPPY,
                writer,
                ops(op("reset", "rootId" to r1)),
                200,
                expectContains = listOf("\"appended\":true"),
                expectDbChanged = true
            )
        )

    val scopeForbidden: List<ParityScenario> =
        listOf(
            foreign("get", "get", op("get", "rootId" to r2)),
            foreign("history", "history", op("history", "rootId" to r2)),
            foreign("push", "push", obj("op" to "push", "rootId" to r2, "configYaml" to CfgYaml.rev(3))),
            foreign("validate", "validate", obj("op" to "validate", "rootId" to r2, "configYaml" to CfgYaml.rev(3))),
            foreign("rollback", "rollback", op("rollback", "rootId" to r2, "version" to 1)),
            foreign("reset", "reset", op("reset", "rootId" to r2)),
            foreign("effective", "effective", op("effective", "rootId" to r2)),
            foreign("rules", "rules", op("rules", "rootId" to r2))
        )

    val other: List<ParityScenario> =
        listOf(
            scenario(
                "effective without a rootId under a root scope is the server set",
                "effective",
                ParityKind.OTHER,
                rootScoped,
                ops(op("effective")),
                200,
                expectContains = listOf("\"globalFingerprint\""),
                expectAbsent = listOf("perRootFingerprint")
            ),
            scenario(
                "ghost root under a root scope is forbidden",
                "get",
                ParityKind.OTHER,
                rootScoped,
                ops(op("get", "rootId" to ghost)),
                207,
                "partial_failure",
                forbiddenRoot
            ),
            scenario(
                "ghost root unrestricted is not_found root",
                "get",
                ParityKind.OTHER,
                reader,
                ops(op("get", "rootId" to ghost)),
                207,
                "partial_failure",
                listOf("\"code\":\"not_found\"", "\"kind\":\"root\"")
            ),
            scenario(
                "push without the write-config capability",
                "push",
                ParityKind.OTHER,
                reader,
                ops(obj("op" to "push", "rootId" to r1, "configYaml" to CfgYaml.rev(3))),
                207,
                "partial_failure",
                listOf("\"scope\":\"capability\"", "\"required\":\"write-config\"")
            ),
            scenario(
                "push superseded content is version_conflict",
                "push",
                ParityKind.OTHER,
                writer,
                ops(obj("op" to "push", "rootId" to r1, "configYaml" to CfgYaml.rev(1))),
                207,
                "partial_failure",
                listOf("\"code\":\"version_conflict\"", "\"kind\":\"config\"", "\"expected\":1", "\"actual\":2")
            ),
            scenario(
                "push stale expectedVersion is version_conflict",
                "push",
                ParityKind.OTHER,
                writer,
                ops(obj("op" to "push", "rootId" to r1, "configYaml" to CfgYaml.rev(3), "expectedVersion" to 1)),
                207,
                "partial_failure",
                listOf("\"code\":\"version_conflict\"", "\"expected\":1", "\"actual\":2")
            ),
            scenario(
                "push of an invalid document is config_invalid",
                "push",
                ParityKind.OTHER,
                writer,
                ops(obj("op" to "push", "rootId" to r1, "configYaml" to CfgYaml.INVALID)),
                207,
                "partial_failure",
                listOf("\"code\":\"config_invalid\"", "/orchestration")
            ),
            scenario(
                "validate of an invalid document is data",
                "validate",
                ParityKind.OTHER,
                reader,
                ops(obj("op" to "validate", "rootId" to r1, "configYaml" to CfgYaml.INVALID)),
                200,
                expectContains = listOf("\"valid\":false", "/orchestration")
            ),
            scenario(
                "atomic push with a forbidden second element fails with its index",
                "push",
                ParityKind.OTHER,
                rootScoped,
                ops(
                    obj("op" to "push", "rootId" to r1, "configYaml" to CfgYaml.rev(3)),
                    obj("op" to "push", "rootId" to r2, "configYaml" to CfgYaml.rev(3)),
                    atomic = true
                ),
                403,
                "forbidden",
                listOf("\"index\":1")
            ),
            scenario(
                "rules with an invalid combination is invalid_request",
                "rules",
                ParityKind.OTHER,
                reader,
                ops(op("rules", "key" to "alpha-rule")),
                207,
                "partial_failure",
                listOf("\"code\":\"invalid_request\"")
            ),
            scenario(
                "history limit above the maximum is invalid_request",
                "history",
                ParityKind.OTHER,
                reader,
                ops(op("history", "rootId" to r1, "limit" to 201)),
                207,
                "partial_failure",
                listOf("\"code\":\"invalid_request\"", "limit")
            ),
            scenario(
                "get unknown member",
                "get",
                ParityKind.OTHER,
                reader,
                ops(op("get", "rootId" to r1, "bogus" to 1)),
                207,
                "partial_failure",
                listOf("\"code\":\"unknown_parameter\"", "/bogus")
            ),
            scenario(
                "get of an absent version is not_found config",
                "get",
                ParityKind.OTHER,
                reader,
                ops(op("get", "rootId" to r1, "version" to 9)),
                207,
                "partial_failure",
                listOf("\"code\":\"not_found\"", "\"kind\":\"config\"")
            ),
            scenario(
                "rules for an absent key is not_found document",
                "rules",
                ParityKind.OTHER,
                reader,
                ops(op("rules", "rootId" to r1, "key" to "no-such-rule")),
                207,
                "partial_failure",
                listOf("\"code\":\"not_found\"", "\"kind\":\"document\"", "rule/no-such-rule")
            )
        )

    /** Scenarios that leave the database unchanged: the shared harness compares them as is. */
    val nonWriting: List<ParityScenario> = happy + scopeForbidden + other

    val scenarios: List<ParityScenario> = nonWriting + writing

    val exemptions: List<ScopeExempt> = emptyList()
}

@Timeout(value = 900, unit = TimeUnit.SECONDS)
class ConfigParityTest {
    private val harness = ParityHarness(ToolRegistry(buildToolSpecs()))

    /** The root-scope version rows (the boot import also leaves one server-scope row in both legs). */
    private fun rootVersions(outcome: ParityOutcome): Int = outcome.snapshot.getValue("config_versions").count { it["scope"] == "root" }

    @Test
    fun `every read and failure config scenario agrees across MCP and REST in body, status and database`() {
        for (s in ConfigParityScenarios.nonWriting) harness.run(s)
    }

    @Test
    fun `push happy agrees across MCP and REST and appends one version and one event on both legs`() {
        val outcome = harness.run(ConfigParityScenarios.writing.single { it.member == "push" })
        assertEquals(4, rootVersions(outcome), "R1 two seeded + R2 one seeded + the pushed one")
        assertEquals(1, outcome.snapshot.getValue("events").count { it["type"] == "config.version_created" })
        assertTrue(outcome.dbChanged)
    }

    @Test
    fun `rollback happy agrees across MCP and REST and appends the rollback version and one event`() {
        val outcome = harness.run(ConfigParityScenarios.writing.single { it.member == "rollback" })
        assertEquals(4, rootVersions(outcome), "R1 two seeded + R2 one seeded + the appended one")
        assertEquals(1, outcome.snapshot.getValue("events").count { it["type"] == "config.version_created" })
    }

    @Test
    fun `reset happy agrees across MCP and REST and appends the reset version and one event`() {
        val outcome = harness.run(ConfigParityScenarios.writing.single { it.member == "reset" })
        assertEquals(4, rootVersions(outcome), "R1 two seeded + R2 one seeded + the appended one")
        assertEquals(1, outcome.snapshot.getValue("events").count { it["type"] == "config.version_created" })
    }

    @Test
    fun `control - the catalog holds a happy and a restricted scope-forbidden scenario for each op`() {
        val byMember = ConfigParityScenarios.scenarios.groupBy { it.member }
        assertEquals(
            setOf("get", "history", "push", "validate", "rollback", "reset", "effective", "rules"),
            byMember.keys
        )
        for ((member, list) in byMember) {
            assertTrue(list.any { it.kind == ParityKind.HAPPY }, "$member has a HAPPY scenario")
            assertTrue(
                list.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$member has a restricted-scope SCOPE_FORBIDDEN scenario"
            )
        }
    }
}
