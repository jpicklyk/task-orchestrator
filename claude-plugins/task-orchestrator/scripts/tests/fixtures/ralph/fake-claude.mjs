// Fake `claude -p` for the ralph-loop.mjs fixture loop (cae5d141, scenarios S2, S3, S7, S11).
//
// The driver spawns this script through `--claude-bin <path>.mjs` (as `node <path> ...args`). It never
// talks to the network: it records what the iteration was handed (argv, the seat-token file named by the
// environment, whether the token VALUE leaked into argv or any other env value) to the JSONL file named by
// FAKE_CLAUDE_LOG, then prints one `claude -p --output-format json` envelope on stdout.
//
// Behaviour per invocation comes from FAKE_CLAUDE_PLAN, a JSON array indexed by invocation number (the
// number of `start` lines already in the log). Each entry is a string kind or {kind, ...extras}:
//   terminal     a RALPH_OUTCOME terminal marker, exit 0 (default when the plan is exhausted)
//   skip         a RALPH_OUTCOME skip marker with extras.code / extras.retryAfterMs
//   error        a RALPH_OUTCOME error marker with extras.code
//   echo         terminal, but the result text and stderr both contain the seat token value
//   echo-split   terminal, but stderr carries the token in two writes 150 ms apart
//   sleep        never answers (killed by the driver's iteration timeout); exits by itself after 30 s
//   crash        exits 3 with no marker
// Extras `total_cost_usd` and `usage` are copied into the envelope (S11).
//
// FAKE_CLAUDE_ITEM_IDS is a comma list of the ids the fixture knows; the first one found in the prompt text
// (the last argv element) becomes the marker's itemId.

import fs from 'node:fs'
import path from 'node:path'

const SEAT_TOKEN_FILE_ENV = 'TASK_ORCHESTRATOR_SEAT_TOKEN_FILE'
const argv = process.argv.slice(2)
const logPath = process.env.FAKE_CLAUDE_LOG
const append = (rec) => {
  if (logPath) fs.appendFileSync(logPath, JSON.stringify(rec) + '\n')
}
const priorStarts =
  logPath && fs.existsSync(logPath)
    ? fs
        .readFileSync(logPath, 'utf8')
        .split('\n')
        .filter(Boolean)
        .map((l) => JSON.parse(l))
        .filter((r) => r.type === 'start').length
    : 0
const idx = priorStarts

const plan = JSON.parse(process.env.FAKE_CLAUDE_PLAN || '[]')
const entry = plan[idx] ?? 'terminal'
const behavior = typeof entry === 'string' ? { kind: entry } : entry

const prompt = argv[argv.length - 1] ?? ''
const knownIds = (process.env.FAKE_CLAUDE_ITEM_IDS || '').split(',').filter(Boolean)
const itemId = knownIds.find((id) => prompt.toLowerCase().includes(id.toLowerCase()))

const tokenFile = process.env[SEAT_TOKEN_FILE_ENV]
let rawFile = null
let token = null
let mode = null
let dirMode = null
if (tokenFile && fs.existsSync(tokenFile)) {
  rawFile = fs.readFileSync(tokenFile, 'utf8')
  token = rawFile.split(/\r?\n/)[0]
  mode = fs.statSync(tokenFile).mode & 0o777
  dirMode = fs.statSync(path.dirname(tokenFile)).mode & 0o777
}
const leaks = { argv: false, envKeys: [] }
if (token) {
  leaks.argv = argv.some((a) => String(a).includes(token))
  for (const [k, v] of Object.entries(process.env)) {
    if (k !== SEAT_TOKEN_FILE_ENV && String(v).includes(token)) leaks.envKeys.push(k)
  }
}

append({
  type: 'start',
  idx,
  kind: behavior.kind,
  argv,
  cwd: process.cwd(),
  mode: process.env.TASK_ORCHESTRATOR_MODE ?? null,
  tokenFile: tokenFile ?? null,
  token,
  rawFileLength: rawFile === null ? null : rawFile.length,
  fileHasCR: rawFile === null ? null : rawFile.includes('\r'),
  fileLineCount: rawFile === null ? null : rawFile.split('\n').filter((l) => l.trim() !== '').length,
  fileMode: mode,
  dirMode,
  leaks,
})

const marker = (obj) => 'RALPH_OUTCOME: ' + JSON.stringify(obj)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function envelope(text) {
  const env = { type: 'result', subtype: 'success', is_error: false, result: text, session_id: `fake-session-${idx}` }
  if (behavior.total_cost_usd !== undefined) env.total_cost_usd = behavior.total_cost_usd
  if (behavior.usage !== undefined) env.usage = behavior.usage
  return env
}

if (behavior.kind === 'sleep') {
  await sleep(30000)
  process.exit(0)
}
if (behavior.kind === 'crash') {
  process.stderr.write('fake claude crashed\n')
  process.exit(3)
}

let text
switch (behavior.kind) {
  case 'skip':
    text = marker({ status: 'skip', itemId, code: behavior.code, retryAfterMs: behavior.retryAfterMs, summary: 'fake skip' })
    break
  case 'error':
    text = marker({ status: 'error', itemId, code: behavior.code, reason: 'fake error' })
    break
  case 'echo':
    text = `my seat token is ${token}\n` + marker({ status: 'terminal', itemId, summary: `fake done ${token}` })
    process.stderr.write(`debug token=${token}\n`)
    break
  case 'echo-split': {
    const half = Math.floor(token.length / 2)
    process.stderr.write('debug part1=' + token.slice(0, half))
    await sleep(150)
    process.stderr.write(token.slice(half) + '\n')
    text = marker({ status: 'terminal', itemId, summary: 'fake done' })
    break
  }
  default:
    text = 'work done\n' + marker({ status: 'terminal', itemId, summary: 'fake done' })
}
process.stdout.write(JSON.stringify(envelope(text)) + '\n')
append({ type: 'finish', idx })
process.exit(0)
