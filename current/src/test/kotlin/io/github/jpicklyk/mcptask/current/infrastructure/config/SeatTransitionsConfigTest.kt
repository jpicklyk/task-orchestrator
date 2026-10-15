package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.ItemSchemaView
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 9536cf09, R23) for the per-seat `transitions:` list: parsing on schema seats and
 * trait seats (S1), the F5 fatal structural check (S11), the JSON Schema entry (S1, strict mode) and the seat JSON served by the
 * schema view (S1, served only when declared).
 *
 * Oracles (the frozen task-scope "What to build" 1 and decision D11, never a run): `transitions` is an optional list of user
 * trigger wires other than `start` (`start` is governed by `enters`); absent = the default `[block, hold]`, declared (even
 * empty) = exactly that list; a non-list is a soft warning at `<entry>/transitions` and treated as absent; an unknown or `start`
 * element is a soft warning at `<entry>/transitions/<i>` and dropped; duplicates collapse. F5: a seat with `enters: true` whose
 * `transitions` names `complete` or `cancel` is a fatal structural error naming the seat and the trigger, in every mode. The JSON
 * Schema declares `transitions` as an array whose items are the enum complete, block, hold, resume, cancel, reopen. The seat JSON
 * carries `transitions` only when the seat declared it (an empty declared list is served as `[]`).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class SeatTransitionsConfigTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    private fun seatsOf(parsed: YamlSchemaParser.ParsedConfig): List<SeatDefinition> = parsed.workItemSchemas.getValue("bug-fix").seats

    // ---------------------------------------------------------------------------------------------
    // S1 -- parsing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 a declared transitions list parses on a schema seat verbatim and an absent one is the default block and hold`() {
        val seats =
            seatsOf(
                parse(
                    """
                    work_item_schemas:
                      bug-fix:
                        seats:
                          - { name: builder, phase: work, enters: true }
                          - { name: checker, phase: work, transitions: [complete, resume] }
                    """.trimIndent()
                )
            )

        val builder = seats.single { it.name == "builder" }
        assertNull(builder.transitions, "absent is null, not an empty list")
        assertEquals(listOf("block", "hold"), builder.effectiveTransitions)
        assertEquals(listOf("block", "hold"), SeatDefinition.DEFAULT_TRANSITIONS)
        val checker = seats.single { it.name == "checker" }
        assertEquals(listOf("complete", "resume"), checker.transitions)
        assertEquals(listOf("complete", "resume"), checker.effectiveTransitions)
    }

    @Test
    fun `S1 a transitions list parses on a trait seat`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    default_traits: [t1]
                    seats:
                      - { name: builder, phase: work, enters: true }
                traits:
                  t1:
                    seats:
                      - { name: auditor, phase: work, transitions: [cancel, reopen] }
                """.trimIndent()
            )

        assertEquals(
            listOf(SeatDefinition("auditor", Role.WORK, transitions = listOf("cancel", "reopen"))),
            parsed.traitSeats["t1"]
        )
    }

    @Test
    fun `S1 a declared empty list is kept as empty and is not the default`() {
        val seat =
            seatsOf(
                parse(
                    """
                    work_item_schemas:
                      bug-fix:
                        seats:
                          - { name: silent, phase: work, transitions: [] }
                    """.trimIndent()
                )
            ).single()

        assertEquals(emptyList(), seat.transitions)
        assertEquals(emptyList(), seat.effectiveTransitions)
    }

    @Test
    fun `S1 every user trigger other than start is accepted and duplicates collapse`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: all, phase: work, transitions: [complete, block, hold, resume, cancel, reopen] }
                      - { name: twice, phase: work, transitions: [hold, hold, block, hold] }
                """.trimIndent()
            )

        val all = seatsOf(parsed).single { it.name == "all" }
        assertEquals(setOf("complete", "block", "hold", "resume", "cancel", "reopen"), all.transitions?.toSet())
        assertEquals(6, all.transitions?.size)
        assertTrue(parsed.warnings.none { it.contains("transitions") }, "valid wires warn nothing: ${parsed.warnings}")
        val twice = seatsOf(parsed).single { it.name == "twice" }
        assertEquals(setOf("hold", "block"), twice.transitions?.toSet())
        assertEquals(2, twice.transitions?.size, "duplicates collapse: ${twice.transitions}")
    }

    @Test
    fun `S1 leniently, an unknown or start element is dropped with a warning naming the seat and the element`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: mixed, phase: work, transitions: [start, bogus, complete, Hold] }
                """.trimIndent()
            )

        val mixed = seatsOf(parsed).single()
        assertEquals(listOf("complete"), mixed.transitions, "start, bogus and the wrongly cased Hold are dropped")
        for (dropped in listOf("start", "bogus", "Hold")) {
            assertTrue(
                parsed.warnings.any { it.contains("mixed") && it.contains(dropped) },
                "a warning names the seat and the dropped element $dropped: ${parsed.warnings}"
            )
        }
        assertEquals(3, parsed.warnings.count { it.contains("mixed") }, "one warning per dropped element: ${parsed.warnings}")
    }

    // Arbitration (test-plan section 8): task-scope says the warning is "at `<entry>/transitions/<i>`". Read literally the warning
    // carries the element's index path; the other seat warnings in SeatConfigParseTest name the seat and the offending value only,
    // so the literal reading is kept here as its own scenario and reported, not folded into the test above.
    @Test
    fun `S1 leniently, the warning of a dropped element is located at the path entry slash transitions slash index`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: mixed, phase: work, transitions: [start, bogus, complete, Hold] }
                """.trimIndent()
            )

        for (index in listOf(0, 1, 3)) {
            assertTrue(parsed.warnings.any { it.contains("transitions/$index") }, "a warning at transitions/$index: ${parsed.warnings}")
        }
        assertFalse(parsed.warnings.any { it.contains("transitions/2") }, "the valid element at index 2 warns nothing: ${parsed.warnings}")
    }

    @Test
    fun `S1 leniently, a transitions value that is not a list is a warning and is treated as absent`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: scalar, phase: work, transitions: complete }
                """.trimIndent()
            )

        val seat = seatsOf(parsed).single()
        assertNull(seat.transitions, "treated as absent")
        assertEquals(listOf("block", "hold"), seat.effectiveTransitions)
        assertTrue(parsed.warnings.any { it.contains("transitions") }, "a warning names the key: ${parsed.warnings}")
    }

    @Test
    fun `S1 transitions is a documented seat key and adds no unknown-field warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: builder, phase: work, enters: true, transitions: [block] }
                """.trimIndent()
            )

        assertTrue(parsed.warnings.none { it.contains("transitions") }, "transitions is part of the seat entry: ${parsed.warnings}")
        assertEquals(listOf("block"), seatsOf(parsed).single().transitions)
    }

    // ---------------------------------------------------------------------------------------------
    // S11 -- F5
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 F5 an entering seat that names complete or cancel is a fatal structural error naming the seat and the trigger`() {
        for (trigger in listOf("complete", "cancel")) {
            val error =
                assertFailsWith<ConfigStructureException>("entering seat with $trigger") {
                    parse(
                        """
                        work_item_schemas:
                          bug-fix:
                            seats:
                              - { name: entrant, phase: work, enters: true, transitions: [block, $trigger] }
                        """.trimIndent()
                    )
                }
            val message = error.message.orEmpty()
            assertTrue(message.contains("entrant"), "names the seat: $message")
            assertTrue(message.contains(trigger), "names the trigger: $message")
        }
    }

    @Test
    fun `S11 F5 applies to a trait seat too`() {
        val error =
            assertFailsWith<ConfigStructureException> {
                parse(
                    """
                    work_item_schemas:
                      bug-fix:
                        default_traits: [t1]
                        seats:
                          - { name: builder, phase: work }
                    traits:
                      t1:
                        seats:
                          - { name: trait-entrant, phase: review, enters: true, transitions: [complete] }
                    """.trimIndent()
                )
            }
        assertTrue(error.message.orEmpty().contains("trait-entrant"), "names the seat: ${error.message}")
    }

    @Test
    fun `S11 F5 does not fire for a non-entering seat, an entering seat with other wires, or an entering seat with an empty list`() {
        val seats =
            seatsOf(
                parse(
                    """
                    work_item_schemas:
                      bug-fix:
                        seats:
                          - { name: completer, phase: work, transitions: [complete, cancel] }
                          - { name: entrant, phase: review, enters: true, transitions: [block, hold, resume, reopen] }
                          - { name: quiet, phase: queue, enters: true, transitions: [] }
                    """.trimIndent()
                )
            )

        assertEquals(listOf("complete", "cancel"), seats.single { it.name == "completer" }.transitions)
        assertEquals(listOf("block", "hold", "resume", "reopen"), seats.single { it.name == "entrant" }.transitions)
        assertEquals(emptyList(), seats.single { it.name == "quiet" }.transitions)
    }

    @Test
    fun `S11 F5 a start element on an entering seat is dropped with a warning and is not the fatal error`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: entrant, phase: work, enters: true, transitions: [start, block] }
                """.trimIndent()
            )

        assertEquals(listOf("block"), seatsOf(parsed).single().transitions)
        assertTrue(
            parsed.warnings.any { it.contains("entrant") && it.contains("start") },
            "start is dropped with a warning naming the seat and the element: ${parsed.warnings}"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S1 -- the strict-mode JSON Schema entry and the served seat JSON
    // ---------------------------------------------------------------------------------------------

    /** Every `properties` object anywhere in the schema document. */
    private fun propertiesObjects(node: JsonElement): List<JsonObject> =
        when (node) {
            is JsonObject -> listOfNotNull(node["properties"] as? JsonObject) + node.values.flatMap { propertiesObjects(it) }
            is JsonArray -> node.flatMap { propertiesObjects(it) }
            else -> emptyList()
        }

    @Test
    fun `S1 the JSON Schema declares transitions as an array of exactly the six non-start wires`() {
        val schema = Json.parseToJsonElement(ConfigJsonSchema.text)
        // every `properties` object that declares a `transitions` property: the seat definition (and nothing else may use the name)
        val declarations = propertiesObjects(schema).mapNotNull { it["transitions"] as? JsonObject }
        assertTrue(declarations.isNotEmpty(), "the schema declares a transitions property")
        for (declaration in declarations) {
            assertEquals("array", declaration["type"]?.jsonPrimitive?.content)
            val enum =
                assertNotNull(
                    (declaration["items"] as? JsonObject)?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content },
                    "items carry an enum: $declaration"
                )
            assertEquals(setOf("complete", "block", "hold", "resume", "cancel", "reopen"), enum.toSet())
            assertEquals(6, enum.size)
            assertFalse("start" in enum, "start is governed by enters and is not a valid element")
        }
    }

    @Test
    fun `S1 the served seat JSON carries transitions only when the seat declared them`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig =
                EventLogRig.build(
                    db.db,
                    dir,
                    """
                    work_item_schemas:
                      served-task:
                        seats:
                          - { name: builder, phase: work, enters: true }
                          - { name: checker, phase: work, transitions: [complete, resume] }
                          - { name: silent, phase: work, transitions: [] }
                        notes:
                          - { key: build-notes, role: work, required: false, seat: builder }
                      plain-served:
                        seats:
                          - { name: only, phase: work, enters: true }
                        notes:
                          - { key: only-notes, role: work, required: false, seat: only }
                    """.trimIndent() + "\n"
                )

            val served = assertNotNull(ItemSchemaView.buildTypeSchemaJson("served-task", null, rig.ctx.configResolver))
            val seats = served["seats"]!!.jsonArray.map { it.jsonObject }

            fun seat(name: String) = seats.single { it["name"]?.jsonPrimitive?.content == name }

            assertFalse("transitions" in seat("builder"), "an undeclared list is not served: ${seat("builder")}")
            assertEquals(
                listOf("complete", "resume"),
                seat("checker")["transitions"]?.jsonArray?.map { it.jsonPrimitive.content }
            )
            assertEquals(emptyList(), seat("silent")["transitions"]?.jsonArray?.map { it.jsonPrimitive.content }, "empty is served as []")

            // a schema that declares no transitions anywhere serves none (the response shape of an existing config is unchanged)
            val plain = assertNotNull(ItemSchemaView.buildTypeSchemaJson("plain-served", null, rig.ctx.configResolver))
            assertTrue(plain["seats"]!!.jsonArray.none { "transitions" in it.jsonObject }, "no transitions key: ${plain["seats"]}")
        }
}
