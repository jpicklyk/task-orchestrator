package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Store-write confinement guard (item 947f0230, task-scope AC6, test-plan S15; the P14 ClaimWriteConfinementTest shape):
 * every write to the item, note, dependency, project-config and plan-document stores belongs to the application services
 * that record the matching event rows in the same unit, so a new store method cannot opt out of events silently (AR-02).
 *
 * In production code OUTSIDE `application/service/`, the port declarations (`application/port/`) and the SQLite store
 * implementations (`infrastructure/sqlite/repository/`) there must be no call to
 *  - ItemStore / WorkItemRepository: create, update, delete, deleteAll
 *  - NoteStore: upsert, delete, deleteByItemId
 *  - DependencyStore: create, createBatch, delete, deleteByItemId
 *  - ProjectConfigStore: upsert, upsertGuarded, delete
 *  - PlanDocumentStore: stash, markAdopted
 * on a receiver that is one of those stores. A receiver is recognised two ways: an accessor chain
 * (`provider.workItemRepository().create(...)`, however the chain is wrapped across lines) or a name declared with a store
 * type (`repo: WorkItemRepository`, `val notes = provider.noteRepository()`). The scan is zero-tolerance (no baseline).
 *
 * The second half pins the deletions the same item makes: no production source mentions the seven removed classes, none of
 * their files exists, and no two-way ratchet baseline still names one.
 *
 * Vacuity: [violations] is proven on the shapes it must flag and the shapes it must ignore (positive and negative strings
 * per family), the scope function is proven on included and excluded paths, and a control proves the matcher recognises
 * real store writes inside the excluded owner directory (so a matcher that went blind would fail loudly).
 *
 * Oracle: task-scope section 4 "Guard" and AC6; plan 3.7 (each service records its own rows).
 */
class StoreWriteConfinementTest {
    private enum class Family(
        val methods: Set<String>
    ) {
        ITEM(setOf("create", "update", "delete", "deleteAll")),
        NOTE(setOf("upsert", "delete", "deleteByItemId")),
        DEPENDENCY(setOf("create", "createBatch", "delete", "deleteByItemId")),
        CONFIG(setOf("upsert", "upsertGuarded", "delete")),
        PLAN(setOf("stash", "markAdopted"))
    }

    companion object {
        const val OWNER_DIR = "application/service/"
        const val PORT_DIR = "application/port/"
        const val SQLITE_STORES_DIR = "infrastructure/sqlite/repository/"

        private val ACCESSORS =
            mapOf(
                "workItemRepository" to Family.ITEM,
                "itemStore" to Family.ITEM,
                "noteRepository" to Family.NOTE,
                "noteStore" to Family.NOTE,
                "dependencyRepository" to Family.DEPENDENCY,
                "dependencyStore" to Family.DEPENDENCY,
                "projectConfigRepository" to Family.CONFIG,
                "projectConfigStore" to Family.CONFIG,
                "planDocumentRepository" to Family.PLAN,
                "planDocumentStore" to Family.PLAN
            )

        private val TYPES =
            mapOf(
                "ItemStore" to Family.ITEM,
                "WorkItemRepository" to Family.ITEM,
                "NoteStore" to Family.NOTE,
                "DependencyStore" to Family.DEPENDENCY,
                "ProjectConfigStore" to Family.CONFIG,
                "PlanDocumentStore" to Family.PLAN
            )

        private val TYPE_ALTERNATIVES: String = TYPES.keys.joinToString(separator = "|")
        private val ACCESSOR_ALTERNATIVES: String = ACCESSORS.keys.joinToString(separator = "|")

        /** A property, parameter or local declared with a store type, optionally package qualified. */
        private val TYPE_DECL = Regex("""\b(\w+)\s*:\s*(?:[\w.]+\.)?($TYPE_ALTERNATIVES)\b""")

        /** `val x = ...provider.accessor()` where the accessor call ends the initializer (x IS the store). */
        private val LOCAL_ACCESSOR =
            Regex("""\b(?:val|var)\s+(\w+)\s*=\s*[^\n=]*?\b($ACCESSOR_ALTERNATIVES)\s*\(\s*\)(?!\s*\??\s*\.)""")

        fun inScope(path: String): Boolean =
            !path.startsWith(OWNER_DIR) && !path.startsWith(PORT_DIR) && !path.startsWith(SQLITE_STORES_DIR)

        private fun codeOf(text: String): String =
            text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .joinToString("\n") { GuardSupport.stripStrings(it).substringBefore("//") }

        /** Violating store write calls in [text]: comment lines are dropped and string contents blanked before matching. */
        fun violations(text: String): Int {
            val code = codeOf(text)
            var count = 0
            // 1. accessor chains: provider.workItemRepository().create(...)
            for ((accessor, family) in ACCESSORS) {
                val chain = Regex("""\b$accessor\s*\(\s*\)\s*\??\s*\.\s*(\w+)\s*\(""")
                count += chain.findAll(code).count { it.groupValues[1] in family.methods }
            }
            // 2. names declared with a store type, or bound to an accessor call
            val families = mutableMapOf<String, MutableSet<Family>>()
            TYPE_DECL.findAll(code).forEach { families.getOrPut(it.groupValues[1]) { mutableSetOf() } += TYPES.getValue(it.groupValues[2]) }
            LOCAL_ACCESSOR.findAll(code).forEach {
                families.getOrPut(it.groupValues[1]) { mutableSetOf() } += ACCESSORS.getValue(it.groupValues[2])
            }
            for ((name, fams) in families) {
                val methods = fams.flatMap { it.methods }.toSet()
                val call = Regex("""(?<!\w)$name\s*\??\s*\.\s*(\w+)\s*\(""")
                count += call.findAll(code).count { it.groupValues[1] in methods }
            }
            return count
        }

        val DELETED_CLASSES =
            listOf(
                "EventPublishingRepositoryProvider",
                "DeferredEventPublisher",
                "WorkTreeExecutor",
                "SQLiteWorkTreeService",
                "WorkItemPlacementService",
                "ItemHierarchyValidator",
                "WorkItemDeletion"
            )

        /** Code references to a deleted class name in [text] (comments and string contents are not references). */
        fun deletedReferences(text: String): Int {
            val code = codeOf(text)
            return DELETED_CLASSES.sumOf { name -> Regex("""\b$name\b""").findAll(code).count() }
        }
    }

