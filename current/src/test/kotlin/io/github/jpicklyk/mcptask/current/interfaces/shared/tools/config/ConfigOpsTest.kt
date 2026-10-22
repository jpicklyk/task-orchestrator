package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.detail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.wholeError
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item bfdc296b / Q23) for the read and write behaviour of the `config` tool, scenarios
 * S1-S13 and S23 of the frozen test-plan plus the probe list (NEW-SURFACE), through the real dispatcher in the orchestration
 * profile over the real composition.
 *
 * Oracles: the task-scope Contract table (fields, result keys) and decisions D1-D12 (D3 superseded push is
 * `version_conflict{config, rootId, expected, actual}` with expected the newest stored version carrying that content, D4 error
 * mapping, D6 reset idempotence, D7 validate reports in data, D12 history pages by `before`); the pre-existing ConfigService
 * KDoc (an identical re-push appends nothing; `force` bypasses the validation and fast-forward guards but never the
 * compare-and-set; a rollback to the head writes nothing; a reset with no head or a reset head returns false; a reset reads as
 * absence); the envelope spec (a failed element of a non-atomic call is a 207 `partial_failure` with the error at
 * `results[i]`, `atomic: true` fails the call with the code's own status and the failing index); the declared field docs
 * (`ConfigDocs`: version >= 1, configYaml at most 131072 bytes, rootId a UUID or an 8 to 32 character hex prefix). Expected
 * version numbers are counted from the sequence of calls. Fixture documents come from [CfgYaml] and the shared strict-validation
 * cases of [StrictConfigCases].
 *
 * Every rejection is paired with a control through the same call shape that succeeds, and every "writes nothing" claim compares
 * a database snapshot taken before the call.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigOpsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val ghost = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")

    private val a = CfgYaml.rev(1)
    private val b = CfgYaml.rev(2)
    private val c = CfgYaml.rev(3)

    private suspend fun seeded(): CfgKit {
        val k = CfgKit(EventLogRig.build(db.db, dir))
        k.root("R1", r1, tags = "alpha")
        k.root("R2", r2, tags = "beta")
        return k
    }

    private fun JsonObject.code(): String = wholeError().str("code")

    // ------------------------------------------------------------------ S1 / S2 push and get

    @Test
    fun `S1 push to a fresh root appends version 1, get returns the same document, and one config event is recorded`(): Unit =
        runBlocking {
            val k = seeded()
            val pushed = k.call(pushOp(r1, a)).okResult()
            assertEquals(r1.toString(), pushed.str("rootId"))
            assertEquals(1, pushed.int("version"))
            assertTrue(pushed.bool("appended"))
            assertTrue(pushed.str("fingerprint").isNotBlank())
            assertTrue(pushed.str("createdAt").isNotBlank())
            assertFalse("warnings" in pushed, "a clean document carries no warnings member: $pushed")

            val got = k.call(getOp(r1)).okResult()
            assertEquals(a, got.str("configYaml"), "the stored document is the pushed document, byte for byte")
            assertEquals(1, got.int("version"))
            assertEquals("push", got.str("source"))
            assertEquals(pushed.str("fingerprint"), got.str("fingerprint"))
            assertEquals(r1.toString(), got.str("rootId"))

            val event = k.configEvents().single()
            assertEquals("config", event.entityKind)
            assertEquals(r1, event.rootId)
        }

    @Test
    fun `S1 a document with an advisory schema warning is accepted and the warning is reported`(): Unit =
        runBlocking {
            val k = seeded()
            val pushed = k.call(pushOp(r1, CfgYaml.WITH_WARNING)).okResult()
            assertEquals(1, pushed.int("version"))
            val warnings = pushed.names("warnings")
            assertTrue(warnings.isNotEmpty(), "the seat-aware schema with an unowned required note warns: $pushed")
            assertEquals(CfgYaml.WITH_WARNING, k.call(getOp(r1)).okResult().str("configYaml"), "control: the document was stored")
        }

    @Test
    fun `S2 an identical re-push appends nothing, keeps version 1 and records no new event`(): Unit =
        runBlocking {
            val k = seeded()
            val first = k.call(pushOp(r1, a)).okResult()
            val before = k.snapshot()
            val again = k.call(pushOp(r1, a)).okResult()
            assertFalse(again.bool("appended"))
            assertEquals(1, again.int("version"))
            assertEquals(first.str("fingerprint"), again.str("fingerprint"))
            assertEquals(1, k.versions(r1))
            assertEquals(1, k.configEvents().size)
            assertEquals(before, k.snapshot(), "an identical re-push changes no table")
        }

    @Test
    fun `S2 the same push twice in one batch appends once, and the second element reports appended false`(): Unit =
        runBlocking {
            val k = seeded()
            val body = k.call(pushOp(r1, a), pushOp(r1, a))
            assertTrue(body.okResult(0).bool("appended"))
            assertFalse(body.okResult(1).bool("appended"))
            assertEquals(1, body.okResult(1).int("version"))
            assertEquals(1, k.versions(r1))
            assertEquals(1, k.configEvents().size)
        }

    @Test
    fun `S2 an idempotencyKey replay of a push returns the stored result and writes no new version`(): Unit =
        runBlocking {
            val k = seeded()
            val keyed = pushOp(r1, a, "idempotencyKey" to ref("cfg-key-1"))
            val first = k.call(keyed, actor = CFG_ACTOR).okResult()
            assertTrue(first.bool("appended"))
            val second = k.call(keyed, actor = CFG_ACTOR).okResult()
            assertEquals("true", second.str("replayed"))
            assertEquals(first.int("version"), second.int("version"))
            assertEquals(1, k.versions(r1))
            assertEquals(1, k.configEvents().size)

            val other = k.call(pushOp(r1, b, "idempotencyKey" to ref("cfg-key-1")), actor = CFG_ACTOR).errorAt()
            assertEquals("idempotency_mismatch", other.str("code"), "the same key with a different payload is a mismatch")
            assertEquals(1, k.versions(r1), "the mismatching push wrote nothing")
        }

    // ------------------------------------------------------------------ S3 / S4 compare-and-set

    @Test
    fun `S3 pushing content equal to an older version is version_conflict with the older and head numbers, force appends`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r1, b)).okResult()
            val before = k.snapshot()

            val err = k.call(pushOp(r1, a)).errorAt()
            assertEquals("version_conflict", err.str("code"))
            assertEquals("config", err.detail().str("kind"))
            assertEquals(r1.toString(), err.detail().str("id"))
            assertEquals(1, err.detail().int("expected"), "expected is the stored version carrying the pushed content")
            assertEquals(2, err.detail().int("actual"), "actual is the head")
            assertEquals(before, k.snapshot(), "the refused push wrote nothing")

            val forced = k.call(pushOp(r1, a, "force" to flag(true))).okResult()
            assertEquals(3, forced.int("version"))
            assertTrue(forced.bool("appended"))
            assertEquals(a, k.call(getOp(r1)).okResult().str("configYaml"), "control: force re-instated the older content")
        }

    @Test
    fun `S3 the superseded push reports the newest stored version with that content`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r1, b)).okResult()
            k.call(pushOp(r1, a, "force" to flag(true))).okResult()
            k.call(pushOp(r1, c)).okResult()
            val err = k.call(pushOp(r1, a)).errorAt()
            assertEquals("version_conflict", err.str("code"))
            assertEquals(3, err.detail().int("expected"), "a (v1) was re-appended as v3: the newest copy is named")
            assertEquals(4, err.detail().int("actual"))
        }

    @Test
    fun `S4 expectedVersion different from the head is version_conflict and force does not bypass it`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r1, b)).okResult()
            val before = k.snapshot()

            val variants: List<Array<Pair<String, JsonElement>>> =
                listOf(arrayOf("expectedVersion" to num(1)), arrayOf("expectedVersion" to num(1), "force" to flag(true)))
            for (extra in variants) {
                val err = k.call(pushOp(r1, c, *extra)).errorAt()
                assertEquals("version_conflict", err.str("code"), "extra: ${extra.toList()}")
                assertEquals("config", err.detail().str("kind"))
                assertEquals(r1.toString(), err.detail().str("id"))
                assertEquals(1, err.detail().int("expected"))
                assertEquals(2, err.detail().int("actual"))
            }
            assertEquals(before, k.snapshot())

            val ok = k.call(pushOp(r1, c, "expectedVersion" to num(2))).okResult()
            assertEquals(3, ok.int("version"), "control: the matching expectedVersion pushes")
        }
    // ------------------------------------------------------------------ S5 / S6 / S8 admission

    @Test
    fun `S5 a top-level plugin key is config_invalid at that key, force does not bypass, nothing is stored`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val variants: List<Array<Pair<String, JsonElement>>> = listOf(emptyArray(), arrayOf("force" to flag(true)))
            for (extra in variants) {
                val err = k.call(pushOp(r1, CfgYaml.INVALID, *extra)).errorAt()
                assertEquals("config_invalid", err.str("code"))
                assertTrue(err.violations().any { it.first == "/orchestration" }, "errors: ${err.violations()}")
            }
            assertEquals(before, k.snapshot())
            assertEquals(0, k.versions(r1))
            assertEquals(1, k.call(pushOp(r1, a)).okResult().int("version"), "control: a valid document pushes on the same root")
        }

    @Test
    fun `S5 every shared strict case is rejected at its pointer and its repaired twin is accepted`(): Unit =
        runBlocking {
            val k = CfgKit(EventLogRig.build(db.db, dir))
            for ((index, case) in StrictConfigCases.ROOT_CASES.withIndex()) {
                val root = k.root("case-$index").id
                val err = k.call(pushOp(root, case.yaml)).errorAt()
                assertEquals("config_invalid", err.str("code"), case.name)
                val paths = err.violations().map { it.first }
                val hit = if (case.withinEntry) paths.any { it.startsWith(case.errorPath) } else case.errorPath in paths
                assertTrue(hit, "${case.name}: expected ${case.errorPath} in $paths")
                assertEquals(0, k.versions(root), "${case.name}: nothing stored")
                assertEquals(1, k.call(pushOp(root, case.fixedYaml)).okResult().int("version"), "${case.name}: repaired twin")
            }
        }

    @Test
    fun `S5 unparseable YAML is config_invalid with one pointer-less violation`(): Unit =
        runBlocking {
            val k = seeded()
            val err = k.call(pushOp(r1, "work_item_schemas: [unclosed\n")).errorAt()
            assertEquals("config_invalid", err.str("code"))
            val violations = err.violations()
            assertEquals(1, violations.size, "violations: $violations")
            assertEquals("", violations.single().first, "a parse failure without a pointer is reported at the empty path")
            assertTrue(violations.single().second.isNotBlank())
            assertEquals(0, k.versions(r1))
        }

    @Test
    fun `S6 a document of exactly 131072 bytes is accepted and one of 131073 bytes is payload_too_large`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val over = k.call(pushOp(r1, CfgYaml.sized(131_073))).errorAt()
            assertEquals("payload_too_large", over.str("code"))
            assertEquals(131_072, over.detail().int("max"))
            assertEquals(131_073, over.detail().int("actual"))
            assertEquals(before, k.snapshot())

            val exact = k.call(pushOp(r1, CfgYaml.sized(131_072))).okResult()
            assertEquals(1, exact.int("version"), "control: the boundary value itself is admitted")
        }

    @Test
    fun `S6 the size is measured in UTF-8 bytes, not characters`(): Unit =
        runBlocking {
            val k = seeded()
            val doc = CfgYaml.rev(1) + "#" + "\u00e9".repeat(65_537)
            assertTrue(doc.length < 131_072, "fixture: fewer characters than the limit")
            val bytes = doc.toByteArray(Charsets.UTF_8).size
            assertTrue(bytes > 131_072, "fixture: more bytes than the limit")
            val err = k.call(pushOp(r1, doc)).errorAt()
            assertEquals("payload_too_large", err.str("code"))
            assertEquals(bytes, err.detail().int("actual"))
            assertEquals(0, k.versions(r1))
        }

    @Test
    fun `S6 validate of an oversized document stays an error and the boundary value validates`(): Unit =
        runBlocking {
            val k = seeded()
            val err = k.call(validateOp(r1, CfgYaml.sized(131_073))).errorAt()
            assertEquals("payload_too_large", err.str("code"))
            assertTrue(k.call(validateOp(r1, CfgYaml.sized(131_072))).okResult().bool("valid"), "control: the boundary value validates")
        }

    @Test
    fun `S8 an embedded project rootId that is not the target is config_invalid at project rootId and force admits it`(): Unit =
        runBlocking {
            val k = seeded()
            val mismatched = "project:\n  rootId: $r2\n" + a
            val err = k.call(pushOp(r1, mismatched)).errorAt()
            assertEquals("config_invalid", err.str("code"))
            val hit = err.violations().single { it.first == "/project/rootId" }
            assertTrue(hit.second.contains(r1.toString().take(8)), "the reason names the target root: ${hit.second}")
            assertTrue(hit.second.contains("force: true"), "the reason names the override: ${hit.second}")
            assertEquals(0, k.versions(r1))

            val forced = k.call(pushOp(r1, mismatched, "force" to flag(true))).okResult()
            assertEquals(1, forced.int("version"))

            val matching = "project:\n  rootId: $r2\n" + b
            assertEquals(
                1,
                k.call(pushOp(r2, matching)).okResult().int("version"),
                "control: an embedded id equal to the target is admitted"
            )
        }

    // ------------------------------------------------------------------ S9 validate

    @Test
    fun `S9 validate of a valid document reports valid true with the diff per kind and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, CfgYaml.DIFF_HEAD)).okResult()
            val before = k.snapshot()

            val checked = k.call(validateOp(r1, CfgYaml.DIFF_PROPOSED)).okResult()
            assertTrue(checked.bool("valid"))
            assertEquals(emptyList(), checked.list("errors"))
            val diff = checked.obj("diff")
            assertEquals(listOf("chore"), diff.obj("types").names("added"))
            assertEquals(emptyList(), diff.obj("types").names("removed"))
            assertEquals(listOf("feature-task"), diff.obj("types").names("changed"))
            assertEquals(emptyList(), diff.obj("traits").names("added"))
            assertEquals(listOf("needs-docs"), diff.obj("traits").names("removed"))
            assertEquals(emptyList(), diff.obj("traits").names("changed"))
            assertTrue("settings" in diff, "the diff has a settings member: $diff")
            assertEquals(before, k.snapshot(), "validate is read-only")
        }

    @Test
    fun `S9 validate of an invalid document is ok with valid false and errors and no diff`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            val before = k.snapshot()
            val checked = k.call(validateOp(r1, CfgYaml.INVALID)).okResult()
            assertFalse(checked.bool("valid"))
            assertTrue(checked.list("errors").any { it.str("path") == "/orchestration" }, "errors: ${checked["errors"]}")
            assertFalse("diff" in checked, "an invalid document carries no diff: $checked")
            assertEquals(before, k.snapshot())

            val unparseable = k.call(validateOp(r1, "work_item_schemas: [unclosed\n")).okResult()
            assertFalse(unparseable.bool("valid"), "a parse failure is data, not an error (D7)")
            assertTrue(unparseable.list("errors").isNotEmpty())

            val mismatch = k.call(validateOp(r1, "project:\n  rootId: $r2\n" + b)).okResult()
            assertFalse(mismatch.bool("valid"), "a root-id mismatch is data, not an error (D7)")
            assertTrue(mismatch.list("errors").any { it.str("path") == "/project/rootId" })
        }

    @Test
    fun `S9 validating the head document yields a valid empty diff, and with no head everything is added`(): Unit =
        runBlocking {
            val k = seeded()
            val noHead = k.call(validateOp(r1, CfgYaml.DIFF_HEAD)).okResult()
            assertEquals(
                listOf("bug-fix", "feature-task"),
                noHead
                    .obj("diff")
                    .obj("types")
                    .names("added")
                    .sorted()
            )
            assertEquals(
                listOf("needs-docs", "needs-review"),
                noHead
                    .obj("diff")
                    .obj("traits")
                    .names("added")
                    .sorted()
            )

            k.call(pushOp(r1, CfgYaml.DIFF_HEAD)).okResult()
            val same = k.call(validateOp(r1, CfgYaml.DIFF_HEAD)).okResult()
            assertTrue(same.bool("valid"))
            val diff = same.obj("diff")
            for (kind in listOf("types", "traits")) {
                for (change in listOf("added", "removed", "changed")) {
                    assertEquals(emptyList(), diff.obj(kind).names(change), "$kind.$change")
                }
            }
        }
    // ------------------------------------------------------------------ S10 history

    @Test
    fun `S10 history lists newest first with the head version and pages by limit and before`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a), pushOp(r1, b), pushOp(r1, c), actor = CFG_ACTOR).okResult(2)

            val all = k.call(historyOp(r1)).okResult()
            assertEquals(3, all.int("headVersion"))
            assertEquals(listOf(3, 2, 1), all.list("versions").map { it.int("version") })
            val newest = all.list("versions").first()
            assertEquals("push", newest.str("source"))
            assertEquals(0, newest.int("warningsCount"))
            assertTrue(newest.bool("canonical"))
            assertEquals("agent-cfg", newest.str("principalId"), "the version records the calling principal")
            assertTrue(newest.str("fingerprint").isNotBlank() && newest.str("createdAt").isNotBlank())

            val limited = k.call(historyOp(r1, "limit" to num(2))).okResult()
            assertEquals(listOf(3, 2), limited.list("versions").map { it.int("version") })
            assertEquals(3, limited.int("headVersion"), "the head is reported whatever the page")

            val paged = k.call(historyOp(r1, "before" to num(3))).okResult()
            assertEquals(listOf(2, 1), paged.list("versions").map { it.int("version") })
            val one = k.call(historyOp(r1, "before" to num(3), "limit" to num(1))).okResult()
            assertEquals(listOf(2), one.list("versions").map { it.int("version") })
            val past = k.call(historyOp(r1, "before" to num(1))).okResult()
            assertEquals(emptyList(), past.list("versions"), "nothing is older than version 1")
        }

    @Test
    fun `S10 a warning-carrying version reports its warning count`(): Unit =
        runBlocking {
            val k = seeded()
            val pushed = k.call(pushOp(r1, CfgYaml.WITH_WARNING)).okResult()
            val count = pushed.names("warnings").size
            assertTrue(count > 0)
            assertEquals(
                count,
                k
                    .call(historyOp(r1))
                    .okResult()
                    .list("versions")
                    .single()
                    .int("warningsCount")
            )
        }

    // ------------------------------------------------------------------ S11 rollback

    @Test
    fun `S11 rollback appends the target content as a new version with source rollback, and a rollback to the head writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r1, b)).okResult()
            k.call(pushOp(r1, c)).okResult()

            val rolled = k.call(rollbackOp(r1, 1)).okResult()
            assertEquals(4, rolled.int("version"))
            assertEquals("rollback", rolled.str("source"))
            assertTrue(rolled.bool("appended"))
            assertTrue(rolled.str("fingerprint").isNotBlank() && rolled.str("createdAt").isNotBlank())
            val head = k.call(getOp(r1)).okResult()
            assertEquals(a, head.str("configYaml"))
            assertEquals(4, head.int("version"))
            assertEquals(4, k.configEvents().size, "three pushes and the rollback each recorded an event")

            val before = k.snapshot()
            val noop = k.call(rollbackOp(r1, 4)).okResult()
            assertFalse(noop.bool("appended"))
            assertEquals(4, noop.int("version"))
            assertEquals(before, k.snapshot(), "a rollback to the head changes no table")
        }

    @Test
    fun `S11 rollback expectedVersion is a compare-and-set on the head`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r1, b)).okResult()
            val before = k.snapshot()
            val err = k.call(rollbackOp(r1, 1, "expectedVersion" to num(1))).errorAt()
            assertEquals("version_conflict", err.str("code"))
            assertEquals("config", err.detail().str("kind"))
            assertEquals(r1.toString(), err.detail().str("id"))
            assertEquals(1, err.detail().int("expected"))
            assertEquals(2, err.detail().int("actual"))
            assertEquals(before, k.snapshot())
            assertEquals(3, k.call(rollbackOp(r1, 1, "expectedVersion" to num(2))).okResult().int("version"), "control")
        }

    // ------------------------------------------------------------------ S12 / S13 reset and reads of a reset

    @Test
    fun `S12 reset appends a reset version that reads as absence, history keeps everything, a second reset appends nothing`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()

            val reset = k.call(resetOp(r1)).okResult()
            assertTrue(reset.bool("appended"))
            assertEquals(r1.toString(), reset.str("rootId"))
            val gone = k.call(getOp(r1)).errorAt()
            assertEquals("not_found", gone.str("code"))
            assertEquals("config", gone.detail().str("kind"))
            assertEquals(r1.toString(), gone.detail().str("id"))
            assertEquals(2, k.versions(r1), "the push and the reset are both kept")
            assertEquals(2, k.call(historyOp(r1)).okResult().int("headVersion"))
            assertEquals(2, k.configEvents().size)

            val before = k.snapshot()
            val again = k.call(resetOp(r1)).okResult()
            assertFalse(again.bool("appended"))
            assertEquals(before, k.snapshot(), "a second reset changes no table")
        }

    @Test
    fun `S12 reset of a root with no config at all is ok with appended false`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertFalse(k.call(resetOp(r1)).okResult().bool("appended"))
            assertEquals(before, k.snapshot())
            assertEquals(1, k.call(pushOp(r1, a)).okResult().int("version"), "control: a push still starts at version 1")
        }

    @Test
    fun `S13 get of the reset version is an empty reset document and an explicit version reads that version`(): Unit =
        runBlocking {
            val k = seeded()
            val first = k.call(pushOp(r1, a)).okResult()
            k.call(resetOp(r1)).okResult()
            val reset = k.call(getOp(r1, "version" to num(2))).okResult()
            assertEquals("", reset.str("configYaml"))
            assertEquals("reset", reset.str("source"))
            assertEquals(2, reset.int("version"))
            val older = k.call(getOp(r1, "version" to num(1))).okResult()
            assertEquals(a, older.str("configYaml"), "an explicit version reads that version")
            assertEquals(first.str("fingerprint"), older.str("fingerprint"))
        }

    @Test
    fun `S13 get by an older fingerprint reports superseded while returning the head`(): Unit =
        runBlocking {
            val k = seeded()
            val first = k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r1, b)).okResult()
            val headFingerprint = k.call(getOp(r1)).okResult().str("fingerprint")
            val relation = k.call(getOp(r1, "fingerprint" to ref(first.str("fingerprint")))).okResult()
            assertEquals("superseded", relation.str("relation"))
            assertEquals(b, relation.str("configYaml"), "the document returned is still the head")
            assertEquals(2, relation.int("version"))
            val current = k.call(getOp(r1, "fingerprint" to ref(headFingerprint))).okResult()
            assertNotEquals("superseded", (current["relation"] as? JsonPrimitive)?.content, "the head fingerprint is not superseded")
        }

    @Test
    fun `S7 get of a version that does not exist and rollback to one are not_found config`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            val before = k.snapshot()
            for (op in listOf(getOp(r1, "version" to num(9)), rollbackOp(r1, 9))) {
                val err = k.call(op).errorAt()
                assertEquals("not_found", err.str("code"), "op: $op")
                assertEquals("config", err.detail().str("kind"))
                assertEquals(r1.toString(), err.detail().str("id"))
            }
            assertEquals(before, k.snapshot())
            val noConfig = k.call(getOp(r2)).errorAt()
            assertEquals("not_found", noConfig.str("code"), "a root with no config has nothing to get")
            assertEquals("config", noConfig.detail().str("kind"))
        }

    // ------------------------------------------------------------------ S7 root errors, id spellings

    @Test
    fun `S7 a depth-1 rootId is invalid_request at rootId and an absent root is not_found root, for every op`(): Unit =
        runBlocking {
            val k = seeded()
            val childItem = k.child("Child", k.root("R3"))
            val before = k.snapshot()
            val ops: (Any) -> List<JsonObject> =
                { id ->
                    listOf(
                        getOp(id),
                        historyOp(id),
                        pushOp(id, a),
                        validateOp(id, a),
                        rollbackOp(id, 1),
                        resetOp(id),
                        effectiveOp(id)
                    )
                }
            for (op in ops(childItem.id)) {
                val err = k.call(op).errorAt()
                assertEquals("invalid_request", err.str("code"), "op ${op.str("op")} on a depth-1 id")
                assertTrue(err.fieldPointers().any { it.endsWith("/rootId") }, "op ${op.str("op")}: ${err.fieldPointers()}")
            }
            for (op in ops(ghost)) {
                val err = k.call(op).errorAt()
                assertEquals("not_found", err.str("code"), "op ${op.str("op")} on an absent id")
                assertEquals("root", err.detail().str("kind"))
            }
            assertEquals(before, k.snapshot(), "no rejected call wrote")
            assertEquals(1, k.call(pushOp(r1, a)).okResult().int("version"), "control: a depth-0 root takes the same push")
        }

    @Test
    fun `S7 an absent rule key under an existing root is not_found document with the rule slug`(): Unit =
        runBlocking {
            val k = seeded()
            val err = k.call(rulesOp("rootId" to ref(r1), "key" to ref("no-such-rule"))).errorAt()
            assertEquals("not_found", err.str("code"))
            assertEquals("document", err.detail().str("kind"))
            assertEquals("rule/no-such-rule", err.detail().str("id"))
        }

    @Test
    fun `S7 root references - 8 hex prefix and upper-case UUID resolve, 7 hex does not, a shared prefix is ambiguous`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            assertEquals(1, k.call(getOp(r1.toString().take(8))).okResult().int("version"), "8-hex prefix")
            assertEquals(1, k.call(getOp(r1.toString().uppercase())).okResult().int("version"), "upper-case UUID")
            assertEquals(1, k.call(getOp(r1.toString().take(8).uppercase())).okResult().int("version"), "upper-case prefix")

            val short = k.call(getOp(r1.toString().take(7))).errorAt()
            assertEquals("invalid_request", short.str("code"))
            assertTrue(short.fieldPointers().any { it.endsWith("/rootId") }, "${short.fieldPointers()}")

            val twin = UUID.fromString("a1000001-ffff-4000-8000-000000000009")
            k.root("Twin", twin)
            val ambiguous = k.call(getOp(r1.toString().take(8))).errorAt()
            assertEquals("ambiguous_id", ambiguous.str("code"))
            assertEquals(r1.toString(), k.call(getOp(r1)).okResult().str("rootId"), "control: the full id still resolves")
        }

    // ------------------------------------------------------------------ S23 numeric bounds

    @Test
    fun `S23 history limit 0 and 201, get and rollback version 0, expectedVersion 0 are invalid_request at their field`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            val before = k.snapshot()
            val cases =
                listOf(
                    historyOp(r1, "limit" to num(0)) to "limit",
                    historyOp(r1, "limit" to num(201)) to "limit",
                    historyOp(r1, "limit" to num(-1)) to "limit",
                    getOp(r1, "version" to num(0)) to "version",
                    getOp(r1, "version" to num(-1)) to "version",
                    rollbackOp(r1, 0) to "version",
                    pushOp(r1, b, "expectedVersion" to num(0)) to "expectedVersion",
                    rollbackOp(r1, 1, "expectedVersion" to num(0)) to "expectedVersion"
                )
            for ((op, field) in cases) {
                val err = k.call(op).errorAt()
                assertEquals("invalid_request", err.str("code"), "$op")
                assertTrue(err.fieldPointers().any { it.endsWith("/$field") }, "$op: ${err.fieldPointers()}")
            }
            assertEquals(before, k.snapshot())
            assertEquals(
                1,
                k
                    .call(historyOp(r1, "limit" to num(200)))
                    .okResult()
                    .list("versions")
                    .size,
                "control: 200 is the maximum"
            )
            assertEquals(
                1,
                k
                    .call(historyOp(r1, "limit" to num(1)))
                    .okResult()
                    .list("versions")
                    .size,
                "control: 1 is the minimum"
            )
        }

    // ------------------------------------------------------------------ probes: request shape

    @Test
    fun `probe an unknown element field is unknown_parameter at its pointer and the element does not run`(): Unit =
        runBlocking {
            val k = seeded()
            val err = k.call(pushOp(r1, a, "bogus" to num(1))).errorAt()
            assertEquals("unknown_parameter", err.str("code"))
            assertEquals(0, k.versions(r1))
            assertEquals(1, k.call(pushOp(r1, a)).okResult().int("version"), "control: without the bogus field the push runs")
        }

    @Test
    fun `probe configYaml absent and null are invalid_request, and the rejected pushes store nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val absent = k.call(cop("push", "rootId" to ref(r1))).errorAt()
            assertEquals("invalid_request", absent.str("code"))
            assertTrue(absent.fieldPointers().any { it.endsWith("/configYaml") }, "${absent.fieldPointers()}")
            val explicitNull = k.call(cop("push", "rootId" to ref(r1), "configYaml" to JsonNull)).errorAt()
            assertEquals("invalid_request", explicitNull.str("code"))
            assertTrue(explicitNull.fieldPointers().any { it.endsWith("/configYaml") }, "${explicitNull.fieldPointers()}")
            assertEquals(0, k.versions(r1))
        }

    @Test
    fun `probe an empty configYaml never answers with an internal fault`(): Unit =
        runBlocking {
            // No frozen oracle states whether "" is admitted or rejected, so the assertion is only that the answer is a defined
            // envelope (not an internal fault) and that the stored history agrees with the answer. Oracle-degraded probe.
            val k = seeded()
            val element = k.call(pushOp(r1, "")).results().single()
            val admitted = element["ok"].toString() == "true"
            if (!admitted) assertNotEquals("internal", element.getValue("error").let { (it as JsonObject).str("code") })
            assertEquals(if (admitted) 1 else 0, k.versions(r1))
        }

    @Test
    fun `probe a missing op field is an element error and the batch continues`(): Unit =
        runBlocking {
            val k = seeded()
            val body =
                k.call(
                    buildJsonObject { put("rootId", r1.toString()) },
                    pushOp(r1, a)
                )
            assertEquals("partial_failure", body.code())
            assertEquals("invalid_request", body.errorAt(0).str("code"))
            assertEquals(1, body.okResult(1).int("version"))
        }

    // ------------------------------------------------------------------ probes: atomicity

    @Test
    fun `probe atomic with a valid push followed by an invalid push stores nothing and reports the failing index`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val body = k.call(pushOp(r1, a), pushOp(r2, CfgYaml.INVALID), atomic = true)
            assertEquals("config_invalid", body.code())
            assertEquals("1", body.wholeError().str("index"))
            assertEquals(before, k.snapshot(), "the valid first element was rolled back")
            assertEquals(0, k.configEvents().size)

            val loose = k.call(pushOp(r1, a), pushOp(r2, CfgYaml.INVALID))
            assertEquals("partial_failure", loose.code())
            assertEquals(1, k.versions(r1), "control: without atomic the valid element stands")
            assertEquals(0, k.versions(r2))
        }

    @Test
    fun `probe the history of a root is independent of another root's pushes`(): Unit =
        runBlocking {
            val k = seeded()
            k.call(pushOp(r1, a)).okResult()
            k.call(pushOp(r2, a)).okResult()
            k.call(pushOp(r2, b)).okResult()
            assertEquals(1, k.versions(r1))
            assertEquals(2, k.versions(r2))
            assertEquals(1, k.call(historyOp(r1)).okResult().int("headVersion"))
            assertEquals(2, k.call(historyOp(r2)).okResult().int("headVersion"))
            assertEquals(
                1,
                k.rig.raw
                    .configStore()
                    .history(ConfigScope.ROOT, r1, 10)
                    .size
            )
        }
}
