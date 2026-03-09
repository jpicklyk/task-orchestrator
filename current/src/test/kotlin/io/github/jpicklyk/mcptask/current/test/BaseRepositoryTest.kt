package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import java.util.UUID

/**
 * Base class for repository integration tests.
 *
 * Sets up a fresh H2 in-memory database before each test with the full schema applied,
 * and provides convenience methods for creating persisted entities.
 */
abstract class BaseRepositoryTest {
    protected lateinit var database: Database
    protected lateinit var databaseManager: DatabaseManager
    protected lateinit var repositoryProvider: DefaultRepositoryProvider

    @BeforeEach
    fun setUpDatabase() {
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
    }

    /** Create and persist a WorkItem, returning the persisted copy. */
    protected suspend fun createPersistedItem(
        title: String = "Test Item",
        role: Role = Role.QUEUE,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0,
        tags: String? = null,
        priority: Priority = Priority.MEDIUM
    ): WorkItem {
        val item = makeItem(
            title = title,
            role = role,
            parentId = parentId,
            depth = depth,
            tags = tags,
            priority = priority
        )
        val result = repositoryProvider.workItemRepository().create(item)
        return (result as Result.Success).data
    }

    /** Create and persist a Note. */
    protected suspend fun createPersistedNote(
        itemId: UUID,
        key: String = "test-note",
        role: String = "queue",
        body: String = "Test body"
    ): Note {
        val note = makeNote(itemId = itemId, key = key, role = role, body = body)
        val result = repositoryProvider.noteRepository().upsert(note)
        return (result as Result.Success).data
    }

    /** Create and persist a Dependency. */
    protected fun createPersistedDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        val dep = Dependency(fromItemId = fromItemId, toItemId = toItemId, type = type, unblockAt = unblockAt)
        return repositoryProvider.dependencyRepository().create(dep)
    }
}
