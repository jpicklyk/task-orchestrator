package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.service.JsonElementCodec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** The `config` tool name. */
const val CONFIG_TOOL = "config"

/**
 * The `config` tool (plan section 4.1, Q23): read, validate, write and inspect per-root config and rule text in
 * one `ops[]` call. It replaces `manage_project_config`, `query_rules` and the per-root config, rule and
 * config-discovery REST routes. Each op lives in its own file; every op is orchestration-only.
 */
fun configTool(): OpsToolSpec<JsonElement> =
    OpsToolSpec(
        name = CONFIG_TOOL,
        description =
            "Per-root config and rules. Each ops[] element has an op: get, history, push, validate, rollback, reset " +
                "(a root's versioned config document), effective (the resolved view), or rules (rule text). Reads need " +
                "the read capability, push, rollback and reset the write-config capability. rootId is a depth-0 item.",
        ops =
            listOf(
                configGetOp,
                configHistoryOp,
                configPushOp,
                configValidateOp,
                configRollbackOp,
                configResetOp,
                configEffectiveOp,
                configRulesOp
            ),
        codec = JsonElementCodec,
        element = { it.jsonObject }
    )
