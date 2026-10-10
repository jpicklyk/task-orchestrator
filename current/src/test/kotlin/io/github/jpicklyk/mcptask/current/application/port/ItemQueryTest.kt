package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `67ba3514` ([Q5]) -- the value objects of the item-list query:
 * [ItemFilter.validate], [ItemSortField]/[SortDirection]/[ItemSort] parsing, [ItemPage] bounds and the moved
 * [AccessScope] shape. Scenarios S11 and S12 of the frozen `test-plan` note, plus the value-object halves of S9/S10.
 *
 * Oracles (all NEW-SURFACE):
 *  - S11: `task-scope` item 4 (aliases `created`/`modified`; unknown -> null, no silent fallback; DEFAULT = createdAt
 *    desc) and AR-46; case-insensitivity and the two aliases are also pinned by the pre-existing
 *    `QueryItemsToolSortByTest` S9 (public evidence: `MODIFIEDAT` and `modified` both resolve).
 *  - S12: `QueryItemsTool.kt:941-949` as cited by `task-scope` item 4 -- an inverted range is a violation, the check
 *    is strict (`after > before`), so equal bounds pass.
 *  - ItemPage: the declaration's `init` comment (`require(limit >= 1)`, `require(offset >= 0)`); `task-scope` item 4
 *    "no 200 cap at the store".
 *  - AccessScope: the base-commit KDoc of the type (an empty `rootIds` admits nothing; null means no root restriction).
 *
 * BLINDNESS: authored from the frozen `task-scope`/`test-plan`/`performance-baseline` notes and the
 * orchestrator-supplied declarations block only. No `src/main` file, implementer diff or implementer note was opened.
 *
 * ARBITRATION: the violation `field` names of an inverted range are not declared, so the tests pin only that each
 * range reports something, that the three ranges report distinguishable fields, and that a violation list is
 * non-empty -- never an exact field string.
 */
class ItemQueryTest {
    private val early = Instant.parse("2025-01-01T00:00:00Z")
    private val late = Instant.parse("2025-12-01T00:00:00Z")

    // ---------------------------------------------------------------------------------- ItemFilter

    @Test
    fun `S12 ItemFilter ALL equals the no-argument filter and has no field set and no violation`() {
        val all = ItemFilter.ALL
        assertEquals(ItemFilter(), all)
        assertNull(all.parentId)
        assertNull(all.ancestorId)
        assertNull(all.depth)
        assertNull(all.role)
        assertNull(all.priority)
        assertNull(all.tagsAny)
        assertNull(all.type)
        assertNull(all.createdAfter)
        assertNull(all.createdBefore)
        assertNull(all.modifiedAfter)
        assertNull(all.modifiedBefore)
        assertNull(all.roleChangedAfter)
        assertNull(all.roleChangedBefore)
        assertNull(all.claimStatus)
        assertEquals(emptyList(), all.validate())
    }

    @Test
    fun `S12 an inverted created range is a violation`() {
        val violations = ItemFilter(createdAfter = late, createdBefore = early).validate()
        assertTrue(violations.isNotEmpty(), "createdAfter ($late) > createdBefore ($early) must be reported")
    }

    @Test
    fun `S12 an inverted modified range is a violation`() {
        val violations = ItemFilter(modifiedAfter = late, modifiedBefore = early).validate()
        assertTrue(violations.isNotEmpty(), "modifiedAfter ($late) > modifiedBefore ($early) must be reported")
    }

    @Test
    fun `S12 an inverted roleChanged range is a violation`() {
        val violations = ItemFilter(roleChangedAfter = late, roleChangedBefore = early).validate()
        assertTrue(violations.isNotEmpty(), "roleChangedAfter ($late) > roleChangedBefore ($early) must be reported")
    }

    @Test
    fun `S12 the three ranges are reported under distinguishable fields`() {
        val created = ItemFilter(createdAfter = late, createdBefore = early).validate().map { it.field }.toSet()
        val modified = ItemFilter(modifiedAfter = late, modifiedBefore = early).validate().map { it.field }.toSet()
        val roleChanged = ItemFilter(roleChangedAfter = late, roleChangedBefore = early).validate().map { it.field }.toSet()
        assertNotEquals(created, modified, "created and modified violations must not be indistinguishable")
        assertNotEquals(created, roleChanged, "created and roleChanged violations must not be indistinguishable")
        assertNotEquals(modified, roleChanged, "modified and roleChanged violations must not be indistinguishable")
    }

    @Test
    fun `S12 all three ranges inverted at once report at least three violations`() {
        val violations =
            ItemFilter(
                createdAfter = late,
                createdBefore = early,
                modifiedAfter = late,
                modifiedBefore = early,
                roleChangedAfter = late,
                roleChangedBefore = early,
            ).validate()
        assertTrue(violations.size >= 3, "one violation per inverted range expected, got $violations")
    }

