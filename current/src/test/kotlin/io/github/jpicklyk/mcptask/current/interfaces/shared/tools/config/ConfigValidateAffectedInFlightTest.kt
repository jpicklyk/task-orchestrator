package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.assertForbidden
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item dcae633d) for `affectedInFlight` on the `config` op `validate`: scenarios S1-S16 of
 * the frozen test-plan (all NEW-SURFACE), through the real dispatcher over the production composition (CfgKit).
 *
 * Oracles (frozen task-scope wire contract, plan 3.6 / 3.8; none read from the implementation): `validate` of a VALID document
 * adds `affectedInFlight { count, sample[ { itemId, changedNotes, schema?, lifecycle? } ] }`. The population is the
 * non-terminal items (queue, work, review, blocked) of the root's subtree visible to the caller. For each, the schema it
 * resolves NOW (at its pin; an unpinned item at the heads) is compared with the schema it would resolve to AFTER a push of the
 * candidate (what a `repin` would give it): `changedNotes` is the sorted set of merged note keys present on one side only or
 * whose `role` / `required` differ (guidance, description and skill edits do not count); `schema` is present iff the base
 * schema's (key, layer) differs (layers `root` / `server`, null = schema-free); `lifecycle` is present iff the base lifecycle
 * differs; an item is affected iff any of the three is non-empty; `count` is exact, `sample` holds the first 20 affected items
 * in the store's order (role rank, roleChangedAt ascending, id ascending); an invalid document has no `affectedInFlight`; a
 * config read fault fails the element closed as `unavailable`; the call is read-only. Trait merging follows the documented
 * rules: base notes win over trait notes, the first trait in item order wins among traits.
 *
 * Fixture conditions are named per scenario. Pinned items are created through the item command service under a pushed
 * per-root document (so they are pinned to it); unpinned items are inserted through the undecorated repository.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigValidateAffectedInFlightTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val caps = setOf(ApiCapability.READ, ApiCapability.WRITE_CONFIG)

    private fun kit(global: String = "work_item_schemas: {}\n") = CfgKit(EventLogRig.build(db.db, dir, global))

    // ------------------------------------------------------------------ documents

    private fun note(
        key: String,
        role: String = "queue",
        required: Boolean = true,
        extra: String = ""
    ): String = "      - key: $key\n        role: $role\n        required: $required\n$extra"

    private val spec = note("spec")

    /** A per-root document declaring `feature-task` with [notes] (and [lifecycle]); [rest] is appended verbatim after it. */
    private fun doc(
        vararg notes: String,
        lifecycle: String? = null,
        rest: String = ""
    ): String =
        "work_item_schemas:\n  feature-task:\n" +
            (if (lifecycle != null) "    lifecycle: $lifecycle\n" else "") +
            (if (notes.isEmpty()) "    notes: []\n" else "    notes:\n" + notes.joinToString("")) +
            rest

    private fun traits(vararg defs: Pair<String, List<String>>): String =
        "traits:\n" + defs.joinToString("") { (name, notes) -> "  $name:\n    notes:\n" + notes.joinToString("") }

    private val v1 = doc(spec)
    private val design = note("design")

    // ------------------------------------------------------------------ fixture plumbing

    private suspend fun CfgKit.newRoot(
        tags: String? = null,
        type: String = "project"
    ): WorkItem =
        rig.ctx.itemCommandService
            .create(ItemCreateCommand(parentId = null, title = "R", type = type, tags = tags))
            .orFail()

    private suspend fun CfgKit.push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(rig.ctx.configService).push(root.id, yaml), "push of '${yaml.take(40)}'")
    }

    /** An item created through the command service: it is pinned to the heads current at this moment. */
    private suspend fun CfgKit.pinned(
        root: WorkItem,
        title: String,
        type: String = "feature-task",
        traits: List<String> = emptyList(),
        tags: String? = null
    ): WorkItem =
        rig.ctx.itemCommandService
            .create(ItemCreateCommand(parentId = root.id, title = title, type = type, tags = tags, traits = traits))
            .orFail()

    private suspend fun CfgKit.setRole(
        item: WorkItem,
        role: Role
    ) {
        val repo = rig.raw.workItemRepository()
        val current = checkNotNull(repo.getById(item.id))
        repo.update(current.copy(role = role, previousRole = if (role == Role.BLOCKED) Role.WORK else current.previousRole))
    }

    private suspend fun CfgKit.checked(
        root: WorkItem,
        yaml: String
    ): JsonObject {
        val checked = call(validateOp(root.id, yaml)).okResult()
        assertTrue(checked.bool("valid"), "fixture: the candidate is a valid document: ${checked["errors"]}")
        return checked
    }

    private suspend fun CfgKit.impact(
        root: WorkItem,
        yaml: String
    ): JsonObject = checked(root, yaml).obj("affectedInFlight")

    private fun JsonObject.ids(): List<String> = list("sample").map { it.str("itemId") }

    private fun JsonObject.entry(item: WorkItem): JsonObject = list("sample").single { it.str("itemId") == item.id.toString() }

    private fun repinOp(item: WorkItem): JsonObject =
        buildJsonObject {
            put("op", "repin")
            put("id", item.id.toString())
        }

    private fun assertEmptyDiff(checked: JsonObject) {
        val diff = checked.obj("diff")
        for (kind in listOf("types", "traits")) {
            for (change in listOf("added", "removed", "changed")) {
                assertEquals(emptyList(), diff.obj(kind).names(change), "diff.$kind.$change")
            }
        }
    }

    // ------------------------------------------------------------------ S1 happy

    @Test
    fun `S1 a candidate that adds a required note reports the pinned item with changedNotes and nothing else`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val i = k.pinned(r, "I")

            val impact = k.impact(r, doc(spec, design))

            assertEquals(setOf("count", "sample"), impact.keys, "the exact members of affectedInFlight")
            assertEquals(1, impact.int("count"))
            assertEquals(listOf(i.id.toString()), impact.ids())
            val entry = impact.entry(i)
            assertEquals(setOf("itemId", "changedNotes"), entry.keys, "no schema and no lifecycle member when neither changed")
            assertEquals(listOf("design"), entry.names("changedNotes"))

            val same = k.impact(r, v1)
            assertEquals(0, same.int("count"), "control: the head's own text changes nothing for the item pinned to it")
            assertEquals(emptyList(), same.list("sample"))
        }

    // ------------------------------------------------------------------ S2 pinned

    @Test
    fun `S2 a pinned item is compared at its pin, so a head that already moved still counts while the diff is empty`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val i = k.pinned(r, "I")
            val v2 = doc(spec, design)
            k.push(r, v2)

            val checked = k.checked(r, v2)

            assertEmptyDiff(checked)
            val impact = checked.obj("affectedInFlight")
            assertEquals(1, impact.int("count"), "the item is still pinned to the document without `design`")
            assertEquals(listOf("design"), impact.entry(i).names("changedNotes"))

            val back = k.impact(r, v1)
            assertEquals(0, back.int("count"), "control: against the document it is pinned to, the item is unchanged (the head has design)")
        }

    // ------------------------------------------------------------------ S3 unpinned

    @Test
    fun `S3 an unpinned item is compared at the heads - equal and equivalent text change nothing, an added note counts`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val v2 = doc(spec, design)
            k.push(r, v2)
            val u = k.rig.make("U", r, type = "feature-task")
            assertNull(
                checkNotNull(
                    k.rig.raw
                        .workItemRepository()
                        .getById(u.id)
                ).pin,
                "fixture: the row carries no pin"
            )

            assertEquals(0, k.impact(r, v2).int("count"), "the head document itself")
            val reformatted =
                "# an equivalent spelling\nwork_item_schemas:\n  feature-task:\n    notes:\n" +
                    "      - role: queue\n        required: true\n        key: spec\n" +
                    "      - role: queue\n        required: true\n        key: design\n"
            assertEquals(0, k.impact(r, reformatted).int("count"), "the same schema spelled differently")

            val added = k.impact(r, doc(spec, design, note("audit")))
            assertEquals(1, added.int("count"))
            assertEquals(listOf("audit"), added.entry(u).names("changedNotes"))
        }

    // ------------------------------------------------------------------ S4 gate fields

    @Test
    fun `S4 role, required, removal and addition of a note are changes, and changedNotes is sorted`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val i = k.pinned(r, "I")

            suspend fun changed(yaml: String): List<String> {
                val impact = k.impact(r, yaml)
                assertEquals(1, impact.int("count"), "candidate: ${yaml.take(120)}")
                return impact.entry(i).names("changedNotes")
            }

            assertEquals(listOf("spec"), changed(doc(note("spec", role = "work"))), "role queue -> work")
            assertEquals(listOf("spec"), changed(doc(note("spec", required = false))), "required true -> false")
            assertEquals(listOf("spec"), changed(doc(note("spec", role = "review", required = false))), "both fields, one key")
            assertEquals(listOf("spec"), changed(doc()), "a removed note")
            assertEquals(listOf("alpha", "zeta"), changed(doc(spec, note("zeta"), note("alpha"))), "added notes, sorted by key")
        }

    // ------------------------------------------------------------------ S5 traits

    @Test
    fun `S5 trait notes are part of the merged schema on both sides`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            val withTrait = doc(spec, rest = traits("t1" to listOf(note("tk"))))
            k.push(r, withTrait)
            val i = k.pinned(r, "I", traits = listOf("t1"))

            assertEquals(0, k.impact(r, withTrait).int("count"), "control: the identical document changes nothing")

            val flipped = k.impact(r, doc(spec, rest = traits("t1" to listOf(note("tk", required = false)))))
            assertEquals(1, flipped.int("count"))
            assertEquals(listOf("tk"), flipped.entry(i).names("changedNotes"))
        }

    @Test
    fun `S5 among traits the first one in item order wins a duplicate note key`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()

            fun defs(
                first: Boolean,
                second: Boolean
            ) = doc(
                spec,
                rest = traits("t1" to listOf(note("dup", required = first)), "t2" to listOf(note("dup", required = second)))
            )
            k.push(r, defs(first = true, second = false))
            val i = k.pinned(r, "I", traits = listOf("t1", "t2"))

            assertEquals(0, k.impact(r, defs(first = true, second = true)).int("count"), "the later trait's entry is shadowed")
            val flippedFirst = k.impact(r, defs(first = false, second = false))
            assertEquals(1, flippedFirst.int("count"), "the winning trait's entry changed")
            assertEquals(listOf("dup"), flippedFirst.entry(i).names("changedNotes"))
        }

    @Test
    fun `S5 a base schema note wins over a trait note with the same key`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()

            fun defs(traitSpec: String) = doc(spec, rest = traits("t3" to listOf(traitSpec)))
            k.push(r, defs(note("spec", required = false)))
            k.pinned(r, "I", traits = listOf("t3"))

            val shadowed = k.impact(r, defs(note("spec", role = "work", required = false)))

            assertEquals(0, shadowed.int("count"), "the trait's version of `spec` never reaches the merged schema")
        }

    // ------------------------------------------------------------------ S6 base schema and lifecycle

    @Test
    fun `S6 dropping the type but keeping a per-root default moves the base schema, dropping both leaves it schema-free`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val i = k.pinned(r, "I")
            // The root itself would resolve to the default too; take it out of the population so the count is about I.
            k.setRole(r, Role.TERMINAL)

            val toDefault = k.impact(r, "work_item_schemas:\n  default:\n    notes: []\n")
            assertEquals(1, toDefault.int("count"))
            val entry = toDefault.entry(i)
            val schema = entry.obj("schema")
            assertEquals("feature-task", schema.obj("from").str("key"))
            assertEquals("root", schema.obj("from").str("layer"))
            assertEquals("default", schema.obj("to").str("key"))
            assertEquals("root", schema.obj("to").str("layer"))
            assertEquals(listOf("spec"), entry.names("changedNotes"))
            assertFalse("lifecycle" in entry, "the lifecycle of both schemas is the unset default: $entry")

            val toNothing = k.impact(r, "work_item_schemas: {}\n")
            assertEquals(1, toNothing.int("count"))
            val gone = toNothing.entry(i).obj("schema")
            assertEquals("feature-task", gone.obj("from").str("key"))
            assertEquals("root", gone.obj("from").str("layer"))
            assertTrue(gone.getValue("to") is JsonNull, "schema-free after the push: $gone")
            assertEquals(listOf("spec"), toNothing.entry(i).names("changedNotes"))
        }

    @Test
    fun `S6 a lifecycle change alone is reported as lifecycle, not as notes or schema`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, doc(spec, lifecycle = "manual"))
            val i = k.pinned(r, "I")

            val impact = k.impact(r, doc(spec, lifecycle = "permanent"))

            assertEquals(1, impact.int("count"))
            val entry = impact.entry(i)
            assertEquals(setOf("itemId", "changedNotes", "lifecycle"), entry.keys, "$entry")
            assertEquals(emptyList(), entry.names("changedNotes"))
            val lifecycle = entry.obj("lifecycle")
            assertEquals("manual", lifecycle.str("from").lowercase(), "wire casing of the mode is not part of the frozen contract")
            assertEquals("permanent", lifecycle.str("to").lowercase())
        }

    @Test
    fun `S6 the same type moving from the server layer to the root layer is a schema change even with identical notes`(): Unit =
        runBlocking {
            val k = kit(EventLogRig.GATED_CONFIG)
            val r = k.newRoot(type = "test-item")
            val i = k.pinned(r, "I")

            val impact = k.impact(r, doc(spec))

            assertEquals(1, impact.int("count"))
            val entry = impact.entry(i)
            assertEquals("server", entry.obj("schema").obj("from").str("layer"))
            assertEquals("feature-task", entry.obj("schema").obj("from").str("key"))
            assertEquals("root", entry.obj("schema").obj("to").str("layer"))
            assertEquals("feature-task", entry.obj("schema").obj("to").str("key"))
            assertEquals(emptyList(), entry.names("changedNotes"), "the declared notes are the same on both sides")
        }

    @Test
    fun `probe - an item that was schema-free at its pin reports schema from null when the candidate declares its type`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            val i = k.pinned(r, "I")

            val impact = k.impact(r, v1)

            assertEquals(1, impact.int("count"))
            val entry = impact.entry(i)
            assertTrue(entry.obj("schema").getValue("from") is JsonNull, "${entry.obj("schema")}")
            assertEquals("feature-task", entry.obj("schema").obj("to").str("key"))
            assertEquals("root", entry.obj("schema").obj("to").str("layer"))
            assertEquals(listOf("spec"), entry.names("changedNotes"))
        }

    // ------------------------------------------------------------------ S7 / S8 failure

    @Test
    fun `S7 an invalid or unparseable candidate has no affectedInFlight`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            k.pinned(r, "I")
            val control = k.impact(r, doc(spec, design))
            assertEquals(1, control.int("count"), "control: a valid candidate that changes the item is counted")

            for (bad in listOf(CfgYaml.INVALID, "work_item_schemas: [unclosed\n")) {
                val result = k.call(validateOp(r.id, bad)).okResult()
                assertFalse(result.bool("valid"), "fixture: '${bad.take(30)}' is invalid")
                assertFalse("affectedInFlight" in result, "no impact for an invalid document: $result")
            }
        }

    @Test
    fun `S8 a config read fault during validate fails the element closed as unavailable`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            k.pinned(r, "I")
            val candidate = doc(spec, design)
            assertEquals(1, k.impact(r, candidate).int("count"), "control: healthy config reports the affected item")

            P11Driver(k.rig).breakConfigReads()
            val err = k.call(validateOp(r.id, candidate)).errorAt()

            assertEquals("unavailable", err.str("code"), "$err")
        }

    // ------------------------------------------------------------------ S9 non-gate edits

    @Test
    fun `S9 guidance, description and skill edits are not changes`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            k.pinned(r, "I")

            val cosmetic =
                doc(
                    note("spec", extra = "        description: what this is\n        guidance: how to fill it\n        skill: some-skill\n")
                )
            assertEquals(0, k.impact(r, cosmetic).int("count"), "only non-gate fields differ")

            val gate = doc(note("spec", required = false, extra = "        description: what this is\n        guidance: how to fill it\n"))
            assertEquals(1, k.impact(r, gate).int("count"), "control: the same cosmetic fields plus a gate change are counted")
        }

    // ------------------------------------------------------------------ S10 population

    @Test
    fun `S10 queue, work, review and blocked items are counted and a terminal one is not`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val byRole =
                listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.BLOCKED, Role.TERMINAL).associateWith { role ->
                    k.pinned(r, "I-$role").also { if (role != Role.QUEUE) k.setRole(it, role) }
                }

            val impact = k.impact(r, doc(spec, design))

            assertEquals(4, impact.int("count"))
            assertEquals(
                (byRole - Role.TERMINAL).values.map { it.id.toString() }.toSet(),
                impact.ids().toSet(),
                "exactly the four non-terminal items"
            )
            assertFalse(byRole.getValue(Role.TERMINAL).id.toString() in impact.ids())
        }

    // ------------------------------------------------------------------ S11 sample cap

    @Test
    fun `S11 count is exact while the sample is capped at 20 in the store order`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val seeded = mutableListOf<WorkItem>()
            val candidate = doc(spec, design)

            suspend fun grow(to: Int) {
                while (seeded.size < to) seeded += k.pinned(r, "I-${seeded.size}")
            }

            suspend fun expectedFirst(): List<String> {
                val repo = k.rig.raw.workItemRepository()
                return seeded
                    .map { checkNotNull(repo.getById(it.id)) }
                    .sortedWith(compareBy({ it.roleChangedAt }, { it.id.toString() }))
                    .take(20)
                    .map { it.id.toString() }
            }

            grow(20)
            val at20 = k.impact(r, candidate)
            assertEquals(20, at20.int("count"))
            assertEquals(20, at20.list("sample").size)
            assertEquals(expectedFirst(), at20.ids())

            grow(21)
            val at21 = k.impact(r, candidate)
            assertEquals(21, at21.int("count"))
            assertEquals(20, at21.list("sample").size)
            assertEquals(expectedFirst(), at21.ids(), "the first 20 in (roleChangedAt, id) order")

            grow(25)
            val at25 = k.impact(r, candidate)
            assertEquals(25, at25.int("count"))
            assertEquals(20, at25.ids().toSet().size, "twenty distinct ids")
            assertTrue(seeded.map { it.id.toString() }.containsAll(at25.ids()), "every sampled id is a seeded item")
            assertEquals(expectedFirst(), at25.ids())
        }

    // ------------------------------------------------------------------ S12 scope

    @Test
    fun `S12 a tag-scoped caller sees only visible items in the count and the sample and never the others' ids`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot(tags = "t")
            k.push(r, v1)
            val a = k.pinned(r, "A", tags = "t")
            val b = k.pinned(r, "B")
            val scoped = ParityToken("av-tag", caps, tagsInclude = setOf("t"))
            val everything = ParityToken("av-all", caps)
            val op = validateOp(r.id, doc(spec, design))

            val body = k.callScoped(scoped, op)
            val impact = body.okResult().obj("affectedInFlight")

            assertEquals(1, impact.int("count"))
            assertEquals(listOf(a.id.toString()), impact.ids())
            val text = body.toString()
            assertFalse(b.id.toString() in text, "the invisible item's id leaked: $text")
            assertFalse(b.id.toString().take(8) in text, "the invisible item's id prefix leaked")

            val open = k.callScoped(everything, op).okResult().obj("affectedInFlight")
            assertEquals(2, open.int("count"), "control: the unrestricted caller counts both")
            assertEquals(setOf(a.id.toString(), b.id.toString()), open.ids().toSet())
        }

    @Test
    fun `S12 a validate aimed at a root outside the scope is forbidden and carries no impact`(): Unit =
        runBlocking {
            val k = kit()
            val visible = k.newRoot(tags = "t")
            val hidden = k.newRoot(tags = "other")
            k.push(hidden, v1)
            val h = k.pinned(hidden, "H")
            val scoped = ParityToken("av-tag2", caps, tagsInclude = setOf("t"))

            val body = k.callScoped(scoped, validateOp(hidden.id, doc(spec, design)))

            val err = body.errorAt()
            assertForbidden(err, "tag", "t")
            assertFalse("affectedInFlight" in err)
            assertFalse(h.id.toString() in body.toString())
            assertEquals(0, k.impact(visible, v1).int("count"), "control: the visible root answers")
        }

    // ------------------------------------------------------------------ S13 empty

    @Test
    fun `S13 a root with no other non-terminal items reports count 0 and an empty sample, with or without a head`(): Unit =
        runBlocking {
            val k = kit()
            val bare = k.newRoot()

            val noHead = k.impact(bare, v1)
            assertEquals(0, noHead.int("count"))
            assertEquals(emptyList(), noHead.list("sample"))

            val r = k.newRoot()
            k.push(r, v1)
            val withHead = k.impact(r, doc(spec, design))
            assertEquals(0, withHead.int("count"), "the schema-free root itself is not changed by a document about feature-task")
            assertEquals(emptyList(), withHead.list("sample"))
        }

    // ------------------------------------------------------------------ S14 / S15 read-only and repeatable

    @Test
    fun `S14 validate writes nothing and leaves every item and its pin as it was`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val p = k.pinned(r, "P")
            val u = k.rig.make("U", r, type = "feature-task")
            val repo = k.rig.raw.workItemRepository()
            val before = k.snapshot()
            val pBefore = checkNotNull(repo.getById(p.id))
            val uBefore = checkNotNull(repo.getById(u.id))
            val seq = k.rig.maxSeq()

            val impact = k.impact(r, doc(spec, design))

            assertEquals(2, impact.int("count"), "fixture: both items are affected, so a write-back would have something to do")
            assertEquals(before, k.snapshot())
            assertEquals(pBefore, repo.getById(p.id))
            assertEquals(uBefore, repo.getById(u.id))
            assertEquals(seq, k.rig.maxSeq(), "no event was recorded")
            assertEquals(1, k.versions(r.id), "no config version was appended")
        }

    @Test
    fun `S15 validating the same candidate twice answers the same`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            k.pinned(r, "P")
            k.rig.make("U", r, type = "feature-task")
            val candidate = doc(spec, design)

            val first = k.impact(r, candidate)
            val second = k.impact(r, candidate)

            assertEquals(2, first.int("count"))
            assertEquals(first, second)
        }

    // ------------------------------------------------------------------ S16 push and repin

    @Test
    fun `S16 after the push the pinned item is still affected until it is repinned`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, v1)
            val i = k.pinned(r, "I")
            val candidate = doc(spec, design)
            assertEquals(1, k.impact(r, candidate).int("count"), "before the push")

            k.push(r, candidate)
            val afterPush = k.impact(r, candidate)
            assertEquals(1, afterPush.int("count"), "a push does not move pins")
            assertEquals(listOf("design"), afterPush.entry(i).names("changedNotes"))

            k.rig.itemsWrite(repinOp(i)).okResult()
            val afterRepin = k.impact(r, candidate)
            assertEquals(0, afterRepin.int("count"), "a repin settles exactly what validate predicted")
            assertNotNull(
                checkNotNull(
                    k.rig.raw
                        .workItemRepository()
                        .getById(i.id)
                ).pin
            )
        }
}
