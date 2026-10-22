package io.github.jpicklyk.mcptask.current.application.config

/**
 * How the config parser classifies a finding that the 3.x parser used to skip softly (an unknown key, a note entry
 * missing `role`, an invalid lifecycle, ...).
 *
 * Extraction is identical in both modes: the same [ConfigDocument] comes out of the same input. The mode only decides
 * whether a former soft skip is reported as an error ([STRICT]) or as a warning ([MIGRATION]). Advisory findings
 * (resource fan-out, a required note no seat owns) are warnings in both modes, and a structural seat error (F1-F4) is
 * fatal in both.
 */
enum class ParseMode {
    /** The admission point for new config (push, validate): a former soft skip is an error. */
    STRICT,

    /** Reading config that already exists (stored 3.x versions, the global file, canonicalization): never rejects. */
    MIGRATION,
}
