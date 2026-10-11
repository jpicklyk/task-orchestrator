package io.github.jpicklyk.mcptask.current.infrastructure.config

import java.security.MessageDigest

/**
 * The published JSON Schema (draft 2020-12) for a config document: ONE hand-authored copy on the classpath at
 * [RESOURCE_PATH]. The structural validator compiles it, and `GET /api/v1/config/schema` serves exactly these bytes.
 */
object ConfigJsonSchema {
    /** Classpath location of the schema document. */
    const val RESOURCE_PATH = "/config/config.schema.json"

    /** The media type the schema is served as. */
    const val MEDIA_TYPE = "application/schema+json"

    /** The schema document's UTF-8 bytes, read once. */
    val bytes: ByteArray by lazy {
        ConfigJsonSchema::class.java.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes() }
            ?: error("The config JSON Schema is missing from the classpath at $RESOURCE_PATH")
    }

    /** The schema as text. */
    val text: String by lazy { String(bytes, Charsets.UTF_8) }

    /** A strong ETag (quoted SHA-256 prefix of [bytes]) that changes exactly when the schema does. */
    val etag: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        "\"cfgschema-" + digest.joinToString("") { "%02x".format(it) }.take(32) + "\""
    }
}
