package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.run.internal.wire.RunsWriteCodec
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsAckOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsAnswerOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsDelegateOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsEscalateOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsListSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsRaiseOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.decisionsWithdrawOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsAckOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsAnswerOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsAssignOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsCloseOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsDecisionSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsDecisionsSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsEndSeatOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsFrontierSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsGetSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsListSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsMarkOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsOpenOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsPauseOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsRaiseOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsReissueOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsResumeOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsSeatsSelect
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsUpdateOp
import io.github.jpicklyk.mcptask.current.run.internal.wire.runsWithdrawOp
import kotlinx.serialization.json.JsonObject

/** The run slice's contribution to the tool registry: the `runs_read` and `runs_write` tools and the `decisions_read` and `decisions_write` tools. */
object RunTools {
    /** The `runs_read` tool name. */
    const val RUNS_READ = "runs_read"

    /** The `runs_write` tool name. */
    const val RUNS_WRITE = "runs_write"

    /** The `decisions_read` tool name. */
    const val DECISIONS_READ = "decisions_read"

    /** The `decisions_write` tool name. */
    const val DECISIONS_WRITE = "decisions_write"

    /**
     * The runs_read tool: one run, a filtered page of runs, a run's seats, a run's frontier, a page of decisions or one
     * decision. Orchestration and director profiles (seats: orchestration; decisions and decision: director). The shared
     * description is the full (REST) form; each MCP profile gets its own, naming only the selects it serves.
     */
    fun read(): SelectToolSpec =
        SelectToolSpec(
            name = RUNS_READ,
            description =
                "Read runs, the bounded budgeted passes over a set of items. select: get (one run), list (runs, newest " +
                    "first), seats (a run's seat assignments), frontier (ready, in-flight and waiting members), decisions " +
                    "or decision. A run or decision is outside the call scope when its root is.",
            profileDescriptions =
                mapOf(
                    ToolProfile.ORCHESTRATION to
                        "Read runs, the bounded budgeted passes over a set of items. select: get (one run), list (runs, " +
                        "newest first), seats (a run's seat assignments) or frontier (ready, in-flight and waiting members). " +
                        "A run is outside the call scope when its root is.",
                    ToolProfile.DIRECTOR to
                        "Read runs, the bounded budgeted passes over a set of items. select: get (one run), list (runs, " +
                        "newest first), frontier (ready, in-flight and waiting members), decisions or decision (the decision " +
                        "inbox). A run or decision is outside the call scope when its root is."
                ),
            selects =
                listOf(
                    runsGetSelect,
                    runsListSelect,
                    runsSeatsSelect,
                    runsFrontierSelect,
                    runsDecisionsSelect,
                    runsDecisionSelect
                )
        )

    /**
     * The runs_write tool: open, update, close, pause, resume, reissue and mark runs, assign and end seats, and raise, acknowledge,
     * answer and withdraw decisions. Every op needs the runs capability except ack and answer, which need decide. mark is
     * REST-only (it is on no MCP profile); pause, resume, reissue, ack and answer are director ops; open, update, assign,
     * end_seat and raise are orchestration ops; close and withdraw are on both profiles. The shared description is the full (REST)
     * form; each MCP profile gets its own, naming only the ops it serves.
     */
    fun write(): OpsToolSpec<JsonObject> =
        OpsToolSpec(
            name = RUNS_WRITE,
            description =
                "Open, update, close, pause, resume, reissue and mark runs; assign and end seats; raise, ack, answer and withdraw " +
                    "decisions. Each op needs the runs capability (ack, answer: decide) and a root in scope. close: " +
                    "run token, director or a human; update, assign: run token; end_seat: the seat's own token; " +
                    "pause, resume and reissue: director or human only; mark: REST only. Result: the run (close adds snapshot, mark " +
                    "adds marked) or the decision.",
            profileDescriptions =
                mapOf(
                    ToolProfile.ORCHESTRATION to
                        "Open, update and close runs; assign and end seats; raise and withdraw decisions. Each op needs the " +
                        "runs capability and a root in scope. close: run token, director or a human; update, assign: " +
                        "run token; end_seat: the seat's own token. Result: the run (close adds snapshot) or the decision.",
                    ToolProfile.DIRECTOR to
                        "Close, pause, resume and reissue runs; ack, answer and withdraw decisions. Each op needs the " +
                        "runs capability (ack, answer: decide) and a root in scope. close: run token, director or a human; " +
                        "pause, resume and reissue: director or human only. Result: the run (close adds snapshot) or the decision."
                ),
            ops =
                listOf(
                    runsOpenOp,
                    runsUpdateOp,
                    runsCloseOp,
                    runsPauseOp,
                    runsResumeOp,
                    runsReissueOp,
                    runsMarkOp,
                    runsAssignOp,
                    runsEndSeatOp,
                    runsRaiseOp,
                    runsAckOp,
                    runsAnswerOp,
                    runsWithdrawOp
                ),
            codec = RunsWriteCodec,
            element = { it }
        )

    /**
     * The decisions_read tool: the decision inbox, one `list` select. Orchestration and director profiles; the shared description is
     * the full (REST) form. `runs_read decisions` and `decision` are its deprecated aliases (ADR 0005).
     */
    fun decisionsRead(): SelectToolSpec =
        SelectToolSpec(
            name = DECISIONS_READ,
            description =
                "Read decisions, the questions raised for an answer. select: list (decisions, newest first, with the " +
                    "decision classes in force on their roots). A decision is outside the call scope when its root is.",
            selects = listOf(decisionsListSelect)
        )

    /**
     * The decisions_write tool: raise, ack, delegate, escalate, answer and withdraw decisions. raise, delegate and withdraw
     * are orchestration ops; ack, delegate, escalate, answer and withdraw are director ops. The shared description is the
     * full (REST) form; each MCP profile gets its own, naming only the ops it serves. `runs_write` raise, ack, answer and
     * withdraw are its deprecated aliases (ADR 0005).
     */
    fun decisionsWrite(): OpsToolSpec<JsonObject> =
        OpsToolSpec(
            name = DECISIONS_WRITE,
            description =
                "Raise, ack, delegate, escalate, answer and withdraw decisions. Each op needs a root in scope and the runs " +
                    "capability (ack, answer, delegate, escalate: decide). Only the answerers of a decision's class ack, " +
                    "answer, delegate or escalate it; withdraw: its raiser or the run token of its run. Result: the decision.",
            profileDescriptions =
                mapOf(
                    ToolProfile.ORCHESTRATION to
                        "Raise, delegate and withdraw decisions. Each op needs a root in scope and the runs capability " +
                        "(delegate: decide). Only the answerers of a decision's class delegate it; withdraw: its raiser or " +
                        "the run token of its run. Result: the decision.",
                    ToolProfile.DIRECTOR to
                        "Ack, delegate, escalate, answer and withdraw decisions. Each op needs a root in scope and the " +
                        "decide capability (withdraw: runs). Only the answerers of a decision's class ack, answer or " +
                        "delegate it; the director also escalates a human-only decision. Result: the decision."
                ),
            ops =
                listOf(
                    decisionsRaiseOp,
                    decisionsAckOp,
                    decisionsDelegateOp,
                    decisionsEscalateOp,
                    decisionsAnswerOp,
                    decisionsWithdrawOp
                ),
            codec = RunsWriteCodec,
            element = { it }
        )
}
