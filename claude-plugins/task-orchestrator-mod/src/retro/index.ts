// Retrospective dispatch in-process (T5, ported to 4.0 run marks).
// Owned by work item 0f4fe024-9c3f-454c-83f7-ec13de173355.
//
// In-process port of retro-trigger.mjs (PostToolUse on runs_write) and retro-backstop.mjs (Stop). State
// lives in session-scoped `$.state` (it survives /compact and hot reload), the cross-session dedupe is the
// server's `retro` run mark (first write wins), and the retrospective is spawned with `$.agent.spawn` at
// the run boundary instead of being handed to the model as a directive.
//
//   tool.call (runs_write)   a successful close: in a subagent or without a project rootId, record it;
//                            else evaluate the root's closed, unmarked runs (runs_read list + frontier,
//                            REST runs_write mark) and add the one-line "queued" notice or a nudge to the
//                            result's context
//   classic.PostToolUse      forward `to_mod_retro` so retro-trigger.mjs steps aside
//   classic.Stop             spawn the queued retrospective once nothing runs in the background; else
//                            evaluate again (rootId) or nudge once from recorded closes (no rootId)
// Reads go through the engine's own MCP connection (the mod's own calls are skipped by every mod hook, own
// origin); the mark is REST only on the server, so it is a curl POST to /api/v1/ops/runs_write, and a failed
// mark (no API URL, curl error, bad body, error envelope) nudges, never dispatches unmarked.
//
// Ownership: the mod owns retro handling only when the session is not a headless iteration AND the
// cwd config is readable (the mod cannot see AGENT_CONFIG_DIR fallbacks such as the main-checkout or user
// configs, and must not override a config it cannot see). Otherwise every hook passes through untouched
// and the command hooks act as before. The flag is feature-scoped (`to_mod_retro`), so it cannot silence
// another feature's command hook.
//
// This is the only file here that touches `$` (the validator follows `$` only into functions
// declared in the same file); logic.ts is pure.
import type { EngineInterface, On } from 'claude-code'

import type { RetroState } from '../../types'
import { CONFIG_PATH } from '../shared/config.ts'
import { PLUGIN, TO_SERVER, toToolName } from '../shared/constants.ts'
import { parseToResult } from '../shared/to-client.ts'
import { resolveApiUrl } from '../graph-data/live.ts'
import { readSection, scalar } from '../lib/yaml-lite.mjs'
import {
  QUEUED_NOTICE,
  buildDispatch,
  buildNudge,
  buildSpawnPrompt,
  evaluateRuns,
  holdsForTasks,
  markRequest,
  normalizeState,
  parseRetrospectiveConfig,
  recordCloses,
  successfulCloses,
} from './logic.ts'
import type { RetroCall, RetroConfig } from './logic.ts'

/** Added to a classic.PostToolUse / classic.Stop event so the retro command hooks exit early. */
export const RETRO_OWNED_FLAG = 'to_mod_retro'

const MAX_RETRO_AGENTS = 20

/** The one tool the retro hooks care about: runs_write (a run close). */
const RETRO_TOOLS = /^mcp__.*task-orchestrator.*__runs_write$/

type Ownership = { owned: boolean; cfg: RetroConfig; ancestorId: string | null }
type Held = { state: RetroState; version: number }

async function ownership($: EngineInterface): Promise<Ownership> {
  const none: Ownership = { owned: false, cfg: parseRetrospectiveConfig(null), ancestorId: null }
  if ((await $.env.get('TASK_ORCHESTRATOR_MODE')) === 'headless-iteration') return none
  // Same first step as the command hooks' config-locator: $AGENT_CONFIG_DIR's config wins when readable.
  // The rest of the locator (walk-up, main checkout of a worktree, user-level config) is not mirrored:
  // the mod reads only the cwd-relative config, so ownership is claimed only for what it can see.
  let text: string | null = null
  const dir = await $.env.get('AGENT_CONFIG_DIR')
  if (typeof dir === 'string' && dir.length > 0) {
    try {
      text = await $.fs.read(`${dir.replace(/[\\/]+$/, '')}/${CONFIG_PATH}`)
    } catch {
      text = null
    }
  }
  if (text === null) {
    try {
      text = await $.fs.read(CONFIG_PATH)
    } catch {
      return none
    }
  }
  const cfg = parseRetrospectiveConfig(text)
  const section = readSection(text, 'project', { blockOnly: true })
  const id = section ? scalar(section.lines, 'rootId') : null

  return { owned: cfg.mode !== 'off', cfg, ancestorId: typeof id === 'string' && id.length > 0 ? id : null }
}

