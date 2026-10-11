package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.workTreeService
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Service-level tests of [WorkTreeService] (item c820b3d0, implementer seat, task-scope B7): the behaviours the retired
 * `create_work_tree` tests pinned that now live in the service, over a real SQLite database and the production
 * composition. Op-level wire behaviour is the test author's (test-plan S1-S19); these pin the algorithm: parent-before-child
 * ordering through `TopoOrder`, the edge policy, note precedence and policy, type rules, closed parents, attach mode and
 * the all-or-nothing unit.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WorkTreeServiceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val config =
        "work_item_schemas:\n" +
            "  test-item:\n" +
            "    notes: []\n" +
            "  gated:\n" +
            "    notes:\n" +
            "      - { key: spec, role: queue, required: true }\n" +
            "      - { key: impl, role: work, required: true }\n"

    private fun rig(dir: Path) = EventLogRig.build(db.db, dir, config)

    private fun item(
        ref: String,
        parentRef: String? = WorkTreeService.ROOT_REF,
        type: String = "test-item",
        anchors: List<TreeAnchorInput> = emptyList()
    ) = TreeItemInput(ref = ref, parentRef = parentRef, title = "T $ref", type = type, anchors = anchors)

    private fun request(
        children: List<TreeItemInput> = emptyList(),
        deps: List<TreeDepInput> = emptyList(),
        notes: List<TreeNoteInput> = emptyList(),
        rootType: String = "test-item",
        parentId: UUID? = null,
        createNotes: Boolean = false,
        docSlug: String? = null,
        rootAnchors: List<TreeAnchorInput> = emptyList()
    ) = WorkTreeRequest(
        root = TreeRootInput.Create(item(WorkTreeService.ROOT_REF, parentRef = null, type = rootType, anchors = rootAnchors), parentId),
        children = children,
        deps = deps,
        notes = notes,
        createNotes = createNotes,
        docSlug = docSlug
    )

    private fun count(table: String): Int = rawCount(db.jdbcUrl, "SELECT count(*) FROM $table")

    private fun <T> Outcome<T>.ok(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> error("expected Ok, got ${error.code.wire}: ${error.message}")
        }

    private fun <T> Outcome<T>.err(): DomainError =
        when (this) {
            is Outcome.Ok -> error("expected Err, got $value")
            is Outcome.Err -> error
        }

    @Test
    fun `children listed child first are written parent before child with stored depths`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val tree =
                workTreeService(rig.ctx)
                    .create(request(children = listOf(item("gc", parentRef = "c1"), item("c1"))))
                    .ok()

            assertEquals(listOf("root", "c1", "gc"), tree.items.map { it.ref }, "root first, then insertion (parent-before-child) order")
            assertEquals(listOf(0, 1, 2), tree.items.map { it.depth })
            val gc = rig.raw.workItemRepository().getById(tree.items[2].id)!!
            assertEquals(tree.items[1].id, gc.parentId)
            assertEquals(tree.rootId, gc.rootId)
        }

    @Test
    fun `a parentRef cycle is invalid_request on children and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val before = count("work_items")
            val error = workTreeService(rig.ctx).create(request(children = listOf(item("a", "b"), item("b", "a")))).err()

            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
            assertEquals("/children", (error.detail as ErrorDetail.InvalidRequest).fields.single().field)
            assertEquals(before, count("work_items"))
        }

    @Test
    fun `orderChildRefs keeps request order among siblings and reports a self parent as cyclic`() {
        val (ordered, cyclic) = WorkTreeService.orderChildRefs(listOf("b" to "root", "c" to "a", "a" to "root"))
        assertEquals(listOf("b", "a", "c"), ordered)
        assertEquals(emptyList(), cyclic)
        val (_, selfCycle) = WorkTreeService.orderChildRefs(listOf("x" to "x", "y" to "root"))
        assertEquals(listOf("x"), selfCycle)
    }

    @Test
    fun `a dependency cycle is cycle_detected with item ids in the path and the ref in the message`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val before = count("work_items")
            val error =
                workTreeService(rig.ctx)
                    .create(
                        request(
                            children = listOf(item("a"), item("b")),
                            deps = listOf(TreeDepInput("a", "b"), TreeDepInput("b", "a"))
                        )
                    ).err()

            assertEquals(ErrorCode.CYCLE_DETECTED, error.code)
            val path = (error.detail as ErrorDetail.CycleDetected).path
            assertTrue(path.isNotEmpty())
            assertTrue(error.message.contains("'a'") || error.message.contains("'b'"), error.message)
            assertTrue(path.none { error.message.contains(it.toString()) }, "ids stay in the path, refs in the message")
            assertEquals(before, count("work_items"))
            assertEquals(0, count("dependencies"))
        }

    @Test
    fun `a restated dependency is duplicate and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val error =
                workTreeService(rig.ctx)
                    .create(
                        request(
                            children = listOf(item("a"), item("b")),
                            deps = listOf(TreeDepInput("a", "b"), TreeDepInput("a", "b", DependencyType.BLOCKS))
                        )
                    ).err()

            assertEquals(ErrorCode.DUPLICATE, error.code)
            assertEquals(0, count("work_items"))
        }

    @Test
    fun `explicit notes win over anchors, anchors over createNotes blanks, and the last explicit note wins`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val project = rig.seed("Project")
            rig.raw.planDocumentRepository().stash(project.id, "plan", "# Overview\nOverview text.\n# Impl\nImpl text.")
            val tree =
                workTreeService(rig.ctx)
                    .create(
                        request(
                            rootType = "gated",
                            parentId = project.id,
                            createNotes = true,
                            docSlug = "plan",
                            rootAnchors = listOf(TreeAnchorInput("spec", "queue", "overview"), TreeAnchorInput("impl", "work", "impl")),
                            notes = listOf(TreeNoteInput("root", "impl", "work", "first"), TreeNoteInput("root", "impl", "work", "second"))
                        )
                    ).ok()

            val notes =
                rig.raw
                    .noteRepository()
                    .findByItemId(tree.rootId)
                    .associateBy { it.key }
            assertEquals("second", notes.getValue("impl").body, "the last explicit note wins over the anchor")
            assertTrue(notes.getValue("spec").body.contains("Overview text."), "the anchor wins over the createNotes blank")
            assertEquals(setOf("spec", "impl"), notes.keys)
            assertEquals(
                PlanDocumentStatus.ADOPTED,
                rig.raw
                    .planDocumentRepository()
                    .get(project.id, "plan")!!
                    .status
            )
        }

    @Test
    fun `a schema role mismatch is schema_violation and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val error =
                workTreeService(rig.ctx)
                    .create(request(rootType = "gated", notes = listOf(TreeNoteInput("root", "spec", "work", "x"))))
                    .err()

            assertEquals(ErrorCode.SCHEMA_VIOLATION, error.code)
            assertEquals(0, count("work_items"))
            assertEquals(0, count("notes"))
        }

    @Test
    fun `an undeclared child type rolls the whole tree back`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val error = workTreeService(rig.ctx).create(request(children = listOf(item("a"), item("b", type = "nope")))).err()

            assertEquals(ErrorCode.SCHEMA_VIOLATION, error.code)
            assertEquals(0, count("work_items"), "the root and the first child roll back with the failing second child")
            assertEquals(0, rig.rows().count { it.type == "item.created" })
        }

    @Test
    fun `a closed parent is invalid_transition and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val closed = rig.seed("Closed", role = Role.TERMINAL)
            val error = workTreeService(rig.ctx).create(request(parentId = closed.id, children = listOf(item("a")))).err()

            assertEquals(ErrorCode.INVALID_TRANSITION, error.code)
            assertEquals(1, count("work_items"))
        }

    @Test
    fun `attach mode writes no root row and places children under the existing item`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val project = rig.seed("Project")
            val existing = rig.seed("Existing", project)
            val tree =
                workTreeService(rig.ctx)
                    .create(WorkTreeRequest(root = TreeRootInput.Attach(existing.id), children = listOf(item("a"))))
                    .ok()

            assertEquals(existing.id, tree.rootId)
            assertEquals(listOf(existing.id, tree.items[1].id), tree.items.map { it.id })
            assertEquals(listOf(1, 2), tree.items.map { it.depth })
            assertEquals(3, count("work_items"))
            val child = rig.raw.workItemRepository().getById(tree.items[1].id)!!
            assertEquals(project.id, child.rootId)
        }

    @Test
    fun `a dependency names the stored rows in request order with their refs`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val tree =
                workTreeService(rig.ctx)
                    .create(
                        request(
                            children = listOf(item("a"), item("b"), item("c")),
                            deps = listOf(TreeDepInput("b", "c"), TreeDepInput("a", "b", DependencyType.RELATES_TO))
                        )
                    ).ok()

            assertEquals(listOf("b" to "c", "a" to "b"), tree.deps.map { it.fromRef to it.toRef })
            assertEquals(listOf(DependencyType.BLOCKS, DependencyType.RELATES_TO), tree.deps.map { it.dependency.type })
            assertIs<UUID>(
                tree.deps
                    .first()
                    .dependency.id
            )
        }
}
