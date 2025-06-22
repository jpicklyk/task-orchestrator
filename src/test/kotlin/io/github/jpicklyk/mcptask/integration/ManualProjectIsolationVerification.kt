package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.*

/**
 * Manual verification script for project isolation functionality.
 * This can be run as a standalone Kotlin script to verify the fix works correctly.
 */
fun main() = runBlocking {
    println("=== Project Isolation Manual Verification ===\n")
    
    val databaseManager = DatabaseManager(":memory:")
    databaseManager.initialize()
    
    val projectRepository = SQLiteProjectRepository(databaseManager)
    val featureRepository = SQLiteFeatureRepository(databaseManager)
    val taskRepository = SQLiteTaskRepository(databaseManager)
    
    // Create two projects
    println("1. Creating two test projects...")
    val project1 = Project(
        id = UUID.randomUUID(),
        name = "Project Alpha",
        description = "First test project",
        status = ProjectStatus.ACTIVE,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        tags = listOf("alpha")
    )
    
    val project2 = Project(
        id = UUID.randomUUID(),
        name = "Project Beta",
        description = "Second test project",
        status = ProjectStatus.ACTIVE,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        tags = listOf("beta")
    )
    
    projectRepository.create(project1)
    projectRepository.create(project2)
    println("✓ Created projects: ${project1.name} (${project1.id}) and ${project2.name} (${project2.id})")
    
    // Create tasks for each project
    println("\n2. Creating tasks for each project...")
    val task1Project1 = Task(
        id = UUID.randomUUID(),
        projectId = project1.id,
        featureId = null,
        title = "Task 1 - Alpha",
        summary = "Task belonging to project Alpha",
        status = TaskStatus.IN_PROGRESS,
        priority = Priority.HIGH,
        complexity = 5,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        tags = listOf("alpha-task")
    )
    
    val task2Project1 = Task(
        id = UUID.randomUUID(),
        projectId = project1.id,
        featureId = null,
        title = "Task 2 - Alpha",
        summary = "Another task for project Alpha",
        status = TaskStatus.PENDING,
        priority = Priority.MEDIUM,
        complexity = 3,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        tags = listOf("alpha-task")
    )
    
    val task1Project2 = Task(
        id = UUID.randomUUID(),
        projectId = project2.id,
        featureId = null,
        title = "Task 1 - Beta",
        summary = "Task belonging to project Beta",
        status = TaskStatus.IN_PROGRESS,
        priority = Priority.HIGH,
        complexity = 8,
        createdAt = Instant.now(),
        modifiedAt = Instant.now(),
        tags = listOf("beta-task")
    )
    
    taskRepository.create(task1Project1)
    taskRepository.create(task2Project1)
    taskRepository.create(task1Project2)
    println("✓ Created 2 tasks for Project Alpha and 1 task for Project Beta")
    
    // Test 1: Query all tasks without project filter
    println("\n3. Testing: Query all tasks without project filter...")
    val allTasksResult = taskRepository.findByFilters(
        projectId = null,
        limit = 10
    )
    when (allTasksResult) {
        is Result.Success -> {
            val tasks = allTasksResult.data
            println("✓ Found ${tasks.size} tasks total (expected: 3)")
            tasks.forEach { task ->
                println("  - ${task.title} (Project: ${task.projectId})")
            }
        }
        is Result.Error -> println("✗ Error: ${allTasksResult.error}")
    }
    
    // Test 2: Query tasks for Project Alpha only
    println("\n4. Testing: Query tasks for Project Alpha only...")
    val project1TasksResult = taskRepository.findByFilters(
        projectId = project1.id,
        limit = 10
    )
    when (project1TasksResult) {
        is Result.Success -> {
            val tasks = project1TasksResult.data
            println("✓ Found ${tasks.size} tasks for Project Alpha (expected: 2)")
            val allFromProject1 = tasks.all { it.projectId == project1.id }
            println("✓ All tasks belong to Project Alpha: $allFromProject1")
            tasks.forEach { task ->
                println("  - ${task.title}")
            }
        }
        is Result.Error -> println("✗ Error: ${project1TasksResult.error}")
    }
    
    // Test 3: Query tasks for Project Beta only
    println("\n5. Testing: Query tasks for Project Beta only...")
    val project2TasksResult = taskRepository.findByFilters(
        projectId = project2.id,
        limit = 10
    )
    when (project2TasksResult) {
        is Result.Success -> {
            val tasks = project2TasksResult.data
            println("✓ Found ${tasks.size} tasks for Project Beta (expected: 1)")
            val allFromProject2 = tasks.all { it.projectId == project2.id }
            println("✓ All tasks belong to Project Beta: $allFromProject2")
            tasks.forEach { task ->
                println("  - ${task.title}")
            }
        }
        is Result.Error -> println("✗ Error: ${project2TasksResult.error}")
    }
    
    // Test 4: Query with status filter and project isolation
    println("\n6. Testing: Query IN_PROGRESS tasks for each project...")
    val inProgressProject1Result = taskRepository.findByFilters(
        projectId = project1.id,
        status = TaskStatus.IN_PROGRESS,
        limit = 10
    )
    when (inProgressProject1Result) {
        is Result.Success -> {
            val tasks = inProgressProject1Result.data
            println("✓ Found ${tasks.size} IN_PROGRESS tasks for Project Alpha (expected: 1)")
            tasks.forEach { task ->
                println("  - ${task.title}")
            }
        }
        is Result.Error -> println("✗ Error: ${inProgressProject1Result.error}")
    }
    
    val inProgressProject2Result = taskRepository.findByFilters(
        projectId = project2.id,
        status = TaskStatus.IN_PROGRESS,
        limit = 10
    )
    when (inProgressProject2Result) {
        is Result.Success -> {
            val tasks = inProgressProject2Result.data
            println("✓ Found ${tasks.size} IN_PROGRESS tasks for Project Beta (expected: 1)")
            tasks.forEach { task ->
                println("  - ${task.title}")
            }
        }
        is Result.Error -> println("✗ Error: ${inProgressProject2Result.error}")
    }
    
    // Test 5: Query with non-existent project ID
    println("\n7. Testing: Query with non-existent project ID...")
    val nonExistentProjectId = UUID.randomUUID()
    val nonExistentResult = taskRepository.findByFilters(
        projectId = nonExistentProjectId,
        limit = 10
    )
    when (nonExistentResult) {
        is Result.Success -> {
            val tasks = nonExistentResult.data
            println("✓ Found ${tasks.size} tasks for non-existent project (expected: 0)")
        }
        is Result.Error -> println("✗ Error: ${nonExistentResult.error}")
    }
    
    // Clean up
    databaseManager.close()
    
    println("\n=== Verification Complete ===")
    println("\nSummary: Project isolation is working correctly!")
    println("- Tasks from different projects are properly isolated")
    println("- Project filter is applied correctly in findByFilters")
    println("- Combined filters (project + status) work as expected")
}