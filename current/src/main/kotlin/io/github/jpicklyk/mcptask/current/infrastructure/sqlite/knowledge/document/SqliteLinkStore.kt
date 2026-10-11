package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.document

import io.github.jpicklyk.mcptask.current.application.port.LinkStore
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQL_IN_CHUNK_SIZE
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.LinksTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * SQLite implementation of [LinkStore] over [LinksTable].
 *
 * A link whose target is unresolved ([Link.to] null) is stored with `to_kind = 'document'` (the only kind an
 * unresolved reference can name today) and `to_id` NULL; the partial unique index covers resolved links only.
 */
class SqliteLinkStore(
    private val databaseManager: DatabaseManager
) : LinkStore {
    override suspend fun insert(link: Link): Link {
        require(link.to != null || link.toRef != null) { "a link needs a resolved target or a target reference" }
        return databaseManager.writeTx("LinkStore.insert") {
            LinksTable.insert {
                it[id] = link.id
                it[rootItemId] = link.rootItemId
                it[fromKind] = link.from.kind.toDbValue()
                it[fromId] = link.from.id
                it[toKind] = (link.to?.kind ?: RefKind.DOCUMENT).toDbValue()
                it[toId] = link.to?.id
                it[toRef] = link.toRef
                it[relation] = link.relation.toDbValue()
                it[anchor] = link.anchor
                it[createdAt] = link.createdAt
            }
            LinksTable
                .selectAll()
                .where { LinksTable.id eq link.id }
                .single()
                .let(::toLink)
        }
    }

    override suspend fun findFrom(ref: EntityRef): List<Link> =
        databaseManager.readTx {
            LinksTable
                .selectAll()
                .where { (LinksTable.fromKind eq ref.kind.toDbValue()) and (LinksTable.fromId eq ref.id) }
                .orderBy(LinksTable.createdAt, SortOrder.ASC)
                .map(::toLink)
        }

    override suspend fun findTo(ref: EntityRef): List<Link> =
        databaseManager.readTx {
            LinksTable
                .selectAll()
                .where { (LinksTable.toKind eq ref.kind.toDbValue()) and (LinksTable.toId eq ref.id) }
                .orderBy(LinksTable.createdAt, SortOrder.ASC)
                .map(::toLink)
        }

    override suspend fun findToAny(
        refs: List<EntityRef>,
        relation: LinkRelation
    ): List<Link> {
        if (refs.isEmpty()) return emptyList()
        return databaseManager.readTx {
            refs
                .groupBy({ it.kind }, { it.id })
                .flatMap { (kind, ids) ->
                    ids.distinct().chunked(SQL_IN_CHUNK_SIZE).flatMap { chunk ->
                        LinksTable
                            .selectAll()
                            .where {
                                (LinksTable.fromKind eq kind.toDbValue()) and
                                    (LinksTable.fromId inList chunk) and
                                    (LinksTable.relation eq relation.toDbValue())
                            }.map(::toLink)
                    }
                }.sortedBy { it.createdAt }
        }
    }

    override suspend fun deleteFrom(
        ref: EntityRef,
        relation: LinkRelation?
    ): Int =
        databaseManager.writeTx("LinkStore.deleteFrom") {
            val kind = ref.kind.toDbValue()
            LinksTable.deleteWhere {
                val from = (LinksTable.fromKind eq kind) and (LinksTable.fromId eq ref.id)
                if (relation == null) from else from and (LinksTable.relation eq relation.toDbValue())
            }
        }

    override suspend fun deleteByIds(ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return databaseManager.writeTx("LinkStore.deleteByIds") {
            ids.distinct().chunked(SQL_IN_CHUNK_SIZE).sumOf { chunk -> LinksTable.deleteWhere { LinksTable.id inList chunk } }
        }
    }

    override suspend fun findDangling(
        rootItemId: UUID,
        toRefs: Collection<String>
    ): List<Link> {
        if (toRefs.isEmpty()) return emptyList()
        return databaseManager.readTx {
            toRefs
                .distinct()
                .chunked(SQL_IN_CHUNK_SIZE)
                .flatMap { chunk ->
                    LinksTable
                        .selectAll()
                        .where {
                            (LinksTable.rootItemId eq rootItemId) and
                                LinksTable.toId.isNull() and
                                (LinksTable.toRef inList chunk)
                        }.map(::toLink)
                }.sortedBy { it.createdAt }
        }
    }

    override suspend fun resolve(
        id: UUID,
        target: EntityRef
    ): Boolean =
        databaseManager.writeTx("LinkStore.resolve") {
            LinksTable.update({ (LinksTable.id eq id) and LinksTable.toId.isNull() }) {
                it[toKind] = target.kind.toDbValue()
                it[toId] = target.id
            } == 1
        }

    override suspend fun unresolveTo(ref: EntityRef): Int =
        databaseManager.writeTx("LinkStore.unresolveTo") {
            val kind = ref.kind.toDbValue()
            val deleted =
                LinksTable.deleteWhere {
                    (LinksTable.toKind eq kind) and (LinksTable.toId eq ref.id) and LinksTable.toRef.isNull()
                }
            val dangled =
                LinksTable.update({ (LinksTable.toKind eq kind) and (LinksTable.toId eq ref.id) }) {
                    it[toId] = null
                }
            deleted + dangled
        }

    private fun toLink(row: ResultRow): Link {
        val toId = row[LinksTable.toId]
        return Link(
            id = row[LinksTable.id],
            rootItemId = row[LinksTable.rootItemId],
            from = EntityRef(RefKind.fromDbValue(row[LinksTable.fromKind]), row[LinksTable.fromId]),
            to = toId?.let { EntityRef(RefKind.fromDbValue(row[LinksTable.toKind]), it) },
            toRef = row[LinksTable.toRef],
            relation = LinkRelation.fromDbValue(row[LinksTable.relation]),
            anchor = row[LinksTable.anchor],
            createdAt = row[LinksTable.createdAt]
        )
    }
}
