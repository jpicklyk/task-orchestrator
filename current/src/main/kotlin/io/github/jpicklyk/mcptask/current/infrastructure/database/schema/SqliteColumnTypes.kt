package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.BasicUuidColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.InstantColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import java.time.Instant
import java.util.UUID

// SQLite-conditional column types. These correct ONLY the DDL type NAME SchemaUtils.create(...)
// emits for the SQLite dialect, so Exposed-generated DDL matches the Flyway migration SQL's
// declared column type verbatim (SchemaParityTest diffs the two). Read/write behavior is
// unchanged: every value conversion delegates to Exposed's own column type for the same Kotlin
// type (JavaInstantColumnType / UUIDColumnType / BooleanColumnType) — only sqlType() differs, and
// only on SQLite. H2 (the JVM-only repository test dialect) is untouched: sqlType() falls through
// to the delegate's own sqlType() for every dialect other than SQLite.
//
// Deliberately NOT applied to any UUIDTable `id` (primary-key) column: Exposed generates `id`
// internally as part of UUIDTable's identity machinery (BINARY(16) NOT NULL, no DB-side default),
// with no column-declaration call site in these table objects to intercept short of
// reimplementing UUIDTable's identity column entirely. The Flyway shape
// (`id BLOB PRIMARY KEY DEFAULT (randomblob(16))` — nullable-at-insert with a DB-generated
// default) has no Exposed DSL equivalent regardless of sqlType() text. This is a declared,
// accepted exception (asserted by the parity test) and a follow-up migration-adjacent item, not
// an oversight.

/**
 * `sqlType()` "TIMESTAMP" on SQLite, delegating to [JavaInstantColumnType] otherwise. Matches
 * columns whose Flyway declaration is TIMESTAMP (createdAt/modifiedAt/roleChangedAt-shaped
 * columns) — NOT the claim-style TEXT ISO-8601 columns (e.g. WorkItemsTable's claimedAt /
 * claimExpiresAt / originalClaimedAt, and the equivalent ResourceLeasesTable /
 * ResourceLeaseHistoryTable columns), whose own Flyway declaration is already TEXT and which stay
 * on plain `timestamp()`.
 */
class SqliteInstantColumnType : InstantColumnType<Instant>() {
    private val delegate = JavaInstantColumnType()

    override fun toInstant(value: Instant): kotlin.time.Instant = delegate.toInstant(value)

    override fun fromInstant(value: kotlin.time.Instant): Instant = delegate.fromInstant(value)

    override fun sqlType(): String = if (currentDialect is SQLiteDialect) "TIMESTAMP" else delegate.sqlType()
}

/**
 * `sqlType()` "BLOB" on SQLite, delegating to [UUIDColumnType] otherwise. Matches every non-id
 * UUID reference column (Flyway declares all of them BLOB).
 */
class SqliteUuidColumnType : BasicUuidColumnType<UUID>() {
    private val delegate = UUIDColumnType()

    override fun valueFromDB(value: Any): UUID = delegate.valueFromDB(value)

    override fun notNullValueToDB(value: UUID): Any = delegate.notNullValueToDB(value)

    override fun sqlType(): String = if (currentDialect is SQLiteDialect) "BLOB" else delegate.sqlType()
}

/**
 * `sqlType()` "INTEGER" on SQLite, delegating to [BooleanColumnType] otherwise. Matches
 * `requires_verification` (Flyway declares it `INTEGER NOT NULL DEFAULT 0` — SQLite has no native
 * BOOLEAN storage class).
 */
class SqliteBooleanColumnType : ColumnType<Boolean>() {
    private val delegate = BooleanColumnType()

    override fun valueFromDB(value: Any): Boolean = delegate.valueFromDB(value)

    override fun notNullValueToDB(value: Boolean): Any = delegate.notNullValueToDB(value)

    override fun nonNullValueToString(value: Boolean): String = delegate.nonNullValueToString(value)

    override fun sqlType(): String = if (currentDialect is SQLiteDialect) "INTEGER" else delegate.sqlType()
}

fun Table.timestampSqlite(name: String): Column<Instant> = registerColumn(name, SqliteInstantColumnType())

fun Table.javaUuidSqlite(name: String): Column<UUID> = registerColumn(name, SqliteUuidColumnType())

fun Table.boolSqlite(name: String): Column<Boolean> = registerColumn(name, SqliteBooleanColumnType())
