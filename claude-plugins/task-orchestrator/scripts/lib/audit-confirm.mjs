// audit-confirm.mjs - pure: an audit result (audit/result-v2) plus an audit/metrics-v1 object in, the
// same result out with every measured claim checked against the measurements. Imports nothing, does
// no I/O, reads no clock; the inputs are never modified and the same input gives the same output.
// Only scripts/audit-confirm.mjs does I/O.

const METRICS_CONTRACT = 'audit/metrics-v1';
const CLAIM_FIELDS = ['churn', 'churnRecent', 'complexity', 'hotspotScore', 'cycle'];
const FILE_FIELD = { churn: 'churn', churnRecent: 'churnRecent', complexity: 'indentComplexity', hotspotScore: 'hotspotScore' };

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const arr = (v) => (Array.isArray(v) ? v : []);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/**
 * primaryPath(locations) -> string: the first location with backslashes as '/', no leading './' and a trailing
 * ':<digits>[-<digits>]' stripped; '' with no location. A copy of the workflow's primaryPath
 * (workflows/deep-codebase-audit.js); a workflow cannot import, so a test pins the two together.
 */
export function primaryPath(locations) {
  const first = Array.isArray(locations) && locations.length ? locations[0] : null;
  if (typeof first !== 'string' || !first) return '';
  let out = first.split(String.fromCharCode(92)).join('/');
  out = out.replace(/:\d+(-\d+)?$/, '');
  while (out.indexOf('./') === 0) out = out.slice(2);
  return out;
}

/** validClaim(c): the workflow's rule for a measuredClaim (field in CLAIM_FIELDS; a finite value, or for cycle a non-empty string array) */
function validClaim(c) {
  if (!isObj(c) || CLAIM_FIELDS.indexOf(c.field) === -1) return false;
  if (c.field === 'cycle') return Array.isArray(c.modules) && c.modules.length > 0 && c.modules.every((x) => typeof x === 'string');
  return isNum(c.value);
}

function normalizeInput(input) {
  if (input && Array.isArray(input.kept)) return input;
  if (input && input.result && Array.isArray(input.result.kept)) return input.result;
  throw new TypeError('input has no kept[] array (bare result or wrapper with result.kept expected)');
}

function metricsRows(row, files) {
  const f = files.get(primaryPath(row.locations));
  if (!f) return null;
  const pick = (v) => (isNum(v) ? v : null);
  return { churn: pick(f.churn), churnRecent: pick(f.churnRecent), complexity: pick(f.indentComplexity), hotspotScore: pick(f.hotspotScore) };
}

/** check(claim, row, files, cycles) -> 'match' | 'mismatch' | 'none' (with the measured value for a numeric mismatch) */
function check(claim, row, files, cycles) {
  if (claim.field === 'cycle') {
    if (cycles === null) return { result: 'none' };
    const hit = cycles.some((c) => Array.isArray(c) && claim.modules.every((id) => c.includes(id)));
    return { result: hit ? 'match' : 'mismatch' };
  }
  const f = files.get(primaryPath(row.locations));
  if (!f || !isNum(f[FILE_FIELD[claim.field]])) return { result: 'none' };
  const measured = f[FILE_FIELD[claim.field]];
  return { result: Math.abs(claim.value - measured) * 10 <= Math.abs(measured) ? 'match' : 'mismatch', measured };
}

/**
 * confirmMeasured(input, metrics) -> the bare result with every metrics-pending row decided: CONFIRMED (verifiedBy
 * 'metrics'), moved to refuted[] (REFUTED) or left UNVERIFIED with reason 'no-metrics'. Counts, coverage.verification and the
 * proposal follow. TypeError for an input without kept[], for a metrics value that is not an audit/metrics-v1 object with a
 * files array, and for a metrics-pending row without a valid measuredClaim.
 */
