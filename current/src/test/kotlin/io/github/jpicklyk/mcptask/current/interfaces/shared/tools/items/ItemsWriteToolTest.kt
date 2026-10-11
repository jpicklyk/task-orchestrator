package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.blocksDep
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 5462835d) for `items_write` create / update / delete, scenarios S1 to S11 and S14 to
 * S16 of the frozen test-plan (all NEW-SURFACE), driven through the real dispatcher over the production composition.
 *
 * Oracles: task-scope AC2 to AC7 and decisions D1 (null is keep, `clear` nulls, `[]` clears tags and traits), D4/D5 (a stale
 * `expectedVersion` is `version_conflict{kind,id,expected,actual}` with nothing written, on update and on delete), D6 (non-recursive
 * delete of a parent is `invalid_request` field `recursive`, received = child count), D3 (event rows carry the lifted actor), the
 * envelope spec (per-element failures in a batch are a 207 `partial_failure`, `summary {ok,failed}`, `atomic` fails the call with
 * `error.index`, a repeated `idempotencyKey` replays with `replayed:true`). Expected values are literals written from those clauses.
 *
 * Arbitration: the frozen plan lists 400/409/404 next to the element error codes; the envelope spec (ToolDispatcherTest, OpsRoutesTest
 * S6, public evidence) makes every non-atomic element failure a 207 `partial_failure` carrying the element error, so the element
 * error code is asserted and the batch status is 207; the per-code HTTP statuses are exercised by the parity catalog (atomic 4xx).
 *
 * Every absence assertion is paired with a control through the same call shape that does write.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsWriteToolTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit() = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))

    // ------------------------------------------------------------------ S1 / S2 create

    @Test
    fun `S1 create a root stores every field as sent and returns exactly ok id version`(): Unit =
        runBlocking {
            val k = kit()
            val r =
                k.call(
                    createOp(
                        "Root one",
                        null,
                        TEST_ITEM_TYPE,
                        "tags" to strings("a", "b"),
                        "description" to jstr("desc"),
                        "summary" to jstr("sum"),
                        "priority" to jstr("high"),
                        "complexity" to JsonPrimitive(3),
                        "requiresVerification" to JsonPrimitive(true),
                        "metadata" to jstr("meta"),
                        "properties" to buildJsonObject { put("k", "v") }
                    )
                )
            assertEquals(200, r.envelope.httpStatus)
            val body = r.envelope.body
            assertEquals("""{"ok":1,"failed":0}""", body.summary())
            val ok = body.okResult()
            assertEquals(setOf("ok", "id", "version"), ok.keys, "create element carries exactly ok, id, version")
            val id = body.okId()
            val row = assertNotNull(k.item(id))
            assertEquals(ok.getValue("version").jsonPrimitive.long, row.version, "wire version equals the stored version")
            assertEquals("Root one", row.title)
            assertEquals(TEST_ITEM_TYPE, row.type)
            assertEquals(0, row.depth)
            assertNull(row.parentId)
            assertEquals(id, row.rootId, "a root item is its own root")
            assertEquals(Role.QUEUE, row.role)
            assertEquals("a,b", row.tags, "tags array is joined with a comma")
            assertEquals("desc", row.description)
            assertEquals("sum", row.summary)
            assertEquals(Priority.HIGH, row.priority)
            assertEquals(3, row.complexity)
            assertTrue(row.requiresVerification)
            assertEquals("meta", row.metadata)
            assertEquals("""{"k":"v"}""", row.properties)
            assertEquals(1, k.all().size)
        }

    @Test
    fun `S1 create stores declared traits in order and defaults when optional fields are absent`(): Unit =
        runBlocking {
            val k = kit()
            val body = k.call(createOp("Traited", null, TEST_ITEM_TYPE, "traits" to strings("t-one", "t-two"))).envelope.body
            val row = assertNotNull(k.item(body.okId()))
            assertEquals(listOf("t-one", "t-two"), row.traits)
            assertEquals(Priority.MEDIUM, row.priority, "priority defaults to medium")
            assertEquals("", row.summary)
            assertTrue(!row.requiresVerification)
            assertNull(row.description)
            assertNull(row.tags, "no tags sent stores no tags")
        }

    @Test
    fun `S2 create under a parent given as an 8-hex prefix stamps depth and root from the parent`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("Parent")
            val prefix = root.id.toString().take(8)
            val child =
                k
                    .call(createOp("Child", null, TEST_ITEM_TYPE, "parentId" to jstr(prefix)))
                    .envelope.body
                    .okId()
            val childRow = assertNotNull(k.item(child))
            assertEquals(root.id, childRow.parentId)
            assertEquals(1, childRow.depth)
            assertEquals(root.id, childRow.rootId)
            val grand =
                k
                    .call(createOp("Grand", child))
                    .envelope.body
                    .okId()
            val grandRow = assertNotNull(k.item(grand))
            assertEquals(2, grandRow.depth)
            assertEquals(root.id, grandRow.rootId, "the root is inherited down the chain")
        }

    // ------------------------------------------------------------------ S3 / S4 update

    @Test
    fun `S3 update changes the named field, clear nulls description, every other field keeps its pre-read value`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("Orig", tags = "a,b", description = "d", summary = "sum", priority = Priority.HIGH)
            val r = k.call(updateOp(a.id, "title" to jstr("New"), "clear" to strings("description")))
            assertEquals(200, r.envelope.httpStatus)
            val ok = r.envelope.body.okResult()
            assertEquals(setOf("ok", "id", "version"), ok.keys)
            val row = assertNotNull(k.item(a.id))
            assertEquals("New", row.title)
            assertNull(row.description)
            assertEquals("sum", row.summary)
            assertEquals("a,b", row.tags)
            assertEquals(Priority.HIGH, row.priority)
            assertEquals(TEST_ITEM_TYPE, row.type)
            assertNull(row.parentId)
            assertTrue(row.version > a.version, "an update bumps the version")
            assertEquals(row.version, ok.getValue("version").jsonPrimitive.long)
        }

    @Test
    fun `S3 an explicit null description keeps the stored value while a present value replaces it`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("Keeper", description = "keep me")
            k
                .call(updateOp(a.id, "title" to jstr("Keeper2"), "description" to JsonNull))
                .envelope.body
                .okResult()
            assertEquals("keep me", assertNotNull(k.item(a.id)).description, "null means keep (D1)")
            k
                .call(updateOp(a.id, "description" to jstr("changed")))
                .envelope.body
                .okResult()
            assertEquals("changed", assertNotNull(k.item(a.id)).description, "control: a present value replaces")
        }

    @Test
    fun `S3 clear nulls statusLabel metadata complexity and properties, each only when named`(): Unit =
        runBlocking {
            val k = kit()
            val created =
                k
                    .call(
                        createOp(
                            "Clearable",
                            null,
                            TEST_ITEM_TYPE,
                            "statusLabel" to jstr("lbl"),
                            "metadata" to jstr("m"),
                            "complexity" to JsonPrimitive(4),
                            "properties" to buildJsonObject { put("k", "v") }
                        )
                    ).envelope.body
                    .okId()
            k
                .call(updateOp(created, "clear" to strings("statusLabel", "complexity")))
                .envelope.body
                .okResult()
            val mid = assertNotNull(k.item(created))
            assertNull(mid.statusLabel)
            assertNull(mid.complexity)
            assertEquals("m", mid.metadata, "not named in clear, kept")
            assertEquals("""{"k":"v"}""", mid.properties, "not named in clear, kept")
            k
                .call(updateOp(created, "clear" to strings("metadata", "properties")))
                .envelope.body
                .okResult()
            val end = assertNotNull(k.item(created))
            assertNull(end.metadata)
            assertNull(end.properties)
        }

    @Test
    fun `S3 tags and traits empty arrays clear, absent keeps, a list replaces`(): Unit =
        runBlocking {
            val k = kit()
            val id =
                k
                    .call(
                        createOp("TT", null, TEST_ITEM_TYPE, "tags" to strings("a", "b"), "traits" to strings("t-one", "t-two"))
                    ).envelope.body
                    .okId()
            k
                .call(updateOp(id, "title" to jstr("TT2")))
                .envelope.body
                .okResult()
            val kept = assertNotNull(k.item(id))
            assertEquals("a,b", kept.tags)
            assertEquals(listOf("t-one", "t-two"), kept.traits)
            k
                .call(updateOp(id, "tags" to strings("c"), "traits" to strings("t-two")))
                .envelope.body
                .okResult()
            val replaced = assertNotNull(k.item(id))
            assertEquals("c", replaced.tags)
            assertEquals(listOf("t-two"), replaced.traits)
            k
                .call(updateOp(id, "tags" to JsonArray(emptyList()), "traits" to JsonArray(emptyList())))
                .envelope.body
                .okResult()
            val cleared = assertNotNull(k.item(id))
            assertTrue(cleared.tags.isNullOrEmpty(), "tags cleared, was c: ${cleared.tags}")
            assertEquals(emptyList(), cleared.traits)
        }

    @Test
    fun `S4 update parentId reparents and restamps depth and root of the whole subtree, clear parentId moves to root`(): Unit =
        runBlocking {
            val k = kit()
            val r1 = k.make("R1")
            val r2 = k.make("R2")
            val c = k.make("C", r1)
            val g = k.make("G", c)
            assertEquals(r1.id, assertNotNull(k.item(c.id)).rootId, "control: before the move C is under R1")

            k
                .call(updateOp(c.id, "parentId" to jstr(r2.id.toString())))
                .envelope.body
                .okResult()
            val movedC = assertNotNull(k.item(c.id))
            assertEquals(r2.id, movedC.parentId)
            assertEquals(1, movedC.depth)
            assertEquals(r2.id, movedC.rootId)
            val movedG = assertNotNull(k.item(g.id))
            assertEquals(2, movedG.depth)
            assertEquals(r2.id, movedG.rootId)

            k
                .call(updateOp(c.id, "clear" to strings("parentId")))
                .envelope.body
                .okResult()
            val rootC = assertNotNull(k.item(c.id))
            assertNull(rootC.parentId)
            assertEquals(0, rootC.depth)
            assertEquals(c.id, rootC.rootId, "a root is its own root")
            val subG = assertNotNull(k.item(g.id))
            assertEquals(1, subG.depth)
            assertEquals(c.id, subG.rootId)
        }

    @Test
    fun `S4 a cycle and a self parent are rejected with their reasons and change nothing`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.make("R")
            val c = k.make("C", r)
            val g = k.make("G", c)
            val before = k.snapshot()
            val cycle =
                k
                    .call(updateOp(r.id, "parentId" to jstr(g.id.toString())))
                    .envelope.body
                    .errorAt()
            assertEquals("cycle_detected", cycle.str("code"))
            val self =
                k
                    .call(updateOp(r.id, "parentId" to jstr(r.id.toString())))
                    .envelope.body
                    .errorAt()
            assertEquals("invalid_request", self.str("code"))
            assertEquals(before, k.snapshot(), "nothing written by either rejection")
            assertNull(assertNotNull(k.item(r.id)).parentId)
            k
                .call(updateOp(g.id, "parentId" to jstr(r.id.toString())))
                .envelope.body
                .okResult()
            assertNotEquals(before, k.snapshot(), "control: a legal reparent through the same call shape writes")
        }

    // ------------------------------------------------------------------ S5 delete

    @Test
    fun `S5 delete a leaf returns exactly ok id deleted 1 and removes the row, its notes and its edges`(): Unit =
        runBlocking {
            val k = kit()
            val leaf = k.make("Leaf")
            val other = k.make("Other")
            k.rig.raw
                .noteRepository()
                .upsert(Note(itemId = leaf.id, key = "n1", role = "work", body = "b"))
            k.rig.raw
                .dependencyRepository()
                .create(blocksDep(other.id, leaf.id))
            assertNotNull(
                k.rig.raw
                    .noteRepository()
                    .findByItemIdAndKey(leaf.id, "n1"),
                "control: the note exists before"
            )
            assertEquals(
                1,
                k.rig.raw
                    .dependencyRepository()
                    .findByItemId(leaf.id)
                    .size,
                "control: the edge exists before"
            )

            val r = k.call(deleteOp(leaf.id))
            assertEquals(200, r.envelope.httpStatus)
            val ok = r.envelope.body.okResult()
            assertEquals(setOf("ok", "id", "deleted"), ok.keys)
            assertEquals(leaf.id.toString(), ok.str("id"))
            assertEquals(1L, ok.getValue("deleted").jsonPrimitive.long)
            assertNull(k.item(leaf.id))
            assertNull(
                k.rig.raw
                    .noteRepository()
                    .findByItemIdAndKey(leaf.id, "n1")
            )
            assertEquals(
                emptyList(),
                k.rig.raw
                    .dependencyRepository()
                    .findByItemId(leaf.id)
            )
            assertNotNull(k.item(other.id), "the unrelated item stays")
        }

    @Test
    fun `S5 recursive delete of a parent reports 1 plus the descendants and removes the subtree only`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.make("P")
            val c1 = k.make("C1", p)
            val c2 = k.make("C2", p)
            val g = k.make("G", c1)
            val outsider = k.make("Outsider")
            val r = k.call(deleteOp(p.id, true))
            assertEquals(
                4L,
                r.envelope.body
                    .okResult()
                    .getValue("deleted")
                    .jsonPrimitive.long
            )
            for (gone in listOf(p, c1, c2, g)) assertNull(k.item(gone.id), "${gone.title} is deleted")
            assertNotNull(k.item(outsider.id))
        }

    // ------------------------------------------------------------------ S7 / S8 create failures

    @Test
    fun `S7 create without type is invalid_request at ops 0 type, create with a valid type in the same batch still runs`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.call(createOp("NoType", null, null), createOp("WithType"))
            assertEquals(207, r.envelope.httpStatus)
            val err = r.envelope.body.errorAt(0)
            assertEquals("invalid_request", err.str("code"))
            val f =
                err
                    .detail()
                    .getValue("fields")
                    .jsonArray
                    .first() as JsonObject
            assertEquals("/ops/0/type", f.str("field"))
            assertEquals("is required", f.str("reason"))
            assertEquals(listOf("WithType"), k.titles(), "the typed element created, the untyped one did not")
        }

    @Test
    fun `S7 a blank type is schema_violation stating the type is required`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.call(createOp("Blank", null, ""), createOp("Ok"))
            val err = r.envelope.body.errorAt(0)
            assertEquals("schema_violation", err.str("code"))
            assertTrue("type is required" in err.toString(), "reason names the missing type: $err")
            assertEquals(listOf("Ok"), k.titles())
        }

    @Test
    fun `S7 an undeclared type is schema_violation listing the declared types, a declared type is accepted`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.call(createOp("Bad", null, "no-such-type"), createOp("Good", null, "feature-task"))
            val err = r.envelope.body.errorAt(0)
            assertEquals("schema_violation", err.str("code"))
            val available =
                err
                    .detail()
                    .getValue("availableTypes")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            assertEquals(setOf("test-item", "feature-task"), available)
            assertEquals(listOf("Good"), k.titles())
        }

    @Test
    fun `S8 properties carrying traits is invalid_request on field properties and creates nothing`(): Unit =
        runBlocking {
            val k = kit()
            val bad =
                k
                    .call(
                        createOp(
                            "Bad",
                            null,
                            TEST_ITEM_TYPE,
                            "properties" to buildJsonObject { put("traits", JsonArray(listOf(JsonPrimitive("x")))) }
                        )
                    ).envelope.body
                    .errorAt(0)
            assertEquals("invalid_request", bad.str("code"))
            assertEquals(
                "properties",
                (
                    bad
                        .detail()
                        .getValue("fields")
                        .jsonArray
                        .first() as JsonObject
                ).str("field")
            )
            assertEquals(emptyList(), k.titles())
            k
                .call(createOp("Fine", null, TEST_ITEM_TYPE, "properties" to buildJsonObject { put("other", "y") }))
                .envelope.body
                .okResult()
            assertEquals(listOf("Fine"), k.titles(), "control: properties without traits is accepted")
        }

    // ------------------------------------------------------------------ S9 version conflicts

    @Test
    fun `S9 a stale expectedVersion on update is version_conflict with kind id expected actual and writes nothing`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("V")
            k
                .call(updateOp(a.id, "title" to jstr("V2")))
                .envelope.body
                .okResult()
            val current = assertNotNull(k.item(a.id))
            assertTrue(current.version >= 2)
            val stale = current.version - 1
            val before = k.snapshot()
            val err =
                k
                    .call(updateOp(a.id, "expectedVersion" to JsonPrimitive(stale), "title" to jstr("V3")))
                    .envelope.body
                    .errorAt()
            assertEquals("version_conflict", err.str("code"))
            assertEquals("item", err.detail().str("kind"))
            assertEquals(a.id.toString(), err.detail().str("id"))
            assertEquals(stale.toString(), err.detail().str("expected"))
            assertEquals(current.version.toString(), err.detail().str("actual"))
            assertEquals("V2", assertNotNull(k.item(a.id)).title)
            assertEquals(before, k.snapshot())
            k
                .call(updateOp(a.id, "expectedVersion" to JsonPrimitive(current.version), "title" to jstr("V3")))
                .envelope.body
                .okResult()
            assertEquals("V3", assertNotNull(k.item(a.id)).title, "control: the current version is accepted")
        }

    @Test
    fun `S9 a stale expectedVersion on delete is version_conflict and the item stays`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("D")
            k
                .call(updateOp(a.id, "title" to jstr("D2")))
                .envelope.body
                .okResult()
            val current = assertNotNull(k.item(a.id))
            val stale = current.version - 1
            val err =
                k
                    .call(deleteOp(a.id, false, "expectedVersion" to JsonPrimitive(stale)))
                    .envelope.body
                    .errorAt()
            assertEquals("version_conflict", err.str("code"))
            assertEquals("item", err.detail().str("kind"))
            assertEquals(stale.toString(), err.detail().str("expected"))
            assertEquals(current.version.toString(), err.detail().str("actual"))
            assertNotNull(k.item(a.id), "the item was not deleted")
            k
                .call(deleteOp(a.id, false, "expectedVersion" to JsonPrimitive(current.version)))
                .envelope.body
                .okResult()
            assertNull(k.item(a.id), "control: the current version deletes")
        }

    // ------------------------------------------------------------------ S10 / S11

    @Test
    fun `S10 delete of a parent without recursive is invalid_request on recursive with the child count and deletes nothing`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.make("P")
            k.make("C1", p)
            k.make("C2", p)
            val err =
                k
                    .call(deleteOp(p.id))
                    .envelope.body
                    .errorAt()
            assertEquals("invalid_request", err.str("code"))
            val f =
                err
                    .detail()
                    .getValue("fields")
                    .jsonArray
                    .first() as JsonObject
            assertEquals("recursive", f.str("field"))
            assertEquals("2", f.str("received"))
            assertEquals(3, k.all().size, "nothing deleted")
            k
                .call(deleteOp(p.id, true))
                .envelope.body
                .okResult()
            assertEquals(0, k.all().size, "control: recursive removes the parent and both children")
        }

    @Test
    fun `S11 an unknown id is not_found for update and delete`(): Unit =
        runBlocking {
            val k = kit()
            val ghost = UUID.fromString("11111111-2222-4333-8444-555555555555")
            val upd =
                k
                    .call(updateOp(ghost, "title" to jstr("x")))
                    .envelope.body
                    .errorAt()
            assertEquals("not_found", upd.str("code"))
            assertEquals("item", upd.detail().str("kind"))
            assertEquals(ghost.toString(), upd.detail().str("id"))
            assertEquals(
                "not_found",
                k
                    .call(deleteOp(ghost))
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
        }

    @Test
    fun `S11 an ambiguous 8-hex prefix is ambiguous_id listing both candidates and neither item is touched`(): Unit =
        runBlocking {
            val k = kit()
            val one = k.make("One", id = UUID.fromString("abcdef12-0000-4000-8000-000000000001"))
            val two = k.make("Two", id = UUID.fromString("abcdef12-0000-4000-8000-000000000002"))
            val before = k.snapshot()
            val upd =
                k
                    .call(
                        updateOp(UUID.randomUUID(), "title" to jstr("x")).let {
                            JsonObject(it + ("id" to jstr("abcdef12")))
                        }
                    ).envelope.body
                    .errorAt()
            assertEquals("ambiguous_id", upd.str("code"))
            assertTrue(one.id.toString() in upd.detail().toString() && two.id.toString() in upd.detail().toString(), upd.toString())
            val del =
                k
                    .call(JsonObject(deleteOp(UUID.randomUUID()) + ("id" to jstr("abcdef12"))))
                    .envelope.body
                    .errorAt()
            assertEquals("ambiguous_id", del.str("code"))
            assertEquals(before, k.snapshot(), "neither item was changed or deleted")
            k
                .call(deleteOp(one.id))
                .envelope.body
                .okResult()
            assertNull(k.item(one.id), "control: the full id deletes")
        }

    @Test
    fun `S11 an upper-case UUID resolves to the same item and a 33-hex id is rejected`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("Case")
            k
                .call(
                    updateOp(a.id, "title" to jstr("Case2")).let {
                        JsonObject(it + ("id" to jstr(a.id.toString().uppercase())))
                    }
                ).envelope.body
                .okResult()
            assertEquals("Case2", assertNotNull(k.item(a.id)).title)
            val long33 = a.id.toString().replace("-", "") + "0"
            val err =
                k
                    .call(JsonObject(updateOp(a.id, "title" to jstr("x")) + ("id" to jstr(long33))))
                    .envelope.body
                    .errorAt()
            assertEquals("invalid_request", err.str("code"))
            assertEquals("Case2", assertNotNull(k.item(a.id)).title)
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe an unknown element key is unknown_parameter at its pointer and creates nothing`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.call(JsonObject(createOp("U") + ("x" to JsonPrimitive(1))), createOp("V"))
            val err = r.envelope.body.errorAt(0)
            assertEquals("unknown_parameter", err.str("code"))
            assertEquals(
                listOf("/ops/0/x"),
                err
                    .detail()
                    .getValue("parameters")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )
            assertEquals(listOf("V"), k.titles())
        }

    @Test
    fun `probe priority High with a capital is rejected as invalid_request at the priority pointer`(): Unit =
        runBlocking {
            val k = kit()
            val r =
                k.call(
                    createOp("P", null, TEST_ITEM_TYPE, "priority" to jstr("High")),
                    createOp(
                        "Q",
                        null,
                        TEST_ITEM_TYPE,
                        "priority" to jstr("low")
                    )
                )
            val err = r.envelope.body.errorAt(0)
            assertEquals("invalid_request", err.str("code"))
            assertEquals(
                "/ops/0/priority",
                (
                    err
                        .detail()
                        .getValue("fields")
                        .jsonArray
                        .first() as JsonObject
                ).str("field")
            )
            assertEquals(listOf("Q"), k.titles())
            assertEquals(Priority.LOW, k.all().single().priority)
        }

    @Test
    fun `probe recursive given as the string true is rejected and nothing is deleted`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.make("P")
            k.make("C", p)
            val err =
                k
                    .call(JsonObject(deleteOp(p.id) + ("recursive" to jstr("true"))))
                    .envelope.body
                    .errorAt()
            assertEquals("invalid_request", err.str("code"))
            assertEquals(
                "/ops/0/recursive",
                (
                    err
                        .detail()
                        .getValue("fields")
                        .jsonArray
                        .first() as JsonObject
                ).str("field")
            )
            assertEquals(2, k.all().size)
        }

    @Test
    fun `probe a 501-char title is rejected while a 500-char title is accepted`(): Unit =
        runBlocking {
            val k = kit()
            val ok500 = "t".repeat(500)
            val bad = k.call(createOp("t".repeat(501)), createOp(ok500)).envelope.body
            assertEquals("invalid_request", bad.errorAt(0).str("code"))
            assertEquals(listOf(ok500), k.titles())
        }

    @Test
    fun `probe an empty ops array fails the whole call at the ops pointer`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.call()
            assertEquals(
                "invalid_request",
                r.envelope.body
                    .wholeError()
                    .str("code")
            )
            assertEquals(400, r.envelope.httpStatus)
            assertEquals(emptyList(), k.all())
        }

    // ------------------------------------------------------------------ S14 batch

    @Test
    fun `S14 atomic rolls back every element and reports the failing index, without atomic the others commit`(): Unit =
        runBlocking {
            val k = kit()
            val atomic = k.call(createOp("X"), createOp("Bad", null, null), createOp("Z"), atomic = true)
            assertEquals(400, atomic.envelope.httpStatus)
            val err = atomic.envelope.body.wholeError()
            assertEquals("invalid_request", err.str("code"))
            assertEquals("1", err.str("index"))
            assertEquals(emptyList(), k.titles(), "no element survived the atomic batch")

            val loose = k.call(createOp("A"), createOp("Bad", null, null), createOp("C"))
            assertEquals(207, loose.envelope.httpStatus)
            assertEquals(
                "partial_failure",
                loose.envelope.body
                    .wholeError()
                    .str("code")
            )
            assertEquals("""{"ok":2,"failed":1}""", loose.envelope.body.summary())
            assertEquals(listOf("A", "C"), k.titles(), "control: the same batch without atomic commits its good elements")
            assertEquals(
                3,
                loose.envelope.body
                    .results()
                    .size
            )
        }

    // ------------------------------------------------------------------ S15 idempotency

    @Test
    fun `S15 a repeated idempotencyKey replays the first result with no second row, a different payload is idempotency_mismatch`(): Unit =
        runBlocking {
            val k = kit()
            val keyed = createOp("K", null, TEST_ITEM_TYPE, "idempotencyKey" to jstr("key-1"))
            val first = k.call(keyed, actor = IW_ACTOR).envelope.body
            assertNull(first.okResult().get("replayed"), "the first execution is not a replay")
            val id = first.okId()
            val again = k.call(keyed, actor = IW_ACTOR).envelope.body
            assertEquals("true", again.okResult().str("replayed"))
            assertEquals(id, again.okId(), "the replay returns the first result")
            assertEquals(listOf("K"), k.titles(), "no second row")
            val other = createOp("K2", null, TEST_ITEM_TYPE, "idempotencyKey" to jstr("key-1"))
            val mismatch =
                k
                    .call(other, actor = IW_ACTOR)
                    .envelope.body
                    .errorAt()
            assertEquals("idempotency_mismatch", mismatch.str("code"))
            assertEquals(listOf("K"), k.titles())
        }

    // ------------------------------------------------------------------ S16 event actor

    @Test
    fun `S16 item created updated deleted rows carry the lifted actor as principal`(): Unit =
        runBlocking {
            val k = kit()
            val (id, rows) =
                k.rig.written {
                    val created =
                        k
                            .call(createOp("Ev"), actor = IW_ACTOR)
                            .envelope.body
                            .okId()
                    k
                        .call(updateOp(created, "title" to jstr("Ev2")), actor = IW_ACTOR)
                        .envelope.body
                        .okResult()
                    k
                        .call(deleteOp(created), actor = IW_ACTOR)
                        .envelope.body
                        .okResult()
                    created
                }
            for (type in listOf("item.created", "item.updated", "item.deleted")) {
                val row =
                    rows
                        .filter { it.type == type }
                        .also {
                            assertEquals(
                                1,
                                it.size,
                                "$type rows: ${rows.map { r ->
                                    r.type
                                }}"
                            )
                        }.single()
                assertEquals(id, row.entityId, type)
                assertEquals("agent-1", row.principalId, "$type principal id")
                assertEquals("subagent", row.principalKind, "$type principal kind")
            }
        }

    @Test
    fun `S16 control - without an actor the same writes record no principal`(): Unit =
        runBlocking {
            val k = kit()
            val (_, rows) =
                k.rig.written {
                    k
                        .call(createOp("NoActor"))
                        .envelope.body
                        .okId()
                }
            val created = rows.single { it.type == "item.created" }
            assertNull(created.principalId, "an anonymous call has no principal, so the actor assertion above can fail")
        }
}
