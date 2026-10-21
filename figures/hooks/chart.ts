import type { Figure, RefLine, Series } from '../types'

// Cell-grid charts for a Raster, each with a numbered y gutter and an x axis:
// lines and scatters in braille dots (2x4 per cell) or text glyphs, and
// grouped bars in block elements. Pure functions; no engine calls.

export const PALETTE = [0x26f313, 0xff8800, 0x4aa8ff, 0xff4fd8, 0xffe14a, 0x00e5e5]
const DEFAULT = 0x01000000
const AXIS = 0x888888
const WHISKER = 0xffffff
export const REF_COLOR = 0xcccccc
const DASH = 3 // dots (braille) or cells (text) on, then as many off
const BLANK = 0x20
const BRAILLE_BITS = [
  [0x01, 0x08],
  [0x02, 0x10],
  [0x04, 0x20],
  [0x40, 0x80],
] as const
const MAX_SERIES = PALETTE.length
const MAX_REF_LINES = 6
const MAX_POINTS = 100_000
const MIN_PLOT_COLUMNS = 10
const MAX_BAR_WIDTH = 8
const glyph = (s: string) => s.codePointAt(0)!

export type Chart = { columns: number; rows: number; cells: string }

/** One series in x order; `err` as [minus, plus] per value, bars only. */
type Points = { xs: number[]; ys: number[]; color: number; err?: [number, number][] }

// ---------------------------------------------------------------- input