export function confirmMeasured(input, metrics) {
  const res = structuredClone(normalizeInput(input));
  if (!isObj(metrics) || metrics.contract !== METRICS_CONTRACT || !Array.isArray(metrics.files)) {
    throw new TypeError(`metrics must be an ${METRICS_CONTRACT} object with a files array`);
  }
  const files = new Map();
  for (const f of metrics.files) if (f && typeof f.path === 'string' && !files.has(f.path)) files.set(f.path, f);
  const cycles = metrics.modules && Array.isArray(metrics.modules.cycles) ? metrics.modules.cycles : null;

  const keptIn = res.kept.filter((k) => k && typeof k === 'object');
  const refuted = arr(res.refuted).slice();
  const kept = [];
  const confirmedIds = new Set();
  const refutedIds = new Set();

  for (const row of keptIn) {
    const m = metricsRows(row, files);
    if (row.unverifiedReason !== 'metrics-pending') {
      if (m) row.metrics = m;
      kept.push(row);
      continue;
    }
    if (!validClaim(row.measuredClaim)) {
      throw new TypeError(`row ${row.findingId} is metrics-pending without a valid measuredClaim`);
    }
    const claim = row.measuredClaim;
    const verdict = check(claim, row, files, cycles);
    if (verdict.result === 'match') {
      row.verdict = 'CONFIRMED';
      row.verifiedBy = 'metrics';
      delete row.unverifiedReason;
      if (m) row.metrics = m;
      confirmedIds.add(row.findingId);
      kept.push(row);
    } else if (verdict.result === 'mismatch') {
      const rationale =
        claim.field === 'cycle'
          ? `measured value differs: cycle claimed [${claim.modules.join(', ')}], measured none`
          : `measured value differs: ${claim.field} claimed ${String(claim.value)}, measured ${String(verdict.measured)}`;
      const out = {
        findingId: row.findingId,
        title: row.title,
        category: row.category,
        locations: row.locations,
        stableId: row.stableId,
        basis: row.basis,
        measuredClaim: claim,
        verifiedBy: 'metrics',
        rationale,
      };
      if (m) out.metrics = m;
      refuted.push(out);
      refutedIds.add(row.findingId);
    } else {
      row.unverifiedReason = 'no-metrics';
      if (m) row.metrics = m;
      kept.push(row);
    }
  }
  for (const r of refuted) {
    if (r && typeof r === 'object' && r.verifiedBy !== 'metrics') {
      const m = metricsRows(r, files);
      if (m) r.metrics = m;
    }
  }
  res.kept = kept;
  res.refuted = refuted;

  // counts
  const count = (fn) => kept.filter(fn).length;
  const stats = isObj(res.stats) ? res.stats : {};
  stats.kept = kept.length;
  stats.confirmed = count((k) => k.verdict === 'CONFIRMED');
  stats.plausible = count((k) => k.verdict === 'PLAUSIBLE');
  stats.unverified = count((k) => k.verdict === 'UNVERIFIED');
  stats.refuted = refuted.length;
  // by final state, so a second pass over its own output leaves them unchanged
  stats.confirmedByMetrics = count((k) => k.verdict === 'CONFIRMED' && k.verifiedBy === 'metrics');
  stats.refutedByMetrics = refuted.filter((r) => r && r.verifiedBy === 'metrics').length;
  res.stats = stats;
  const report = res.report;
  if (isObj(report) && isObj(report.coverage)) {
    report.coverage.verification = {
      confirmed: stats.confirmed,
      refuted: refuted.length,
      unverifiedByCap: count((k) => k.unverifiedReason === 'cap'),
      unverifiedNoVerifier: count((k) => k.unverifiedReason === 'no-verifier'),
      unverifiedNoMetrics: count((k) => k.unverifiedReason === 'no-metrics'),
    };
  }

  // proposal
  const proposal = res.proposal;
  if (isObj(proposal)) {
    const keptById = new Map(kept.map((k) => [k.findingId, k]));
    proposal.items = arr(proposal.items).filter((it) => !(it && refutedIds.has(it.findingId)));
    for (const it of proposal.items) {
      if (!it || !confirmedIds.has(it.findingId)) continue;
      it.verdict = 'CONFIRMED';
      if (typeof it.tags === 'string' && it.tags.endsWith(',unverified')) it.tags = it.tags.slice(0, -',unverified'.length);
      if (it.reason === 'verdict UNVERIFIED') {
        const row = keptById.get(it.findingId) || {};
        it.selectedBy = isObj(it.selectedBy) ? it.selectedBy : {};
        if (row.trackedId) {
          it.selectedBy.gated = false;
          it.materialize = false;
          it.reason = `tracked ${row.trackedId}`;
        } else if (it.likelyDuplicate === 'strong' || it.likelyDuplicate === 'unknown') {
          it.selectedBy.gated = false;
          it.materialize = false;
          it.reason = `likely duplicate ${it.likelyDuplicate}`;
        } else {
          it.selectedBy.gated = true;
          it.materialize = true;
          it.reason = 'ok';
        }
      }
    }
    const materializable = proposal.items.filter((it) => it && it.materialize && it.class !== 'observation');
    const batches = [];
    for (let i = 0; i < materializable.length; i += 25) {
      batches.push({
        root: i === 0 ? proposal.container : { id: '<container-id>' },
        childRefs: materializable.slice(i, i + 25).map((it) => it.findingId),
      });
    }
    proposal.batches = batches;
    proposal.observations = proposal.items.filter((it) => it && it.class === 'observation').map((it) => it.findingId);
  }

  res.confirmedBy = 'audit-confirm';
  res.hotSet = arr(metrics.hotSet)
    .map((h) => (typeof h === 'string' ? h : h && typeof h.path === 'string' ? h.path : null))
    .filter((p) => p !== null);
  return res;
}