async function load($: EngineInterface): Promise<Held> {
  const got = await $.state.get({ plugin: 'task-orchestrator-mod', key: 'retro' } as const)

  return { state: normalizeState(got.value), version: got.version }
}

async function save($: EngineInterface, state: RetroState, ifVersion?: number): Promise<boolean> {
  const res = await $.state.set({ plugin: 'task-orchestrator-mod', key: 'retro' } as const, state, ifVersion === undefined ? undefined : { ifVersion })

  return res.isSet
}

/**
 * A TO call returning the envelope `data` (rejects on an error envelope). Reads go over the engine's MCP
 * connection; the one write, `runs_write` `mark`, is REST only on the server, so it is POSTed with curl
 * (markRequest: the token rides curl's stdin, never argv, and never enters an error string).
 */
function toCall($: EngineInterface): RetroCall {
  return async (tool, args) => {
    if (tool !== 'runs_write') return parseToResult<unknown>(tool, await $.mcp.call(TO_SERVER, tool, args))
    const base = await resolveApiUrl({
      envApiUrl: () => $.env.get('TASK_ORCHESTRATOR_API_URL'),
      readFile: p => $.fs.read(p),
      homeDir: async () => (await $.env.get('TASK_ORCHESTRATOR_HOME')) || (await $.env.get('HOME')) || (await $.env.get('USERPROFILE')),
    })
    if (base === null) throw new Error('runs_write: no REST API')
    const ops = (args as { ops?: { runId?: unknown }[] }).ops
    const runId = Array.isArray(ops) && typeof ops[0]?.runId === 'string' ? ops[0].runId : ''
    const token = await $.env.get('TASK_ORCHESTRATOR_API_TOKEN')
    const { argv, input } = markRequest(base, runId, token)
    const ran = await $.process.run(argv, { stdin: input, timeoutMs: 3000 })
    if (ran.exitCode !== 0) throw new Error(`runs_write: curl exit ${ran.exitCode}`)
    let body: unknown
    try {
      body = JSON.parse(ran.stdout)
    } catch {
      throw new Error('runs_write: unparsable response')
    }

    return parseToResult<unknown>('runs_write', body)
  }
}

/** True for a retrospective agent the mod spawned and for any agent it descends from one. */
async function isRetroAgent($: EngineInterface, agentId: string | undefined, state: RetroState): Promise<boolean> {
  const mine = state.retroAgents ?? []
  if (agentId === undefined || mine.length === 0) return false
  if (mine.includes(agentId)) return true
  const list = await $.agent.list()
  let cur = agentId
  for (let i = 0; i < 10; i++) {
    const parent = list.find(a => a.id === cur)?.parentId
    if (parent === undefined) return false
    if (mine.includes(parent)) return true
    cur = parent
  }

  return false
}

function withContext<T extends object>(r: T, line: string): T {
  const ctx = (r as { context?: readonly string[] }).context ?? []

  return { ...r, context: [...ctx, line] }
}

function withBlock<T extends object>(r: T, text: string): T {
  const prior = (r as { block?: string }).block

  return { ...r, block: prior ? `${prior}\n\n${text}` : text }
}

/**
 * Spawns one retrospective agent for `runs` and records it. False when the spawn was denied or threw (the
 * caller then falls back to a Stop block carrying buildDispatch).
 */
async function spawnRetro($: EngineInterface, runs: readonly string[], ancestorId: string | null): Promise<boolean> {
  let agentId: string | undefined
  try {
    const spawned = await $.agent.spawn({
      subagentType: 'general-purpose',
      model: 'sonnet',
      description: 'Session retrospective',
      prompt: buildSpawnPrompt(runs, ancestorId),
    })
    // Core sets agentId on a started agent; a denial carries `deny` and starts none.
    if (spawned.deny !== undefined) return false
    agentId = spawned.agentId
  } catch {
    return false
  }
  const now = await $.clock.now()
  const cur = (await load($)).state
  await save($, {
    ...cur,
    dispatched: { ...(agentId !== undefined && { agentId }), runs: [...runs], at: now },
    ...(agentId !== undefined && { retroAgents: [...(cur.retroAgents ?? []), agentId].slice(-MAX_RETRO_AGENTS) }),
  })

  return true
}

