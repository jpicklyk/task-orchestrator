package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.assertForbidden
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.wholeError
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.pushConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item bfdc296b / Q23) for the scope and capability rules of the `config` tool:
 * scenarios S16 and S17 of the frozen test-plan (NEW-SURFACE), through a real MCP client/server pair whose access seam carries
 * the token's principal, scope and capabilities (the seam the parity harness uses).
 *
 * Oracles: task-scope D2 (the WRITE_CONFIG ops are push, rollback and reset; get, history, validate, effective and rules are
 * READ), D5 (every op runs the scope check on its rootId / itemId FIRST, before existence or depth checks; an absent root under
 * a restricted scope is `forbidden`, never an existence oracle; `effective` without a rootId is the server set and is not
 * scoped), and the ScopeGuard contract exercised by ItemsWriteScopeTest: `forbidden{scope: root, required: <sorted listed root
 * ids>}`, `forbidden{scope: tag, required: <listed tags>}`, `forbidden{scope: capability, required: write-config}`. Every scope
 * rejection precedes any write, so the database snapshot is unchanged. Each forbidden assertion is paired with an in-scope
 * control through the same call shape on the same fixture that DOES run.
 *
 * Fixture: R1 (tag alpha, type ruled) with child C1 and R2 (tag beta) with child D2, each root carrying two config versions and a
 * rule document; fixed ids so the tokens can list them.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigScopeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val d2 = UUID.fromString("b2000002-0000-4000-8000-0000000000d2")
    private val ghost = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")

    private val caps = setOf(ApiCapability.READ, ApiCapability.WRITE_CONFIG)
    private val rootScoped = ParityToken("cfg-root", caps, rootIds = setOf(r1))
    private val tagScoped = ParityToken("cfg-tag", caps, tagsInclude = setOf("alpha"))
    private val readOnly = ParityToken("cfg-read", setOf(ApiCapability.READ))
    private val writer = ParityToken("cfg-writer", caps)

    private val global =
        "work_item_schemas:\n  ruled:\n    notes:\n      - key: guide\n        role: queue\n        required: false\n" +
            "        skill: the-rule\n"

    private suspend fun seeded(): CfgKit {
        val rig = EventLogRig.build(db.db, dir, global)
        val k = CfgKit(rig)
        val root1 = k.root("R1", r1, tags = "alpha", type = "ruled")
        k.child("C1", root1, c1, type = "ruled")
        val root2 = k.root("R2", r2, tags = "beta", type = "ruled")
        k.child("D2", root2, d2, type = "ruled")
        for (root in listOf(r1, r2)) {
            pushConfig(rig.raw, db.db.unitOfWork(), root, CfgYaml.rev(1))
            pushConfig(rig.raw, db.db.unitOfWork(), root, CfgYaml.rev(2))
            rig.raw.planDocumentRepository().stash(root, "rule/the-rule", "rule of $root")
        }
        return k
    }

    /** One minimal, otherwise valid request per op, aimed at [root]. */
    private fun requests(root: Any): List<JsonObject> =
        listOf(
            getOp(root),
            historyOp(root),
            pushOp(root, CfgYaml.rev(3)),
            validateOp(root, CfgYaml.rev(3)),
            rollbackOp(root, 1),
            resetOp(root),
            effectiveOp(root),
            rulesOp("rootId" to ref(root))
        )

    // ------------------------------------------------------------------ S16 root scope

    @Test
    fun `S16 root scope - every op aimed at a root outside the scope is forbidden root and nothing is written`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (op in requests(r2)) {
                assertForbidden(k.callScoped(rootScoped, op).errorAt(), "root", r1.toString())
            }
            assertEquals(before, k.snapshot(), "no forbidden element wrote a row or an event")
            assertEquals(2, k.versions(r2))
        }

    @Test
    fun `S16 root scope - the same request shapes inside the scope run`(): Unit =
        runBlocking {
            val k = seeded()
            for (op in requests(r1)) {
                k.callScoped(rootScoped, op).okResult()
            }
            assertEquals(
                5,
                k.versions(r1),
                "control: the push (v3), the rollback (v4) and the reset (v5) all appended to the seeded two versions"
            )
        }

    @Test
    fun `S16 root scope - an absent root is forbidden for a restricted caller, not_found for an unrestricted one`(): Unit =
        runBlocking {
            val k = seeded()
            for (op in requests(ghost)) {
                assertForbidden(k.callScoped(rootScoped, op).errorAt(), "root", r1.toString())
            }
            for (op in requests(ghost)) {
                assertEquals("not_found", k.callScoped(writer, op).errorAt().str("code"), "control: ${op.str("op")} unrestricted")
            }
        }

    @Test
    fun `S16 root scope - the foreign root given as a prefix or in upper case is judged like the full id`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (spelling in listOf(r2.toString().take(8), r2.toString().uppercase(), r2.toString().take(8).uppercase())) {
                assertForbidden(k.callScoped(rootScoped, pushOp(spelling, CfgYaml.rev(3))).errorAt(), "root", r1.toString())
                assertForbidden(k.callScoped(rootScoped, getOp(spelling)).errorAt(), "root", r1.toString())
            }
            assertEquals(before, k.snapshot())
            k.callScoped(rootScoped, getOp(r1.toString().take(8))).okResult()
        }

    @Test
    fun `S16 root scope - rules by itemId are judged on the item, a foreign item is forbidden`(): Unit =
        runBlocking {
            val k = seeded()
            assertForbidden(
                k.callScoped(rootScoped, rulesOp("itemId" to ref(d2), "noteKey" to ref("guide"))).errorAt(),
                "root",
                r1.toString()
            )
            assertForbidden(
                k.callScoped(rootScoped, rulesOp("itemId" to ref(ghost), "noteKey" to ref("guide"))).errorAt(),
                "root",
                r1.toString()
            )
            val ok = k.callScoped(rootScoped, rulesOp("itemId" to ref(c1), "noteKey" to ref("guide"))).okResult()
            assertEquals("rule of $r1", ok.str("body"), "control: an item inside the scope resolves")
        }

    @Test
    fun `S16 root scope - effective without a rootId is the server set and is allowed`(): Unit =
        runBlocking {
            val k = seeded()
            val view = k.callScoped(rootScoped, effectiveOp()).okResult()
            assertTrue("rootId" !in view, "the server view carries no root: $view")
        }

    @Test
    fun `S16 an atomic batch with a forbidden element fails with its index and rolls back the in-scope write`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val body = k.callScoped(rootScoped, pushOp(r1, CfgYaml.rev(3)), pushOp(r2, CfgYaml.rev(3)), atomic = true)
            val err = body.wholeError()
            assertEquals("forbidden", err.str("code"))
            assertEquals("1", err.str("index"))
            assertEquals(before, k.snapshot(), "the in-scope first element was rolled back")
        }

    // ------------------------------------------------------------------ S16 tag scope

    @Test
    fun `S16 tag scope - a root without a listed tag is forbidden tag for every op and nothing is written`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (op in requests(r2)) {
                assertForbidden(k.callScoped(tagScoped, op).errorAt(), "tag", "alpha")
            }
            assertEquals(before, k.snapshot())
            for (op in requests(r1)) k.callScoped(tagScoped, op).okResult()
        }

    // ------------------------------------------------------------------ S17 capability

    @Test
    fun `S17 a read-only token is forbidden capability write-config on push rollback and reset and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (op in listOf(pushOp(r1, CfgYaml.rev(3)), rollbackOp(r1, 1), resetOp(r1))) {
                assertForbidden(k.callScoped(readOnly, op).errorAt(), "capability", "write-config")
            }
            assertEquals(before, k.snapshot())
            k.callScoped(writer, pushOp(r1, CfgYaml.rev(3))).okResult()
            assertEquals(3, k.versions(r1), "control: a write-config token runs the same call")
        }

    @Test
    fun `S17 a read-only token runs get history validate effective and rules`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val reads = requests(r1).filter { it.str("op") in setOf("get", "history", "validate", "effective", "rules") }
            assertEquals(5, reads.size)
            for (op in reads) k.callScoped(readOnly, op).okResult()
            assertEquals(before, k.snapshot(), "reads (including validate) write nothing")
        }

    @Test
    fun `S17 a token with write-config but no read is not what the reads need - get is forbidden capability read`(): Unit =
        runBlocking {
            val k = seeded()
            val writeOnly = ParityToken("cfg-write-only", setOf(ApiCapability.WRITE_CONFIG))
            assertForbidden(k.callScoped(writeOnly, getOp(r1)).errorAt(), "capability", "read")
            k.callScoped(writeOnly, pushOp(r1, CfgYaml.rev(3))).okResult()
        }
}
