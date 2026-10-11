package io.github.jpicklyk.mcptask.current.application.port

/**
 * The mounted global config file as raw text, for the `server-config-import` data step. Kept as a port so the
 * application layer never touches the filesystem.
 */
interface ConfigFileSource {
    /** Where the text comes from, for log and error messages (a file path). */
    val description: String

    /** The file's UTF-8 text exactly as read (a BOM stays as U+FEFF), or null when the file is absent. */
    fun text(): String?
}