/** The figure, or the reason the input is not one (shown to the model). */
export function parseFigure(input: Record<string, unknown>): Figure | string {
  const { kind = 'line', title, xLabel, yLabel, series, categories, style, aggregate, refLines } = input
  for (const [name, v] of Object.entries({ title, xLabel, yLabel })) {
    if (v !== undefined && typeof v !== 'string') return `${name} must be a string`
  }
  if (kind !== 'line' && kind !== 'scatter' && kind !== 'bar') {
    return `kind must be "line", "scatter" or "bar", got ${JSON.stringify(kind)}`
  }
  if (style !== undefined && style !== 'braille' && style !== 'text') {
    return `style must be "braille" or "text", got ${JSON.stringify(style)}`
  }
  if (style !== undefined && kind === 'bar') return 'style does not apply to bars'
  if (aggregate !== undefined && aggregate !== 'none' && aggregate !== 'mean') {
    return `aggregate must be "none" or "mean", got ${JSON.stringify(aggregate)}`
  }
  if (aggregate !== undefined && kind !== 'line') return 'aggregate applies to lines only'
  if (!Array.isArray(series) || series.length === 0) return 'series must be a non-empty array'
  if (series.length > MAX_SERIES) return `at most ${MAX_SERIES} series, got ${series.length}`

  let total = 0
  for (const [i, s] of series.entries()) {
    const where = `series[${i}]`
    if (typeof s !== 'object' || s === null) return `${where} must be an object`
    const { label, y, x, color, err } = s as Record<string, unknown>
    if (typeof label !== 'string') return `${where}.label must be a string`
    const least = kind === 'line' ? 2 : 1
    if (!isFiniteArray(y) || y.length < least) {
      return `${where}.y must be an array of at least ${least} finite number${least > 1 ? 's' : ''}`
    }
    if (x !== undefined && kind === 'bar') return `${where}.x does not apply to bars; name the groups with categories`
    if (x !== undefined && (!isFiniteArray(x) || x.length !== y.length)) {
      return `${where}.x must be an array of finite numbers the same length as y (${y.length})`
    }
    if (color !== undefined && !(typeof color === 'string' && /^#[0-9a-f]{6}$/i.test(color))) {
      return `${where}.color must be "#rrggbb"`
    }
    if (err !== undefined) {
      if (kind !== 'bar') return `${where}.err applies to bars only`
      if (!Array.isArray(err) || err.length !== y.length) return `${where}.err must have one entry per value (${y.length})`
      const isSize = (n: unknown) => typeof n === 'number' && Number.isFinite(n) && n >= 0
      for (const [j, e] of err.entries()) {
        if (!(isSize(e) || (Array.isArray(e) && e.length === 2 && e.every(isSize)))) {
          return `${where}.err[${j}] must be a number >= 0 or [minus, plus], both >= 0`
        }
      }
    }
    total += y.length
  }
  if (total > MAX_POINTS) return `at most ${MAX_POINTS} points in all, got ${total}`

  if (kind === 'bar') {
    const n = (series[0] as Series).y.length
    if (series.some(s => (s as Series).y.length !== n)) return 'every bar series must have the same number of values'
    if (categories !== undefined && !(Array.isArray(categories) && categories.every(c => typeof c === 'string'))) {
      return 'categories must be an array of strings'
    }
    if (categories !== undefined && categories.length !== n) {
      return `categories must have one label per value (${n}), got ${categories.length}`
    }
  } else if (categories !== undefined) {
    return 'categories apply to bars only'
  }

  if (refLines !== undefined) {
    if (kind === 'bar') return 'refLines apply to lines and scatters only'
    if (!Array.isArray(refLines) || refLines.length > MAX_REF_LINES) return `refLines must be an array of at most ${MAX_REF_LINES}`
    for (const [i, r] of refLines.entries()) {
      const where = `refLines[${i}]`
      if (typeof r !== 'object' || r === null) return `${where} must be an object`
      const { from, to, label, color } = r as Record<string, unknown>
      for (const [name, pt] of Object.entries({ from, to })) {
        if (!isFiniteArray(pt) || pt.length !== 2) return `${where}.${name} must be [x, y], two finite numbers`
      }
      if (label !== undefined && typeof label !== 'string') return `${where}.label must be a string`
      if (color !== undefined && !(typeof color === 'string' && /^#[0-9a-f]{6}$/i.test(color))) {
        return `${where}.color must be "#rrggbb"`
      }
    }
  }

  return {
    kind,
    title,
    xLabel,
    yLabel,
    series: series as Series[],
    categories: categories as string[] | undefined,
    style: style ?? 'braille',
    aggregate: aggregate ?? 'none',
    refLines: refLines as RefLine[] | undefined,
  } as Figure
}

function isFiniteArray(v: unknown): v is number[] {
  return Array.isArray(v) && v.every(n => typeof n === 'number' && Number.isFinite(n))
}

export function seriesColor(s: Series, i: number): number {
  return s.color ? parseInt(s.color.slice(1), 16) : PALETTE[i % PALETTE.length]!
}

export function refColor(r: RefLine): number {
  return r.color ? parseInt(r.color.slice(1), 16) : REF_COLOR
}

/**
 * The part of segment a-b inside [xlo, xhi] x [lo, hi] (Liang-Barsky), or
 * undefined when none of it is.
 */
function clip(a: [number, number], b: [number, number], xlo: number, xhi: number, lo: number, hi: number) {
  const [dx, dy] = [b[0] - a[0], b[1] - a[1]]
  let [t0, t1] = [0, 1]
  for (const [p, q] of [
    [-dx, a[0] - xlo],
    [dx, xhi - a[0]],
    [-dy, a[1] - lo],
    [dy, hi - a[1]],
  ] as const) {
    if (p === 0) {
      if (q < 0) return undefined
      continue
    }
    const t = q / p
    if (p < 0) t0 = Math.max(t0, t)
    else t1 = Math.min(t1, t)
    if (t0 > t1) return undefined
  }
  return { from: [a[0] + t0 * dx, a[1] + t0 * dy], to: [a[0] + t1 * dx, a[1] + t1 * dy] } as const
}

function points(fig: Figure): Points[] {
  return fig.series.map((s, i) => {
    const xs = s.x ?? s.y.map((_, j) => j)
    const order = xs.map((_, j) => j).sort((a, b) => xs[a]! - xs[b]!)
    const err = s.err?.map(e => (typeof e === 'number' ? [e, e] : e) as [number, number])
    return {
      xs: order.map(j => xs[j]!),
      ys: order.map(j => s.y[j]!),
      color: seriesColor(s, i),
      err: err && order.map(j => err[j]!),
    }
  })
}

// ---------------------------------------------------------------- axes

function range(values: number[]): [number, number] {
  let lo = Infinity
  let hi = -Infinity
  for (const v of values) {
    if (v < lo) lo = v
    if (v > hi) hi = v
  }
  return lo === hi ? [lo - 1, hi + 1] : [lo, hi]
}

/** Decimals enough to tell ticks `step` apart; exponent form for huge values. */
function formatter(step: number, magnitude: number): (v: number) => string {
  if (magnitude >= 1e5) return v => v.toExponential(1)
  const decimals = Math.min(6, Math.max(0, -Math.floor(Math.log10(step))))
  return v => v.toFixed(decimals).replace(/^-(0(\.0+)?)$/, '$1')
}

// ---------------------------------------------------------------- grid

class Grid {
  readonly cells: Uint32Array
  constructor(readonly columns: number, readonly rows: number) {
    this.cells = new Uint32Array(columns * rows * 3)
    for (let i = 0; i < columns * rows; i++) this.cells.set([BLANK, DEFAULT, DEFAULT], i * 3)
  }
  put(row: number, col: number, code: number, fg: number, bg = DEFAULT) {
    if (row < 0 || row >= this.rows || col < 0 || col >= this.columns) return
    this.cells.set([code, fg, bg], (row * this.columns + col) * 3)
  }
  text(row: number, col: number, s: string, fg: number) {
    for (const [i, ch] of [...s].entries()) this.put(row, col + i, glyph(ch), fg)
  }
  pack(): Chart {
    const bytes = new Uint8Array(this.cells.buffer)
    let binary = ''
    for (const b of bytes) binary += String.fromCharCode(b)
    return { columns: this.columns, rows: this.rows, cells: btoa(binary) }
  }
}

type Ref = { from: readonly [number, number]; to: readonly [number, number]; color: number }

type Plot = {
  grid: Grid
  gutter: number
  pw: number
  plotRows: number
  lo: number
  hi: number
}

/**
 * The figure as cells `columns` wide: plot rows plus two for the x axis.
 * A string says why it cannot be drawn at this size.
 */
export function renderChart(fig: Figure, columns: number, maxRows: number): Chart | string {
  const plotRows = Math.max(4, Math.min(20, maxRows - 2, Math.round((columns - 8) / 4)))
  const pts = points(fig)
  const [lo, hi] = fig.kind === 'bar' ? barRange(pts, plotRows) : range(pts.flatMap(p => p.ys))

  const isDots = fig.kind !== 'bar' && fig.style === 'braille'
  // Value at each row's vertical centre, as the plot maps it.
  const rowValue = (r: number) =>
    fig.kind === 'bar'
      ? hi - ((r + 0.5) / plotRows) * (hi - lo)
      : isDots
        ? hi - ((4 * r + 1.5) / (plotRows * 4 - 1)) * (hi - lo)
        : hi - (r / (plotRows - 1)) * (hi - lo)
  const yFmt = formatter((hi - lo) / plotRows, Math.max(Math.abs(lo), Math.abs(hi)))
  const every = plotRows > 12 ? 2 : 1
  const labels = Array.from({ length: plotRows }, (_, r) => (r % every === 0 ? yFmt(rowValue(r)) : ''))
  const gutter = Math.max(...labels.map(l => l.length)) + 2
  const pw = columns - gutter
  if (pw < MIN_PLOT_COLUMNS) return 'The pane is too narrow for a chart; widen the terminal.'

  const grid = new Grid(columns, plotRows + 2)
  for (const [r, label] of labels.entries()) {
    grid.text(r, 0, label.padStart(gutter - 2), AXIS)
    grid.put(r, gutter - 1, glyph('┤'), AXIS)
  }
  grid.put(plotRows, gutter - 1, glyph('└'), AXIS)
  for (let c = 0; c < pw; c++) grid.put(plotRows, gutter + c, glyph('─'), AXIS)

  const plot = { grid, gutter, pw, plotRows, lo, hi }
  if (fig.kind === 'bar') {
    const why = drawBars(plot, pts, fig.categories ?? pts[0]!.ys.map((_, i) => String(i)))
    if (why) return why
  } else {
    const [xlo, xhi] = range(pts.flatMap(p => p.xs))
    const refs = (fig.refLines ?? []).flatMap(r => {
      const seg = clip(r.from, r.to, xlo, xhi, lo, hi)
      return seg ? [{ ...seg, color: refColor(r) }] : []
    })
    if (isDots) drawDots(plot, pts, refs, xlo, xhi, fig.kind === 'line', fig.aggregate)
    else drawText(plot, pts, refs, xlo, xhi, fig.kind === 'line')
    drawXTicks(plot, xlo, xhi)
  }
  return grid.pack()
}

function drawXTicks({ grid, gutter, pw, plotRows }: Plot, xlo: number, xhi: number) {
  const ticks = Math.max(2, Math.min(6, Math.floor(pw / 10) + 1))
  const fmt = formatter((xhi - xlo) / (ticks - 1), Math.max(Math.abs(xlo), Math.abs(xhi)))
  let freeFrom = 0
  for (let t = 0; t < ticks; t++) {
    const col = gutter + Math.round((t / (ticks - 1)) * (pw - 1))
    grid.put(plotRows, col, glyph('┬'), AXIS)
    const label = fmt(xlo + (t / (ticks - 1)) * (xhi - xlo))
    const start = Math.min(Math.max(col - Math.floor(label.length / 2), 0), grid.columns - label.length)
    if (start < freeFrom) continue
    grid.text(plotRows + 1, start, label, AXIS)
    freeFrom = start + label.length + 1
  }
}

// ---------------------------------------------------------------- lines and scatters

function drawDots(
  { grid, gutter, pw, plotRows, lo, hi }: Plot,
  pts: Points[],
  refs: Ref[],
  xlo: number,
  xhi: number,
  isJoined: boolean,
  aggregate: Figure['aggregate'],
) {
  const w = pw * 2
  const h = plotRows * 4
  const colors = [...pts.map(p => p.color), ...refs.map(r => r.color)]
  const owner = new Int32Array(w * h).fill(-1) // index into colors per dot
  const toDot = ([x, y]: readonly [number, number]) =>
    [((x - xlo) / (xhi - xlo)) * (w - 1), ((hi - y) / (hi - lo)) * (h - 1)] as const
  for (const [i, r] of refs.entries()) {
    const [x0, y0] = toDot(r.from)
    const [x1, y1] = toDot(r.to)
    const n = Math.max(Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))), 1)
    for (let k = 0; k <= n; k++) {
      if (Math.floor(k / DASH) % 2) continue
      const cx = Math.round(x0 + (k / n) * (x1 - x0))
      const cy = Math.round(y0 + (k / n) * (y1 - y0))
      owner[cy * w + cx] = pts.length + i
    }
  }
  for (const [s, p] of pts.entries()) {
    let px = p.xs.map(x => Math.round(((x - xlo) / (xhi - xlo)) * (w - 1)))
    let py = p.ys.map(y => ((hi - y) / (hi - lo)) * (h - 1))
    if (aggregate === 'mean') [px, py] = meanPerColumn(px, py)
    for (let i = 0; i < px.length; i++) {
      const prev = isJoined ? Math.max(0, i - 1) : i
      const x0 = px[prev]!
      const y0 = Math.round(py[prev]!)
      const x1 = px[i]!
      const y1 = Math.round(py[i]!)
      const n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0), 1)
      for (let k = 0; k <= n; k++) {
        const cx = Math.round(x0 + (k / n) * (x1 - x0))
        const cy = Math.round(y0 + (k / n) * (y1 - y0))
        owner[cy * w + cx] = s
      }
    }
  }
  for (let r = 0; r < plotRows; r++) {
    for (let c = 0; c < pw; c++) {
      let bits = 0
      const counts = new Array<number>(colors.length).fill(0)
      for (let dr = 0; dr < 4; dr++) {
        for (let dc = 0; dc < 2; dc++) {
          const s = owner[(4 * r + dr) * w + 2 * c + dc]!
          if (s < 0) continue
          bits |= BRAILLE_BITS[dr]![dc]!
          counts[s]!++
        }
      }
      if (!bits) continue
      const top = counts.indexOf(Math.max(...counts))
      grid.put(r, gutter + c, 0x2800 + bits, colors[top]!)
    }
  }
}

