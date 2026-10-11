package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.application.port.DocumentStore
import io.github.jpicklyk.mcptask.current.application.port.LinkStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.TreeAnchorInput
import io.github.jpicklyk.mcptask.current.application.service.TreeItemInput
import io.github.jpicklyk.mcptask.current.application.service.TreeRootInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeRequest
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D11 rollback inside the tree unit (item 23faed48, fix round r1, review B2): the work tree ([WorkTreeService], behind
 * `items_write` `create_tree`) records the
 * `derived_from` links of its anchored notes and then a LATER step of the same unit (the plan adoption, the last step)
 * fails. The links must roll back with the items and notes; nothing of the tree may remain.
 *
 * The later failure is injected through a decorating [RepositoryProvider] over the real SQLite stores: in one test the
 * plan document "vanishes" once both `derived_from` links are written (a domain error from the adoption), in the other
 * the adoption insert always throws (a store fault). Each test asserts both provenance links were written BEFORE the
 * fault fired, so the rollback claim is never vacuous. Red-checked in r1 by moving the provenance write out of the
 * tree unit: the store-fault test then fails on the two `derived_from` links the failed tree left behind, and the
 * vanishing-document test fails because its fault never arms and the tree commits.
 */
class LinkTreeRollbackTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var real: RepositoryProvider
    private lateinit var project: UUID

    private val planBody = "# Overview\nFeature overview text.\n# Task 1\nTask 1 detail text."

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            real = db.repositoryProvider()
            val id = UUID.randomUUID()
            project = checkNotNull(real.workItemRepository().create(WorkItem(id = id, rootId = id, title = "Project", depth = 0))).id
            real.planDocumentRepository().stash(project, "my-plan", planBody)
            checkNotNull(real.documentStore().get(project, DocumentKind.PLAN, "my-plan")) { "fixture: the stashed plan is a document" }
        }

    /** Fails the adoption after the provenance links are written: [mode] picks how. */
    private class FaultingProvider(
        private val delegate: RepositoryProvider,
        private val mode: Mode,
    ) : RepositoryProvider by delegate {
        enum class Mode { DOCUMENT_VANISHES, ADOPTION_THROWS }

        var derivedInserts = 0

        /** [derivedInserts] at the moment the fault fired; null while it has not fired. */
        var derivedAtFault: Int? = null

        companion object {
            /** The tree below anchors two notes, so the vanishing-document fault arms once both provenance links are written. */
            const val ANCHORED_NOTES = 2
        }

        override fun linkStore(): LinkStore {
            val links = delegate.linkStore()
            return object : LinkStore by links {
                override suspend fun insert(link: Link): Link {
                    // Unconditional: the adoption insert always fails, whatever the provenance step did before it.
                    if (link.relation == LinkRelation.ADOPTED_BY && mode == Mode.ADOPTION_THROWS) {
                        derivedAtFault = derivedInserts
                        throw IllegalStateException("injected failure in the adoption step")
                    }
                    val stored = links.insert(link)
                    if (link.relation == LinkRelation.DERIVED_FROM) derivedInserts++
                    return stored
                }
            }
        }

        override fun documentStore(): DocumentStore {
            val documents = delegate.documentStore()
            return object : DocumentStore by documents {
                override suspend fun get(
                    rootItemId: UUID,
                    kind: DocumentKind,
                    slug: String
                ): Document? {
                    // The adoption reads the document AFTER the provenance step; from then on it has "vanished".
                    if (mode == Mode.DOCUMENT_VANISHES && derivedInserts >= ANCHORED_NOTES) {
                        if (derivedAtFault == null) derivedAtFault = derivedInserts
                        return null
                    }
                    return documents.get(rootItemId, kind, slug)
                }
            }
        }
    }

    private fun treeRequest(): WorkTreeRequest =
        WorkTreeRequest(
            root =
                TreeRootInput.Create(
                    TreeItemInput(
                        ref = WorkTreeService.ROOT_REF,
                        parentRef = null,
                        title = "Feature X",
                        type = "test-item",
                        anchors = listOf(TreeAnchorInput("requirements", "queue", "overview"))
                    ),
                    parentId = project
                ),
            children =
                listOf(
                    TreeItemInput(
                        ref = "t1",
                        parentRef = WorkTreeService.ROOT_REF,
                        title = "Task 1",
                        type = "test-item",
                        anchors = listOf(TreeAnchorInput("task-scope", "queue", "task-1"))
                    )
                ),
            docSlug = "my-plan"
        )

    /** The tree service over [provider], as the `create_tree` op composes it. */
    private fun serviceOver(provider: RepositoryProvider): WorkTreeService {
        val ctx = ToolExecutionContext(provider, unitOfWork = db.unitOfWork())
        return WorkTreeService(
            ctx.repositoryProvider,
            ctx.unitOfWork,
            ctx.configResolver,
            ctx.itemCommandService,
            ctx.dependencyCommandService,
            ctx.noteCommandService,
            ctx.planDocumentService
        )
    }

    private fun count(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun runFailingTree(mode: FaultingProvider.Mode): FaultingProvider =
        runBlocking {
            val itemsBefore = count("SELECT count(*) FROM work_items")
            val notesBefore = count("SELECT count(*) FROM notes")
            val linksBefore = count("SELECT count(*) FROM links")
            val provider = FaultingProvider(real, mode)

            // A domain error from the adoption is an Err; an injected throw escapes the unit after rolling it back.
            val result = runCatching { serviceOver(provider).create(treeRequest()) }

            assertFalse(result.getOrNull() is Outcome.Ok, "the injected adoption failure must fail the tree; got: $result")
            assertEquals(linksBefore, count("SELECT count(*) FROM links"), "no link of the rolled-back tree remains")
            assertEquals(0, count("SELECT count(*) FROM links WHERE relation = 'derived_from'"))
            assertEquals(itemsBefore, count("SELECT count(*) FROM work_items"), "no item of the rolled-back tree remains")
            assertEquals(notesBefore, count("SELECT count(*) FROM notes"), "no note of the rolled-back tree remains")
            assertEquals(
                FaultingProvider.ANCHORED_NOTES,
                provider.derivedAtFault,
                "control: both anchored notes wrote their derived_from link inside the unit BEFORE the later step failed"
            )
            provider
        }

    @Test
    fun `a tree whose plan document vanishes after the provenance step rolls its derived_from links back`() {
        runFailingTree(FaultingProvider.Mode.DOCUMENT_VANISHES)
    }

    @Test
    fun `a tree whose adoption write throws after the provenance step rolls its derived_from links back`() {
        runFailingTree(FaultingProvider.Mode.ADOPTION_THROWS)
    }

    @Test
    fun `control the same tree without an injected fault commits its derived_from links`(): Unit =
        runBlocking {
            val result = serviceOver(real).create(treeRequest())

            assertTrue(result is Outcome.Ok, "expected success; got: $result")
            assertEquals(2, count("SELECT count(*) FROM links WHERE relation = 'derived_from'"))
        }
}
