export type Series = {
  label: string
  y: number[]
  /** Same length as `y`; absent, the sample index. Not for bars. */
  x?: number[]
  /** `#rrggbb`; absent, the palette's next. */
  color?: string
  /** Bars only: a whisker per value, `e` (symmetric) or `[minus, plus]`, each >= 0. */
  err?: (number | [number, number])[]
}

/** A dashed straight line through two points, drawn under the data and clipped to the plot. */
export type RefLine = {
  from: [number, number]
  to: [number, number]
  label?: string
  /** `#rrggbb`; absent, light grey. */
  color?: string
}

export type Figure = {
  kind: 'line' | 'scatter' | 'bar'
  title?: string
  xLabel?: string
  yLabel?: string
  series: Series[]
  /** Bars only: one label per bar group; absent, the index. */
  categories?: string[]
  /** Line and scatter only. */
  style: 'braille' | 'text'
  /** Line only. */
  aggregate: 'none' | 'mean'
  /** Line and scatter only. */
  refLines?: RefLine[]
}

export type ShowFigureInput = Omit<Figure, 'kind' | 'style' | 'aggregate'> & {
  kind?: Figure['kind']
  style?: Figure['style']
  aggregate?: Figure['aggregate']
}

declare module 'claude-code' {
  interface PluginState {
    figures: { figure: Figure | null }
  }
  interface McpToolInputs {
    mcp__figures__show_figure: ShowFigureInput
  }
}
