package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.StatusGraphBuilder
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Independent P11 test (item 919d379e) for S13: the status graph derived from the transition table must be
 * byte-identical to the graph the pre-P11 code produced.
 *
 * Oracle: the five JSON goldens under `golden/p11/`, captured by the orchestrator at the pre-P11 base commit fef751ec
 * from `StatusGraphBuilder(NoOpNoteSchemaService).buildStatusGraph(schemas)` encoded with
 * `McpJson.encodeToString(StatusGraphDto.serializer(), dto)` for the schema inputs below. The goldens were not
 * produced from the post-change code. Line endings are normalised to LF on read so a CRLF checkout cannot fake a
 * difference (the encoded JSON itself contains no line break).
 */
class StatusGraphGoldenTest {
    private val plain =
        WorkItemSchema(
            type = "golden-plain",
            notes = listOf(NoteSchemaEntry("spec", Role.QUEUE, required = true), NoteSchemaEntry("impl", Role.WORK, required = true)),
        )
    private val review =
        WorkItemSchema(
            type = "golden-review",
            notes =
                listOf(
                    NoteSchemaEntry("spec", Role.QUEUE, required = true),
                    NoteSchemaEntry("impl", Role.WORK, required = true),
                    NoteSchemaEntry("verdict", Role.REVIEW, required = true),
                ),
        )
    private val manual =
        WorkItemSchema(
            type = "golden-manual",
            lifecycleMode = LifecycleMode.MANUAL,
            notes = listOf(NoteSchemaEntry("spec", Role.QUEUE, required = true), NoteSchemaEntry("impl", Role.WORK, required = true)),
        )

    private fun golden(name: String): String {
        val stream = assertNotNull(javaClass.getResourceAsStream("/golden/p11/$name"), "golden resource $name must exist")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }.replace("\r\n", "\n")
    }

    private fun encoded(schemas: Map<String, WorkItemSchema>): String =
        McpJson.encodeToString(StatusGraphDto.serializer(), StatusGraphBuilder(NoOpNoteSchemaService).buildStatusGraph(schemas))

    @Test
    fun `S13 the graph for no schemas equals the pre-change golden`() {
        assertEquals(golden("status-graph-empty.json"), encoded(emptyMap()))
    }

    @Test
    fun `S13 the graph for a schema without a review phase equals the pre-change golden`() {
        assertEquals(golden("status-graph-plain.json"), encoded(mapOf(plain.type to plain)))
    }

    @Test
    fun `S13 the graph for a schema with a review phase equals the pre-change golden`() {
        assertEquals(golden("status-graph-review.json"), encoded(mapOf(review.type to review)))
    }

    @Test
    fun `S13 the graph for a manual lifecycle schema equals the pre-change golden`() {
        assertEquals(golden("status-graph-manual.json"), encoded(mapOf(manual.type to manual)))
    }

    @Test
    fun `S13 the graph for all three schemas in insertion order equals the pre-change golden`() {
        val all = linkedMapOf(plain.type to plain, review.type to review, manual.type to manual)
        assertEquals(golden("status-graph-all.json"), encoded(all))
    }
}