/** One point per column: the mean of the samples that land in it. */
function meanPerColumn(px: number[], py: number[]): [number[], number[]] {
  const sums = new Map<number, [number, number]>()
  for (const [i, x] of px.entries()) {
    const [sum, n] = sums.get(x) ?? [0, 0]
    sums.set(x, [sum + py[i]!, n + 1])
  }
  const cols = [...sums.keys()].sort((a, b) => a - b)
  return [cols, cols.map(c => sums.get(c)![0] / sums.get(c)![1])]
}

/** asciichart-style lines (each column the mean of its samples), or a `•` per point; refs as `·` dashes. */
function drawText(
  { grid, gutter, pw, plotRows, lo, hi }: Plot,
  pts: Points[],
  refs: Ref[],
  xlo: number,
  xhi: number,
  isJoined: boolean,
) {
  const toCell = ([x, y]: readonly [number, number]) =>
    [((x - xlo) / (xhi - xlo)) * (pw - 1), ((hi - y) / (hi - lo)) * (plotRows - 1)] as const
  for (const r of refs) {
    const [x0, y0] = toCell(r.from)
    const [x1, y1] = toCell(r.to)
    const n = Math.max(Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))), 1)
    for (let k = 0; k <= n; k++) {
      if (k % 2) continue
      grid.put(Math.round(y0 + (k / n) * (y1 - y0)), gutter + Math.round(x0 + (k / n) * (x1 - x0)), glyph('·'), r.color)
    }
  }
  for (const p of pts) {
    const px = p.xs.map(x => Math.round(((x - xlo) / (xhi - xlo)) * (pw - 1)))
    const py = p.ys.map(y => ((hi - y) / (hi - lo)) * (plotRows - 1))
    if (!isJoined) {
      for (const [i, x] of px.entries()) grid.put(Math.round(py[i]!), gutter + x, glyph('•'), p.color)
      continue
    }
    const [cols, means] = meanPerColumn(px, py)
    // Fill columns between samples by linear interpolation.
    const row = new Map<number, number>()
    for (let i = 0; i < cols.length; i++) {
      row.set(cols[i]!, Math.round(means[i]!))
      if (i === 0) continue
      const [c0, c1, m0, m1] = [cols[i - 1]!, cols[i]!, means[i - 1]!, means[i]!]
      for (let c = c0 + 1; c < c1; c++) row.set(c, Math.round(m0 + ((c - c0) / (c1 - c0)) * (m1 - m0)))
    }
    let prev: number | undefined
    for (let c = cols[0]!; c <= cols[cols.length - 1]!; c++) {
      const cur = row.get(c)!
      const col = gutter + c
      if (prev === undefined || prev === cur) {
        grid.put(cur, col, glyph('─'), p.color)
      } else {
        grid.put(prev, col, glyph(cur > prev ? '╮' : '╯'), p.color)
        grid.put(cur, col, glyph(cur > prev ? '╰' : '╭'), p.color)
        for (let r = Math.min(prev, cur) + 1; r < Math.max(prev, cur); r++) grid.put(r, col, glyph('│'), p.color)
      }
      prev = cur
    }
  }
}

