package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.detail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item bfdc296b / Q23) for the `effective` and `rules` ops of the `config` tool:
 * scenarios S14, S15, S20, S21 and S22 of the frozen test-plan (NEW-SURFACE).
 *
 * Oracles: plan 4.4 and task-scope D5, D8-D10 (`effective` without a rootId is the server set, with a rootId the per-root
 * resolved view; the per-root types, schemas, traits (dispatch kept, AR-42) and status graph appear; appending writes evict the
 * ambient config memo so a later op of the same call sees the new head; rules modes: rootId alone lists, rootId + key gets,
 * itemId + noteKey resolves the item's effective schema note entry's `skill` as the rule key under the item's own root, any other
 * combination is invalid_request naming a field); the 3.x effective-view field set (schemas[] with type / configSource /
 * configFingerprint / notes, traits[] with name / notes / dispatch, types[], statusGraph.types[].transitions, globalFingerprint,
 * perRootFingerprint) as asserted by the deleted EffectiveConfigRoutesTest; the rule-key pattern of the deleted query_rules
 * tool (lowercase alphanumeric, `.`, `_`, `-`, starting alphanumeric, at most 100 characters). The status-graph oracle for a
 * review-phase note is the pre-existing graph rule: a work -> start transition lands in `review` when the effective schema has a
 * review note and in `terminal` otherwise.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigEffectiveRulesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val ghost = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")

    private val globalYaml =
        "work_item_schemas:\n" +
            "  bug:\n    notes:\n      - key: task-scope\n        role: queue\n        required: true\n" +
            "  feature-task:\n    notes:\n      - key: task-scope\n        role: queue\n        required: true\n" +
            "  ruled:\n    notes:\n" +
            "      - key: guide\n        role: queue\n        required: false\n        skill: alpha-rule\n" +
            "      - key: plain\n        role: queue\n        required: false\n" +
            "      - key: dangling\n        role: queue\n        required: false\n        skill: no-such-rule\n" +
            "      - key: badskill\n        role: queue\n        required: false\n        skill: Not/A Key\n" +
            "traits:\n" +
            "  t1:\n    dispatch:\n      work: { agent: agent-B }\n    notes:\n" +
            "      - key: n-g\n        role: work\n        required: false\n"

    private val perRootYaml =
        "work_item_schemas:\n" +
            "  feature-task:\n    notes:\n" +
            "      - key: task-scope\n        role: queue\n        required: true\n" +
            "      - key: review-checklist\n        role: review\n        required: true\n" +
            "  ghost-type:\n    notes:\n      - key: task-scope\n        role: queue\n        required: true\n" +
            "traits:\n" +
            "  t1:\n    dispatch:\n      work: { agent: agent-A }\n    notes:\n" +
            "      - key: n-pr\n        role: work\n        required: false\n"

    private suspend fun seeded(): CfgKit {
        val k = CfgKit(EventLogRig.build(db.db, dir, globalYaml))
        k.root("R1", r1, tags = "alpha", type = "ruled")
        k.root("R2", r2, tags = "beta")
        return k
    }

    private fun JsonObject.schema(type: String): JsonObject = list("schemas").first { it.str("type") == type }

    private fun JsonObject.graphStart(type: String): String? {
        val transitions = obj("statusGraph").list("types").first { it.str("type") == type }.obj("transitions")
        return (transitions.obj("work")["start"] as? JsonPrimitive)?.content
    }

    // ------------------------------------------------------------------ S14 effective

    @Test
    fun `S14 effective without a rootId is the server set - every schema global, no per-root fingerprint, no rootId`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, perRootYaml)).okResult()
            val view = k.call(effectiveOp()).okResult()
            assertFalse("rootId" in view, "the server set has no root: $view")
            assertEquals(listOf("bug", "feature-task", "ruled"), view.names("types").sorted())
            assertTrue(view.list("schemas").all { it.str("configSource") == "global" }, "schemas: ${view.list("schemas")}")
            assertFalse("perRootFingerprint" in view, "no per-root layer was read: $view")
            assertTrue(view.str("globalFingerprint").isNotBlank())
            assertEquals("terminal", view.graphStart("feature-task"), "the global feature-task schema has no review note")
            val t1 = view.list("traits").first { it.str("name") == "t1" }
            assertEquals("agent-B", t1.obj("dispatch").obj("work").str("agent"))
        }

    @Test
    fun `S14 effective with a rootId is the per-root resolved view - per-root types, traits with dispatch and the status graph`(): Unit =
        runBlocking {
            val k = seeded()
            val pushed = k.call(pushOp(r1, perRootYaml)).okResult()
            val fingerprint = pushed.str("fingerprint")

            val view = k.call(effectiveOp(r1)).okResult()
            assertEquals(r1.toString(), view.str("rootId"))
            assertEquals(listOf("bug", "feature-task", "ghost-type", "ruled"), view.names("types").sorted())
            assertEquals(fingerprint, view.str("perRootFingerprint"))
            assertTrue(view.str("globalFingerprint").isNotBlank())

            val ghostEntry = view.schema("ghost-type")
            assertEquals("per-root", ghostEntry.str("configSource"))
            assertEquals(fingerprint, ghostEntry.str("configFingerprint"))
            assertEquals("per-root", view.schema("feature-task").str("configSource"))
            assertTrue(view.schema("feature-task").list("notes").any { it.str("key") == "review-checklist" })
            assertEquals("global", view.schema("bug").str("configSource"), "a type the root does not override stays global")

            assertEquals("review", view.graphStart("feature-task"), "the graph reflects the per-root review note (AR-42)")
            assertNotNull(
                view.obj("statusGraph").list("types").firstOrNull { it.str("type") == "ghost-type" },
                "per-root type in the graph"
            )

            val t1 = view.list("traits").first { it.str("name") == "t1" }
            assertEquals(
                "agent-A",
                t1.obj("dispatch").obj("work").str("agent"),
                "the per-root trait replaces the global one, dispatch kept"
            )
            assertEquals(listOf("n-pr"), t1.list("notes").map { it.str("key") })
        }

    @Test
    fun `S14 effective for a root with no config equals the server view with the rootId echoed`(): Unit =
        runBlocking {
            val k = seeded()
            val view = k.call(effectiveOp(r2)).okResult()
            assertEquals(r2.toString(), view.str("rootId"))
            assertEquals(listOf("bug", "feature-task", "ruled"), view.names("types").sorted())
            assertTrue(view.list("schemas").all { it.str("configSource") == "global" })
            assertFalse("perRootFingerprint" in view)
        }

    @Test
    fun `S14 another root's per-root config does not leak into this root's view`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, perRootYaml)).okResult()
            val other = k.call(effectiveOp(r2)).okResult()
            assertFalse("ghost-type" in other.names("types"))
            assertEquals("terminal", other.graphStart("feature-task"))
        }

    // ------------------------------------------------------------------ S20 session memo eviction

    @Test
    fun `S20 an effective after a push in the same call sees the new head, and one before it sees the old`(): Unit =
        runBlocking {
            val k = seeded()
            val body = k.call(effectiveOp(r1), pushOp(r1, CfgYaml.rev(2)), effectiveOp(r1))
            val before =
                body
                    .okResult(0)
                    .schema("feature-task")
                    .list("notes")
                    .map { it.str("key") }
            val after =
                body
                    .okResult(2)
                    .schema("feature-task")
                    .list("notes")
                    .map { it.str("key") }
            assertEquals(listOf("task-scope"), before, "the first read is the global schema")
            assertEquals(listOf("spec-2"), after, "the read after the push sees the pushed head, not the memoised layer")
            assertEquals("per-root", body.okResult(2).schema("feature-task").str("configSource"))
        }

    @Test
    fun `S20 two pushes then effective in one call show the second`(): Unit =
        runBlocking {
            val k = seeded()
            val body = k.call(pushOp(r1, CfgYaml.rev(1)), effectiveOp(r1), pushOp(r1, CfgYaml.rev(2)), effectiveOp(r1))
            assertEquals(
                listOf("spec-1"),
                body
                    .okResult(1)
                    .schema("feature-task")
                    .list("notes")
                    .map { it.str("key") }
            )
            assertEquals(
                listOf("spec-2"),
                body
                    .okResult(3)
                    .schema("feature-task")
                    .list("notes")
                    .map { it.str("key") }
            )
        }

    @Test
    fun `S20 rollback and reset also evict, so the effective after them shows the new head`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, CfgYaml.rev(1))).okResult()
            k.call(pushOp(r1, CfgYaml.rev(2))).okResult()

            val rolled = k.call(effectiveOp(r1), rollbackOp(r1, 1), effectiveOp(r1))
            assertEquals(
                listOf("spec-2"),
                rolled
                    .okResult(0)
                    .schema("feature-task")
                    .list("notes")
                    .map { it.str("key") }
            )
            assertEquals(
                listOf("spec-1"),
                rolled
                    .okResult(2)
                    .schema("feature-task")
                    .list("notes")
                    .map { it.str("key") }
            )

            val reset = k.call(effectiveOp(r1), resetOp(r1), effectiveOp(r1))
            assertEquals("per-root", reset.okResult(0).schema("feature-task").str("configSource"))
            val afterReset = reset.okResult(2)
            assertEquals("global", afterReset.schema("feature-task").str("configSource"), "a reset reads as absence")
            assertFalse("perRootFingerprint" in afterReset)
        }

    @Test
    fun `S20 a push that appends nothing leaves the memo valid and a later effective is unchanged`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, CfgYaml.rev(1))).okResult()
            val body = k.call(effectiveOp(r1), pushOp(r1, CfgYaml.rev(1)), effectiveOp(r1))
            assertFalse(body.okResult(1).bool("appended"))
            assertEquals(body.okResult(0), body.okResult(2), "nothing was written, so the view is identical")
        }

    // ------------------------------------------------------------------ S21 failure policy

    @Test
    fun `S21 a head without a canonical form fails effective closed with config_invalid`(): Unit =
        runBlocking {
            val k = seeded()
            val yaml = CfgYaml.rev(1)
            val store = k.rig.raw.configStore()
            val outcome =
                store.append(
                    scope = ConfigScope.ROOT,
                    rootId = r1,
                    append =
                        ConfigAppend(
                            yaml = yaml,
                            fingerprint = store.computeFingerprint(yaml),
                            canonicalJson = null,
                            modelVersion = null,
                            source = ConfigVersionSource.MIGRATION,
                            principalId = null,
                            reqId = null,
                            warnings = emptyList()
                        ),
                    now = Instant.parse("2026-03-01T10:00:00.000Z")
                )
            assertTrue(outcome is ConfigAppendOutcome.Applied, "fixture: the unloadable head was stored")

            val err = k.call(effectiveOp(r1)).errorAt()
            assertEquals("config_invalid", err.str("code"))
            k.call(effectiveOp(r2)).okResult()
        }

    @Test
    fun `S21 a failing head read is unavailable for every op that reads the head, never an empty or global view`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, CfgYaml.rev(1))).okResult()
            val ops =
                listOf(
                    getOp(r1),
                    historyOp(r1),
                    pushOp(r1, CfgYaml.rev(2)),
                    validateOp(r1, CfgYaml.rev(2)),
                    rollbackOp(r1, 1),
                    effectiveOp(r1)
                )
            assertEquals(1, k.call(getOp(r1)).okResult().int("version"), "control: the read works before the fault")

            rawExec(db.db.jdbcUrl, "ALTER TABLE config_heads RENAME TO config_heads_gone")

            val codes =
                ops.associate { op ->
                    op.str("op") to
                        k
                            .call(op)
                            .results()
                            .single()
                            .let { codeOf(it) }
                }
            assertEquals(ops.associate { it.str("op") to "unavailable" }, codes, "the code each op answers with a failing head read")
        }

    private fun codeOf(element: JsonObject): String = (element["error"] as? JsonObject)?.str("code") ?: "ok"
    // ------------------------------------------------------------------ S15 rules

    private suspend fun CfgKit.stash(
        root: UUID,
        key: String,
        body: String
    ) {
        rig.raw.planDocumentRepository().stash(root, "rule/$key", body)
    }

    private val hex64 = Regex("[0-9a-f]{64}")

    @Test
    fun `S15 rules with a rootId lists the rule keys sorted, skips non-rule documents and other roots' rules`(): Unit =
        runBlocking {
            val k = seeded()
            k.stash(r1, "zeta-rule", "zeta body")
            k.stash(r1, "alpha-rule", "alpha body")
            k.stash(r1, "mid.rule", "mid body")
            k.stash(r1, "beta_rule", "beta body")
            k.rig.raw
                .planDocumentRepository()
                .stash(r1, "plan-x", "a plan, not a rule")
            k.stash(r2, "other-rule", "belongs to R2")

            val list = k.call(rulesOp("rootId" to ref(r1))).okResult()
            assertEquals(r1.toString(), list.str("rootId"))
            val rules = list.list("rules")
            assertEquals(listOf("alpha-rule", "beta_rule", "mid.rule", "zeta-rule"), rules.map { it.str("key") })
            for (rule in rules) {
                assertTrue(hex64.matches(rule.str("rulesVersion")), "rulesVersion: ${rule.str("rulesVersion")}")
                assertTrue(rule.str("updatedAt").isNotBlank())
                assertFalse("body" in rule, "a listing never carries bodies: $rule")
            }
            assertEquals(
                4,
                rules.map { it.str("rulesVersion") }.toSet().size,
                "four different bodies give four different versions"
            )

            val none = k.call(rulesOp("rootId" to ref(r2))).okResult().list("rules")
            assertEquals(listOf("other-rule"), none.map { it.str("key") })
        }

    @Test
    fun `S15 rules of a root without rule documents is an empty list`(): Unit =
        runBlocking {
            val k = seeded()
            assertEquals(emptyList(), k.call(rulesOp("rootId" to ref(r2))).okResult().list("rules"))
        }

    @Test
    fun `S15 rules with rootId and key returns the body, slug and version that the listing reported`(): Unit =
        runBlocking {
            val k = seeded()
            k.stash(r1, "alpha-rule", "# alpha\nbody a\n")
            k.stash(r1, "beta-rule", "# beta\nbody b\n")
            val listed =
                k
                    .call(rulesOp("rootId" to ref(r1)))
                    .okResult()
                    .list("rules")
                    .associate { it.str("key") to it.str("rulesVersion") }

            val got = k.call(rulesOp("rootId" to ref(r1), "key" to ref("alpha-rule"))).okResult()
            assertEquals(r1.toString(), got.str("rootId"))
            assertEquals("alpha-rule", got.str("key"))
            assertEquals("rule/alpha-rule", got.str("slug"))
            assertEquals("# alpha\nbody a\n", got.str("body"))
            assertEquals(listed["alpha-rule"], got.str("rulesVersion"))
            assertFalse("resolvedFrom" in got, "a direct get has no pointer provenance: $got")
            val again = k.call(rulesOp("rootId" to ref(r1), "key" to ref("alpha-rule"))).okResult()
            assertEquals(got.str("rulesVersion"), again.str("rulesVersion"), "the version is stable for unchanged content")
            val other = k.call(rulesOp("rootId" to ref(r1), "key" to ref("beta-rule"))).okResult()
            assertNotEquals(got.str("rulesVersion"), other.str("rulesVersion"))
        }

    @Test
    fun `S15 rules by itemId and noteKey resolves the entry skill under the item root, a depth-0 item under itself`(): Unit =
        runBlocking {
            val k = seeded()
            k.stash(r1, "alpha-rule", "alpha body")
            val child =
                k.child(
                    "C1",
                    k.rig.raw
                        .workItemRepository()
                        .getById(r1)!!,
                    c1,
                    type = "ruled"
                )

            val viaChild = k.call(rulesOp("itemId" to ref(child.id), "noteKey" to ref("guide"))).okResult()
            assertEquals(r1.toString(), viaChild.str("rootId"))
            assertEquals("alpha-rule", viaChild.str("key"))
            assertEquals("alpha body", viaChild.str("body"))
            val from = viaChild.obj("resolvedFrom")
            assertEquals(child.id.toString(), from.str("itemId"))
            assertEquals("guide", from.str("noteKey"))
            assertEquals("alpha-rule", from.str("skill"))

            val viaRoot = k.call(rulesOp("itemId" to ref(r1), "noteKey" to ref("guide"))).okResult()
            assertEquals(r1.toString(), viaRoot.str("rootId"), "a depth-0 item is its own root")
            assertEquals("alpha body", viaRoot.str("body"))
            assertEquals(r1.toString(), viaRoot.obj("resolvedFrom").str("itemId"))
        }

    @Test
    fun `S15 a bad noteKey entry is invalid_request at noteKey, a dangling skill is not_found`(): Unit =
        runBlocking {
            val k = seeded()
            k.stash(r1, "alpha-rule", "alpha body")
            for (noteKey in listOf("plain", "no-such-entry", "badskill")) {
                val err = k.call(rulesOp("itemId" to ref(r1), "noteKey" to ref(noteKey))).errorAt()
                assertEquals("invalid_request", err.str("code"), noteKey)
                assertTrue(err.fieldPointers().any { it.endsWith("/noteKey") }, "$noteKey: ${err.fieldPointers()}")
            }
            val dangling = k.call(rulesOp("itemId" to ref(r1), "noteKey" to ref("dangling"))).errorAt()
            assertEquals("not_found", dangling.str("code"))
            assertEquals("document", dangling.detail().str("kind"))
            assertEquals("rule/no-such-rule", dangling.detail().str("id"))

            val missingItem = k.call(rulesOp("itemId" to ref(ghost), "noteKey" to ref("guide"))).errorAt()
            assertEquals("not_found", missingItem.str("code"))
            assertEquals("item", missingItem.detail().str("kind"))
            assertEquals("alpha body", k.call(rulesOp("itemId" to ref(r1), "noteKey" to ref("guide"))).okResult().str("body"), "control")
        }

    // ------------------------------------------------------------------ S22 mode combinations

    private fun JsonObject.namesAnyOf(vararg candidates: String): Boolean {
        val named = fieldPointers().map { it.substringAfterLast('/') }
        return named.any { it in candidates }
    }

    @Test
    fun `S22 every other combination of rules fields is invalid_request naming one of the offending fields`(): Unit =
        runBlocking {
            val k = seeded()
            k.stash(r1, "alpha-rule", "alpha body")
            val cases =
                listOf(
                    Triple("no fields at all", rulesOp(), arrayOf("rootId", "itemId", "key", "noteKey")),
                    Triple("rootId with itemId", rulesOp("rootId" to ref(r1), "itemId" to ref(r1)), arrayOf("rootId", "itemId")),
                    Triple("key without rootId", rulesOp("key" to ref("alpha-rule")), arrayOf("key", "rootId")),
                    Triple("noteKey without itemId", rulesOp("rootId" to ref(r1), "noteKey" to ref("guide")), arrayOf("noteKey", "itemId")),
                    Triple("itemId without noteKey", rulesOp("itemId" to ref(r1)), arrayOf("itemId", "noteKey")),
                    Triple(
                        "rootId key itemId noteKey together",
                        rulesOp("rootId" to ref(r1), "key" to ref("alpha-rule"), "itemId" to ref(r1), "noteKey" to ref("guide")),
                        arrayOf("rootId", "key", "itemId", "noteKey")
                    )
                )
            for ((label, op, candidates) in cases) {
                val err = k.call(op).errorAt()
                assertEquals("invalid_request", err.str("code"), label)
                assertTrue(err.namesAnyOf(*candidates), "$label: ${err.fieldPointers()}")
            }
            assertEquals("alpha body", k.call(rulesOp("rootId" to ref(r1), "key" to ref("alpha-rule"))).okResult().str("body"), "control")
        }

    @Test
    fun `S22 a key outside the rule-key pattern is invalid_request at key, the 100 character boundary is a plain not_found`(): Unit =
        runBlocking {
            val k = seeded()
            for (bad in listOf("Upper", "has/slash", "a b", "-leading", ".leading", "_leading", "a".repeat(101))) {
                val err = k.call(rulesOp("rootId" to ref(r1), "key" to ref(bad))).errorAt()
                assertEquals("invalid_request", err.str("code"), bad)
                assertTrue(err.fieldPointers().any { it.endsWith("/key") }, "$bad: ${err.fieldPointers()}")
            }
            val boundary = "a".repeat(100)
            val absent = k.call(rulesOp("rootId" to ref(r1), "key" to ref(boundary))).errorAt()
            assertEquals("not_found", absent.str("code"), "a 100 character key is well formed")
            assertEquals("rule/$boundary", absent.detail().str("id"))
            for (good in listOf("a", "0", "a.b_c-d", "9lives")) {
                assertEquals("not_found", k.call(rulesOp("rootId" to ref(r1), "key" to ref(good))).errorAt().str("code"), good)
            }
        }
}
