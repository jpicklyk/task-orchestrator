package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S17 — reflection guard for [EventPublishingRepositoryProvider]'s decorator surface.
 *
 * For each of [WorkItemRepository], [NoteRepository], [DependencyRepository] and
 * [WorkTreeExecutor], every declared method name must be classified as either EVENTED (overridden
 * by the corresponding decorator, per the item's `diagnosis`/declarations) or matched by the
 * READ_ONLY_ALLOWLIST pattern the item's `test-plan` fixes: `inTransaction`, `dbNow`, names
 * prefixed `get`, `find` or `count`, plus `search`, `ftsSearch`, `hasCyclicDependency`,
 * `backlinks`, `resolveChildPlacement`. A new interface method that matches neither fails this
 * guard until someone classifies it — that is
 * the point: `by inner` forwarding makes an "is this overridden" reflection check vacuous on its
 * own, so this guard instead enumerates the full method-name surface and requires every name to
 * be accounted for.
 *
 * Also asserts `provider.workTreeExecutor() !== delegate.workTreeExecutor()` — the decorator must
 * return its own wrapping instance, not the delegate's raw one (test-plan: "red at dd26e9e2").
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
        val evented = setOf("create", "update", "delete", "deleteAll", "claim", "release")

        val actualNames = declaredInterfaceMethodNames(WorkItemRepository::class.java)
        assertEquals(expectedNames, actualNames, "WorkItemRepository's declared method-name surface has changed")

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
        val evented = setOf("upsert", "delete", "deleteByItemId")

        val actualNames = declaredInterfaceMethodNames(NoteRepository::class.java)
        assertEquals(expectedNames, actualNames, "NoteRepository's declared method-name surface has changed")

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
        val evented = setOf("create", "delete", "deleteByItemId", "createBatch")

        val actualNames = declaredInterfaceMethodNames(DependencyRepository::class.java)
        assertEquals(expectedNames, actualNames, "DependencyRepository's declared method-name surface has changed")

        val unclassified = actualNames.filterNot { it in evented || isReadOnlyAllowListed(it) }
        assertTrue(unclassified.isEmpty(), "Unclassified DependencyRepository methods: $unclassified")
    }

    @Test
    fun `WorkTreeExecutor has a single, fully-evented method surface`() {
        val actualNames = declaredInterfaceMethodNames(WorkTreeExecutor::class.java)
        assertEquals(setOf("execute"), actualNames, "WorkTreeExecutor's declared method-name surface has changed")
        // Single-method interface: the whole executor is wrapped, so the surface is entirely
        // EVENTED with no read-only allow-list needed (test-plan S17).
    }

    @Test
    fun `decorated provider returns its own WorkTreeExecutor instance, not the delegate's raw one`() {
        val delegate = buildH2RepositoryProvider()
        val bus = ApiEventBus()
        val provider = EventPublishingRepositoryProvider(delegate, bus)

        assertTrue(
            provider.workTreeExecutor() !== delegate.workTreeExecutor(),
            "EventPublishingRepositoryProvider.workTreeExecutor() must return a wrapping decorator " +
                "instance distinct from the delegate's raw executor",
        )
    }
}
