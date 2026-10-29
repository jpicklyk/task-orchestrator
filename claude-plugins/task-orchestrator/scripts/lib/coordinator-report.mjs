// coordinator-report.mjs - the one formatter for the coordinator-to-director report block.
//
// Pure, stdlib only. A coordinator (run-wave, ralph) never hand-writes a report: it passes ids,
// counts and short reasons to formatCoordinatorReport and sends the string it returns. The block
// carries ids (cut to 8 hex), counts and reasons cut to 120 characters, never a note body, seat
// prose, diff or token: a field this function does not know is ignored.

export const REPORT_EVENTS = ['planned', 'decision', 'paused', 'closed']

const REASON_MAX = 120
const LIST_MAX = 10

function clip(value, max = REASON_MAX) {
  const flat = String(value === undefined || value === null ? '' : value)
    .replace(/[^\x20-\x7e]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return flat.length > max ? `${flat.slice(0, max - 3)}...` : flat
}

function id8(value) {
  const s = String(value === undefined || value === null ? '' : value).replace(/-/g, '').trim().toLowerCase()
  return s ? s.slice(0, 8) : '-'
}

function count(value) {
  return Number.isInteger(value) && value >= 0 ? value : 0
}

function capped(entries) {
  if (entries.length <= LIST_MAX) return entries.join('; ')
  return `${entries.slice(0, LIST_MAX).join('; ')}; and ${entries.length - LIST_MAX} more`
}

/**
 * formatCoordinatorReport(fields) -> string.
 * fields: {event, label, runId, status, phase, method, headline, decisions:[{decisionId, itemId}],
 *   pauseReason, pauseDecisionId, counts:{done, stopped, deferred, held, excluded},
 *   stopped:[{item, reason}], closeReason, openDecisions:[id], needs}.
 * Throws 'invalid args: ...' on a non-object or an unknown event.
 */
export function formatCoordinatorReport(fields) {
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) {
    throw new Error('invalid args: the report fields must be an object')
  }
  const event = fields.event
  if (!REPORT_EVENTS.includes(event)) {
    throw new Error(`invalid args: --event must be one of ${REPORT_EVENTS.join(', ')}, got ${JSON.stringify(event)}`)
  }
  const label = clip(fields.label, 60) || 'run'
  const lines = []
  lines.push(`TO report: run ${label} (${id8(fields.runId)}) ${event}: ${clip(fields.headline) || event}`)
  const status = ['open', 'paused', 'closed'].includes(fields.status) ? fields.status : (event === 'closed' ? 'closed' : event === 'paused' ? 'paused' : 'open')
  const method = ['A', 'B', 'ralph'].includes(fields.method) ? fields.method : '-'
  lines.push(`run: status ${status} phase ${clip(fields.phase, 32) || '-'} method ${method}`)

  const decisions = (Array.isArray(fields.decisions) ? fields.decisions : [])
    .filter((d) => d && typeof d === 'object')
    .map((d) => `${id8(d.decisionId)} item ${id8(d.itemId)}`)
  lines.push(`decisions: ${decisions.length > 0 ? capped(decisions) : 'none'}`)

  if (event === 'paused') {
    lines.push(`pause: ${clip(fields.pauseReason, 40) || 'unknown'} decision ${id8(fields.pauseDecisionId)}`)
  }

  const c = fields.counts && typeof fields.counts === 'object' ? fields.counts : {}
  lines.push(
    `items: done ${count(c.done)}, stopped ${count(c.stopped)}, deferred ${count(c.deferred)}, ` +
    `held ${count(c.held)}, excluded ${count(c.excluded)}`
  )

  const stopped = (Array.isArray(fields.stopped) ? fields.stopped : [])
    .filter((s) => s && typeof s === 'object')
    .map((s) => `${id8(s.item)} ${clip(s.reason) || 'stopped'}`)
  if (stopped.length > 0) lines.push(`stopped: ${capped(stopped)}`)

  if (event === 'closed') {
    const open = (Array.isArray(fields.openDecisions) ? fields.openDecisions : []).map(id8)
    let openText = 'none'
    if (open.length > 0) {
      openText = open.slice(0, LIST_MAX).join(',') + (open.length > LIST_MAX ? `,and ${open.length - LIST_MAX} more` : '')
    } else if (Number.isInteger(fields.openDecisionCount) && fields.openDecisionCount > 0) {
      openText = `${fields.openDecisionCount} (ids unlisted)`
    }
    lines.push(`close: ${clip(fields.closeReason, 20) || 'unknown'}, open decisions ${openText}`)
  }

  lines.push(`needs: ${clip(fields.needs) || 'nothing'}`)
  return lines.join('\n')
}