    @Test
    fun `S12 equal bounds pass for every range (the check is strict)`() {
        val filter =
            ItemFilter(
                createdAfter = early,
                createdBefore = early,
                modifiedAfter = early,
                modifiedBefore = early,
                roleChangedAfter = early,
                roleChangedBefore = early,
            )
        assertEquals(emptyList(), filter.validate())
    }

    @Test
    fun `S12 a correctly ordered range, and a single bound alone, pass`() {
        assertEquals(emptyList(), ItemFilter(createdAfter = early, createdBefore = late).validate())
        assertEquals(emptyList(), ItemFilter(modifiedAfter = early, modifiedBefore = late).validate())
        assertEquals(emptyList(), ItemFilter(roleChangedAfter = early, roleChangedBefore = late).validate())
        // One bound only: nothing to invert, whichever side it is on.
        assertEquals(emptyList(), ItemFilter(createdAfter = late).validate())
        assertEquals(emptyList(), ItemFilter(createdBefore = early).validate())
        assertEquals(emptyList(), ItemFilter(modifiedAfter = late).validate())
        assertEquals(emptyList(), ItemFilter(roleChangedBefore = early).validate())
    }

    @Test
    fun `S12 a sub-second inversion is still a violation`() {
        val before = Instant.parse("2025-06-01T00:00:00.000Z")
        val after = Instant.parse("2025-06-01T00:00:00.001Z")
        assertTrue(ItemFilter(createdAfter = after, createdBefore = before).validate().isNotEmpty())
        assertEquals(emptyList(), ItemFilter(createdAfter = before, createdBefore = after).validate())
    }

    @Test
    fun `S12 filters that are not ranges never produce a violation`() {
        val filter =
            ItemFilter(
                parentId = UUID.randomUUID(),
                ancestorId = UUID.randomUUID(),
                depth = 2,
                role = Role.WORK,
                priority = Priority.HIGH,
                tagsAny = listOf("a", "b"),
                type = "feature-task",
                claimStatus = ClaimStatus.CLAIMED,
            )
        assertEquals(emptyList(), filter.validate())
    }

    // ------------------------------------------------------------------------------- ItemSortField

