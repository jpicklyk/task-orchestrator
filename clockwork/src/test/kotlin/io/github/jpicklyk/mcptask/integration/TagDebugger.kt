package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.infrastructure.database.schema.EntityTagsTable
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.*

/**
 * Utility class for debugging tag-related issues
 */
object TagDebugger {

    /**
     * Directly inserts tags into the EntityTagsTable, bypassing the repository logic
     */
    fun insertTagsDirectly(entityId: UUID, entityType: EntityType, tags: List<String>) {
        transaction {
            val now = Instant.now()
            println("Manually inserting ${tags.size} tags for ${entityType.name} with ID: $entityId")

            // Delete any existing tags first
            EntityTagsTable.deleteWhere {
                (EntityTagsTable.entityId eq entityId) and (EntityTagsTable.entityType eq entityType.name)
            }

            // Insert each tag one by one
            tags.forEach { tag ->
                try {
                    EntityTagsTable.insert {
                        it[EntityTagsTable.entityId] = entityId
                        it[EntityTagsTable.entityType] = entityType.name
                        it[EntityTagsTable.tag] = tag
                        it[EntityTagsTable.createdAt] = now
                    }
                    println("  - Successfully inserted tag: '$tag'")
                } catch (e: Exception) {
                    println("  - FAILED to insert tag: '$tag' - Error: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Queries for tags directly, bypassing the repository logic
     */
    fun getTagsDirectly(entityId: UUID, entityType: EntityType): List<String> {
        return transaction {
            println("Manually querying tags for ${entityType.name} with ID: $entityId")

            // Query tags directly
            val tags = EntityTagsTable
                .selectAll().where {
                    (EntityTagsTable.entityId eq entityId) and (EntityTagsTable.entityType eq entityType.name)
                }
                .map { it[EntityTagsTable.tag] }

            println("  - Found ${tags.size} tags directly: $tags")
            tags
        }
    }

    /**
     * Dumps the entire EntityTagsTable content for debugging
     */
    fun dumpAllTags() {
        transaction {
            println("==== DUMPING ALL TAGS IN DATABASE ====")
            val tags = EntityTagsTable.selectAll().toList()

            if (tags.isEmpty()) {
                println("NO TAGS FOUND IN DATABASE!")
            } else {
                println("Found ${tags.size} total tag entries:")
                tags.forEach { row ->
                    val entityId = row[EntityTagsTable.entityId]
                    val entityType = row[EntityTagsTable.entityType]
                    val tag = row[EntityTagsTable.tag]
                    val createdAt = row[EntityTagsTable.createdAt]

                    println("  - Entity: $entityType, ID: $entityId, Tag: '$tag', Created: $createdAt")
                }
            }
            println("====================================")
        }
    }

    /**
     * Runs SQL directly on the EntityTagsTable to test schema
     */
    fun testEntityTagsTableSchema() {
        transaction {
            println("Testing EntityTagsTable schema...")
            try {
                // Test basic schema validation
                exec("SELECT entity_id, entity_type, tag, created_at FROM entity_tags LIMIT 1")
                println("  - Schema validation: SUCCESS")
            } catch (e: Exception) {
                println("  - Schema validation: FAILED")
                println("  - Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}