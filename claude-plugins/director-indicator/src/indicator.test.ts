import { expect, test } from 'claude-code/testing'

import { backdropSvg, enterToast, footerModes, isDirectorSkillCall, markerPath, sanitizeKey, tmpDir } from './model.ts'

const FOOTER = {
  plugin: 'director-indicator',
  component: 'SessionMode',
  requestId: 'session-mode',
  props: { modes: ['focus'] },
} as const

test('the footer gains `director` while the marker state is on', async ($, on) => {
  on('state.get', () => ({ value: { value: { since: '2026-11-07T14:02:00.000Z' }, version: 1 } }))
  // Stands for the engine: draws whatever modes reach it.
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, (e.props as { modes: readonly string[] }).modes.join(' & '))
  })

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...FOOTER, surface })
    expect((await ui.find({ type: 'Text', text: /director/ }))?.text).toBe('focus & director')
    await ui.unmount()
  }
})

test('the footer is left alone while off', async ($, on) => {
  on('state.get', () => ({ value: { value: null, version: 1 } }))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, (e.props as { modes: readonly string[] }).modes.join(' & '))
  })

  const ui = await $.ui.mount({ ...FOOTER, surface: 'desktop' })
  expect((await ui.find({ type: 'Text' }))?.text).toBe('focus')
  await ui.unmount()
})

test('marker path matches the task-orchestrator hooks on both platforms', async () => {
  expect(markerPath('C:\\Users\\me\\AppData\\Local\\Temp', 'abc-123')).toBe(
    'C:\\Users\\me\\AppData\\Local\\Temp\\task-orchestrator\\abc-123\\director.json',
  )
  expect(markerPath('/tmp', 'abc.123/x')).toBe('/tmp/task-orchestrator/abc_123_x/director.json')
  expect(markerPath('/tmp', '')).toBe('/tmp/task-orchestrator/unknown/director.json')
  expect(sanitizeKey('a b:c')).toBe('a_b_c')
})

test('temp dir follows os.tmpdir() precedence and drops a trailing separator', async () => {
  expect(tmpDir({ TMPDIR: '/var/folders/x/' })).toBe('/var/folders/x')
  expect(tmpDir({ TEMP: 'C:\\Temp\\', TMP: 'C:\\Other' })).toBe('C:\\Temp')
  expect(tmpDir({ TMP: 'C:\\Other' })).toBe('C:\\Other')
  expect(tmpDir({})).toBe('/tmp')
})

test('pure helpers', async () => {
  expect(footerModes(['focus'])).toEqual(['focus', 'director'])
  expect(footerModes(['director'])).toEqual(['director'])
  expect(footerModes([])).toEqual(['director'])
  expect(isDirectorSkillCall(' task-orchestrator:director ')).toBe(true)
  expect(isDirectorSkillCall('task-orchestrator:work-summary')).toBe(false)
  expect(isDirectorSkillCall(undefined)).toBe(false)
  expect(enterToast('not a date')).toBe('Director mode on')
  expect(enterToast('2026-11-07T14:02:00.000Z').startsWith('Director mode on since ')).toBe(true)
  const svg = backdropSvg(800, 400)
  expect(svg.startsWith('<svg ')).toBe(true)
  expect(svg.includes('DIRECTOR')).toBe(true)
  expect(svg.length < 131072).toBe(true)
})
