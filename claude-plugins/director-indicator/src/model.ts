// Pure helpers for the director indicator. No `$`, no atoms: indicator.ts feeds these values and
// draws what they return, so they are unit-testable with the plugin test kit alone.

/** The footer label added while director mode is on. */
export const LABEL = 'director'

/** The skill whose Skill tool call enters (or, with args `off`, leaves) director mode. */
export const DIRECTOR_SKILL = 'task-orchestrator:director'

/** Same rule as the task-orchestrator hooks' `sanitizeKey` (hooks/lib/markers.mjs). */
export function sanitizeKey(value: string): string {
  return value.replace(/[^a-zA-Z0-9-]/g, '_')
}

/**
 * The temp directory as Node's `os.tmpdir()` picks it: `TMPDIR`, then `TEMP`, then `TMP`, else
 * `/tmp`; a trailing separator dropped. Windows sets TEMP, POSIX TMPDIR, so one order serves both.
 */
export function tmpDir(env: { TMPDIR?: string; TEMP?: string; TMP?: string }): string {
  const raw = env.TMPDIR || env.TEMP || env.TMP || '/tmp'

  return raw.length > 1 ? raw.replace(/[\\/]+$/, '') : raw
}

/**
 * Where the task-orchestrator `director-guard` hook keeps this session's marker:
 * `<tmp>/task-orchestrator/<session>/director.json` (hooks/lib/markers.mjs `sessionMarkerPath`).
 * The separator follows the temp dir, so a Windows path stays a Windows path.
 */
export function markerPath(tmp: string, sessionId: string): string {
  const sep = tmp.includes('\\') ? '\\' : '/'

  return [tmp, 'task-orchestrator', sanitizeKey(sessionId || 'unknown'), 'director.json'].join(sep)
}

/** True for a Skill tool call of the director skill, whatever its args. */
export function isDirectorSkillCall(skill: unknown): boolean {
  return typeof skill === 'string' && skill.trim() === DIRECTOR_SKILL
}

/** The footer modes with `director` appended once. */
export function footerModes(modes: readonly string[]): string[] {
  return modes.includes(LABEL) ? [...modes] : [...modes, LABEL]
}

/** `Director mode on since 14:02` for the toast; the time in the local clock, no date. */
export function enterToast(since: string): string {
  const at = new Date(since)
  const hh = String(at.getHours()).padStart(2, '0')
  const mm = String(at.getMinutes()).padStart(2, '0')

  return Number.isNaN(at.getTime()) ? 'Director mode on' : `Director mode on since ${hh}:${mm}`
}

export const EXIT_TOAST = 'Director mode off'

/** The backdrop's glass: a warm amber tint so the window reads as "director" at a glance. */
export const GLASS = { blur: 14, tint: '#7c4a03', opacity: 0.42 } as const

/**
 * The backdrop drawing: an amber gradient with a faint repeated DIRECTOR watermark. Sized in
 * pixels because a percentage falls back to a small default; the surface scales it to the window.
 */
export const BACKDROP_WIDTH = 1920
export const BACKDROP_HEIGHT = 1080

export function backdropSvg(width = BACKDROP_WIDTH, height = BACKDROP_HEIGHT): string {
  const rows: string[] = []
  const step = 140
  for (let y = step / 2; y < height + step; y += step) {
    const offset = Math.round(y / step) % 2 === 0 ? 0 : 260
    for (let x = -260 + offset; x < width + 260; x += 520) {
      rows.push(
        `<text x="${x}" y="${y}" font-family="ui-monospace, Consolas, monospace" font-size="56" font-weight="700" letter-spacing="14" fill="#fbbf24" fill-opacity="0.07" transform="rotate(-12 ${x} ${y})">DIRECTOR</text>`,
      )
    }
  }

  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" preserveAspectRatio="xMidYMid slice">` +
    `<defs><radialGradient id="g" cx="50%" cy="0%" r="110%"><stop offset="0" stop-color="#f59e0b" stop-opacity="0.55"/><stop offset="0.55" stop-color="#7c2d12" stop-opacity="0.75"/><stop offset="1" stop-color="#1c0a00" stop-opacity="1"/></radialGradient></defs>` +
    `<rect width="${width}" height="${height}" fill="url(#g)"/>` +
    rows.join('') +
    `<rect x="0" y="0" width="${width}" height="6" fill="#f59e0b"/>` +
    `</svg>`
  )
}

export const BACKDROP_ALT = 'Director mode: this session runs the board and delegates every read or edit'
