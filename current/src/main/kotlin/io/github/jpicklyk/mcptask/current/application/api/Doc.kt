package io.github.jpicklyk.mcptask.current.application.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * The single documentation source of a 4.0 request field (W2 brief section 3.3).
 *
 * On a property, [text] becomes that field's `description` in the generated JSON Schema. On a class,
 * it is the description used for any property of that type that has no `@Doc` of its own. The
 * annotation is a [SerialInfo], so it is read from the serial descriptor and no reflection is needed.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Doc(
    val text: String
)
