package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusFilterTest {

    @Test
    fun `isEmpty returns true when both lists are empty`() {
        val filter = StatusFilter<TaskStatus>()
        assertTrue(filter.isEmpty())
    }

    @Test
    fun `isEmpty returns false when include list has values`() {
        val filter = StatusFilter(include = listOf(TaskStatus.PENDING))
        assertFalse(filter.isEmpty())
    }

    @Test
    fun `isEmpty returns false when exclude list has values`() {
        val filter = StatusFilter(exclude = listOf(TaskStatus.COMPLETED))
        assertFalse(filter.isEmpty())
    }

    @Test
    fun `matches returns true for empty filter`() {
        val filter = StatusFilter<TaskStatus>()
        assertTrue(filter.matches(TaskStatus.PENDING))
        assertTrue(filter.matches(TaskStatus.COMPLETED))
        assertTrue(filter.matches(TaskStatus.IN_PROGRESS))
    }

    @Test
    fun `matches returns true when value is in include list`() {
        val filter = StatusFilter(include = listOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS))
        assertTrue(filter.matches(TaskStatus.PENDING))
        assertTrue(filter.matches(TaskStatus.IN_PROGRESS))
    }

    @Test
    fun `matches returns false when value is not in include list`() {
        val filter = StatusFilter(include = listOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS))
        assertFalse(filter.matches(TaskStatus.COMPLETED))
        assertFalse(filter.matches(TaskStatus.CANCELLED))
    }

    @Test
    fun `matches returns false when value is in exclude list`() {
        val filter = StatusFilter(exclude = listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED))
        assertFalse(filter.matches(TaskStatus.COMPLETED))
        assertFalse(filter.matches(TaskStatus.CANCELLED))
    }

    @Test
    fun `matches returns true when value is not in exclude list`() {
        val filter = StatusFilter(exclude = listOf(TaskStatus.COMPLETED))
        assertTrue(filter.matches(TaskStatus.PENDING))
        assertTrue(filter.matches(TaskStatus.IN_PROGRESS))
        assertTrue(filter.matches(TaskStatus.DEFERRED))
    }

    @Test
    fun `exclude takes precedence over include`() {
        // If a value is in both include and exclude, exclude wins
        val filter = StatusFilter(
            include = listOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED),
            exclude = listOf(TaskStatus.COMPLETED)
        )
        assertTrue(filter.matches(TaskStatus.PENDING))
        assertTrue(filter.matches(TaskStatus.IN_PROGRESS))
        assertFalse(filter.matches(TaskStatus.COMPLETED)) // Excluded even though in include list
    }

    @Test
    fun `matches works with Priority type`() {
        val filter = StatusFilter(include = listOf(Priority.HIGH, Priority.MEDIUM))
        assertTrue(filter.matches(Priority.HIGH))
        assertTrue(filter.matches(Priority.MEDIUM))
        assertFalse(filter.matches(Priority.LOW))
    }

    @Test
    fun `complex filter example - non-completed tasks`() {
        // Real-world use case: Get all tasks that are NOT completed or cancelled
        val filter = StatusFilter<TaskStatus>(
            exclude = listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)
        )

        assertTrue(filter.matches(TaskStatus.PENDING))
        assertTrue(filter.matches(TaskStatus.IN_PROGRESS))
        assertTrue(filter.matches(TaskStatus.DEFERRED))
        assertFalse(filter.matches(TaskStatus.COMPLETED))
        assertFalse(filter.matches(TaskStatus.CANCELLED))
    }

    @Test
    fun `complex filter example - active tasks only`() {
        // Real-world use case: Get only pending or in-progress tasks
        val filter = StatusFilter(
            include = listOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        )

        assertTrue(filter.matches(TaskStatus.PENDING))
        assertTrue(filter.matches(TaskStatus.IN_PROGRESS))
        assertFalse(filter.matches(TaskStatus.COMPLETED))
        assertFalse(filter.matches(TaskStatus.DEFERRED))
        assertFalse(filter.matches(TaskStatus.CANCELLED))
    }

    @Test
    fun `PriorityFilter typealias works correctly`() {
        val filter: PriorityFilter = StatusFilter(
            include = listOf(Priority.HIGH, Priority.MEDIUM)
        )

        assertTrue(filter.matches(Priority.HIGH))
        assertTrue(filter.matches(Priority.MEDIUM))
        assertFalse(filter.matches(Priority.LOW))
        assertEquals("StatusFilter", filter::class.simpleName)
    }
}
