package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import java.util.UUID

object WorkItemsTable : IdTable<UUID>("work_items") {
    // id's declared SQL type is aligned to Flyway's BLOB the same way as every other UUID
    // column; only its NOT NULL / randomblob(16) DB-side default stay an accepted exception —
    // Exposed's identity column has no DSL surface for a nullable-at-insert PK with a DB default.
    override val id: Column<EntityID<UUID>> = javaUuidSqlite("id").autoGenerate().entityId()
    override val primaryKey = PrimaryKey(id)

    val parentId = javaUuidSqlite("parent_id").nullable()
    val rootId = javaUuidSqlite("root_id").nullable()
    val title = text("title")
    val description = text("description").nullable()
    val summary = text("summary").default("")
    val role = varchar("role", 20).default("queue").nullable()
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
    val createdAt = utcTimestamp("created_at")
    val modifiedAt = utcTimestamp("modified_at")
    val roleChangedAt = utcTimestamp("role_changed_at")
    val version = long("version").default(1)
    val claimedBy = text("claimed_by").nullable()
    val claimedAt = utcTimestampText("claimed_at").nullable()
    val claimExpiresAt = utcTimestampText("claim_expires_at").nullable()
    val originalClaimedAt = utcTimestampText("original_claimed_at").nullable()

    // V26: lifecycle and the config/schema pin set (all NULL until Q24/Q31b write them).
    val lifecycle = text("lifecycle").nullable()
    val pinnedServerCfg = javaUuidSqlite("pinned_server_cfg").nullable()
    val pinnedRootCfg = javaUuidSqlite("pinned_root_cfg").nullable()
    val pinnedSchemaKey = text("pinned_schema_key").nullable()
    val pinnedSchemaLayer = text("pinned_schema_layer").nullable()
    val pinnedAt = utcTimestampText("pinned_at").nullable()

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

        // V26 role enum CHECK (nullable since U13a), mirrored
        check("chk_work_items_role") {
            role.isNull() or role.inList(listOf("queue", "work", "review", "blocked", "terminal"))
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
