package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import com.lemonappdev.konsist.api.Konsist
import io.github.jpicklyk.mcptask.current.application.port.ClaimStore
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.HierarchyStore
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * S17 — reflection + source guard for [EventPublishingRepositoryProvider]'s decorator surface.
 *
 * ## Why a hand-written `evented` constant can't catch a dropped override (O4)
 *
 * A prior version of this test classified interface method names against HAND-WRITTEN `evented`
 * sets. That is vacuous: if someone deletes, say, `override suspend fun deleteAll` from the
 * decorator, `by inner` forwards it silently, the hand-written constant still lists `deleteAll`,
 * and the test stayed green. Reflecting on the DECORATOR class doesn't help either — `by`
 * delegation emits ordinary non-synthetic forwarding methods, so `Class.declaredMethods` on the
 * decorator lists every interface method whether or not it is overridden. kotlin-reflect's
 * `declaredMemberFunctions` also includes DELEGATION-kind members (it excludes only FAKE_OVERRIDE),
 * and neither kotlin-reflect nor kotlin-metadata-jvm (which exposes `MemberKind.DELEGATION`) is on
 * this module's classpath — adding either just for this test is rejected (residual limit below).
 *
 * ## The fix: derive the evented set from SOURCE with Konsist
 *
 * [overriddenFunctionNames] scans the decorator's SOURCE (Konsist, already a `testImplementation`
 * dependency for `LayeringTest`) for each inner decorator class's functions carrying the `override`
 * keyword. A dropped `override suspend fun deleteAll` then simply does not appear in that set —
 * `deleteAll` becomes "unclassified" (it matches neither the derived-evented set nor the read-only
 * allowlist), and the classification assertion below goes RED. This is a genuine source-level
 * check, not a name-list that has to be kept in sync by hand.
 *
 * ## Residual limit
 *
 * This check is still name-level, the same granularity as before, and it trusts the read-only
 * allowlist. A future WRITE method named `find*`/`get*`/`count*` would be misclassified as
 * read-only. This guard does not check BEHAVIOUR (that an evented method actually publishes) — the
 * O3/O5 event tests in this package do that.
 */
class EventPublishingDecoratorGuardTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    /**
     * Declared method names on [cls], excluding synthetic/bridge artifacts the Kotlin compiler
     * generates for default-parameter overloads (e.g. `foo$default`) — those are not part of the
     * interface's logical method surface and would otherwise pollute an exact-name comparison.
     */
    private fun declaredInterfaceMethodNames(cls: Class<*>): Set<String> =
        cls.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge || it.name.contains("$") }
            .map { it.name }
            .toSet()

    /**
     * Function names carrying Kotlin's `override` modifier, declared directly (not in a nested
     * class) on the class named [simpleClassName] anywhere in this module's production source.
     *
     * Scanning source rather than compiled bytecode is what makes this immune to `by inner`
     * delegation hiding a dropped override — the compiler generates an identical forwarding method
     * either way, but only a genuine `override fun` shows up here.
     */
    private fun overriddenFunctionNames(simpleClassName: String): Set<String> {
        val scope = Konsist.scopeFromProduction()
        val matches = scope.classes(includeNested = true, includeLocal = false).filter { it.name == simpleClassName }
        require(matches.size == 1) {
            "Expected exactly one production class named '$simpleClassName', found ${matches.size} - " +
                "the Konsist scope may be misrooted, or the decorator class was renamed/duplicated."
        }
        return matches
            .single()
            .functions(includeNested = false, includeLocal = false)
            .filter { it.hasOverrideModifier }
            .map { it.name }
            .toSet()
    }

    private val readOnlyAllowListExact =
        setOf(
            // P8: `clear` is no longer allow-listed; it records claim.released (reason cleared).
            "ping",
            "descendantIds",
            "search",
            "ftsSearch",
            "hasCyclicDependency",
            "backlinks",
            "resolveChildPlacement",
            "list",
            // Pure functions on the config / plan-document ports (no store access).
            "computeFingerprint",
            "classifyFingerprint",
            "computeContentHash",
        )

    /**
     * Stores the decorator deliberately passes through unrecorded, with their full method surface (P8 F4). A new
     * method on one of them fails here until it is classified. The event store is never decorated: its appends
     * are what the decorator records.
     */
    private val passThroughSurfaces =
        mapOf(
            IdempotencyStore::class.java to setOf("find", "upsert", "insertIfAbsent", "deleteExpired"),
            EventStore::class.java to setOf("append", "readAfter", "maxSeq"),
        )

    /** Shared classification: every declared method of [port] is overridden by [decoratorClass] or allow-listed. */
    private fun assertClassified(
        port: Class<*>,
        expectedNames: Set<String>,
        decoratorClass: String,
    ) {
        val actualNames = declaredInterfaceMethodNames(port)
        assertEquals(expectedNames, actualNames, "${port.simpleName}'s declared method-name surface has changed")

        val evented = overriddenFunctionNames(decoratorClass)
        assertTrue(evented.isNotEmpty(), "$decoratorClass must override at least one method")
        val staleOrTypoed = evented - actualNames
        assertTrue(staleOrTypoed.isEmpty(), "$decoratorClass overrides name(s) not on ${port.simpleName}: $staleOrTypoed")

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified ${port.simpleName} methods: $unclassified")
    }

    @Test
    fun `LeaseStore method surface is fully classified as EVENTED or read-only allow-listed`() =
        assertClassified(
            LeaseStore::class.java,
            setOf(
                "acquireAll",
                "releaseAllForItem",
                "releaseAllForItems",
                "forceReleaseByKey",
                "findActiveByKeys",
                "findActiveForItem",
                "findAllActive",
                "findHoldersAt",
                "findRecentIntervals",
            ),
            "EventPublishingLeaseStore",
        )

    @Test
    fun `TransitionStore method surface is fully classified as EVENTED or read-only allow-listed`() =
        assertClassified(
            TransitionStore::class.java,
            setOf("create", "findByItemId", "findByTimeRange", "findSince"),
            "EventPublishingTransitionStore",
        )

    @Test
    fun `ProjectConfigStore method surface is fully classified as EVENTED or read-only allow-listed`() =
        assertClassified(
            ProjectConfigStore::class.java,
            setOf("upsert", "upsertGuarded", "get", "getFingerprint", "delete", "computeFingerprint", "classifyFingerprint"),
            "EventPublishingProjectConfigStore",
        )

    @Test
    fun `PlanDocumentStore method surface is fully classified as EVENTED or read-only allow-listed`() =
        assertClassified(
            PlanDocumentStore::class.java,
            setOf("stash", "get", "list", "markAdopted", "computeContentHash"),
            "EventPublishingPlanDocumentStore",
        )

    @Test
    fun `pass-through stores are named explicitly and handed through unwrapped`() {
        for ((port, surface) in passThroughSurfaces) {
            assertEquals(surface, declaredInterfaceMethodNames(port), "${port.simpleName}'s declared method-name surface has changed")
        }
        val delegate = db.repositoryProvider()
        val provider = EventPublishingRepositoryProvider(delegate, ApiEventBus())
        assertTrue(provider.idempotencyStore() === delegate.idempotencyStore(), "the idempotency store must pass through")
        assertTrue(provider.eventStore() === delegate.eventStore(), "the event store must never be decorated")
    }

    private fun isReadOnlyAllowListed(name: String): Boolean {
        if (name in readOnlyAllowListExact) return true
        return name.startsWith("get") || name.startsWith("find") || name.startsWith("count")
    }

    @Test
    fun `WorkItemRepository method surface is fully classified as EVENTED or read-only allow-listed`() {
        val expectedNames =
            setOf(
                "ping",
                "descendantIds",
                "clear",
                "getById",
                "create",
                "update",
                "delete",
                "findByParent",
                "findByRole",
                "findByDepth",
                "findProjectRoots",
                "search",
                "count",
                "findChildren",
                "findByFilters",
                "countByFilters",
                "countChildrenByRole",
                "findRootItems",
                "countRootItems",
                "findDescendants",
                "findByIds",
                "deleteAll",
                "claim",
                "release",
                "findByIdPrefix",
                "findAncestorChains",
                "findAncestorChainsDetailed",
                "findForNextItem",
                "findClaimable",
                "countByClaimStatus",
                "countSelectorMatches",
                "findInScope",
                "countInScope",
                "countInScopeByRole",
                "ftsSearch",
                "resolveChildPlacement",
            )

        // WorkItemRepository is the member-less composite of the four narrow ports; its surface is their union.
        assertEquals(
            emptySet<String>(),
            declaredInterfaceMethodNames(WorkItemRepository::class.java),
            "the composite must declare no members"
        )
        val actualNames =
            listOf(ItemStore::class.java, HierarchyStore::class.java, ClaimStore::class.java, SearchIndex::class.java)
                .flatMap { declaredInterfaceMethodNames(it) }
                .toSet()
        assertEquals(expectedNames, actualNames, "the work-item ports' declared method-name surface has changed")

        val evented = overriddenFunctionNames("EventPublishingWorkItemRepository")
        assertTrue(evented.isNotEmpty(), "EventPublishingWorkItemRepository must override at least one method")
        val staleOrTypoed = evented - actualNames
        assertTrue(
            staleOrTypoed.isEmpty(),
            "EventPublishingWorkItemRepository overrides name(s) not on WorkItemRepository: $staleOrTypoed",
        )

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified WorkItemRepository methods: $unclassified")
    }

    @Test
    fun `NoteStore method surface is fully classified as EVENTED or read-only allow-listed`() {
        val expectedNames =
            setOf(
                "getById",
                "upsert",
                "delete",
                "deleteByItemId",
                "findByItemId",
                "findByItemIdAndKey",
                "findByItemIds",
                "findRefsByItemIds",
                "ftsSearch",
            )

        val actualNames = declaredInterfaceMethodNames(NoteStore::class.java)
        assertEquals(expectedNames, actualNames, "NoteStore's declared method-name surface has changed")

        val evented = overriddenFunctionNames("EventPublishingNoteRepository")
        assertTrue(evented.isNotEmpty(), "EventPublishingNoteRepository must override at least one method")
        val staleOrTypoed = evented - actualNames
        assertTrue(
            staleOrTypoed.isEmpty(),
            "EventPublishingNoteRepository overrides name(s) not on NoteStore: $staleOrTypoed",
        )

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified NoteStore methods: $unclassified")
    }

    @Test
    fun `DependencyStore method surface is fully classified as EVENTED or read-only allow-listed`() {
        val expectedNames =
            setOf(
                "create",
                "findById",
                "findByItemId",
                "findByFromItemId",
                "findByToItemId",
                "delete",
                "deleteByItemId",
                "createBatch",
                "hasCyclicDependency",
                "findByItemIds",
                "backlinks",
            )

        val actualNames = declaredInterfaceMethodNames(DependencyStore::class.java)
        assertEquals(expectedNames, actualNames, "DependencyStore's declared method-name surface has changed")

        val evented = overriddenFunctionNames("EventPublishingDependencyRepository")
        assertTrue(evented.isNotEmpty(), "EventPublishingDependencyRepository must override at least one method")
        val staleOrTypoed = evented - actualNames
        assertTrue(
            staleOrTypoed.isEmpty(),
            "EventPublishingDependencyRepository overrides name(s) not on DependencyStore: $staleOrTypoed",
        )

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified DependencyStore methods: $unclassified")
    }

    @Test
    fun `WorkTreeExecutor has a single, fully-evented method surface`() {
        val actualNames = declaredInterfaceMethodNames(WorkTreeExecutor::class.java)
        assertEquals(setOf("execute"), actualNames, "WorkTreeExecutor's declared method-name surface has changed")
        // Single-method interface: the whole executor is wrapped, so the surface is entirely
        // EVENTED with no read-only allow-list needed (test-plan S17).

        val evented = overriddenFunctionNames("EventPublishingWorkTreeExecutor")
        assertEquals(setOf("execute"), evented, "EventPublishingWorkTreeExecutor must override exactly `execute`")
    }

    @Test
    fun `decorated provider returns its own wrapping instances, not the delegate's raw ones`() {
        val delegate = db.repositoryProvider()
        val bus = ApiEventBus()
        val provider = EventPublishingRepositoryProvider(delegate, bus)

        assertTrue(
            provider.workItemRepository() !== delegate.workItemRepository(),
            "EventPublishingRepositoryProvider.workItemRepository() must return a wrapping decorator " +
                "instance distinct from the delegate's raw repository",
        )
        assertTrue(
            provider.noteRepository() !== delegate.noteRepository(),
            "EventPublishingRepositoryProvider.noteRepository() must return a wrapping decorator " +
                "instance distinct from the delegate's raw repository",
        )
        assertTrue(
            provider.dependencyRepository() !== delegate.dependencyRepository(),
            "EventPublishingRepositoryProvider.dependencyRepository() must return a wrapping decorator " +
                "instance distinct from the delegate's raw repository",
        )
        assertTrue(
            provider.workTreeExecutor() !== delegate.workTreeExecutor(),
            "EventPublishingRepositoryProvider.workTreeExecutor() must return a wrapping decorator " +
                "instance distinct from the delegate's raw executor",
        )
        assertTrue(provider.resourceLeaseRepository() !== delegate.resourceLeaseRepository(), "the lease store must be wrapped")
        assertTrue(provider.roleTransitionRepository() !== delegate.roleTransitionRepository(), "the transition store must be wrapped")
        assertTrue(provider.projectConfigRepository() !== delegate.projectConfigRepository(), "the config store must be wrapped")
        assertTrue(provider.planDocumentRepository() !== delegate.planDocumentRepository(), "the plan-document store must be wrapped")
    }
}