// ---------------------------------------------------------------- bars

/**
 * A y range holding 0 with zero on a row boundary, so bars rise from (and
 * hang below) a clean baseline: [lo, hi] with hi - lo split into whole rows.
 */
function barRange(pts: Points[], plotRows: number): [number, number] {
  const ys = pts.flatMap(p => p.ys.flatMap((y, i) => (p.err ? [y - p.err[i]![0], y + p.err[i]![1]] : [y])))
  const lo = Math.min(0, ...ys)
  const hi = Math.max(0, ...ys)
  if (lo === 0 && hi === 0) return [0, 1]
  if (lo === 0) return [0, hi]
  if (hi === 0) return [lo, 0]
  // The zero row giving the finest scale: the longer side then fills its rows.
  let best = { zeroRow: 1, perRow: Infinity }
  for (let zeroRow = 1; zeroRow < plotRows; zeroRow++) {
    const perRow = Math.max(hi / zeroRow, -lo / (plotRows - zeroRow))
    if (perRow < best.perRow) best = { zeroRow, perRow }
  }
  return [-best.perRow * (plotRows - best.zeroRow), best.perRow * best.zeroRow]
}

/**
 * Grouped bars, one group per category, centred in the plot. Bars above
 * zero rise in eighths of a cell (▁..█); below zero they hang in halves (▀).
 * A whisker runs down each bar's middle column, its ends in whole cells,
 * on the bar's colour inside the bar. The bar's partly filled end cell keeps
 * its block, so the whisker passes behind it and the bar keeps its height.
 * Returns why the bars do not fit, or undefined once drawn.
 */
