# figures

A Claude Code mod that gives Claude a side pane for charts. Claude calls its
`show_figure` tool with the data itself (not an image) and the pane draws it
in terminal cells, sized to the pane's width. The pane stays hidden until a
figure arrives; `/fig` shows or hides it.

Charts are drawn as coloured cells (a `Raster`), not pixels, so they work
inside tmux, where terminal image protocols do not pass through.

## Install

At the prompt of a Claude Code terminal session:

```
/plugin install figures --marketplace nathanneuro/mats-open
```

Answer `y` to add the marketplace, then pick a scope. From a shell:

```
claude plugin install figures --marketplace nathanneuro/mats-open
```

In the desktop app, where `/plugin` does not run: click + next to the prompt
box, then Plugins, Add marketplace with `nathanneuro/mats-open`, then Add plugin.

The pane docks beside the conversation in Claude Code's fullscreen layout at
110 columns or wider; otherwise it opens above the prompt.

## What it draws

- `kind: "line"` (default): braille dots (2x4 per cell) or `style: "text"`
  box-drawing lines. By default every sample is drawn, so noise shows as a
  thick band; `aggregate: "mean"` draws one averaged point per dot column.
- `kind: "scatter"`: one dot per point, unjoined (`•` per point in text style).
- `kind: "bar"`: grouped bars, one group per `categories` label. Bars rise
  from zero in eighths of a cell and hang below it in halves. Optional
  whiskers per value via `err`: a number (symmetric) or `[minus, plus]`;
  whisker ends land on whole cells.
- `refLines` (line and scatter): dashed straight lines under the data, e.g.
  y = x or a fitted trend, clipped to the plot so they never widen the axes.
- Numbered y and x axes, a colour key, optional `title`, `xLabel`, `yLabel`.
  Up to 6 series.

Input that does not fit the chart kind (say, `err` on a line chart) is
refused with the reason rather than ignored.

## Developing

`claude plugin validate figures` checks the manifest and hooks;
`claude plugin test figures` runs `tests/`. `tsconfig.json` extends the
types Claude Code writes to `.claude-plugin/types/` once it has loaded the
mod (for example with `claude --plugin-dir figures`); then `tsc -p figures`
type-checks it.
