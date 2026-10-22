package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lifecycle-free items (role absent, type `lifecycle: none`) belong to the `affectedInFlight` population of `config validate`
 * (Q31b B2 ruling): they are non-terminal, and a candidate can change their schema or flip their lifecycle. One whose
 * candidate schema changes is counted; one whose schema is unchanged is not.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigValidateLifecycleFreeInFlightTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit() = CfgKit(EventLogRig.build(db.db, dir, "work_item_schemas: {}\n"))

    private val spec = "      - key: spec\n        role: queue\n        required: true\n"

    private fun doc(
        pageLifecycle: String,
        pageNotes: String = "    notes: []\n",
        featureNotes: String = spec
    ): String =
        "work_item_schemas:\n" +
            "  feature-task:\n    notes:\n" + featureNotes +
            "  page:\n    lifecycle: $pageLifecycle\n" + pageNotes

    private val base = doc("none")

    private suspend fun CfgKit.newRoot(): WorkItem =
        rig.ctx.itemCommandService
            .create(ItemCreateCommand(parentId = null, title = "R", type = "project"))
            .orFail()

    private suspend fun CfgKit.push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(rig.ctx.configService).push(root.id, yaml))
    }

    private suspend fun CfgKit.page(root: WorkItem): WorkItem {
        val item =
            rig.ctx.itemCommandService
                .create(ItemCreateCommand(parentId = root.id, title = "P", type = "page"))
                .orFail()
        assertNull(item.role, "fixture: a `page` item is lifecycle-free")
        return item
    }

    private suspend fun CfgKit.impact(
        root: WorkItem,
        yaml: String
    ): JsonObject {
        val checked = call(validateOp(root.id, yaml)).okResult()
        assertTrue(checked.bool("valid"), "fixture: the candidate is valid: ${checked["errors"]}")
        return checked.obj("affectedInFlight")
    }

    @Test
    fun `a lifecycle-free item whose candidate schema turns role-bearing is counted with its lifecycle change`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, base)
            val p = k.page(r)

            val impact = k.impact(r, doc("auto"))

            assertEquals(1, impact.int("count"))
            val entry = impact.list("sample").single()
            assertEquals(p.id.toString(), entry.str("itemId"))
            assertTrue("lifecycle" in entry.keys, "the lifecycle flip is reported: $entry")
        }

    @Test
    fun `a lifecycle-free item whose candidate schema gains a note is counted`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, base)
            val p = k.page(r)

            val withNote = doc("none", pageNotes = "    notes:\n      - key: summary\n        role: queue\n        required: false\n")
            val impact = k.impact(r, withNote)

            assertEquals(1, impact.int("count"))
            val entry = impact.list("sample").single()
            assertEquals(p.id.toString(), entry.str("itemId"))
            assertEquals(listOf("summary"), entry.names("changedNotes"))
        }

    @Test
    fun `a lifecycle-free item whose schema is unchanged is not counted`(): Unit =
        runBlocking {
            val k = kit()
            val r = k.newRoot()
            k.push(r, base)
            k.page(r)

            assertEquals(0, k.impact(r, base).int("count"), "the head document itself")
            val design = "      - key: design\n        role: queue\n        required: true\n"
            val impact = k.impact(r, doc("none", featureNotes = spec + design))
            assertEquals(0, impact.int("count"), "only another type changed")
            assertEquals(emptyList(), impact.list("sample"))
        }
}
