// Retrospective dispatch in-process (T5).
// Owned by work item 0f4fe024-9c3f-454c-83f7-ec13de173355.
//
// In-process port of retro-trigger.mjs (PostToolUse) and retro-backstop.mjs (Stop). State lives in
// session-scoped `$.state` (it survives /compact and hot reload and is never keyed by rootId), and
// the retrospective is spawned with `$.agent.spawn` at the run boundary instead of being handed to
// the model as a directive.
//
//   tool.call (advance_item / complete_tree)  classify, update state, add a nudge or the one-line
//                                             "queued" notice to the result's context
//   tool.call (Bash running retro-ack.mjs)    ack the mod's state
//   classic.PostToolUse                       forward `to_mod_retro` so retro-trigger.mjs steps aside
//   classic.Stop                              spawn the queued retrospective once nothing runs in the
//                                             background; else the nudge-only backstop
//
// Ownership: the mod owns retro handling only when the session is not a headless iteration AND the
// cwd config is readable (the mod cannot see AGENT_CONFIG_DIR / main-checkout / user configs, and
// must not override a config it cannot see). Otherwise every hook passes through untouched and the
// command hooks act as before. The flag is feature-scoped (`to_mod_retro`), so it cannot silence
// another feature's command hook.
//
// This is the only file here that touches `$` (the validator follows `$` only into functions
// declared in the same file); logic.ts is pure.
import type { EngineInterface, On } from 'claude-code'

import type { RetroState } from '../../types'
import { CONFIG_PATH } from '../shared/config.ts'
import { PLUGIN, toToolName } from '../shared/constants.ts'
import { readSection, scalar } from '../lib/yaml-lite.mjs'
import {
  ackState,
  afterBackstop,
  applyCall,
  backstopRoots,
  buildDispatch,
  buildNudge,
  buildSpawnPrompt,
  classifyCall,
  extractResponseJson,
  holdsForTasks,
  normalizeState,
  parseRetrospectiveConfig,
  QUEUED_NOTICE,
} from './logic.ts'
import type { RetroConfig } from './logic.ts'

/** Added to a classic.PostToolUse / classic.Stop event so the retro command hooks exit early. */
export const RETRO_OWNED_FLAG = 'to_mod_retro'

const MAX_RETRO_AGENTS = 20

/** The tools the retro hooks care about: advance_item, complete_tree, and Bash (the retro-ack.mjs call). Matchers keep this feature's hooks distinct from the other features' registrations. */
const RETRO_TOOLS = /^(?:Bash|mcp__.*task-orchestrator.*__(?:advance_item|complete_tree))$/

type Ownership = { owned: boolean; cfg: RetroConfig; ancestorId: string | null }
type Held = { state: RetroState; version: number }

async function ownership($: EngineInterface): Promise<Ownership> {
  const none: Ownership = { owned: false, cfg: parseRetrospectiveConfig(null), ancestorId: null }
  if ((await $.env.get('TASK_ORCHESTRATOR_MODE')) === 'headless-iteration') return none
  let text: string
  try {
    text = await $.fs.read(CONFIG_PATH)
  } catch {
    return none
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

function parseResponse(r: { text?: string; result?: unknown }): Record<string, unknown> | null {
  if (typeof r.text === 'string') {
    try {
      const parsed = JSON.parse(r.text)
      if (parsed !== null && typeof parsed === 'object') return parsed as Record<string, unknown>
    } catch {
      // fall through to the structured result
    }
  }

  return extractResponseJson(r.result)
}

function withContext<T extends object>(r: T, line: string): T {
  const ctx = (r as { context?: readonly string[] }).context ?? []

  return { ...r, context: [...ctx, line] }
}

function withBlock<T extends object>(r: T, text: string): T {
  const prior = (r as { block?: string }).block

  return { ...r, block: prior ? `${prior}\n\n${text}` : text }
}

export function registerRetro(on: On): void {
  on('tool.call', { tool: RETRO_TOOLS }, async ($, e, next) => {
    if (next.origin?.plugin === PLUGIN) return next(e)
    const name = toToolName(e.tool)
    const command = e.tool === 'Bash' ? (e as unknown as { command?: unknown }).command : undefined
    const isAck = typeof command === 'string' && command.includes('retro-ack.mjs')
    if (name !== 'advance_item' && name !== 'complete_tree' && !isAck) return next(e)

    let own: Ownership
    let held: Held
    let retro: boolean
    try {
      own = await ownership($)
      if (!own.owned) return next(e)
      held = await load($)
      retro = await isRetroAgent($, e.agentId, held.state)
    } catch {
      return next(e)
    }

    if (isAck) {
      const r = await next(e)
      try {
        const now = await $.clock.now()
        const cur = await load($)
        await save($, ackState(cur.state, now, !retro))
      } catch {
        // an ack that cannot be recorded leaves the cooldown to run out
      }

      return r
    }
    if (retro) return next(e)

    const r = await next(e)
    if (r.deny !== undefined || r.isError === true) return r
    try {
      const input = e as unknown as Record<string, unknown>
      const classified = classifyCall(name as string, input, parseResponse(r))
      if (classified === null) return r
      const now = await $.clock.now()
      const cur = await load($)
      const out = applyCall(cur.state, classified, own.cfg, now, e.agentId !== undefined)
      if (out.state !== cur.state) await save($, out.state)
      if (out.action === 'queue') return withContext(r, QUEUED_NOTICE)
      if (out.action === 'nudge') return withContext(r, buildNudge(out.roots))
    } catch {
      return r
    }

    return r
  })

  on('classic.PostToolUse', { tool_name: RETRO_TOOLS }, async ($, e, next) => {
    const name = toToolName(e.tool_name)
    if ((name !== 'advance_item' && name !== 'complete_tree') || next.origin?.plugin === PLUGIN) return next(e)
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
      const now = await $.clock.now()

      const roots = state.pendingDispatch ?? []
      if (roots.length > 0) {
        // Hold while work runs in the background; the Stop that ends the wake-up turn re-checks.
        const busy = e.background_tasks !== undefined ? holdsForTasks(e.background_tasks) : holdsForTasks(await $.agent.list())
        if (busy) return r
        // Claim it: only the Stop whose write lands at `version` spawns.
        if (!(await save($, { ...state, pendingDispatch: null }, version))) return r

        let agentId: string | undefined
        let spawnedOk = false
        try {
          const spawned = await $.agent.spawn({
            subagentType: 'general-purpose',
            model: 'sonnet',
            description: 'Session retrospective',
            prompt: buildSpawnPrompt(roots, own.ancestorId),
          })
          // Core sets agentId on a started agent; a denial carries `deny` and starts none.
          spawnedOk = spawned.deny === undefined
          agentId = spawned.deny === undefined ? spawned.agentId : undefined
        } catch {
          spawnedOk = false
        }
        if (!spawnedOk) return withBlock(r, buildDispatch(roots, own.ancestorId))

        const cur = (await load($)).state
        await save($, {
          ...cur,
          handledAt: now,
          dispatched: { ...(agentId !== undefined && { agentId }), roots: [...roots], at: now },
          ...(agentId !== undefined && { retroAgents: [...(cur.retroAgents ?? []), agentId].slice(-MAX_RETRO_AGENTS) }),
        })

        return r
      }

      const nudgeRoots = backstopRoots(state, own.cfg, now)
      if (nudgeRoots === null) return r
      await save($, afterBackstop(state, now))

      return withBlock(r, buildNudge(nudgeRoots))
    } catch {
      return r
    }
  })
}
