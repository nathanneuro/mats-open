import { expect, test } from 'claude-code/testing'

import type { Figure } from '../types'
import { parseFigure, renderChart } from '../hooks/chart'
import type { Chart } from '../hooks/chart'

const xs = Array.from({ length: 400 }, (_, i) => (i / 399) * 4 * Math.PI)
const noisy: Figure = {
  kind: 'line',
  series: [{ label: 'noisy', x: xs, y: xs.map((x, i) => Math.cos(x) + (i % 2 ? 0.15 : -0.15)) }],
  style: 'braille',
  aggregate: 'none',
}

/** Rows of code points, decoded from a chart's packed cells. */
function glyphs(chart: Chart | string): string[] {
  if (typeof chart === 'string') throw new Error(chart)
  const bin = atob(chart.cells)
  const words = new Uint32Array(Uint8Array.from(bin, c => c.charCodeAt(0)).buffer)
  expect(words.length).toBe(chart.columns * chart.rows * 3)
  return Array.from({ length: chart.rows }, (_, r) =>
    Array.from({ length: chart.columns }, (_, c) => String.fromCodePoint(words[(r * chart.columns + c) * 3]!)).join(''),
  )
}

function litDots(chart: Chart | string): number {
  let n = 0
  for (const row of glyphs(chart)) {
    for (const ch of row) {
      const b = ch.codePointAt(0)! - 0x2800
      if (b > 0 && b <= 0xff) n += b.toString(2).replaceAll('0', '').length
    }
  }
  return n
}

test('parseFigure fills defaults and rejects bad shapes', async () => {
  const ok = parseFigure({ series: [{ label: 'a', y: [1, 2, 3] }] })
  expect(typeof ok).toBe('object')
  expect((ok as Figure).style).toBe('braille')
  expect((ok as Figure).aggregate).toBe('none')

  expect(parseFigure({ series: [] })).toBe('series must be a non-empty array')
  expect(parseFigure({ series: [{ label: 'a', y: [1] }] })).toMatch(/at least 2/)
  expect(parseFigure({ series: [{ label: 'a', y: [1, 2], x: [1] }] })).toMatch(/same length/)
  expect(parseFigure({ series: [{ label: 'a', y: [1, Number.NaN] }] })).toMatch(/finite/)
  expect(parseFigure({ series: [{ label: 'a', y: [1, 2] }], style: 'png' })).toMatch(/style/)
  expect(parseFigure({ kind: 'scatter', series: [{ label: 'a', y: [1, 2] }], aggregate: 'mean' })).toMatch(/lines only/)
  expect(parseFigure({ kind: 'bar', series: [{ label: 'a', y: [1, 2] }], style: 'text' })).toMatch(/bars/)
  expect(parseFigure({ kind: 'bar', series: [{ label: 'a', y: [1, 2] }, { label: 'b', y: [1] }] })).toMatch(/same number/)
  expect(parseFigure({ kind: 'bar', series: [{ label: 'a', y: [1, 2] }], categories: ['x'] })).toMatch(/one label per value/)
  expect(parseFigure({ series: [{ label: 'a', y: [1, 2] }], categories: ['x', 'y'] })).toMatch(/bars only/)
})

test('a chart fills the width it is given, with numbered axes', async () => {
  for (const style of ['braille', 'text'] as const) {
    const chart = renderChart({ ...noisy, style }, 60, 30)
    const rows = glyphs(chart)
    expect(rows[0]!.length).toBe(60)
    expect(rows[0]).toMatch(/^\s*-?\d+\.\d+ ┤/)
    expect(rows[rows.length - 2]).toMatch(/└[─┬]+$/)
    expect(rows[rows.length - 1]!.trim().startsWith('0')).toBe(true)
  }
})

test('aggregate mean draws a thinner line than every sample', async () => {
  const all = renderChart(noisy, 60, 30)
  const mean = renderChart({ ...noisy, aggregate: 'mean' }, 60, 30)
  expect(litDots(mean)).toBeLessThan(litDots(all))
})

test('a scatter lights one dot per point, unjoined', async () => {
  const scatter: Figure = { ...noisy, kind: 'scatter', series: [{ label: 'p', x: [0, 10], y: [0, 10] }] }
  expect(litDots(renderChart(scatter, 60, 30))).toBe(2)
  expect(litDots(renderChart({ ...scatter, kind: 'line' }, 60, 30))).toBeGreaterThan(2)
})

test('a dashed ref line lights dots under the data, clipped, and is refused on bars', async () => {
  const scatter: Figure = { ...noisy, kind: 'scatter', series: [{ label: 'p', x: [0, 10], y: [0, 10] }] }
  const diag = { ...scatter, refLines: [{ from: [-100, -100], to: [100, 100] }] } as Figure
  const lit = litDots(renderChart(diag, 60, 30))
  // A solid diagonal across a 2*53 x 4*13 dot plot lights ~106 dots; dashed, about half.
  expect(lit).toBeGreaterThan(30)
  expect(lit).toBeLessThan(80)
  expect(typeof renderChart({ ...diag, style: 'text' }, 60, 30)).toBe('object')
  expect(parseFigure({ kind: 'bar', series: [{ label: 'a', y: [1] }], refLines: [{ from: [0, 0], to: [1, 1] }] })).toMatch(/lines and scatters/)
  expect(parseFigure({ kind: 'scatter', series: [{ label: 'a', y: [1] }], refLines: [{ from: [0], to: [1, 1] }] })).toMatch(/\[x, y\]/)
})

