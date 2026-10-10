package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import java.io.File
import java.io.RandomAccessFile

/**
 * The second OS process of [FlywayMigrationLockCrossProcessTest]: a plain `main` on the test runtime classpath.
 * It talks to the test over stdin/stdout only, one line per message.
 *
 * - `migrate <jdbcUrl>`: prints `READY`, waits for `GO` on stdin, runs the production startup migration
 *   ([FlywayDatabaseSchemaManager.updateSchema]) and prints `OUTCOME ok=<bool> applied=<n>`, where n is the
 *   migration count the manager logged (-1 when it logged none).
 * - `hold <lockFile>`: takes the OS lock on the file, prints `LOCKED`, waits for `RELEASE` (or end of stdin),
 *   releases it and prints `RELEASED`.
 */
object MigrationLockChildProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val stdin = System.`in`.bufferedReader()
        when (args.getOrNull(0)) {
            "migrate" -> {
                println("READY")
                System.out.flush()
                check(stdin.readLine() == "GO") { "expected GO" }
                var ok = false
                val logs = SchemaTestSupport.captureLogs { ok = FlywayDatabaseSchemaManager(args[1], repair = false).updateSchema() }
                println("OUTCOME ok=$ok applied=${appliedCount(logs)}")
            }
            "hold" -> {
                RandomAccessFile(File(args[1]), "rw").use { raf ->
                    val lock = raf.channel.lock()
                    println("LOCKED")
                    System.out.flush()
                    while (true) {
                        val line = stdin.readLine() ?: break
                        if (line == "RELEASE") break
                    }
                    lock.release()
                }
                println("RELEASED")
            }
            else -> error("unknown mode ${args.toList()}")
        }
        System.out.flush()
    }

    private val APPLIED = Regex("Successfully applied (\\d+) migration")

    /** The migration count from the manager's "Successfully applied N migration(s)" line, or -1. */
    fun appliedCount(logs: List<CapturedLog>): Int =
        logs
            .firstNotNullOfOrNull { APPLIED.find(it.text) }
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: -1
}
