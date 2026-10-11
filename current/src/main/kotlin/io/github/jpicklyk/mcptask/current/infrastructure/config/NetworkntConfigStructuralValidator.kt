package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import io.github.jpicklyk.mcptask.current.application.config.CanonicalConfigJson
import io.github.jpicklyk.mcptask.current.application.config.ConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation

/**
 * Checks a parsed config root against the published JSON Schema ([ConfigJsonSchema]) with networknt
 * json-schema-validator (draft 2020-12). The schema is compiled once; the validator holds no other state.
 *
 * Paths are RFC 6901 JSON Pointers. An unknown key (`additionalProperties: false`) is reported at the unknown key's
 * own path, so it lines up with the parser's finding for the same key.
 */
class NetworkntConfigStructuralValidator : ConfigStructuralValidator {
    private val mapper = ObjectMapper()

    private val schema: JsonSchema by lazy {
        val config = SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build()
        JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(ConfigJsonSchema.text, config)
    }

    override fun validate(root: Map<String, Any?>): List<ConfigViolation> {
        val instance: JsonNode = mapper.readTree(CanonicalConfigJson.encode(root))
        return schema
            .validate(instance)
            .map(::toViolation)
            .distinct()
            .sortedWith(compareBy({ it.path }, { it.reason }))
    }

    private fun toViolation(message: ValidationMessage): ConfigViolation {
        val location = message.instanceLocation.toString()
        val property = message.property
        val path =
            if (message.messageKey == "additionalProperties" && property != null) {
                location + "/" + ConfigValidator.escape(property)
            } else {
                location
            }
        val text = message.message
        val reason = if (text.startsWith("$location: ")) text.removePrefix("$location: ") else text
        return ConfigViolation(path, reason)
    }
}
