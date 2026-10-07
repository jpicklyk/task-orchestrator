package io.github.jpicklyk.mcptask.current.infrastructure.repository

/**
 * Maximum number of bound variables placed in a single SQL `IN (...)` list.
 *
 * SQLite caps bound variables per statement at SQLITE_MAX_VARIABLE_NUMBER (250,000 in the bundled
 * xerial build, 32,766 in stock SQLite builds). Every `IN` list whose size scales with a subtree or
 * caller-supplied id set iterates `ids.chunked(SQL_IN_CHUNK_SIZE)` inside ONE transaction and merges
 * the results, so the statement count grows linearly but no single statement can hit the limit.
 * 500 is deliberate headroom well below any known build's limit, not a tuned optimum.
 */
internal const val SQL_IN_CHUNK_SIZE = 500
