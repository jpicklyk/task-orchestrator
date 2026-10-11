package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.createCommand
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for the Q2b `type` rule at the service seam (item 2c67eeca): scenarios S1-S6 and S10 of the frozen
 * test-plan, plus adversarial probes. Everything runs through the real production composition ([EventLogRig]) over a
 * real SQLite file; the per-root layer is pushed with the project-config store exactly as `manage_project_config` does.
 *
 * Oracles (frozen in the queue phase, none read from the implementation): task-scope AC1 (missing type on create: always
 * rejected, `schema_violation`, message `type is required` plus `; available types: a, b` only when the effective set is
 * non-empty, detail `SchemaViolation(type = null, reason = "type is required", availableTypes = sorted | null)`), AC2
 * (undeclared type rejected only when the effective set is non-empty, matching exact and case-sensitive, reason
 * `type '<t>' is not declared`, `detail.type` echoes the input), AC3 (a patch whose final type is blank is rejected, a
 * changed type is checked like a create, an unchanged type is never checked, rejection precedes the write so version and
 * modifiedAt are untouched), AC4 (effective set = declared keys of both layers under the root's resolution mode, per-root
 * only under isolated, `default` is a key like any other, sorted ascending; a per-root read fault is
 * `config_unavailable`), AC5 (classifiers), decisions D1 D2 D3 D4 D10 D12, plan s3.7 and U7. The per-mode layering of
 * the two layers is the documented table in `config-format.md` ("Global vs Per-Project Config").
 *
 * Probe inventory: blank variants (null, empty, spaces, tab), leading and trailing whitespace on an otherwise declared
 * type, case variants, the reserved residue value `untyped`, `default` as a declared key, sort order against declaration
 * order, isolated versus legacy and layered enumeration, reparent into a root with a different set, unchanged type on a
 * stale value, and a config read fault with a healthy control.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemTypeRuleTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // Declared out of order on purpose: availableTypes must come back sorted, not in declaration order.
    private val typesYaml =
        "work_item_schemas:\n" +
            "  gamma-type:\n    notes: []\n" +
            "  alpha-type:\n    notes: []\n" +
            "  default:\n    notes: []\n"

    private val declared = listOf("alpha-type", "default", "gamma-type")

    private fun rig(
        dir: Path,
        yaml: String = typesYaml,
    ) = EventLogRig.build(db.db, dir, yaml)

    private val EventLogRig.svc: ItemCommandService get() = ctx.itemCommandService

    private suspend fun EventLogRig.itemCount(): Int = rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items")

    private suspend fun EventLogRig.make(
        title: String,
        type: String,
        parentId: UUID? = null,
    ): WorkItem = svc.create(createCommand(title = title, parentId = parentId, type = type)).orFail()

    private suspend fun EventLogRig.pushRootConfig(
        root: WorkItem,
        yaml: String,
    ) {
        raw.configStore().upsert(root.id, yaml)
    }

    private suspend fun EventLogRig.reload(item: WorkItem): WorkItem = raw.workItemRepository().getById(item.id)!!

    private fun <T> Outcome<T>.errorOrFail(): DomainError =
        when (this) {
            is Outcome.Err -> error
            is Outcome.Ok -> throw AssertionError("expected a rejection, got $value")
        }

    private fun DomainError.violation(): ErrorDetail.SchemaViolation = assertIs<ErrorDetail.SchemaViolation>(detail)

    // ---------------------------------------------------------------------------------------------
    // S1 - declared types are accepted
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 create with a type declared in the global config stores it as given`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)

            val item = r.make("root", "gamma-type")

            assertEquals("gamma-type", r.reload(item).type)
        }

    @Test
    fun `S1 create under a root with a per-root config accepts a per-root key and, under legacy, a global key`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val root = r.make("root", "alpha-type")
            r.pushRootConfig(root, "work_item_schemas:\n  per-root-type:\n    notes: []\n")

            val perRoot = r.make("per-root child", "per-root-type", root.id)
            val global = r.make("global child", "gamma-type", root.id)

            assertEquals("per-root-type", r.reload(perRoot).type)
            assertEquals("gamma-type", r.reload(global).type)
        }

    @Test
    fun `S1 the removed schema_resolution isolated key has no effect -- the global keys stay declared`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val root = r.make("root", "alpha-type")
            r.pushRootConfig(root, "schema_resolution: isolated\nwork_item_schemas:\n  only-here:\n    notes: []\n")

            val ok = r.make("accepted", "only-here", root.id)
            val global = r.make("global key", "gamma-type", root.id)
            val before = r.itemCount()
            val err = r.svc.create(createCommand(title = "nowhere", parentId = root.id, type = "nope-type")).errorOrFail()

            assertEquals("only-here", r.reload(ok).type)
            assertEquals("gamma-type", r.reload(global).type, "a global key is still declared for the root")
            assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code)
            assertEquals(listOf("alpha-type", "default", "gamma-type", "only-here"), err.violation().availableTypes)
            assertEquals("nope-type", err.violation().type)
            assertEquals(before, r.itemCount(), "the rejected create wrote nothing")
        }

    @Test
    fun `S1 the available types are the sorted union of both layers under legacy and layered, default included`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)

            for (mode in listOf("legacy", "layered")) {
                val root = r.make("root-$mode", "alpha-type")
                r.pushRootConfig(
                    root,
                    "schema_resolution: $mode\nwork_item_schemas:\n  zz-type:\n    notes: []\n  aa-type:\n    notes: []\n"
                )

                val err = r.svc.create(createCommand(title = "x", parentId = root.id, type = "nope")).errorOrFail()

                assertEquals(
                    listOf("aa-type", "alpha-type", "default", "gamma-type", "zz-type"),
                    err.violation().availableTypes,
                    "mode=$mode",
                )
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S2 - schema-free server accepts any non-blank type
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 with no schema declared any non-blank type is accepted exactly as given`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir, "work_item_schemas: {}\n")

            for (type in listOf("anything", "untyped", "default", "Mixed Case 1")) {
                val item = r.make("item $type", type)
                assertEquals(type, r.reload(item).type)
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S3 - patches leave stale and residue types editable
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 an untyped residue item and a stale undeclared type stay editable while the type is unchanged`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val residue = r.raw.workItemRepository().create(WorkItem(title = "residue", type = "untyped"))
            val stale = r.raw.workItemRepository().create(WorkItem(title = "stale", type = "gone-type"))

            val patchedResidue =
                r.svc
                    .patch(patchOf(residue, title = "residue renamed"))
                    .orFail()
                    .item
            val patchedStale =
                r.svc
                    .patch(patchOf(stale, title = "stale renamed"))
                    .orFail()
                    .item

            assertEquals("residue renamed", r.reload(residue).title)
            assertEquals("untyped", patchedResidue.type)
            assertEquals("untyped", r.reload(residue).type)
            assertEquals("stale renamed", r.reload(stale).title)
            assertEquals("gone-type", patchedStale.type)
            assertEquals("gone-type", r.reload(stale).type)
        }

    @Test
    fun `S3 a changed type on a residue item is checked like a create`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val residue = r.raw.workItemRepository().create(WorkItem(title = "residue", type = "untyped"))

            val rejected = r.svc.patch(patchOf(residue).copy(type = "nope")).errorOrFail()
            assertEquals("untyped", r.reload(residue).type, "a rejected change leaves the residue value")

            r.svc.patch(patchOf(residue).copy(type = "alpha-type")).orFail()

            assertEquals(ErrorCode.SCHEMA_VIOLATION, rejected.code)
            assertEquals("alpha-type", r.reload(residue).type)
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - missing type on create
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 a create without a type is rejected with the typed error and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)

            for (blank in listOf(null, "", "  ", "\t")) {
                val items = r.itemCount()
                val seq = r.maxSeq()

                val err = r.svc.create(createCommand(title = "no type", type = blank)).errorOrFail()

                val label = "type=${blank?.let { "'$it'" }}"
                assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code, label)
                assertEquals("type is required; available types: alpha-type, default, gamma-type", err.message, label)
                val violation = err.violation()
                assertNull(violation.type, label)
                assertEquals("type is required", violation.reason, label)
                assertEquals(declared, violation.availableTypes, label)
                assertTrue(ItemCommandErrors.isTypeViolation(err), label)
                assertEquals(declared, ItemCommandErrors.availableTypes(err), label)
                assertEquals(items, r.itemCount(), "$label: no row written")
                assertEquals(seq, r.maxSeq(), "$label: no event recorded")
            }
        }

    @Test
    fun `S4 a schema-free server still requires a type and reports no available types`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir, "work_item_schemas: {}\n")

            val err = r.svc.create(createCommand(title = "no type", type = null)).errorOrFail()

            assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code)
            assertEquals("type is required", err.message, "no suffix when nothing is declared")
            assertNull(err.violation().availableTypes)
            assertNull(ItemCommandErrors.availableTypes(err))
            assertTrue(ItemCommandErrors.isTypeViolation(err))
            assertEquals(0, r.itemCount())
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - undeclared type on create
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 an undeclared type is rejected exactly and the input is echoed, nothing is written`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val control = r.make("declared control", "alpha-type")

            // Each of these differs from the declared "alpha-type" only by case or surrounding whitespace, or is the
            // reserved residue value; none may be normalised into a match.
            for (type in listOf("nope", " alpha-type", "alpha-type ", "Alpha-Type", "ALPHA-TYPE", "untyped")) {
                val items = r.itemCount()
                val seq = r.maxSeq()

                val err = r.svc.create(createCommand(title = "bad", type = type)).errorOrFail()

                assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code, "type='$type'")
                val violation = err.violation()
                assertEquals(type, violation.type, "type='$type': the input is echoed unchanged")
                assertEquals("type '$type' is not declared", violation.reason, "type='$type'")
                assertEquals(declared, violation.availableTypes, "type='$type'")
                assertTrue(err.message.contains("type '$type' is not declared"), "type='$type': ${err.message}")
                assertTrue(ItemCommandErrors.isTypeViolation(err), "type='$type'")
                assertEquals(declared, ItemCommandErrors.availableTypes(err), "type='$type'")
                assertEquals(items, r.itemCount(), "type='$type': no row written")
                assertEquals(seq, r.maxSeq(), "type='$type': no event recorded")
            }
            assertEquals("alpha-type", r.reload(control).type, "control: the declared spelling is accepted")
        }

    @Test
    fun `S5 default is accepted when it is a declared key`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)

            val item = r.make("typed default", "default")

            assertEquals("default", r.reload(item).type)
        }

    // ---------------------------------------------------------------------------------------------
    // S6 - patches that clear or change the type
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a patch that clears the type is rejected and the stored row is untouched`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val item = r.make("item", "alpha-type")
            val before = r.reload(item)
            val seq = r.maxSeq()

            for (blank in listOf(null, "", "   ")) {
                val err = r.svc.patch(patchOf(item, title = "renamed").copy(type = blank)).errorOrFail()

                val label = "type=${blank?.let { "'$it'" }}"
                assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code, label)
                assertEquals("type is required", err.violation().reason, label)
                assertNull(err.violation().type, label)
                assertEquals(declared, err.violation().availableTypes, label)
                assertEquals(before, r.reload(item), "$label: version, modifiedAt, title and type are all untouched")
                assertEquals(seq, r.maxSeq(), "$label: no event recorded")
            }
        }

    @Test
    fun `S6 a patch to an undeclared type is rejected and a patch to a declared type is applied`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val item = r.make("item", "alpha-type")
            val before = r.reload(item)

            val err = r.svc.patch(patchOf(item).copy(type = "nope")).errorOrFail()

            assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code)
            assertEquals("nope", err.violation().type)
            assertEquals("type 'nope' is not declared", err.violation().reason)
            assertEquals(declared, err.violation().availableTypes)
            assertEquals(before, r.reload(item), "the rejected patch changed nothing")

            val applied =
                r.svc
                    .patch(patchOf(item).copy(type = "gamma-type"))
                    .orFail()
                    .item
            assertEquals("gamma-type", applied.type)
            assertEquals("gamma-type", r.reload(item).type)
        }

    @Test
    fun `S6 a changed type is checked against the destination root and an unchanged type is not`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = rig(dir)
            val rootA = r.make("root A", "alpha-type")
            val rootB = r.make("root B", "alpha-type")
            r.pushRootConfig(rootB, "work_item_schemas:\n  only-b:\n    notes: []\n")
            val x = r.make("X", "gamma-type", rootA.id)

            // Changing the type while moving into root B: "only-a-type" is declared nowhere for root B.
            val err = r.svc.patch(patchOf(x, parent = ParentChange.MoveUnder(rootB.id)).copy(type = "only-a-type")).errorOrFail()
            assertEquals(listOf("alpha-type", "default", "gamma-type", "only-b"), err.violation().availableTypes)
            assertEquals(rootA.id, r.reload(x).parentId, "the rejected move left X where it was")
            assertEquals("gamma-type", r.reload(x).type)

            // The type valid in the destination, supplied with the move, is accepted.
            r.svc.patch(patchOf(x, parent = ParentChange.MoveUnder(rootB.id)).copy(type = "only-b")).orFail()
            assertEquals("only-b", r.reload(x).type)
            assertEquals(rootB.id, r.reload(x).parentId)

            // An unchanged type (stale for the new root) is never re-checked on a move.
            val y = r.make("Y", "gamma-type", rootA.id)
            r.svc.patch(patchOf(y, parent = ParentChange.MoveUnder(rootB.id))).orFail()
            assertEquals("gamma-type", r.reload(y).type)
            assertEquals(rootB.id, r.reload(y).parentId)
        }

    // ---------------------------------------------------------------------------------------------
    // S10 - a per-root config read fault fails a create under any parent
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 a per-root read fault fails a create under a non-terminal parent as transient config_unavailable and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val f = d.configFaultFixture()

            val control = d.rig.itemsWrite(createOp("child", f.root.id))
            assertEquals(
                1,
                control["data"]!!
                    .jsonObject["summary"]!!
                    .jsonObject["ok"]!!
                    .jsonPrimitive.int,
                "control: the same create succeeds while config is readable"
            )

            d.breakConfigReads()
            val items = rawCount(d.jdbcUrl, "SELECT COUNT(*) FROM work_items")
            val seq = d.rig.maxSeq()

            val result = d.rig.itemsWrite(createOp("child", f.root.id))

            val failure = result.errorAt(0)
            assertEquals("unavailable", failure["code"]!!.jsonPrimitive.content, "$result")
            assertEquals("shedding", failure["kind"]!!.jsonPrimitive.content, "$result")
            assertEquals(items, rawCount(d.jdbcUrl, "SELECT COUNT(*) FROM work_items"), "the faulted create wrote no row")
            assertEquals(seq, d.rig.maxSeq(), "the faulted create recorded no event")
        }
}
