// Cross-session invalidation: an SSE stream read through `curl` while a consumer is subscribed,
// with a 15s poll when there is no API URL or curl keeps failing. Zero subscribers, no stream, no poll.
import type { GraphIo } from './io.ts'
import { invalidateLabels } from './labels.ts'
import { refresh, refreshNow } from './refresh.ts'
import { readRootId } from './snapshot.ts'

export const POLL_MS = 15_000
export const BACKOFF_START_MS = 2_000
export const BACKOFF_MAX_MS = 60_000
export const MAX_SPAWN_FAILURES = 3
/** Spelled out: `types` is a comma-separated list of exact names, not a glob. */
export const SSE_TYPES = ['item.created', 'item.updated', 'item.deleted', 'item.advanced', 'note.upserted', 'note.deleted', 'dependency.added', 'dependency.removed']
const MAX_BUFFER = 1_048_576

/** True only for a bare loopback origin (no credentials, path, query or fragment); mirrors hooks/api-client.mjs. */
export function isLoopbackApiUrl(base: string): boolean {
  try {
    const u = new URL(base.replace(/\/+$/, '') + '/')
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return false
    if (u.username !== '' || u.password !== '') return false
    if (u.search !== '' || u.hash !== '' || u.pathname !== '/') return false
    const h = u.hostname

    return h === 'localhost' || h === '[::1]' || /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(h)
  } catch {
    return false
  }
}

async function readClientApiUrl(io: GraphIo, path: string): Promise<string | null> {
  try {
    let text = await io.readFile(path)
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1)
    const parsed: unknown = JSON.parse(text)
    const url = typeof parsed === 'object' && parsed !== null ? (parsed as { apiUrl?: unknown }).apiUrl : undefined
    const trimmed = typeof url === 'string' ? url.trim().replace(/\/+$/, '') : ''

    return trimmed === '' ? null : trimmed
  } catch {
    return null
  }
}

/**
 * The REST base URL: env `TASK_ORCHESTRATOR_API_URL`; else `apiUrl` of the project client.json (only
 * when a bare loopback origin, so a repo file cannot redirect the token); else the user-level
 * client.json. Null when none yields one.
 */
export async function resolveApiUrl(io: GraphIo): Promise<string | null> {
  const fromEnv = await io.envApiUrl()
  if (fromEnv) return fromEnv.replace(/\/+$/, '')
  const project = await readClientApiUrl(io, '.taskorchestrator/client.json')
  if (project !== null && isLoopbackApiUrl(project)) return project
  const home = await io.homeDir()
  if (!home) return null

  return readClientApiUrl(io, `${home.replace(/[\\/]+$/, '')}/.taskorchestrator/client.json`)
}

/** Feeds raw SSE text in; calls `onEvent(name)` once per complete frame that carries an `event:` line. */
export function createSseParser(onEvent: (name: string) => void): (chunk: string) => void {
  let buffer = ''

  return chunk => {
    buffer += chunk.replace(/\r\n?/g, '\n')
    let end = buffer.indexOf('\n\n')
    while (end >= 0) {
      const frame = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) {
          onEvent(line.slice(6).trim())
          break
        }
      }
      end = buffer.indexOf('\n\n')
    }
    if (buffer.length > MAX_BUFFER) buffer = ''
  }
}

