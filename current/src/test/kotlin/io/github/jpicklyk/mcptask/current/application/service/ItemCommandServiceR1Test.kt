package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * r1 follow-up tests for item 947f0230.
 *
 * F1: the `note.upserted` payload key `bodyFromFile` carries the caller-supplied path string for a manage_notes
 * bodyFromFile upsert (never the file's text) and is present-as-null for an inline body. Oracle: the r1 declaration
 * (key always present; value is the supplied path; null for inline; file contents never recorded).
 *
 * F6a: create_work_tree in attach mode (`root: {id}`) adopts an existing root and records NO `item.created` for it,
 * only for each new child. Oracle: attach mode creates nothing for the root, so there is no creation to record.
 *
 * F6b (fan-out guard, throwing live subscriber): NOT COVERED. No public declaration or existing test seam lets a
 * subscriber handler throw during fan-out; see the test-manifest.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ItemCommandServiceR1Test {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun upsertParams(
        itemId: String,
        key: String,
        field: String,
        value: String,
    ) = arrayOf(
        "operation" to JsonPrimitive("upsert"),
        "notes" to
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("itemId", itemId)
                        put("key", key)
                        put("role", "work")
                        put(field, value)
                    },
                )
            },
    )

    @Test
    fun `F1 a bodyFromFile upsert records the supplied path and never the file text`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val item = rig.seed("note owner")
            val secret = "FILE-TEXT-MUST-NOT-APPEAR-7f3a"
            Files.write(dir.resolve("note.txt"), secret.toByteArray(Charsets.UTF_8))

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        ManageNotesTool(agentConfigBaseDir = dir),
                        *upsertParams(item.id.toString(), "from-file", "bodyFromFile", "note.txt"),
                    )
                }

            val row = rows.single { it.type == "note.upserted" }
            assertEquals("note.txt", row.payload()["bodyFromFile"]!!.jsonPrimitive.content)
            assertFalse(row.data.contains(secret), "the file's text must not be recorded in the event: ${row.data}")
            assertEquals(
                secret.length,
                row
                    .payload()["bodyLength"]!!
                    .jsonPrimitive.content
                    .toInt(),
                "control: the body was read"
            )
        }

    @Test
    fun `F1 an inline body upsert records bodyFromFile as null`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val item = rig.seed("note owner")

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        ManageNotesTool(agentConfigBaseDir = dir),
                        *upsertParams(item.id.toString(), "inline", "body", "inline text"),
                    )
                }

            val row = rows.single { it.type == "note.upserted" }
            assertTrue(row.payload().containsKey("bodyFromFile"), "the key is always present: ${row.data}")
            assertEquals(JsonNull, row.payload()["bodyFromFile"])
        }

    @Test
    fun `F6a attach mode records item created for each new child and none for the adopted root`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val existing = rig.seed("pre-existing root")

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        CreateWorkTreeTool(),
                        "root" to buildJsonObject { put("id", existing.id.toString()) },
                        "children" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("ref", "a")
                                        put("title", "Child A")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("ref", "b")
                                        put("title", "Child B")
                                    },
                                )
                            },
                    )
                }

            val created = rows.filter { it.type == "item.created" }
            assertEquals(2, created.size, "one item.created per new child: ${rows.map { it.type }}")
            assertFalse(created.any { it.entityId == existing.id }, "no item.created for the adopted root")
        }
}