/** Whether background work is running: the Stop event's own list, else the agent list. */
async function isBusy($: EngineInterface, tasks: readonly { status: string }[] | undefined): Promise<boolean> {
  return tasks !== undefined ? holdsForTasks(tasks) : holdsForTasks(await $.agent.list())
}

export function registerRetro(on: On): void {
  on('tool.call', { tool: RETRO_TOOLS }, async ($, e, next) => {
    if (next.origin?.plugin === PLUGIN) return next(e)
    if (toToolName(e.tool) !== 'runs_write') return next(e)

    let own: Ownership
    let retro: boolean
    try {
      own = await ownership($)
      if (!own.owned) return next(e)
      retro = await isRetroAgent($, e.agentId, (await load($)).state)
    } catch {
      return next(e)
    }
    if (retro) return next(e)

    const r = await next(e)
    // `isError` is not consulted: a batch with a failed element is isError although its closes succeeded.
    if (r.deny !== undefined) return r
    try {
      const closes = successfulCloses(e, r)
      if (closes === null) return r
      const cur = (await load($)).state
      // A subagent never acts, and without a project rootId there is nothing to evaluate: record the close
      // for the main session's Stop.
      if (e.agentId !== undefined || own.ancestorId === null) {
        await save($, recordCloses(cur, closes))

        return r
      }
      const result = await evaluateRuns(toCall($), own.ancestorId, own.cfg, cur, await $.clock.now())
      const updated: RetroState = result.won.length > 0 ? { ...result.state, pendingDispatch: [...new Set([...(result.state.pendingDispatch ?? []), ...result.won])] } : result.state
      if (updated !== cur) await save($, updated)
      if (result.won.length > 0) return withContext(r, QUEUED_NOTICE)
      if (result.nudge.length > 0) return withContext(r, buildNudge(result.nudge))
    } catch {
      return r
    }

    return r
  })

  on('classic.PostToolUse', { tool_name: RETRO_TOOLS }, async ($, e, next) => {
    if (toToolName(e.tool_name) !== 'runs_write' || next.origin?.plugin === PLUGIN) return next(e)
    try {
      if (!(await ownership($)).owned) return next(e)
    } catch {
      return next(e)
    }

    return next({ ...e, [RETRO_OWNED_FLAG]: true } as never)
  })

  on('classic.Stop', async ($, e, next) => {
    if (next.origin?.plugin === PLUGIN) return next(e)
    let own: Ownership
    try {
      own = await ownership($)
      if (!own.owned) return next(e)
    } catch {
      return next(e)
    }
    // Other plugins' Stop hooks run first, whatever this one decides.
    const r = await next({ ...e, [RETRO_OWNED_FLAG]: true } as never)
    if (e.stop_hook_active) return r

    try {
      const { state, version } = await load($)

      const queued = state.pendingDispatch ?? []
      if (queued.length > 0) {
        // Hold while work runs in the background; the Stop that ends the wake-up turn re-checks.
        if (await isBusy($, e.background_tasks)) return r
        // Claim it: only the Stop whose write lands at `version` spawns.
        if (!(await save($, { ...state, pendingDispatch: null }, version))) return r
        if (!(await spawnRetro($, queued, own.ancestorId))) return withBlock(r, buildDispatch(queued, own.ancestorId))

        return r
      }

      if (own.ancestorId !== null) {
        const result = await evaluateRuns(toCall($), own.ancestorId, own.cfg, state, await $.clock.now())
        if (result.won.length > 0) {
          if (await isBusy($, e.background_tasks)) {
            await save($, { ...result.state, pendingDispatch: [...result.won] })

            return r
          }
          if (result.state !== state) await save($, result.state)
          if (!(await spawnRetro($, result.won, own.ancestorId))) return withBlock(r, buildDispatch(result.won, own.ancestorId))

          return r
        }
        if (result.nudge.length > 0) {
          await save($, result.state)

          return withBlock(r, buildNudge(result.nudge))
        }

        return r
      }

      // No project rootId: nudge once per session from the closes this session recorded.
      if (!state.sawClose || state.backstopNudged) return r
      await save($, { ...state, backstopNudged: true })

      return withBlock(r, buildNudge(state.closes ?? []))
    } catch {
      return r
    }
  })
}