test('bars rise from zero in eighths, hang below in halves, labelled by category', async () => {
  const bars: Figure = {
    kind: 'bar',
    series: [{ label: 'gain', y: [4, -2, 1] }],
    categories: ['alpha', 'beta', 'gamma'],
    style: 'braille',
    aggregate: 'none',
  }
  const rows = glyphs(renderChart(bars, 60, 30))
  const plot = rows.slice(0, -2).join('\n')
  expect(plot).toMatch(/█/)
  expect(rows[rows.length - 1]).toMatch(/alpha.*beta.*gamma/)
  // Zero sits on a row boundary, so one side sets the scale and fills its edge.
  const top = /█/.test(rows[0]!)
  const bottom = /[█▀]/.test(rows[rows.length - 3]!)
  expect(top || bottom).toBe(true)
  expect(plot).toMatch(/[█▀]/)
})

test('whiskers span value -minus..+plus, stretch the axis, and are bars only', async () => {
  const bars: Figure = {
    kind: 'bar',
    series: [{ label: 'a', y: [5, 2], err: [1, [0.5, 6]] }],
    style: 'braille',
    aggregate: 'none',
  }
  const rows = glyphs(renderChart(bars, 60, 30))
  const plot = rows.slice(0, -2)
  // The second bar's whisker reaches 2 + 6 = 8 > the tallest bar: the top row holds its cap.
  expect(plot[0]).toMatch(/┬/)
  expect(plot.join('\n')).toMatch(/┴/)
  expect(parseFigure({ series: [{ label: 'a', y: [1, 2], err: [1, 1] }] })).toMatch(/bars only/)
  expect(parseFigure({ kind: 'bar', series: [{ label: 'a', y: [1, 2], err: [1] }] })).toMatch(/one entry per value/)
  expect(parseFigure({ kind: 'bar', series: [{ label: 'a', y: [1, 2], err: [1, -1] }] })).toMatch(/>= 0/)
})

test('a whisker leaves the bar end cell as drawn: no bump up or down', async () => {
  // 3.3 on an axis stretched to 5.3 by the whisker ends mid-cell, so the bar has a partial top row.
  const withErr: Figure = { kind: 'bar', series: [{ label: 'a', y: [3.3], err: [[2, 2]] }], style: 'braille', aggregate: 'none' }
  const b = glyphs(renderChart(withErr, 60, 30))
  const partial = /[▁▂▃▄▅▆▇]/
  const endRow = (rows: string[]) => rows.findIndex(r => partial.test(r))
  const rowB = endRow(b)
  expect(rowB).toBeGreaterThanOrEqual(0)
  // Every bar column on that row still holds the partial block (none replaced by the whisker).
  const blocks = (row: string) => [...row].filter(ch => partial.test(ch)).length
  // The bar's width, from the full row below: its blocks plus the whisker's column.
  const barCols = [...b[rowB + 1]!].filter(ch => ch === '█' || ch === '│').length
  expect(blocks(b[rowB]!)).toBe(barCols)
})

test('too narrow a pane, or too many bars, says why', async () => {
  expect(renderChart(noisy, 12, 30)).toMatch(/too narrow/)
  const many: Figure = { ...noisy, kind: 'bar', series: [{ label: 'a', y: Array.from({ length: 80 }, () => 1) }] }
  expect(renderChart(many, 60, 30)).toMatch(/do not fit/)
})

test('show_figure opens the pane and draws the chart', async ($, on) => {
  const opened: string[] = []
  on('tool.register', () => ({ value: { tool: 'mcp__figures__show_figure' } }))
  on('command.register', () => ({ value: { command: 'fig' } }))
  on('ui.open', (_, e) => {
    opened.push(e.id)
    return { value: { isPlaced: true } }
  })
  on('session.start', (_, e) => ({ cwd: e.cwd }))
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })

  const bad = await $.tool.call({ tool: 'mcp__figures__show_figure', series: [] })
  expect(bad.deny).toMatch(/non-empty/)
  expect(opened).toEqual([])

  const ran = await $.tool.call({
    tool: 'mcp__figures__show_figure',
    title: 'loss',
    series: [{ label: 'train', y: [3, 2, 1.5, 1.2, 1.1] }],
  })
  expect(ran.result).toMatch(/Shown in the figures pane/)
  expect(opened).toEqual(['figures'])

  const ui = await $.ui.mount({
    plugin: 'figures',
    component: 'Pane',
    requestId: 'figures',
    surface: 'terminal',
    props: {
      title: 'loss',
      isFocused: false,
      bodyColumns: 50,
      placement: 'dock',
      scroll: { offset: 0, bodyRows: 30 },
      view: {},
    },
  })
  expect((await ui.find({ type: 'Text', text: /^loss$/ }))?.text).toBe('loss')
  expect(await ui.find({ type: 'Raster', key: 'chart' })).toBeTruthy()
  await ui.unmount()
})
