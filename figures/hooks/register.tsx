import { atom, read, update } from 'claude-code'
import type { Register } from 'claude-code'

import type { Figure } from '../types'
import { parseFigure, refColor, renderChart, seriesColor } from './chart'

const PANE = 'figures'
const TOOL = 'mcp__figures__show_figure'
const figure = atom({ plugin: 'figures', key: 'figure' } as const, null as Figure | null)

const DESCRIPTION = `Draw a chart in the person's figures pane (a side panel in their terminal, hidden until a figure is shown; each call replaces the last figure).

Use it when a plot would help the person see data: training curves, benchmark results, comparisons across runs or models, distributions over a sweep. Send the data itself, not an image.

- kind: "line" (default), "scatter" (one dot per point, unjoined) or "bar" (grouped bars, one group per category).
- series: up to 6, each { label, y, x?, color? }; x defaults to the sample index; color "#rrggbb" defaults to a palette.
- Bars: every series has the same number of y values, one per category; name the groups with categories (strings, one per value; default the index). No x. Bars above zero rise in eighths of a cell, bars below hang in halves.
- err (bars only, per series): one whisker per value, a number (symmetric) or [minus, plus], each >= 0; whisker ends land on whole cells, and a whisker that fits inside the bar's partly filled top cell is below the chart's resolution and not drawn. Always say what the whiskers are (SD, SE, 95% CI, ...) in the title or yLabel.
- style (line and scatter): "braille" (default; 2x4 dots per cell, finer) or "text" (box-drawing lines, each column the mean of its samples; scatter as one bullet per point).
- aggregate (braille lines only): "none" (default) draws every sample, so noisy data shows as a thick band; "mean" draws one averaged point per dot column, a thin trend line that hides the noise. Say so when you use "mean".
- refLines (line and scatter): up to 6 dashed straight lines under the data, each { from: [x, y], to: [x, y], label?, color? }, clipped to the plot (they never widen the axes): y = x, a fitted trend, a threshold. Default colour light grey.
- title, xLabel, yLabel: optional strings.

Charts are about 40-90 cells wide, so a few hundred points per series is plenty; downsample larger data first. Options that do not apply to the kind are refused, not ignored.`

const hex = (color: number) => `#${color.toString(16).padStart(6, '0')}`

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    await $.tool.register({
      name: 'show_figure',
      description: DESCRIPTION,
      isDeferred: false,
      inputSchema: {
        type: 'object',
        properties: {
          kind: { type: 'string', enum: ['line', 'scatter', 'bar'] },
          title: { type: 'string' },
          xLabel: { type: 'string' },
          yLabel: { type: 'string' },
          style: { type: 'string', enum: ['braille', 'text'] },
          aggregate: { type: 'string', enum: ['none', 'mean'] },
          categories: { type: 'array', items: { type: 'string' } },
          refLines: {
            type: 'array',
            maxItems: 6,
            items: {
              type: 'object',
              properties: {
                from: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2 },
                to: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2 },
                label: { type: 'string' },
                color: { type: 'string', pattern: '^#[0-9a-fA-F]{6}$' },
              },
              required: ['from', 'to'],
            },
          },
          series: {
            type: 'array',
            minItems: 1,
            maxItems: 6,
            items: {
              type: 'object',
              properties: {
                label: { type: 'string' },
                y: { type: 'array', items: { type: 'number' }, minItems: 1 },
                x: { type: 'array', items: { type: 'number' } },
                color: { type: 'string', pattern: '^#[0-9a-fA-F]{6}$' },
                err: {
                  type: 'array',
                  items: {
                    anyOf: [
                      { type: 'number', minimum: 0 },
                      { type: 'array', items: { type: 'number', minimum: 0 }, minItems: 2, maxItems: 2 },
                    ],
                  },
                },
              },
              required: ['label', 'y'],
            },
          },
        },
        required: ['series'],
      },
    })
    await $.command.register({ name: 'fig', description: 'Show or hide the figures pane' })
    return next(e)
  })

  on('tool.call', { tool: TOOL }, async ($, e) => {
    const { tool, tool_use_id, ...input } = e
    const fig = parseFigure(input)
    if (typeof fig === 'string') return { deny: `show_figure: ${fig}` }

    await update($, figure, () => fig)
    const { isPlaced } = await $.ui.open({ id: PANE, title: fig.title ?? 'Figure' })
    const what = `${fig.kind}, ${fig.series.length} series${fig.kind === 'bar' ? '' : `, ${fig.style}`}${fig.aggregate === 'mean' ? ', averaged per column' : ''}`
    return {
      result: isPlaced
        ? `Shown in the figures pane (${what}).`
        : `Queued in the figures pane (${what}); it shows once the terminal has room.`,
    }
  })

  on('command.run', { command: 'fig' }, async $ => {
    const isOpen = (await $.ui.panes()).some(pane => pane.id === PANE)
    if (isOpen) {
      await $.ui.close({ id: PANE })
      return { text: 'Figures pane hidden.' }
    }
    const fig = await read($, figure)
    await $.ui.open({ id: PANE, title: fig?.title ?? 'Figure' })
    return { text: 'Figures pane shown.' }
  })

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const fig = await read($, figure)
    if (e.surface !== 'terminal') {
      const { Text } = $.ui.resolve(e)
      return <Text>Figures draw in the terminal only.</Text>
    }
    const { Box, Text, Raster } = $.ui.resolve(e)
    if (!fig) return <Text dimColor>No figure yet. Claude shows one with show_figure.</Text>

    // Rows the pane spends on text around the chart: title, y label, x label, legend.
    const chart = renderChart(fig, e.props.bodyColumns, e.props.scroll.bodyRows - 4)
    if (typeof chart === 'string') return <Text dimColor>{chart}</Text>

    return (
      <Box flexDirection="column">
        {fig.title && <Text bold>{fig.title}</Text>}
        {fig.yLabel && <Text dimColor>{fig.yLabel}</Text>}
        <Raster key="chart" columns={chart.columns} rows={chart.rows} cells={chart.cells} />
        {fig.xLabel && (
          <Box flexDirection="row" justifyContent="center">
            <Text dimColor>{fig.xLabel}</Text>
          </Box>
        )}
        <Box flexDirection="row" flexWrap="wrap" columnGap={2}>
          {fig.series.map((s, i) => (
            <Text color={hex(seriesColor(s, i))}>━━ {s.label}</Text>
          ))}
          {(fig.refLines ?? []).flatMap(r => (r.label ? [<Text color={hex(refColor(r))}>╌╌ {r.label}</Text>] : []))}
        </Box>
      </Box>
    )
  })
}
