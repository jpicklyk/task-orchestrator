package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import java.util.UUID

object WorkItemsTable : IdTable<UUID>("work_items") {
    // id's declared SQL type is aligned to Flyway's BLOB the same way as every other UUID
    // column; only its NOT NULL / randomblob(16) DB-side default stay an accepted exception —
    // Exposed's identity column has no DSL surface for a nullable-at-insert PK with a DB default.
    override val id: Column<EntityID<UUID>> = javaUuidSqlite("id").entityId()
    override val primaryKey = PrimaryKey(id)

    val parentId = javaUuidSqlite("parent_id").nullable()
    val rootId = javaUuidSqlite("root_id").nullable()
    val title = text("title")
    val description = text("description").nullable()
    val summary = text("summary").default("")
    val role = varchar("role", 20).default("queue")
    val statusLabel = text("status_label").nullable()
    val previousRole = varchar("previous_role", 20).nullable()
    val priority = varchar("priority", 20).default("medium")
    val complexity = integer("complexity").nullable()
    val requiresVerification = boolSqlite("requires_verification").default(false)
    val depth = integer("depth").default(0)
    val metadata = text("metadata").nullable()
    val tags = text("tags").nullable()
    val type = text("type").nullable()
    val properties = text("properties").nullable()
    val createdAt = timestampSqlite("created_at")
    val modifiedAt = timestampSqlite("modified_at")
    val roleChangedAt = timestampSqlite("role_changed_at")
    val version = long("version").default(1)
    val claimedBy = text("claimed_by").nullable()
    val claimedAt = timestamp("claimed_at").nullable()
    val claimExpiresAt = timestamp("claim_expires_at").nullable()
    val originalClaimedAt = timestamp("original_claimed_at").nullable()

    init {
        foreignKey(parentId to WorkItemsTable.id)
        index(isUnique = false, parentId)
        index(isUnique = false, rootId)
        index(isUnique = false, role)
        index(isUnique = false, depth)
        index(isUnique = false, priority)
        index(isUnique = false, columns = arrayOf(role, roleChangedAt))
        index(isUnique = false, claimedBy)
        index(isUnique = false, claimExpiresAt)

        // V7__FTS5_And_Unbounded_Depth.sql:37-38 — role enum CHECK, mirrored verbatim
        check("chk_work_items_role") {
            role.inList(listOf("queue", "work", "review", "blocked", "terminal"))
        }
        // V7__FTS5_And_Unbounded_Depth.sql:40 — previous_role NULL-or-enum CHECK, mirrored verbatim
        check("chk_work_items_previous_role") {
            previousRole.isNull() or previousRole.inList(listOf("queue", "work", "review", "blocked", "terminal"))
        }
        // V7__FTS5_And_Unbounded_Depth.sql:41-42 — priority enum CHECK, mirrored verbatim
        check("chk_work_items_priority") {
            priority.inList(listOf("high", "medium", "low"))
        }
    }
}
