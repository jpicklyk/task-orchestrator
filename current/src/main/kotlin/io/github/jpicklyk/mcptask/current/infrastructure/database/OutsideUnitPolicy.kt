package io.github.jpicklyk.mcptask.current.infrastructure.database

/**
 * What a store write made OUTSIDE any unit of work does. Every such write is counted in
 * [UnitRunner.outsideUnitWrites] first, under either policy.
 */
enum class OutsideUnitPolicy {
    /** Throw [OutsideUnitWriteException] before any connection is checked out or the writer lock is taken. */
    FAIL,

    /** Run the write as its own implicit write unit (the P5a ratchet behaviour). */
    IMPLICIT
}

/** A store write labelled [op] was attempted outside a unit of work under [OutsideUnitPolicy.FAIL]. */
class OutsideUnitWriteException(
    val op: String
) : IllegalStateException("Store write '$op' ran outside a unit of work")