function drawBars({ grid, gutter, pw, plotRows, lo, hi }: Plot, pts: Points[], categories: string[]): string | undefined {
  const n = categories.length
  const m = pts.length
  const gap = n > 1 ? 1 : 0
  const bw = Math.min(MAX_BAR_WIDTH, Math.floor((pw - (n - 1) * gap) / (n * m)))
  if (bw < 1) return `${n * m} bars do not fit in ${pw} columns; widen the terminal or send fewer bars.`

  const perRow = (hi - lo) / plotRows
  const zeroRow = Math.round(hi / perRow) // boundary above this row index is zero
  const group = m * bw
  const offset = Math.floor((pw - (n * group + (n - 1) * gap)) / 2)
  for (let i = 0; i < n; i++) {
    const start = gutter + offset + i * (group + gap)
    for (const [j, p] of pts.entries()) {
      const v = p.ys[i]!
      for (let c = start + j * bw; c < start + (j + 1) * bw; c++) {
        if (v >= 0) {
          const eighths = Math.round((v / perRow) * 8)
          for (let k = 0; k < Math.floor(eighths / 8); k++) grid.put(zeroRow - 1 - k, c, glyph('█'), p.color)
          if (eighths % 8) grid.put(zeroRow - 1 - Math.floor(eighths / 8), c, 0x2580 + (eighths % 8), p.color)
        } else {
          const halves = Math.round((-v / perRow) * 2)
          for (let k = 0; k < Math.floor(halves / 2); k++) grid.put(zeroRow + k, c, glyph('█'), p.color)
          if (halves % 2) grid.put(zeroRow + Math.floor(halves / 2), c, glyph('▀'), p.color)
        }
      }
      if (p.err) drawWhisker(grid, start + j * bw + Math.floor(bw / 2), v, p.err[i]!, p.color, { plotRows, perRow, zeroRow, hi })
    }
    const label = [...categories[i]!].slice(0, group).join('')
    grid.text(plotRows + 1, start + Math.floor((group - [...label].length) / 2), label, AXIS)
    grid.put(plotRows, start + Math.floor(group / 2), glyph('┬'), AXIS)
  }
  return undefined
}

