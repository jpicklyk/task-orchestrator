// Director-mode indicator: footer label, desktop backdrop and toasts, keyed to the task-orchestrator
// director marker. Owned by work item 7380eb6a-7c30-4c4f-9451-2cccaa33d421.
//
// Source of truth is the marker file the plugin's `director-guard` hook writes for the session
// (`<tmp>/task-orchestrator/<session>/director.json`), never this module's own memory of a Skill call,
// so the indicator agrees with the guard after a resume, a reload, or an `off` from any path.
//
// Validator rules (see ../README.md): matchers are literals in this file, `$` never leaves it,
// and the atom is declared here beside its reads and writes.
import { atom, read, update } from 'claude-code'
import type { EngineInterface, On, PluginOptions } from 'claude-code'

import type { DirectorState } from '../types'
import {
  BACKDROP_ALT,
  BACKDROP_HEIGHT,
  BACKDROP_WIDTH,
  EXIT_TOAST,
  backdropSvg,
  enterToast,
  footerModes,
  isDirectorSkillCall,
  markerPath,
  tmpDir,
  GLASS,
} from './model.ts'

const PLUGIN = 'director-indicator'
const BACKDROP_ID = 'director'

const director = atom({ plugin: 'director-indicator', key: 'director' } as const, null as DirectorState)

const enter = (): DirectorState => ({ since: new Date().toISOString() })
const leave = (): DirectorState => null

/** Whether the desktop backdrop is wanted (userConfig `backdrop`, default true). Set once per load. */
let backdropWanted = true
/** Whether this module instance has set the backdrop; a reload resets it so the backdrop is re-set once. */
let backdropSet = false

/** True while this session's director marker exists. Fail-open to false on any fault. */
async function markerExists($: EngineInterface): Promise<boolean> {
  try {
    const env = {
      TMPDIR: await $.env.get('TMPDIR'),
      TEMP: await $.env.get('TEMP'),
      TMP: await $.env.get('TMP'),
    }
    const sessionId = await $.session.id()

    return await $.fs.exists(markerPath(tmpDir(env), sessionId))
  } catch {
    return false
  }
}

async function showBackdrop($: EngineInterface): Promise<void> {
  if (!backdropWanted || backdropSet) return
  backdropSet = true
  try {
    await $.ui.backdrop({ id: BACKDROP_ID, glass: GLASS })
  } catch {
    backdropSet = false
  }
}

async function clearBackdrop($: EngineInterface): Promise<void> {
  backdropSet = false
  try {
    await $.ui.backdrop(undefined)
  } catch {
    // nothing stood
  }
}

/**
 * Brings the indicator to the marker: enter (state, backdrop, toast), leave (clear all), or, while
 * already on, make sure the backdrop stands (it is display-only and a reload or resume drops it).
 * Idempotent; safe from any hook.
 */
async function reconcile($: EngineInterface): Promise<void> {
  const active = await markerExists($)
  const current = await read($, director)
  if (active && current === null) {
    const next = await update($, director, enter)
    await showBackdrop($)
    $.ui.toast(enterToast(next?.since ?? new Date().toISOString()))
  } else if (!active && current !== null) {
    await update($, director, leave)
    await clearBackdrop($)
    $.ui.toast(EXIT_TOAST)
  } else if (active) {
    await showBackdrop($)
  }
}

export function registerDirectorIndicator(on: On, options: PluginOptions): void {
  backdropWanted = options.backdrop !== false

  // A resume or reload: the marker may already exist while $.state is empty or the backdrop is gone.
  on('session.start', async ($, e, next) => {
    await reconcile($)

    return next(e)
  })

  // The director skill itself: after `next`, the guard hook has written or removed the marker.
  on('tool.call', { tool: 'Skill' }, async ($, e, next) => {
    const result = await next(e)
    if (isDirectorSkillCall((e as { skill?: unknown }).skill)) await reconcile($)

    return result
  })

  // Anything else that moved the marker (a guard write from a subagent-free path, a manual rm).
  on('turn.complete', async ($, e, next) => {
    await reconcile($)

    return next(e)
  })

  // The dim mode labels at the right of the prompt footer: add `director` while on. Reading the
  // atom here subscribes the footer, so the write in reconcile redraws it.
  on('ui.render', { component: 'SessionMode' }, async ($, e, next) => {
    if ((await read($, director)) === null) return next(e)

    return next({ ...e, props: { ...e.props, modes: footerModes(e.props.modes) } })
  })

  // The desktop backdrop this plugin set: an amber gradient with a faint DIRECTOR watermark.
  on('ui.render', { component: 'Backdrop' }, ($, e, next) => {
    if (e.props.plugin !== PLUGIN || e.props.id !== BACKDROP_ID) return next(e)
    if (e.surface !== 'desktop') return next(e)
    const { Svg } = $.ui.resolve(e)

    return h(Svg, {
      source: backdropSvg(BACKDROP_WIDTH, BACKDROP_HEIGHT),
      alt: BACKDROP_ALT,
      width: BACKDROP_WIDTH,
      height: BACKDROP_HEIGHT,
    })
  })
}
