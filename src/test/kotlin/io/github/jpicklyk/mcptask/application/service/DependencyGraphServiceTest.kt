package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.test.mock.MockDependencyRepository
import io.github.jpicklyk.mcptask.test.mock.MockTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class DependencyGraphServiceTest {

    private lateinit var service: DependencyGraphService
    private lateinit var mockTaskRepo: MockTaskRepository
    private lateinit var mockDepRepo: MockDependencyRepository

    @BeforeEach
    fun setup() {
        mockTaskRepo = MockTaskRepository()
        mockDepRepo = MockDependencyRepository()
        service = DependencyGraphService(mockDepRepo, mockTaskRepo)
    }

    private fun createTask(id: UUID, title: String, complexity: Int = 5): Task {
        val task = Task(
            id = id,
            title = title,
            summary = "Test task: $title",
            status = TaskStatus.PENDING,
            complexity = complexity
        )
        mockTaskRepo.addTask(task)
        return task
    }

    private fun addBlocksDependency(from: UUID, to: UUID) {
        mockDepRepo.addDependency(
            Dependency(
                fromTaskId = from,
                toTaskId = to,
                type = DependencyType.BLOCKS
            )
        )
    }

    private fun addRelatesDependency(from: UUID, to: UUID) {
        mockDepRepo.addDependency(
            Dependency(
                fromTaskId = from,
                toTaskId = to,
                type = DependencyType.RELATES_TO
            )
        )
    }

    // ========== EMPTY / SINGLE NODE TESTS ==========

    @Test
    fun `empty graph - task with no dependencies`() = runBlocking {
        val taskId = UUID.randomUUID()
        createTask(taskId, "Solo Task")

        val result = service.analyzeGraph(taskId, "all", null, false)

        assertEquals(listOf(taskId), result.chain)
        assertEquals(0, result.depth)
        assertEquals(listOf(taskId), result.criticalPath)
        assertTrue(result.bottlenecks.isEmpty())
        assertTrue(result.parallelizable.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    // ========== LINEAR CHAIN TESTS ==========

    @Test
    fun `linear chain A to B to C to D - outgoing direction`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val d = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")
        createTask(d, "Task D")

        // A -> B -> C -> D
        addBlocksDependency(a, b)
        addBlocksDependency(b, c)
        addBlocksDependency(c, d)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        // Chain should contain all 4 nodes in topological order
        assertEquals(4, result.chain.size)
        assertTrue(result.chain.indexOf(a) < result.chain.indexOf(b))
        assertTrue(result.chain.indexOf(b) < result.chain.indexOf(c))
        assertTrue(result.chain.indexOf(c) < result.chain.indexOf(d))

        // Depth should be 3 (longest path has 4 nodes = 3 edges)
        assertEquals(3, result.depth)

        // Critical path should be the full chain
        assertEquals(4, result.criticalPath.size)
        assertEquals(a, result.criticalPath[0])
        assertEquals(d, result.criticalPath[3])

        // No bottlenecks (fan-out is 1 everywhere)
        assertTrue(result.bottlenecks.isEmpty())

        // No parallelizable groups (each depth has only 1 task)
        assertTrue(result.parallelizable.isEmpty())
    }

    @Test
    fun `linear chain - incoming direction from end`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")

        // A -> B -> C (BLOCKS)
        addBlocksDependency(a, b)
        addBlocksDependency(b, c)

        // Query from C, incoming direction
        val result = service.analyzeGraph(c, "incoming", null, false)

        // Should discover A -> B -> C
        assertEquals(3, result.chain.size)
        assertTrue(result.chain.indexOf(a) < result.chain.indexOf(b))
        assertTrue(result.chain.indexOf(b) < result.chain.indexOf(c))
        assertEquals(2, result.depth)
    }

    // ========== DIAMOND GRAPH TESTS ==========

    @Test
    fun `diamond graph A to B and C, both to D`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val d = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")
        createTask(d, "Task D")

        //   A
        //  / \
        // B   C
        //  \ /
        //   D
        addBlocksDependency(a, b)
        addBlocksDependency(a, c)
        addBlocksDependency(b, d)
        addBlocksDependency(c, d)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        // All 4 nodes should be in the chain
        assertEquals(4, result.chain.size)

        // A must come before B and C; B and C must come before D
        assertTrue(result.chain.indexOf(a) < result.chain.indexOf(b))
        assertTrue(result.chain.indexOf(a) < result.chain.indexOf(c))
        assertTrue(result.chain.indexOf(b) < result.chain.indexOf(d))
        assertTrue(result.chain.indexOf(c) < result.chain.indexOf(d))

        // Depth: A=0, B=1, C=1, D=2
        assertEquals(2, result.depth)

        // Critical path should be length 3: A -> (B or C) -> D
        assertEquals(3, result.criticalPath.size)
        assertEquals(a, result.criticalPath[0])
        assertEquals(d, result.criticalPath[2])

        // A is a bottleneck with fan-out 2
        assertEquals(1, result.bottlenecks.size)
        assertEquals(a, result.bottlenecks[0].taskId)
        assertEquals(2, result.bottlenecks[0].fanOut)

        // B and C should be parallelizable at depth 1
        val parallelGroup = result.parallelizable.find { it.depth == 1 }
        assertNotNull(parallelGroup)
        assertEquals(2, parallelGroup!!.tasks.size)
        assertTrue(parallelGroup.tasks.containsAll(listOf(b, c)))
    }

    // ========== FAN-OUT TESTS ==========

    @Test
    fun `fan-out graph - one task blocking many`() = runBlocking {
        val root = UUID.randomUUID()
        val child1 = UUID.randomUUID()
        val child2 = UUID.randomUUID()
        val child3 = UUID.randomUUID()
        val child4 = UUID.randomUUID()

        createTask(root, "Root Task")
        createTask(child1, "Child 1")
        createTask(child2, "Child 2")
        createTask(child3, "Child 3")
        createTask(child4, "Child 4")

        addBlocksDependency(root, child1)
        addBlocksDependency(root, child2)
        addBlocksDependency(root, child3)
        addBlocksDependency(root, child4)

        val result = service.analyzeGraph(root, "outgoing", null, false)

        assertEquals(5, result.chain.size)
        assertEquals(1, result.depth)

        // Root is a bottleneck with fan-out 4
        assertEquals(1, result.bottlenecks.size)
        assertEquals(root, result.bottlenecks[0].taskId)
        assertEquals(4, result.bottlenecks[0].fanOut)

        // All 4 children are parallelizable at depth 1
        val parallelGroup = result.parallelizable.find { it.depth == 1 }
        assertNotNull(parallelGroup)
        assertEquals(4, parallelGroup!!.tasks.size)
    }

    // ========== TASK INFO IN BOTTLENECKS ==========

    @Test
    fun `bottleneck includes task title when includeTaskInfo is true`() = runBlocking {
        val root = UUID.randomUUID()
        val child1 = UUID.randomUUID()
        val child2 = UUID.randomUUID()

        createTask(root, "Design API Schema")
        createTask(child1, "Implement Endpoint A")
        createTask(child2, "Implement Endpoint B")

        addBlocksDependency(root, child1)
        addBlocksDependency(root, child2)

        val result = service.analyzeGraph(root, "outgoing", null, true)

        assertEquals(1, result.bottlenecks.size)
        assertEquals("Design API Schema", result.bottlenecks[0].title)
    }

    @Test
    fun `bottleneck does not include title when includeTaskInfo is false`() = runBlocking {
        val root = UUID.randomUUID()
        val child1 = UUID.randomUUID()
        val child2 = UUID.randomUUID()

        createTask(root, "Design API Schema")
        createTask(child1, "Child 1")
        createTask(child2, "Child 2")

        addBlocksDependency(root, child1)
        addBlocksDependency(root, child2)

        val result = service.analyzeGraph(root, "outgoing", null, false)

        assertEquals(1, result.bottlenecks.size)
        assertNull(result.bottlenecks[0].title)
    }

    // ========== TYPE FILTER TESTS ==========

    @Test
    fun `type filter restricts traversal to matching dependency types`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")

        // A --BLOCKS--> B --RELATES_TO--> C
        addBlocksDependency(a, b)
        addRelatesDependency(b, c)

        // Filter to BLOCKS only: should only find A -> B
        val result = service.analyzeGraph(a, "outgoing", "BLOCKS", false)

        assertEquals(2, result.chain.size)
        assertTrue(result.chain.contains(a))
        assertTrue(result.chain.contains(b))
        assertFalse(result.chain.contains(c))
        assertEquals(1, result.depth)
    }

    // ========== SINGLE DEPENDENCY TESTS ==========

    @Test
    fun `single dependency - minimal graph`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")

        addBlocksDependency(a, b)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        assertEquals(2, result.chain.size)
        assertEquals(a, result.chain[0])
        assertEquals(b, result.chain[1])
        assertEquals(1, result.depth)
        assertEquals(2, result.criticalPath.size)
        assertTrue(result.bottlenecks.isEmpty()) // fan-out of 1 is not a bottleneck
        assertTrue(result.parallelizable.isEmpty())
    }

    // ========== COMPLEX GRAPH TESTS ==========

    @Test
    fun `complex graph with multiple paths and bottlenecks`() = runBlocking {
        // Graph:
        //   A
        //  /|\
        // B C D
        // |   |
        // E   F
        //  \ /
        //   G

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val d = UUID.randomUUID()
        val e = UUID.randomUUID()
        val f = UUID.randomUUID()
        val g = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")
        createTask(d, "Task D")
        createTask(e, "Task E")
        createTask(f, "Task F")
        createTask(g, "Task G")

        addBlocksDependency(a, b)
        addBlocksDependency(a, c)
        addBlocksDependency(a, d)
        addBlocksDependency(b, e)
        addBlocksDependency(d, f)
        addBlocksDependency(e, g)
        addBlocksDependency(f, g)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        assertEquals(7, result.chain.size)

        // Depth: A=0, B/C/D=1, E/F=2, G=3
        assertEquals(3, result.depth)

        // A is a bottleneck (fan-out 3)
        assertTrue(result.bottlenecks.any { it.taskId == a && it.fanOut == 3 })

        // B/C/D should be parallelizable at depth 1
        val depth1Group = result.parallelizable.find { it.depth == 1 }
        assertNotNull(depth1Group)
        assertTrue(depth1Group!!.tasks.containsAll(listOf(b, c, d)))

        // E/F should be parallelizable at depth 2
        val depth2Group = result.parallelizable.find { it.depth == 2 }
        assertNotNull(depth2Group)
        assertTrue(depth2Group!!.tasks.containsAll(listOf(e, f)))
    }

    // ========== DIRECTION TESTS ==========

    @Test
    fun `all direction discovers full connected component`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")

        // A -> B -> C
        addBlocksDependency(a, b)
        addBlocksDependency(b, c)

        // Starting from B with direction=all should find A, B, C
        val result = service.analyzeGraph(b, "all", null, false)

        assertEquals(3, result.chain.size)
        assertTrue(result.chain.containsAll(listOf(a, b, c)))
    }

    @Test
    fun `outgoing direction only follows downstream`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")

        // A -> B -> C
        addBlocksDependency(a, b)
        addBlocksDependency(b, c)

        // Starting from B with outgoing should only find B -> C
        val result = service.analyzeGraph(b, "outgoing", null, false)

        assertEquals(2, result.chain.size)
        assertTrue(result.chain.containsAll(listOf(b, c)))
        assertFalse(result.chain.contains(a))
    }

    @Test
    fun `incoming direction only follows upstream`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")

        // A -> B -> C
        addBlocksDependency(a, b)
        addBlocksDependency(b, c)

        // Starting from B with incoming should only find A -> B
        val result = service.analyzeGraph(b, "incoming", null, false)

        assertEquals(2, result.chain.size)
        assertTrue(result.chain.containsAll(listOf(a, b)))
        assertFalse(result.chain.contains(c))
    }

    // ========== PARALLELIZABLE GROUP EDGE CASES ==========

    @Test
    fun `parallelizable groups exclude tasks with inter-dependencies at same depth`() = runBlocking {
        // A -> B, A -> C, B -> C
        // B and C are at different effective depths because B -> C
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")

        addBlocksDependency(a, b)
        addBlocksDependency(a, c)
        addBlocksDependency(b, c)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        // A=0, B=1, C=2 (because C depends on both A and B)
        assertEquals(3, result.chain.size)
        assertEquals(2, result.depth)

        // No parallelizable groups since each depth has only 1 task (except A at 0)
        assertTrue(result.parallelizable.isEmpty())
    }

    // ========== BOTTLENECK ORDERING ==========

    @Test
    fun `bottlenecks are sorted by fan-out descending`() = runBlocking {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c1 = UUID.randomUUID()
        val c2 = UUID.randomUUID()
        val c3 = UUID.randomUUID()
        val d1 = UUID.randomUUID()
        val d2 = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c1, "Task C1")
        createTask(c2, "Task C2")
        createTask(c3, "Task C3")
        createTask(d1, "Task D1")
        createTask(d2, "Task D2")

        // A has fan-out 3 (to c1, c2, c3)
        addBlocksDependency(a, c1)
        addBlocksDependency(a, c2)
        addBlocksDependency(a, c3)

        // B has fan-out 2 (to d1, d2)
        addBlocksDependency(a, b)
        addBlocksDependency(b, d1)
        addBlocksDependency(b, d2)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        // A should have fan-out 4 (b, c1, c2, c3), B should have fan-out 2 (d1, d2)
        assertTrue(result.bottlenecks.size >= 2)
        // Sorted descending by fan-out
        for (i in 0 until result.bottlenecks.size - 1) {
            assertTrue(result.bottlenecks[i].fanOut >= result.bottlenecks[i + 1].fanOut)
        }
    }

    // ========== CRITICAL PATH TESTS ==========

    @Test
    fun `critical path follows longest path in diamond`() = runBlocking {
        //   A
        //  / \
        // B   C
        //  \ /
        //   D
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val d = UUID.randomUUID()

        createTask(a, "Task A")
        createTask(b, "Task B")
        createTask(c, "Task C")
        createTask(d, "Task D")

        addBlocksDependency(a, b)
        addBlocksDependency(a, c)
        addBlocksDependency(b, d)
        addBlocksDependency(c, d)

        val result = service.analyzeGraph(a, "outgoing", null, false)

        // Critical path should be 3 nodes: A -> (B or C) -> D
        assertEquals(3, result.criticalPath.size)
        assertEquals(a, result.criticalPath.first())
        assertEquals(d, result.criticalPath.last())
    }
}