function drawWhisker(
  grid: Grid,
  col: number,
  v: number,
  [minus, plus]: [number, number],
  barColor: number,
  { plotRows, perRow, zeroRow, hi }: { plotRows: number; perRow: number; zeroRow: number; hi: number },
) {
  // The bar's extent in rows, quantized as drawn: eighths above zero, halves below.
  const q = v >= 0 ? Math.round((v / perRow) * 8) / 8 : -Math.round((-v / perRow) * 2) / 2
  const [barTop, barBottom] = [Math.min(zeroRow, zeroRow - q), Math.max(zeroRow, zeroRow - q)]
  const filled = (r: number) => Math.max(0, Math.min(barBottom, r + 1) - Math.max(barTop, r))
  const rowOf = (u: number) => Math.min(plotRows - 1, Math.max(0, Math.floor((hi - u) / perRow)))
  const [top, bottom] = [rowOf(v + plus), rowOf(v - minus)]
  for (let r = top; r <= bottom; r++) {
    const f = filled(r)
    if (f > 0 && f < 1) continue // the bar's partial end cell: keep its block
    const code = top === bottom ? '┼' : r === top ? '┬' : r === bottom ? '┴' : '│'
    grid.put(r, col, glyph(code), WHISKER, f === 1 ? barColor : DEFAULT)
  }
}