    @Test
    fun `no production code outside the owning services writes to the item note dependency config or plan document stores`() {
        val inScope = GuardSupport.productionSources().filter { inScope(it.path) }
        assertTrue(inScope.size >= 100, "fixture: the scan covers tools, routes and infrastructure, found ${inScope.size} files")
        assertTrue(inScope.any { it.path.startsWith("application/tools/") }, "fixture: tool files are scanned")
        assertTrue(inScope.any { it.path.startsWith("interfaces/api/v1/routes/") }, "fixture: REST route files are scanned")
        val actual = GuardSupport.countByFile(inScope) { violations(it.text) }
        assertEquals(emptyMap(), actual, "store writes outside application/service (file to call count): $actual")
    }

    @Test
    fun `the matcher recognises real store writes inside the excluded owner directory`() {
        val owners = GuardSupport.productionSources().filter { it.path.startsWith(OWNER_DIR) }
        assertTrue(owners.size >= 10, "fixture: application/service holds the write services, found ${owners.size} files")
        val perFile = GuardSupport.countByFile(owners) { violations(it.text) }
        assertTrue(
            perFile.size >= 2,
            "control: the services own the store writes, so the same matcher must see them (a blind matcher would pass the " +
                "scan above vacuously); seen in: $perFile"
        )
    }

    @Test
    fun `the guard flags each store write shape`() {
        listOf(
            "repositoryProvider.workItemRepository().create(item)",
            "ctx.repositoryProvider.itemStore().update(item)",
            "provider.workItemRepository()?.deleteAll(ids)",
            "provider.noteRepository().upsert(note)",
            "provider.noteRepository().deleteByItemId(id)",
            "provider.dependencyRepository().createBatch(deps)",
            "provider.dependencyRepository().deleteByItemId(id)",
            "provider.projectConfigRepository().upsertGuarded(root, yaml)",
            "provider.planDocumentRepository().markAdopted(root, slug, by)",
            "provider.planDocumentRepository().stash(root, slug, body)",
            "provider\n    .dependencyRepository()\n    .create(dep)",
            "private val repo: WorkItemRepository\nfun f() { repo.update(x) }",
            "class A(private val store: NoteStore) { fun f() { store.delete(id) } }",
            "class B(val deps: DependencyStore) { fun f() = deps.delete(id) }",
            "fun f(configStore: ProjectConfigStore) { configStore.upsert(root, yaml) }",
            "val notes = provider.noteRepository()\nnotes.upsert(n)",
            "val planRepo = ctx.repositoryProvider.planDocumentRepository()\nplanRepo.stash(a, b, c)",
            "fun f(items: ItemStore) { items?.deleteAll(ids) }",
            "provider.noteRepository().upsert(note) // a trailing comment does not hide the call before it"
        ).forEach { assertEquals(1, violations(it), "not flagged: $it") }
    }

