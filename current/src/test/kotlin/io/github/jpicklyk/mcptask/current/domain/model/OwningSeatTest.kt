package io.github.jpicklyk.mcptask.current.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 43b60546) for the owning-seat rule, scenarios S8-S15 and the probes of the frozen
 * test-plan (NEW-SURFACE: OwningSeat and the two WorkItemSchema shortcuts).
 *
 * Oracle: rule R of the test-plan (frozen decision 2, glossary "Owning seat"): with no seats declared the owner is the note's
 * seat label; with seats declared the owner is the label only when a seat of that name is declared with phase equal to the
 * note's role, otherwise none. Expected values are written by hand from that clause.
 *
 * Forbidden-construct declaration: none.
 */
class OwningSeatTest {
    private val seats =
        listOf(
            SeatDefinition(name = "implementer", phase = Role.WORK),
            SeatDefinition(name = "reviewer", phase = Role.REVIEW)
        )

    private fun entry(
        key: String = "k",
        role: Role = Role.WORK,
        seat: String?
    ) = NoteSchemaEntry(key = key, role = role, seat = seat)

    @Test
    fun `S8 with no seats declared the label is the owner`() {
        assertEquals("x", OwningSeat.of(entry(seat = "x"), emptyList()))
        assertEquals("x", OwningSeat.of(entry(role = Role.REVIEW, seat = "x"), emptyList()))
    }

    @Test
    fun `S9 a label naming a seat declared in the note's own phase is the owner`() {
        assertEquals("implementer", OwningSeat.of(entry(role = Role.WORK, seat = "implementer"), seats))
        assertEquals("reviewer", OwningSeat.of(entry(role = Role.REVIEW, seat = "reviewer"), seats))
    }

    @Test
    fun `S10 a label naming a seat declared for another phase is no owner`() {
        assertNull(OwningSeat.of(entry(role = Role.REVIEW, seat = "implementer"), seats))
        assertNull(OwningSeat.of(entry(role = Role.WORK, seat = "reviewer"), seats))
    }

    @Test
    fun `S11 a label naming no declared seat on a seat-aware schema is no owner`() {
        assertNull(OwningSeat.of(entry(seat = "ghost"), seats))
    }

    @Test
    fun `S12 a note with no seat label has no owner with or without seats`() {
        assertNull(OwningSeat.of(entry(seat = null), emptyList()))
        assertNull(OwningSeat.of(entry(seat = null), seats))
    }

    @Test
    fun `S13 no entry has no owner`() {
        assertNull(OwningSeat.of(null, emptyList()))
        assertNull(OwningSeat.of(null, seats))
    }

    private val notes =
        listOf(
            entry(key = "a", role = Role.WORK, seat = "implementer"),
            entry(key = "b", role = Role.REVIEW, seat = "implementer"),
            entry(key = "c", role = Role.REVIEW, seat = "reviewer"),
            entry(key = "d", role = Role.WORK, seat = "ghost"),
            entry(key = "e", role = Role.WORK, seat = null)
        )

    @Test
    fun `S14 keysOf returns exactly the keys whose owner is the seat`() {
        assertEquals(setOf("a"), OwningSeat.keysOf("implementer", notes, seats))
        assertEquals(setOf("c"), OwningSeat.keysOf("reviewer", notes, seats))
        assertEquals(emptySet(), OwningSeat.keysOf("ghost", notes, seats))
        for (name in listOf("implementer", "reviewer", "ghost", "nobody")) {
            assertEquals(
                notes.filter { OwningSeat.of(it, seats) == name }.map { it.key }.toSet(),
                OwningSeat.keysOf(name, notes, seats),
                name
            )
        }
    }

    @Test
    fun `S14 keysOf on a seatless schema returns the keys whose label matches`() {
        assertEquals(setOf("a", "b"), OwningSeat.keysOf("implementer", notes, emptyList()))
        assertEquals(setOf("d"), OwningSeat.keysOf("ghost", notes, emptyList()))
        assertEquals(emptySet(), OwningSeat.keysOf("nobody", notes, emptyList()))
    }

    @Test
    fun `S15 WorkItemSchema shortcuts agree with the rule`() {
        val schema = WorkItemSchema(type = "t", notes = notes, seats = seats)
        for (n in notes) {
            assertEquals(OwningSeat.of(n, seats), schema.owningSeatOf(n.key), n.key)
        }
        assertEquals("implementer", schema.owningSeatOf("a"))
        assertNull(schema.owningSeatOf("b"))
        assertNull(schema.owningSeatOf("off-schema-key"))
        for (name in listOf("implementer", "reviewer", "ghost")) {
            assertEquals(OwningSeat.keysOf(name, notes, seats), schema.noteKeysOwnedBy(name), name)
        }
        assertEquals(setOf("a"), schema.noteKeysOwnedBy("implementer"))
    }

    @Test
    fun `probe labels are case sensitive`() {
        assertNull(OwningSeat.of(entry(seat = "Implementer"), seats))
        assertEquals("Implementer", OwningSeat.of(entry(seat = "Implementer"), emptyList()))
    }

    @Test
    fun `probe an empty label is the label on a seatless schema and no owner on a seat-aware one`() {
        assertEquals("", OwningSeat.of(entry(seat = ""), emptyList()))
        assertNull(OwningSeat.of(entry(seat = ""), seats))
    }
}
