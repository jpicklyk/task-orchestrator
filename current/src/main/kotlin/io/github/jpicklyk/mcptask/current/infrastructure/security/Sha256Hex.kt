package io.github.jpicklyk.mcptask.current.infrastructure.security

import io.github.jpicklyk.mcptask.current.application.support.sha256Hex

/**
 * Normalizes [configYaml] for fingerprinting: strips exactly one leading UTF-8 BOM (U+FEFF) if
 * present, then replaces every CRLF (`\r\n`) with LF (`\n`). Nothing else is touched — a lone
 * `\r`, trailing-newline presence/count, trailing whitespace, a non-leading U+FEFF, and a second
 * leading BOM are all left as-is. This mirrors `config-sync.mjs`'s `normalizeForFingerprint`
 * exactly; the two implementations must never diverge.
 */
fun normalizeConfigForFingerprint(configYaml: String): String = configYaml.removePrefix("﻿").replace("\r\n", "\n")

/**
 * Computes the config-fingerprint hash: lowercase-hex SHA-256 of [normalizeConfigForFingerprint]'s
 * result, UTF-8 encoded. This is the single server-side definition of the config-fingerprint hash:
 * fingerprints are compared for equality between the client (`config-sync.mjs`'s
 * `configFingerprint`) and the server
 * ([io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteConfigStore.computeFingerprint]
 * and [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService]'s global-config
 * fingerprint) — every server-side config-fingerprint call site MUST route through this function so
 * the two sides can never silently diverge on BOM/CRLF handling (e.g. a Windows checkout with CRLF
 * line endings must fingerprint identically to an LF checkout of the same content). The STORED
 * config body is never normalized — only the value fed into the hash.
 */
fun configFingerprint(configYaml: String): String = sha256Hex(normalizeConfigForFingerprint(configYaml).toByteArray(Charsets.UTF_8))