    @Test
    fun `the guard ignores services reads other stores comments and strings`() {
        listOf(
            "itemCommandService.create(cmd)",
            "noteCommandService.upsert(cmd)",
            "dependencyCommandService.deleteById(id)",
            "provider.workItemRepository().getById(id)",
            "provider.workItemRepository().findByFilters(limit = 10)",
            "provider.noteRepository().findByItemId(id)",
            "provider.dependencyRepository().findByItemId(id)",
            "provider.roleTransitionRepository().create(row)",
            "provider.resourceLeaseRepository().acquireAll(id, a, r)",
            "provider.eventStore().append(rows)",
            "provider.planDocumentRepository().get(root, slug)",
            "private val repo: WorkItemRepository\nfun f() { repo.getById(x) }",
            "fun f(store: NoteStore) { store.findByItemId(id) }",
            "fun f(notes: NoteStore) { notes.update(x) }",
            "val list = mutableListOf<Int>()\nlist.delete(1)",
            "// provider.workItemRepository().create(item)",
            "     * provider.noteRepository().upsert(note)",
            "val msg = \"provider.workItemRepository().create(item)\"",
            "interface Foo { suspend fun create(item: WorkItem): WorkItem }"
        ).forEach { assertEquals(0, violations(it), "wrongly flagged: $it") }
    }

    @Test
    fun `the scope covers tools routes and non store infrastructure and excludes only owners ports and the SQLite stores`() {
        listOf(
            "application/tools/items/CreateItemHandler.kt",
            "application/tools/dependency/ManageDependenciesTool.kt",
            "application/tools/ToolExecutionContext.kt",
            "interfaces/api/v1/routes/ItemWriteRoutes.kt",
            "interfaces/api/v1/routes/DependencyWriteRoutes.kt",
            "interfaces/api/v1/routes/ProjectConfigRoutes.kt",
            "interfaces/mcp/ServerComposition.kt",
            "infrastructure/ExpirySweeper.kt",
            "infrastructure/sqlite/StartupIntegrity.kt",
            "infrastructure/config/GlobalConfigFile.kt",
            "CurrentMain.kt"
        ).forEach { assertTrue(inScope(it), "must be scanned: $it") }
        listOf(
            "application/service/ItemCommandService.kt",
            "application/service/NoteCommandService.kt",
            "application/service/DependencyCommandService.kt",
            "application/service/PlanDocumentService.kt",
            "application/service/ProjectConfigPushService.kt",
            "application/port/ItemStore.kt",
            "infrastructure/sqlite/repository/SqliteItemStore.kt",
            "infrastructure/sqlite/repository/SQLiteNoteRepository.kt",
            "infrastructure/sqlite/repository/DefaultRepositoryProvider.kt"
        ).forEach { assertFalse(inScope(it), "must be excluded: $it") }
    }

    @Test
    fun `no production source references a deleted class and none of their files exists`() {
        val sources = GuardSupport.productionSources()
        val actual = GuardSupport.countByFile(sources) { deletedReferences(it.text) }
        assertEquals(emptyMap(), actual, "production references to deleted classes (file to count): $actual")
        val deletedFiles = DELETED_CLASSES.map { "$it.kt" }.toSet()
        val surviving = sources.filter { it.path.substringAfterLast('/') in deletedFiles }.map { it.path }
        assertEquals(emptyList(), surviving, "files of deleted classes still exist")
    }

    @Test
    fun `the deleted class reference matcher flags code and ignores comments and strings`() {
        listOf(
            "import io.x.EventPublishingRepositoryProvider",
            "val p = DeferredEventPublisher(bus)",
            "class Foo(private val executor: WorkTreeExecutor)",
            "x as SQLiteWorkTreeService",
            "WorkItemPlacementService(repo).place(a)",
            "ItemHierarchyValidator.recompute(x)",
            "WorkItemDeletion(ctx).delete(ids)"
        ).forEach { assertEquals(1, deletedReferences(it), "not flagged: $it") }
        listOf(
            "// the old EventPublishingRepositoryProvider decorated every store",
            "     * DeferredEventPublisher is gone",
            "val s = \"WorkTreeExecutor\"",
            "val x = MyWorkTreeExecutorAdapter()",
            "val y = WorkItemDeletionResult()"
        ).forEach { assertEquals(0, deletedReferences(it), "wrongly flagged: $it") }
    }

    @Test
    fun `the placement write baseline is unchanged and no ratchet baseline still names a deleted file`() {
        assertEquals(
            mapOf("infrastructure/sqlite/repository/SqliteItemStore.kt" to 6),
            GuardSupport.readBaseline("placement-write-baseline.txt"),
            "AC6: placement-write-baseline.txt stays exactly the item store's six insert/update writes"
        )
        val deletedFiles = DELETED_CLASSES.map { "$it.kt" }
        val dir = File("src/test/resources/architecture")
        val baselines = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().toList()
        assertTrue(baselines.size >= 5, "fixture: the architecture baselines are found, saw ${baselines.map { it.name }}")
        val stale =
            baselines.flatMap { f ->
                f
                    .readLines()
                    .filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                    .filter { line -> deletedFiles.any { line.contains(it) } }
                    .map { "${f.name}: $it" }
            }
        assertEquals(emptyList(), stale, "baseline lines naming deleted files")
    }
}
