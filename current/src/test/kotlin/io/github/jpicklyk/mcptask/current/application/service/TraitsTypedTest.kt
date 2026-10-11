package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureWriteTestApp
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.createCommand
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for typed item traits at the service, MCP and REST seams (item 22088359, Q15): scenarios S1-S5, S9-S11,
 * S13 and S15 of the frozen test-plan plus probes. Everything runs through the real production composition ([EventLogRig])
 * or the REST write test application over a real SQLite file; stored rows are read back with raw SQL against the V26 DDL
 * (`item_traits(item_id, position, name)`), never through the code under test alone.
 *
 * Oracles (frozen task-scope B2-B7 and AC1-AC4, AC8, AC9, plan 3.7 and U7, none read from the implementation):
 *  - AC1/B2/B4: traits are a typed list stored in the given order (positions 0..n-1); `properties` never carries a `traits`
 *    key; a `properties` string that parses to a JSON object with a top-level `traits` key is rejected with
 *    `invalid_request` (field `properties`) on create and on patch, while an unparseable or non-object `properties` string
 *    is stored as-is; the shape rule (non-blank, trimmed, no comma, at most 100 characters, no duplicate, case-sensitive) is
 *    `invalid_request`.
 *  - AC9/B4/D2: when the root's effective trait set is non-empty, every name of a create (and of a patch whose list differs
 *    from the stored one) must be in it, else `schema_violation` with `ErrorDetail.SchemaViolation(type = null,
 *    reason = "trait '<name>' is not declared", availableTraits = [...])` and nothing is written (version and modifiedAt
 *    untouched); an empty set accepts any well-formed name. The declared set is sorted ascending in the fixtures below
 *    (declaration order equals sorted order), so the order of `availableTraits` is not an independent claim here.
 *  - AC2/B5 (docs: api-reference.md manage_items): the 3.x `traits` string is split on `,`, trimmed, empty elements dropped,
 *    duplicates dropped first-wins; on update an absent value keeps the list and `""` clears it; a top-level `traits` is a
 *    shared default that a per-item value overrides; `query_items get` returns `traits` as a top-level array and omits it
 *    when empty; an undeclared name is an element failure `schema_violation` carrying `availableTraits`.
 *  - AC3: `get_context` on an item carrying a trait lists the trait's required note among the missing notes (plan 3.7).
 *  - AC4/B7 (docs: api-rest.md POST/PATCH/section 23, openapi.yaml): `traits` is a JSON array on POST and PATCH, `ItemDto.traits`
 *    is always present, `{"traits": null}` clears (RFC 7396), an absent field keeps the list, a `properties` object with a
 *    `traits` key and an undeclared name are `400 validation_error` (the latter with `details.availableTraits`).
 *  - AC8/B4(c): `item.updated.changedFields` lists `traits` exactly when the list differs, and a patch that changes nothing
 *    records no row.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class TraitsTypedTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // Declared in sorted order on purpose (see the class note on availableTraits).
    private val typedYaml =
        "work_item_schemas:\n" +
            "  test-item:\n    notes: []\n" +
            "  feature-task:\n    notes:\n      - { key: spec, role: queue, required: true }\n" +
            "traits:\n" +
            "  alpha-trait:\n    notes:\n      - { key: alpha-note, role: work, required: false }\n" +
            "  beta-trait:\n    notes:\n      - { key: beta-note, role: work, required: false }\n" +
            "  gamma-trait:\n    notes:\n      - { key: gamma-note, role: work, required: false }\n" +
            "  needs-migration-review:\n    notes:\n      - { key: migration-assessment, role: queue, required: true }\n"

    private val declared = listOf("alpha-trait", "beta-trait", "gamma-trait", "needs-migration-review")

    private fun typedRig(dir: Path) = EventLogRig.build(db.db, dir, typedYaml)

    private fun freeRig(dir: Path) = EventLogRig.build(db.db, dir)

    private val EventLogRig.svc: ItemCommandService get() = ctx.itemCommandService

    private suspend fun EventLogRig.make(
        title: String,
        traits: List<String> = emptyList(),
        parent: WorkItem? = null,
        properties: String? = null,
        type: String = TEST_ITEM_TYPE,
    ): WorkItem =
        svc
            .create(createCommand(title = title, parentId = parent?.id, type = type, properties = properties).copy(traits = traits))
            .orFail()

    private suspend fun EventLogRig.tryCreate(
        title: String,
        traits: List<String> = emptyList(),
        parent: WorkItem? = null,
        properties: String? = null,
    ): Outcome<WorkItem> = svc.create(createCommand(title = title, parentId = parent?.id, properties = properties).copy(traits = traits))

    private suspend fun EventLogRig.reload(item: WorkItem): WorkItem = raw.workItemRepository().getById(item.id)!!

    private suspend fun EventLogRig.itemCount(): Int = rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items")

    private fun rawTraits(id: UUID): List<Pair<Int, String>> =
        P7Raw.query(db.jdbcUrl, "SELECT position, name FROM item_traits WHERE item_id = ? ORDER BY position", id) {
            it.getInt(1) to it.getString(2)
        }

    private fun rawTraitsByTitle(title: String): List<Pair<Int, String>> =
        P7Raw.query(
            db.jdbcUrl,
            "SELECT t.position, t.name FROM item_traits t JOIN work_items w ON w.id = t.item_id WHERE w.title = ? ORDER BY t.position",
            title,
        ) { it.getInt(1) to it.getString(2) }

    private fun ordered(vararg names: String): List<Pair<Int, String>> = names.mapIndexed { i, n -> i to n }

    private fun <T> Outcome<T>.errorOrFail(): DomainError =
        when (this) {
            is Outcome.Err -> error
            is Outcome.Ok -> throw AssertionError("expected a rejection, got $value")
        }

    private fun DomainError.violation(): ErrorDetail.SchemaViolation = assertIs<ErrorDetail.SchemaViolation>(detail)

    private fun EventRecord.changed(): Set<String> = payload()["changedFields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun nameOf(length: Int): String = "n".repeat(length)

    // ---------------------------------------------------------------------------------------------
    // S1 - create and patch store a typed, ordered list
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 create stores declared traits in the given order and properties never carries them`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)

            val item = r.make("ordered", listOf("gamma-trait", "alpha-trait"))

            assertEquals(listOf("gamma-trait", "alpha-trait"), r.reload(item).traits, "order is the given order, not alphabetical")
            assertEquals(ordered("gamma-trait", "alpha-trait"), rawTraits(item.id))
            assertNull(r.reload(item).properties, "no properties were supplied, so none are stored")
            val bare = r.make("bare")
            assertEquals(emptyList(), r.reload(bare).traits)
            assertEquals(emptyList(), rawTraits(bare.id))
        }

    @Test
    fun `S1 supplied properties are stored as given beside the traits`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)

            val item = r.make("both", listOf("beta-trait"), properties = """{"custom":1}""")

            assertEquals("""{"custom":1}""", r.reload(item).properties)
            assertEquals(listOf("beta-trait"), r.reload(item).traits)
        }

    @Test
    fun `S1 a patch replaces the list wholesale and clears it with an empty list`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            var item = r.make("evolving", listOf("alpha-trait", "beta-trait", "gamma-trait"))
            val other = r.make("other", listOf("beta-trait"))

            item =
                r.svc
                    .patch(patchOf(r.reload(item)).copy(traits = listOf("gamma-trait")))
                    .orFail()
                    .item
            assertEquals(listOf("gamma-trait"), r.reload(item).traits)
            assertEquals(ordered("gamma-trait"), rawTraits(item.id), "3 names shrink to 1 row, no stale position")

            item =
                r.svc
                    .patch(patchOf(r.reload(item)).copy(traits = listOf("beta-trait", "alpha-trait")))
                    .orFail()
                    .item
            assertEquals(ordered("beta-trait", "alpha-trait"), rawTraits(item.id))

            item =
                r.svc
                    .patch(patchOf(r.reload(item)).copy(traits = listOf("alpha-trait", "beta-trait")))
                    .orFail()
                    .item
            assertEquals(ordered("alpha-trait", "beta-trait"), rawTraits(item.id), "a reorder is a change")

            item =
                r.svc
                    .patch(patchOf(r.reload(item), title = "renamed"))
                    .orFail()
                    .item
            assertEquals(ordered("alpha-trait", "beta-trait"), rawTraits(item.id), "a patch that keeps the list keeps the rows")

            item =
                r.svc
                    .patch(patchOf(r.reload(item)).copy(traits = emptyList()))
                    .orFail()
                    .item
            assertEquals(emptyList(), rawTraits(item.id))
            assertEquals(emptyList(), r.reload(item).traits)
            assertEquals(ordered("beta-trait"), rawTraits(other.id), "another item is never touched")
        }

    // ---------------------------------------------------------------------------------------------
    // S11 - shape violations (a schema-free server declares nothing, so the shape rule is the only check)
    // ---------------------------------------------------------------------------------------------

    private val badShapes: List<Pair<String, List<String>>> =
        listOf(
            "empty name" to listOf(""),
            "blank name" to listOf("   "),
            "leading space" to listOf(" a"),
            "trailing space" to listOf("a "),
            "leading tab" to listOf("\ta"),
            "comma inside" to listOf("a,b"),
            "101 characters" to listOf(nameOf(101)),
            "exact duplicate" to listOf("a", "a"),
            "valid then empty" to listOf("ok", ""),
            "valid then comma" to listOf("ok", "b,c"),
            "duplicate not adjacent" to listOf("a", "b", "a"),
        )

    @Test
    fun `S11 a create with a malformed name or a duplicate is invalid_request and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            r.make("control", listOf("fine"))

            for ((label, traits) in badShapes) {
                val items = r.itemCount()
                val seq = r.maxSeq()

                val err = r.tryCreate("bad", traits).errorOrFail()

                assertEquals(ErrorCode.INVALID_REQUEST, err.code, label)
                assertEquals(items, r.itemCount(), "$label: no row written")
                assertEquals(seq, r.maxSeq(), "$label: no event recorded")
            }
            assertEquals(
                0,
                rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM item_traits WHERE name IN ('ok', 'b,c', 'a')"),
                "no partial write of a rejected list",
            )
        }

    @Test
    fun `S11 a patch with a malformed list is invalid_request and the stored row is untouched`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            val item = r.make("subject", listOf("keep-1", "keep-2"))
            val before = r.reload(item)

            for ((label, traits) in badShapes) {
                val seq = r.maxSeq()

                val err = r.svc.patch(patchOf(before, title = "renamed").copy(traits = traits)).errorOrFail()

                assertEquals(ErrorCode.INVALID_REQUEST, err.code, label)
                assertEquals(before, r.reload(item), "$label: version, modifiedAt, title and traits are untouched")
                assertEquals(ordered("keep-1", "keep-2"), rawTraits(item.id), label)
                assertEquals(seq, r.maxSeq(), "$label: no event recorded")
            }
        }

    @Test
    fun `S11 boundary and permitted spellings are accepted exactly as given`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)

            for (traits in listOf(
                listOf(nameOf(100)),
                listOf("A", "a"),
                listOf("with space inside"),
                listOf("gr\u00f6\u00dfe-\u00e9"),
                listOf("x-1", "y_2", "z.3"),
                listOf("\u00e9".repeat(100)),
            )) {
                val item = r.make("ok ${traits.first().take(8)}", traits)

                assertEquals(traits, r.reload(item).traits, "stored as given: $traits")
                assertEquals(traits.mapIndexed { i, n -> i to n }, rawTraits(item.id))
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S9 / S13 - the declared set
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 an undeclared trait on create is schema_violation with availableTraits and nothing is written`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            val control = r.make("declared control", listOf("alpha-trait"))

            for (traits in listOf(listOf("nope"), listOf("alpha-trait", "nope"), listOf("ALPHA-TRAIT"), listOf("alpha-trait2"))) {
                val items = r.itemCount()
                val seq = r.maxSeq()

                val err = r.tryCreate("bad", traits).errorOrFail()

                assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code, "$traits")
                val violation = err.violation()
                assertNull(violation.type, "$traits: a trait violation names no type")
                assertEquals(declared, violation.availableTraits, "$traits")
                assertEquals(declared, ItemCommandErrors.availableTraits(err), "$traits")
                assertEquals(items, r.itemCount(), "$traits: no row written, the valid first element included")
                assertEquals(seq, r.maxSeq(), "$traits: no event recorded")
            }
            val single = r.tryCreate("bad", listOf("nope")).errorOrFail().violation()
            assertEquals("trait 'nope' is not declared", single.reason)
            assertEquals(listOf("alpha-trait"), r.reload(control).traits, "control: the declared spelling is accepted")
        }

    @Test
    fun `S9 an undeclared trait on a changed patch is rejected before the write and an unchanged list is never re-checked`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            val item = r.make("subject", listOf("alpha-trait"))
            val before = r.reload(item)
            val stale = r.raw.workItemRepository().create(WorkItem(title = "stale", type = TEST_ITEM_TYPE, traits = listOf("gone-trait")))

            val err = r.svc.patch(patchOf(before).copy(traits = listOf("nope"))).errorOrFail()
            assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code)
            assertEquals("trait 'nope' is not declared", err.violation().reason)
            assertEquals(declared, err.violation().availableTraits)
            assertEquals(before, r.reload(item), "version and modifiedAt are untouched by the rejection")

            r.svc.patch(patchOf(before).copy(traits = listOf("beta-trait"))).orFail()
            assertEquals(listOf("beta-trait"), r.reload(item).traits)

            // a stale (since undeclared) list is editable while it stays unchanged ...
            r.svc.patch(patchOf(stale, title = "stale renamed")).orFail()
            assertEquals("stale renamed", r.reload(stale).title)
            assertEquals(listOf("gone-trait"), r.reload(stale).traits)
            r.svc.patch(patchOf(r.reload(stale)).copy(traits = listOf("gone-trait"))).orFail()
            // ... and checked as soon as the list changes
            val changed = r.svc.patch(patchOf(r.reload(stale)).copy(traits = listOf("gone-trait", "alpha-trait"))).errorOrFail()
            assertEquals(ErrorCode.SCHEMA_VIOLATION, changed.code)
            assertEquals(listOf("gone-trait"), r.reload(stale).traits, "the rejected change left the stale list")
        }

    @Test
    fun `S13 with no trait declared any well-formed name is accepted and a per-root declaration scopes the rule to that root`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            val rootA = r.make("root A")
            val rootB = r.make("root B")

            val free = r.make("anything", listOf("anything-goes", "Another_One"), parent = rootA)
            assertEquals(listOf("anything-goes", "Another_One"), r.reload(free).traits)

            r.raw.configStore().upsert(
                rootA.id,
                "traits:\n  only-here:\n    notes:\n      - { key: only-note, role: work, required: false }\n"
            )

            val ok = r.make("declared", listOf("only-here"), parent = rootA)
            assertEquals(listOf("only-here"), r.reload(ok).traits)
            val items = r.itemCount()
            val err = r.tryCreate("undeclared", listOf("nope"), parent = rootA).errorOrFail()
            assertEquals(ErrorCode.SCHEMA_VIOLATION, err.code)
            assertEquals(listOf("only-here"), err.violation().availableTraits, "the effective set of root A")
            assertEquals(items, r.itemCount())
            val elsewhere = r.make("other root", listOf("nope"), parent = rootB)
            assertEquals(listOf("nope"), r.reload(elsewhere).traits, "root B declares nothing, so it accepts any well-formed name")
        }
    // ---------------------------------------------------------------------------------------------
    // S10 - properties may not carry traits
    // ---------------------------------------------------------------------------------------------

    private val propertiesWithTraits =
        listOf(
            """{"traits":["a"]}""",
            """{"traits":"a,b"}""",
            """{"traits":[]}""",
            """{"traits":null}""",
            """{"custom":1,"traits":["a"]}""",
            """   {"traits":["a"]}   """,
        )

    private val propertiesStoredAsIs =
        listOf(
            """{"custom":1}""",
            "{}",
            "[]",
            "not json",
            """{"Traits":["a"]}""",
            """{"a":{"traits":1}}""",
            """{"traitsX":1}""",
        )

    @Test
    fun `S10 a create whose properties object holds a traits key is invalid_request on properties and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)

            for (props in propertiesWithTraits) {
                val items = r.itemCount()
                val seq = r.maxSeq()

                val err = r.tryCreate("bad", properties = props).errorOrFail()

                assertEquals(ErrorCode.INVALID_REQUEST, err.code, props)
                val detail = assertIs<ErrorDetail.InvalidRequest>(err.detail, props)
                assertTrue(
                    detail.fields.any { it.field == "properties" },
                    "$props: the violation names the field properties: ${detail.fields}"
                )
                assertEquals(items, r.itemCount(), "$props: no row written")
                assertEquals(seq, r.maxSeq(), "$props: no event recorded")
            }
            for (props in propertiesStoredAsIs) {
                val item = r.make("fine ${props.take(6)}", properties = props)
                assertEquals(props, r.reload(item).properties, "stored as-is: $props")
            }
        }

    @Test
    fun `S10 a patch that leaves properties holding a traits key is invalid_request and the row is untouched`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            val item = r.make("subject", listOf("kept"), properties = """{"custom":1}""")
            val before = r.reload(item)

            for (props in propertiesWithTraits) {
                val seq = r.maxSeq()

                val err = r.svc.patch(patchOf(before, title = "renamed").copy(properties = props)).errorOrFail()

                assertEquals(ErrorCode.INVALID_REQUEST, err.code, props)
                assertTrue(assertIs<ErrorDetail.InvalidRequest>(err.detail).fields.any { it.field == "properties" }, props)
                assertEquals(before, r.reload(item), "$props: untouched")
                assertEquals(seq, r.maxSeq(), "$props: no event recorded")
            }
            r.svc.patch(patchOf(before).copy(properties = """{"custom":2}""")).orFail()
            assertEquals("""{"custom":2}""", r.reload(item).properties)
            assertEquals(listOf("kept"), r.reload(item).traits, "control: a clean properties patch leaves the traits alone")
        }

    // ---------------------------------------------------------------------------------------------
    // S15 - events
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 item updated lists traits exactly when the list changed and an unchanged patch records nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            val item = r.make("subject", listOf("alpha-trait", "beta-trait"))

            val (_, changedRows) = r.written { r.svc.patch(patchOf(r.reload(item)).copy(traits = listOf("gamma-trait"))).orFail() }
            val updated = changedRows.single { it.type == "item.updated" }
            assertEquals(item.id, updated.entityId)
            assertEquals(setOf("traits"), updated.changed(), "only traits changed")

            val (_, titleRows) = r.written { r.svc.patch(patchOf(r.reload(item), title = "renamed")).orFail() }
            assertEquals(
                setOf("title"),
                titleRows.single { it.type == "item.updated" }.changed(),
                "traits did not change, so it is not listed"
            )

            val (_, noop) = r.written { r.svc.patch(patchOf(r.reload(item))).orFail() }
            assertEquals(emptyList(), noop, "a patch that changes nothing records no row: $noop")

            val (_, grownRows) =
                r.written {
                    r.svc
                        .patch(
                            patchOf(r.reload(item)).copy(traits = listOf("gamma-trait", "alpha-trait"))
                        ).orFail()
                }
            assertTrue("traits" in grownRows.single { it.type == "item.updated" }.changed(), "a longer list is a change")
            val (_, swapRows) =
                r.written {
                    r.svc
                        .patch(
                            patchOf(r.reload(item)).copy(traits = listOf("alpha-trait", "gamma-trait"))
                        ).orFail()
                }
            assertTrue("traits" in swapRows.single { it.type == "item.updated" }.changed(), "a reorder is a change")
            val (_, clearRows) = r.written { r.svc.patch(patchOf(r.reload(item)).copy(traits = emptyList())).orFail() }
            assertEquals(setOf("traits"), clearRows.single { it.type == "item.updated" }.changed(), "clearing is a change")
            val (_, again) = r.written { r.svc.patch(patchOf(r.reload(item)).copy(traits = emptyList())).orFail() }
            assertEquals(emptyList(), again, "clearing an already empty list records nothing")
        }

    // ---------------------------------------------------------------------------------------------
    // S2 / S3 / S9 / S10 - the manage_items and create_work_tree adapters
    // ---------------------------------------------------------------------------------------------

    private fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject

    private fun JsonObject.failures(): List<JsonObject> = data()["failures"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content

    private fun createElement(
        title: String,
        traits: String? = null,
        properties: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "test-item")
            put("title", title)
            if (traits != null) put("traits", traits)
            if (properties != null) put("properties", properties)
        }

    private suspend fun EventLogRig.createItems(
        vararg elements: JsonObject,
        sharedTraits: String? = null,
    ): JsonObject {
        val params = mutableListOf<Pair<String, JsonElement>>("operation" to str("create"), "items" to JsonArray(elements.toList()))
        if (sharedTraits != null) params += "traits" to str(sharedTraits)
        return callOk(ManageItemsTool(), *params.toTypedArray())
    }

    private suspend fun EventLogRig.updateItems(
        vararg elements: JsonObject,
        sharedTraits: String? = null,
    ): JsonObject {
        val params = mutableListOf<Pair<String, JsonElement>>("operation" to str("update"), "items" to JsonArray(elements.toList()))
        if (sharedTraits != null) params += "traits" to str(sharedTraits)
        return callOk(ManageItemsTool(), *params.toTypedArray())
    }

    private fun updateElement(
        id: UUID,
        traits: String? = null,
        properties: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("itemId", id.toString())
            if (traits != null) put("traits", traits)
            if (properties != null) put("properties", properties)
        }

    private suspend fun EventLogRig.getData(id: UUID): JsonObject =
        callOk(QueryItemsTool(), "operation" to str("get"), "itemId" to str(id.toString())).data()

    private suspend fun EventLogRig.wireTraits(id: UUID): List<String> =
        getData(id)["traits"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonObject.createdIds(): List<UUID> = data()["items"]!!.jsonArray.map { UUID.fromString(it.jsonObject.text("id")) }

    private suspend fun EventLogRig.storedProperties(id: UUID): String? = raw.workItemRepository().getById(id)!!.properties

    @Test
    fun `S2 manage_items create splits trims drops empty and dedupes the traits string and get returns a top-level array`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)

            val result =
                r.createItems(
                    createElement("messy", "b, a,,b"),
                    createElement("single", "  solo  "),
                    createElement("empty-string", ""),
                    createElement("only-commas", ",,,"),
                    createElement("none"),
                    createElement("with props", "z", """{"custom":"v"}"""),
                )

            assertEquals(6, result.data()["created"]!!.jsonPrimitive.int, "$result")
            val ids = result.createdIds()
            assertEquals(listOf("b", "a"), r.wireTraits(ids[0]), "split, trim, drop empty, first-wins dedupe")
            assertEquals(ordered("b", "a"), rawTraits(ids[0]))
            assertEquals(listOf("solo"), r.wireTraits(ids[1]))
            for (i in 2..4) {
                assertEquals(emptyList(), r.wireTraits(ids[i]), "item $i has no traits")
                assertFalse(r.getData(ids[i]).containsKey("traits"), "an empty list is omitted from get, never an empty array")
                assertEquals(emptyList(), rawTraits(ids[i]))
            }
            assertEquals(listOf("z"), r.wireTraits(ids[5]))
            val props = Json.parseToJsonElement(r.getData(ids[5]).text("properties")).jsonObject
            assertEquals(setOf("custom"), props.keys, "properties keeps its own key and never gains traits")
            assertNull(r.storedProperties(ids[0]), "no properties supplied, none stored")
        }

    @Test
    fun `S2 a shared top-level traits value applies to elements without their own and a per-item value overrides it`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)

            val result = r.createItems(createElement("own", "own-trait"), createElement("inherits"), sharedTraits = "shared-1,shared-2")

            val ids = result.createdIds()
            assertEquals(listOf("own-trait"), r.wireTraits(ids[0]))
            assertEquals(listOf("shared-1", "shared-2"), r.wireTraits(ids[1]))
        }

    @Test
    fun `S3 manage_items update keeps the list when traits is absent, clears it with an empty string and replaces it otherwise`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            val id = r.createItems(createElement("subject", "x,y")).createdIds().single()
            assertEquals(listOf("x", "y"), r.wireTraits(id), "control: created with x,y")

            r.updateItems(updateElement(id))
            assertEquals(listOf("x", "y"), r.wireTraits(id), "absent keeps")

            r.updateItems(updateElement(id, traits = "y, x"))
            assertEquals(listOf("y", "x"), r.wireTraits(id), "replaces, and a reorder is honoured")
            assertEquals(ordered("y", "x"), rawTraits(id))

            r.updateItems(updateElement(id, traits = "z"))
            assertEquals(listOf("z"), r.wireTraits(id), "replaced, not unioned")

            r.updateItems(updateElement(id, traits = ""))
            assertEquals(emptyList(), r.wireTraits(id), "the empty string clears")
            assertEquals(emptyList(), rawTraits(id))
            assertFalse(r.getData(id).containsKey("traits"))
        }

    @Test
    fun `S3 manage_items update applies a shared traits value only to elements without their own`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            val ids =
                r
                    .createItems(
                        createElement("one", "old-1"),
                        createElement("two", "old-2"),
                        createElement("three", "old-3")
                    ).createdIds()

            r.updateItems(updateElement(ids[0]), updateElement(ids[1], traits = "own"), updateElement(ids[2]), sharedTraits = "shared")

            assertEquals(listOf("shared"), r.wireTraits(ids[0]), "the shared default applies")
            assertEquals(listOf("own"), r.wireTraits(ids[1]), "the per-item value wins")
            assertEquals(listOf("shared"), r.wireTraits(ids[2]))
        }

    @Test
    fun `S9 manage_items reports an undeclared trait as an element failure carrying availableTraits and still runs the siblings`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            val before = r.itemCount()

            val result =
                r.createItems(
                    createElement("good-1", "alpha-trait"),
                    createElement("bad", "alpha-trait,nope"),
                    createElement("good-2", "beta-trait, gamma-trait"),
                )

            val data = result.data()
            assertEquals(2, data["created"]!!.jsonPrimitive.int, "$result")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int, "$result")
            val failure = result.failures().single()
            assertEquals(1, failure["index"]!!.jsonPrimitive.int, "$failure")
            assertEquals(ErrorCode.SCHEMA_VIOLATION.wire, failure.text("errorCode"), "$failure")
            assertEquals(declared, failure["availableTraits"]!!.jsonArray.map { it.jsonPrimitive.content }, "$failure")
            assertEquals(before + 2, r.itemCount(), "only the two valid elements were written")
            val ids = result.createdIds()
            assertEquals(listOf("alpha-trait"), r.wireTraits(ids[0]))
            assertEquals(listOf("beta-trait", "gamma-trait"), r.wireTraits(ids[1]))
        }

    @Test
    fun `S9 manage_items update with an undeclared trait fails the element and leaves the item as it was`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            val id = r.createItems(createElement("subject", "alpha-trait")).createdIds().single()
            val before = r.raw.workItemRepository().getById(id)!!

            val result = r.updateItems(updateElement(id, traits = "nope"))

            val failure = result.failures().single()
            assertEquals(ErrorCode.SCHEMA_VIOLATION.wire, failure.text("errorCode"), "$failure")
            assertEquals(id.toString(), failure.text("id"), "$failure")
            assertEquals(declared, failure["availableTraits"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(before, r.raw.workItemRepository().getById(id), "version and traits untouched")
        }

    @Test
    fun `S10 manage_items rejects a properties string carrying traits as an element failure on create and on update`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)
            val id = r.createItems(createElement("subject", "kept")).createdIds().single()
            val before = r.itemCount()

            val created =
                r.createItems(
                    createElement("bad", properties = """{"traits":["a"]}"""),
                    createElement("good", properties = """{"custom":1}"""),
                )
            val updated = r.updateItems(updateElement(id, properties = """{"traits":["a"],"custom":1}"""))

            assertEquals(1, created.data()["created"]!!.jsonPrimitive.int, "$created")
            assertEquals(ErrorCode.INVALID_REQUEST.wire, created.failures().single().text("errorCode"), "$created")
            assertEquals(before + 1, r.itemCount(), "only the clean element was written")
            assertEquals(ErrorCode.INVALID_REQUEST.wire, updated.failures().single().text("errorCode"), "$updated")
            assertEquals(listOf("kept"), r.wireTraits(id), "the failed update changed nothing")
            assertNull(r.storedProperties(id))
        }

    @Test
    fun `S2 create_work_tree stores the traits of the root and of a child as typed lists`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = freeRig(dir)

            r.callOk(
                CreateWorkTreeTool(),
                "root" to
                    buildJsonObject {
                        put("title", "Tree root")
                        put("type", "test-item")
                        put("traits", "b, a,,b")
                    },
                "children" to
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("ref", "c1")
                                put("type", "test-item")
                                put("title", "Tree child")
                                put("traits", "c")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("ref", "c2")
                                put("type", "test-item")
                                put("title", "Tree plain")
                            },
                        )
                    },
            )

            assertEquals(ordered("b", "a"), rawTraitsByTitle("Tree root"))
            assertEquals(ordered("c"), rawTraitsByTitle("Tree child"))
            assertEquals(emptyList(), rawTraitsByTitle("Tree plain"))
            for (title in listOf("Tree root", "Tree child", "Tree plain")) {
                assertNull(
                    P7Raw.query(db.jdbcUrl, "SELECT properties FROM work_items WHERE title = ?", title) { it.getString(1) }.single(),
                    "$title: traits never land in properties",
                )
            }
        }
    // ---------------------------------------------------------------------------------------------
    // S5 - resolution reads the typed list
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 an item carrying needs-migration-review requires migration-assessment and filling it clears the gate`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val r = typedRig(dir)
            val gated = r.make("gated", listOf("needs-migration-review"), type = "feature-task")
            val plain = r.make("plain", type = "feature-task")

            suspend fun gate(item: WorkItem): JsonObject =
                r.callOk(GetContextTool(), "itemId" to str(item.id.toString())).data()["gateStatus"]!!.jsonObject

            suspend fun missing(item: WorkItem): Set<String> = gate(item)["missing"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

            assertEquals(setOf("spec", "migration-assessment"), missing(gated), "base note plus the trait's note")
            assertEquals(setOf("spec"), missing(plain), "control: without the trait only the base note is required")

            r.raw.noteRepository().upsert(Note(itemId = gated.id, key = "spec", role = "queue", body = "spec text"))
            assertEquals(setOf("migration-assessment"), missing(gated))
            r.raw.noteRepository().upsert(Note(itemId = gated.id, key = "migration-assessment", role = "queue", body = "assessed"))
            assertEquals(emptySet(), missing(gated))
            assertTrue(gate(gated)["canAdvance"]!!.jsonPrimitive.boolean, "${gate(gated)}")
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - REST
    // ---------------------------------------------------------------------------------------------

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        etag: String? = null,
        asPatch: Boolean = false,
    ): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $WRITE_TOKEN")
            if (etag != null) header(HttpHeaders.IfMatch, etag)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (asPatch) patch(path, block) else post(path, block)
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.traitList(): List<String> = this["traits"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun idOf(body: String): String = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""").find(body)!!.groupValues[1]

    @Test
    fun `S4 POST accepts a traits array, returns it in order, GET shows it and a missing array reads as empty`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val created = client.send("/api/v1/items", """{"title":"With traits","type":"alpha-type","traits":["b","a"]}""")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val createdJson = parse(created.bodyAsText())
            assertEquals(listOf("b", "a"), createdJson.traitList())
            val id = UUID.fromString(idOf(created.bodyAsText()))
            assertEquals(ordered("b", "a"), rawTraits(id))
            assertEquals(listOf("b", "a"), repo.workItemRepository().getById(id)!!.traits)

            val got = client.get("/api/v1/items/$id") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(listOf("b", "a"), parse(got.bodyAsText()).traitList(), "GET returns the same order")

            val bare = client.send("/api/v1/items", """{"title":"Without traits","type":"alpha-type"}""")
            assertEquals(HttpStatusCode.Created, bare.status, bare.bodyAsText())
            val bareJson = parse(bare.bodyAsText())
            assertTrue(bareJson.containsKey("traits"), "traits is always present on an ItemDto: $bareJson")
            assertEquals(emptyList(), bareJson.traitList())
        }

    @Test
    fun `S4 PATCH replaces the list, keeps it when absent and clears it with null`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val created = client.send("/api/v1/items", """{"title":"Subject","type":"alpha-type","traits":["k1","k2"]}""")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val id = UUID.fromString(idOf(created.bodyAsText()))
            var etag: String? = created.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            suspend fun patchBody(body: String): JsonObject {
                val response = client.send("/api/v1/items/$id", body, etag = etag, asPatch = true)
                assertEquals(HttpStatusCode.OK, response.status, "$body: ${response.bodyAsText()}")
                etag = assertNotNull(response.headers[HttpHeaders.ETag], "$body: the new ETag")
                return parse(response.bodyAsText())
            }

            assertEquals(listOf("k1", "k2"), patchBody("""{"title":"renamed"}""").traitList(), "an absent traits field keeps the list")
            assertEquals(ordered("k1", "k2"), rawTraits(id))

            assertEquals(listOf("z"), patchBody("""{"traits":["z"]}""").traitList())
            assertEquals(ordered("z"), rawTraits(id))

            assertEquals(listOf("b", "a"), patchBody("""{"traits":["b","a"]}""").traitList())
            assertEquals(listOf("a", "b"), patchBody("""{"traits":["a","b"]}""").traitList(), "a reorder is a change")
            assertEquals(ordered("a", "b"), rawTraits(id))

            assertEquals(emptyList(), patchBody("""{"traits":null}""").traitList(), "RFC 7396: null clears")
            assertEquals(emptyList(), rawTraits(id))
            assertEquals(emptyList(), repo.workItemRepository().getById(id)!!.traits)

            assertEquals(listOf("again"), patchBody("""{"traits":["again"]}""").traitList())
            assertEquals(emptyList(), patchBody("""{"traits":[]}""").traitList(), "an empty array clears as well")
        }

    @Test
    fun `S4 a properties object carrying traits is 400 validation_error on POST and on PATCH and a clean object is accepted`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val items = rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items")

            for (props in listOf("""{"traits":["a"]}""", """{"custom":1,"traits":"a,b"}""", """{"traits":null}""")) {
                val response = client.send("/api/v1/items", """{"title":"Bad","type":"alpha-type","properties":$props}""")
                assertEquals(HttpStatusCode.BadRequest, response.status, "$props: ${response.bodyAsText()}")
                val json = parse(response.bodyAsText())
                assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "$props: $json")
                assertTrue(response.bodyAsText().contains("traits is a typed field"), "$props: the error carries the frozen reason: $json")
            }
            assertEquals(items, rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items"), "no rejected POST wrote a row")

            val clean = client.send("/api/v1/items", """{"title":"Clean","type":"alpha-type","properties":{"custom":1}}""")
            assertEquals(HttpStatusCode.Created, clean.status, clean.bodyAsText())
            val id = UUID.fromString(idOf(clean.bodyAsText()))
            val etag = clean.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val before = repo.workItemRepository().getById(id)!!

            for (props in listOf("""{"traits":["a"]}""", """{"custom":2,"traits":"a"}""")) {
                val response = client.send("/api/v1/items/$id", """{"properties":$props}""", etag = etag, asPatch = true)
                assertEquals(HttpStatusCode.BadRequest, response.status, "$props: ${response.bodyAsText()}")
                assertEquals("validation_error", parse(response.bodyAsText())["error"]?.jsonPrimitive?.content)
                assertEquals(before, repo.workItemRepository().getById(id), "$props: the item is untouched")
            }
            val ok = client.send("/api/v1/items/$id", """{"properties":{"custom":2}}""", etag = etag, asPatch = true)
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertTrue(
                repo
                    .workItemRepository()
                    .getById(id)!!
                    .properties!!
                    .contains("2"),
                "control: a clean properties patch merges"
            )
        }

    @Test
    fun `S4 an undeclared trait is 400 validation_error with availableTraits on POST and PATCH and nothing is written`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val root = client.send("/api/v1/items", """{"title":"Root","type":"alpha-type"}""")
            assertEquals(HttpStatusCode.Created, root.status, root.bodyAsText())
            val rootId = UUID.fromString(idOf(root.bodyAsText()))
            repo.configStore().upsert(
                rootId,
                "traits:\n  alpha-trait:\n    notes:\n      - { key: alpha-note, role: work, required: false }\n" +
                    "  beta-trait:\n    notes:\n      - { key: beta-note, role: work, required: false }\n",
            )

            val bad =
                client.send(
                    "/api/v1/items",
                    """{"title":"Bad","type":"alpha-type","parentId":"$rootId","traits":["alpha-trait","nope"]}"""
                )
            assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
            val badJson = parse(bad.bodyAsText())
            assertEquals("validation_error", badJson["error"]?.jsonPrimitive?.content, "$badJson")
            val details = badJson["details"]!!.jsonObject
            assertEquals(
                listOf("alpha-trait", "beta-trait"),
                details["availableTraits"]!!.jsonArray.map { it.jsonPrimitive.content },
                "$badJson"
            )
            assertEquals("trait 'nope' is not declared", details["reason"]?.jsonPrimitive?.content, "$badJson")
            assertEquals(0, repo.workItemRepository().findChildren(rootId).size, "the rejected POST wrote nothing")

            val good = client.send("/api/v1/items", """{"title":"Good","type":"alpha-type","parentId":"$rootId","traits":["beta-trait"]}""")
            assertEquals(HttpStatusCode.Created, good.status, good.bodyAsText())
            val goodId = UUID.fromString(idOf(good.bodyAsText()))
            val etag = good.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val before = repo.workItemRepository().getById(goodId)!!

            val badPatch = client.send("/api/v1/items/$goodId", """{"traits":["nope"]}""", etag = etag, asPatch = true)
            assertEquals(HttpStatusCode.BadRequest, badPatch.status, badPatch.bodyAsText())
            val patchDetails = parse(badPatch.bodyAsText())["details"]!!.jsonObject
            assertEquals(listOf("alpha-trait", "beta-trait"), patchDetails["availableTraits"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(before, repo.workItemRepository().getById(goodId), "the rejected PATCH changed nothing")

            val goodPatch = client.send("/api/v1/items/$goodId", """{"traits":["alpha-trait"]}""", etag = etag, asPatch = true)
            assertEquals(HttpStatusCode.OK, goodPatch.status, goodPatch.bodyAsText())
            assertEquals(listOf("alpha-trait"), repo.workItemRepository().getById(goodId)!!.traits)
        }
}
