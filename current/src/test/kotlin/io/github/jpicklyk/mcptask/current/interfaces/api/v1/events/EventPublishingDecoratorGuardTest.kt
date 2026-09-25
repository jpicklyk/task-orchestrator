package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import com.lemonappdev.konsist.api.Konsist
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
            "inTransaction",
            "dbNow",
            "search",
            "ftsSearch",
            "hasCyclicDependency",
            "backlinks",
            "resolveChildPlacement",
        )

    private fun isReadOnlyAllowListed(name: String): Boolean {
        if (name in readOnlyAllowListExact) return true
        return name.startsWith("get") || name.startsWith("find") || name.startsWith("count")
    }

    @Test
    fun `WorkItemRepository method surface is fully classified as EVENTED or read-only allow-listed`() {
        val expectedNames =
            setOf(
                "dbNow",
                "inTransaction",
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

        val actualNames = declaredInterfaceMethodNames(WorkItemRepository::class.java)
        assertEquals(expectedNames, actualNames, "WorkItemRepository's declared method-name surface has changed")

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
    fun `NoteRepository method surface is fully classified as EVENTED or read-only allow-listed`() {
        val expectedNames =
            setOf(
                "getById",
                "upsert",
                "delete",
                "deleteByItemId",
                "findByItemId",
                "findByItemIdAndKey",
                "findByItemIds",
                "ftsSearch",
            )

        val actualNames = declaredInterfaceMethodNames(NoteRepository::class.java)
        assertEquals(expectedNames, actualNames, "NoteRepository's declared method-name surface has changed")

        val evented = overriddenFunctionNames("EventPublishingNoteRepository")
        assertTrue(evented.isNotEmpty(), "EventPublishingNoteRepository must override at least one method")
        val staleOrTypoed = evented - actualNames
        assertTrue(
            staleOrTypoed.isEmpty(),
            "EventPublishingNoteRepository overrides name(s) not on NoteRepository: $staleOrTypoed",
        )

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified NoteRepository methods: $unclassified")
    }

    @Test
    fun `DependencyRepository method surface is fully classified as EVENTED or read-only allow-listed`() {
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

        val actualNames = declaredInterfaceMethodNames(DependencyRepository::class.java)
        assertEquals(expectedNames, actualNames, "DependencyRepository's declared method-name surface has changed")

        val evented = overriddenFunctionNames("EventPublishingDependencyRepository")
        assertTrue(evented.isNotEmpty(), "EventPublishingDependencyRepository must override at least one method")
        val staleOrTypoed = evented - actualNames
        assertTrue(
            staleOrTypoed.isEmpty(),
            "EventPublishingDependencyRepository overrides name(s) not on DependencyRepository: $staleOrTypoed",
        )

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified DependencyRepository methods: $unclassified")
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
        val delegate = buildH2RepositoryProvider()
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
    }
}