    @Test
    fun `S11 every sort field parses from its own wire name`() {
        for (field in ItemSortField.entries) {
            assertEquals(field, ItemSortField.parse(field.wire), "wire '${field.wire}'")
        }
        assertEquals(
            setOf("title", "priority", "complexity", "createdAt", "modifiedAt"),
            ItemSortField.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `S11 WIRE_NAMES advertises at least the five canonical wire names`() {
        assertTrue(ItemSortField.WIRE_NAMES.containsAll(listOf("title", "priority", "complexity", "createdAt", "modifiedAt")))
        assertTrue(SortDirection.WIRE_NAMES.containsAll(listOf("asc", "desc")))
    }

    @Test
    fun `S11 created and modified are accepted as aliases`() {
        assertEquals(ItemSortField.CREATED_AT, ItemSortField.parse("created"))
        assertEquals(ItemSortField.MODIFIED_AT, ItemSortField.parse("modified"))
    }

    @Test
    fun `S11 sort field parsing is case-insensitive`() {
        assertEquals(ItemSortField.MODIFIED_AT, ItemSortField.parse("MODIFIEDAT"))
        assertEquals(ItemSortField.CREATED_AT, ItemSortField.parse("CreatedAt"))
        assertEquals(ItemSortField.TITLE, ItemSortField.parse("TITLE"))
        assertEquals(ItemSortField.CREATED_AT, ItemSortField.parse("Created"))
    }

    @Test
    fun `S11 an unknown sort field is null with no silent fallback`() {
        assertNull(ItemSortField.parse("bogus"))
        assertNull(ItemSortField.parse("updatedAt"))
        assertNull(ItemSortField.parse("created_at"))
        assertNull(ItemSortField.parse("rank"))
    }

    @Test
    fun `S11 sort direction parses asc and desc in any case and rejects anything else`() {
        assertEquals(SortDirection.ASC, SortDirection.parse("asc"))
        assertEquals(SortDirection.DESC, SortDirection.parse("desc"))
        assertEquals(SortDirection.ASC, SortDirection.parse("ASC"))
        assertEquals(SortDirection.DESC, SortDirection.parse("Desc"))
        assertNull(SortDirection.parse("sideways"))
        assertNull(SortDirection.parse("ascending"))
    }

    // ----------------------------------------------------------------------------------- ItemSort

    @Test
    fun `S11 DEFAULT is createdAt descending`() {
        assertEquals(ItemSort(ItemSortField.CREATED_AT, SortDirection.DESC), ItemSort.DEFAULT)
    }

    @Test
    fun `S11 ItemSort parse of nothing is the default`() {
        assertEquals(ItemSort.DEFAULT, ItemSort.parse(null, null))
    }

    @Test
    fun `S11 ItemSort parse of explicit values and aliases`() {
        assertEquals(ItemSort(ItemSortField.TITLE, SortDirection.ASC), ItemSort.parse("title", "asc"))
        assertEquals(ItemSort(ItemSortField.PRIORITY, SortDirection.DESC), ItemSort.parse("priority", "desc"))
        assertEquals(ItemSort(ItemSortField.MODIFIED_AT, SortDirection.DESC), ItemSort.parse("modified", "desc"))
        assertEquals(ItemSort(ItemSortField.CREATED_AT, SortDirection.ASC), ItemSort.parse("created", "asc"))
        assertEquals(ItemSort(ItemSortField.COMPLEXITY, SortDirection.ASC), ItemSort.parse("COMPLEXITY", "ASC"))
    }

    @Test
    fun `S11 a missing half takes the default for that half`() {
        // sortOrder omitted -> descending (the tool default); sortBy omitted -> createdAt.
        assertEquals(ItemSort(ItemSortField.TITLE, SortDirection.DESC), ItemSort.parse("title", null))
        assertEquals(ItemSort(ItemSortField.CREATED_AT, SortDirection.ASC), ItemSort.parse(null, "asc"))
    }

    @Test
    fun `S11 an unresolvable half makes the whole parse null`() {
        assertNull(ItemSort.parse("bogus", null))
        assertNull(ItemSort.parse("bogus", "asc"))
        assertNull(ItemSort.parse("title", "sideways"))
        assertNull(ItemSort.parse(null, "sideways"))
        assertNull(ItemSort.parse("bogus", "sideways"))
    }

    // ---------------------------------------------------------------------------------- ItemPage

    @Test
    fun `ItemPage defaults offset to zero and keeps its values`() {
        val page = ItemPage(limit = 25)
        assertEquals(25, page.limit)
        assertEquals(0, page.offset)
        assertEquals(ItemPage(25, 0), page)
        assertEquals(7, ItemPage(5, 7).offset)
    }

    @Test
    fun `ItemPage rejects a limit below one`() {
        assertFailsWith<IllegalArgumentException> { ItemPage(0) }
        assertFailsWith<IllegalArgumentException> { ItemPage(-1) }
        assertFailsWith<IllegalArgumentException> { ItemPage(Int.MIN_VALUE) }
        assertEquals(1, ItemPage(1).limit)
    }

    @Test
    fun `ItemPage rejects a negative offset and accepts zero`() {
        assertFailsWith<IllegalArgumentException> { ItemPage(1, -1) }
        assertFailsWith<IllegalArgumentException> { ItemPage(10, Int.MIN_VALUE) }
        assertEquals(0, ItemPage(1, 0).offset)
    }

    @Test
    fun `ItemPage has no 200 cap at the store`() {
        assertEquals(201, ItemPage(201).limit)
        assertEquals(100_000, ItemPage(100_000, 5).limit)
        assertEquals(Int.MAX_VALUE, ItemPage(Int.MAX_VALUE).limit)
    }

    // --------------------------------------------------------------------------------- AccessScope

    @Test
    fun `AccessScope unrestricted has no root and no tag restriction`() {
        val scope = AccessScope.unrestricted()
        assertTrue(scope.isUnrestricted)
        assertNull(scope.rootIds)
        assertEquals(emptySet(), scope.tagsInclude)
    }

    @Test
    fun `AccessScope principal keeps its roots and tags and is restricted`() {
        val root = UUID.randomUUID()
        val scope = AccessScope.principal(setOf(root), setOf("Bug"))
        assertFalse(scope.isUnrestricted)
        assertEquals(setOf(root), scope.rootIds)
        assertEquals(setOf("Bug"), scope.tagsInclude)
    }

    @Test
    fun `AccessScope principal with an empty root set is restricted and keeps the empty set`() {
        val scope = AccessScope.principal(emptySet(), emptySet())
        assertFalse(scope.isUnrestricted, "an empty rootIds admits nothing; it is not 'no restriction'")
        assertNotNull(scope.rootIds)
        assertEquals(emptySet(), scope.rootIds)
    }

    @Test
    fun `AccessScope principal with only a tag allowlist is restricted`() {
        val scope = AccessScope.principal(null, setOf("a"))
        assertFalse(scope.isUnrestricted)
        assertNull(scope.rootIds)
    }
}