/** curl reads its options (the bearer header) from stdin, so the token never appears in argv. */
export function curlRequest(url: string, token: string | undefined): { argv: string[]; input?: string } {
  const argv = ['curl', '-s', '-N', '-f', '-H', 'Accept: text/event-stream']
  if (!token || /[\r\n]/.test(token)) return { argv: [...argv, url] }
  const quoted = `Authorization: Bearer ${token}`.replace(/\\/g, '\\\\').replace(/"/g, '\\"')

  return { argv: [...argv, '-K', '-', url], input: `header = "${quoted}"\n` }
}

export function eventsUrl(base: string, rootId: string): string {
  return `${base}/api/v1/events?root=${encodeURIComponent(rootId)}&types=${SSE_TYPES.join(',')}`
}

let running = false
let generation = 0
let stopLive: (() => void) | null = null

async function setLiveSource(io: GraphIo, liveSource: 'sse' | 'poll' | 'none'): Promise<void> {
  try {
    await io.updateStatus(s => ({ ...s, liveSource }))
  } catch {
    // status is advisory
  }
}

function startPoll(io: GraphIo, gen: number): void {
  if (gen !== generation) return
  const poll = io.every(POLL_MS, () => void refresh(io))
  stopLive = () => poll.cancel()
  void setLiveSource(io, 'poll')
}

/** Reads the stream until stopped. Returns 'fallback' after MAX_SPAWN_FAILURES connects that yielded nothing. */
async function sseLoop(io: GraphIo, url: string, token: string | undefined, gen: number): Promise<'stopped' | 'fallback'> {
  const abort = new AbortController()
  let iterator: ReturnType<GraphIo['spawn']> | null = null
  stopLive = () => {
    abort.abort()
    void iterator?.return(undefined).catch(() => undefined)
  }
  const request = curlRequest(url, token)
  let failures = 0
  let delay = BACKOFF_START_MS
  while (gen === generation && !abort.signal.aborted) {
    let gotData = false
    try {
      iterator = io.spawn(request)
      void setLiveSource(io, 'sse')
      const parse = createSseParser(name => {
        if (name === 'item.updated') invalidateLabels()
        void (name === 'sync.lost' ? refreshNow(io) : refresh(io))
      })
      for (;;) {
        const step = await iterator.next()
        if (step.done) break
        if (step.value.stream === 'stdout') {
          gotData = true
          parse(step.value.text)
        }
      }
    } catch {
      // spawn or read failed: counted below
    }
    iterator = null
    if (gen !== generation || abort.signal.aborted) return 'stopped'
    if (gotData) {
      failures = 0
      delay = BACKOFF_START_MS
    } else if (++failures >= MAX_SPAWN_FAILURES) {
      return 'fallback'
    }
    try {
      await io.sleep(delay, abort.signal)
    } catch {
      return 'stopped'
    }
    delay = Math.min(delay * 2, BACKOFF_MAX_MS)
  }

  return 'stopped'
}

async function startLive(io: GraphIo, gen: number): Promise<void> {
  let rootId: string | null = null
  let base: string | null = null
  let token: string | undefined
  try {
    rootId = await readRootId(io)
    base = rootId === null ? null : await resolveApiUrl(io)
    token = base === null ? undefined : ((await io.envApiToken()) || undefined)
  } catch {
    base = null
  }
  if (gen !== generation) return
  if (rootId === null || base === null) return startPoll(io, gen)
  const outcome = await sseLoop(io, eventsUrl(base, rootId), token, gen)
  if (outcome === 'fallback') startPoll(io, gen)
}

/**
 * Brings the live source in line with the subscriber count (`graphSubscribers`): starts the SSE
 * stream or the poll when it first rises above 0, stops it when it falls back to 0.
 */
export function syncLive(io: GraphIo, subscribers: number): void {
  if (subscribers > 0 && !running) {
    running = true
    const gen = ++generation
    void startLive(io, gen)
  } else if (subscribers <= 0 && running) {
    running = false
    generation++
    stopLive?.()
    stopLive = null
    void setLiveSource(io, 'none')
  }
}

/** Tears down a running live source and starts it again (a fresh spawn budget); nothing starts with no subscribers. */
export function restartLive(io: GraphIo, subscribers: number): void {
  if (running) {
    running = false
    generation++
    stopLive?.()
    stopLive = null
  }
  syncLive(io, subscribers)
}

/** Drops the live source and its state. For tests, which share this module across cases. */
export function resetLiveState(): void {
  running = false
  generation++
  stopLive?.()
  stopLive = null
}
