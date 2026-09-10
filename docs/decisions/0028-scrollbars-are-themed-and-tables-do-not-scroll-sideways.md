# Scrollbars are themed, and tables do not scroll sideways

## Status

Accepted

Amends [ADR-0012](0012-custom-player-controls.md), which established the design system and its
two tiers of tokens. Nothing there is reversed; this adds a token family it did not have and one
layout rule it never had to state.

## Context

The app is dark — the lightest surface in the palette is `oklch(22% 0.04 270)` — and it never
declared `color-scheme`. Without that declaration a browser has no reason to believe the page is
dark, so Chrome drew its light classic scrollbar, arrows and all, down the right of every page
and over the settings popover that floats on the video. The same omission is why the library
toolbar's `<select>` opened as a white menu.

Nothing else about scrolling had been decided either. The whole repository contained one
scrollbar declaration — `scrollbar-width: thin` on the watch page's card rail — and no
`scrollbar-color`, no `scrollbar-gutter`, no `overscroll-behavior`. Three modules had grown
`ResizeObserver` workarounds whose comments named the same cause: the layout shifting when a
scrollbar appeared.

The swarm table had a sharper version of the problem. Nine columns of peer data were laid out by
content width with `white-space: nowrap`, which came to 1529px inside a 1414px panel. The 115px
of overflow scrolled inside a wrapper — and because the table is 100 rows and ~3,700px tall, the
horizontal scrollbar sat at the foot of it. Present, and in practice unreachable: by the time a
reader could see the bar, the header naming the columns was three thousand pixels above them.

Two things measured during the work decided the shape of the fix.

**Contrast is not eyeballed.** Painting the candidate thumb over each backdrop and reading the
pixel gives numbers that the OKLCH lightness channel does not — `L` is perceptual lightness, not
luminance, and estimating from it is wrong by a factor that matters here:

| mix of `--starlight` | page | `--surface` | `--surface-raised` | video (black) |
|---|---|---|---|---|
| 26% | 2.05:1 | 2.16:1 | — | 2.00:1 |
| 40% | 3.47:1 | 3.59:1 | — | 3.44:1 |
| **48%** | **4.59:1** | **4.60:1** | **4.54:1** | **4.56:1** |
| 66% | 8.03:1 | 7.83:1 | 7.42:1 | 8.12:1 |

26% is what looked right. It fails even the 3:1 that WCAG 1.4.11 asks of a non-text control.

**`position: sticky` had two blockers, stacked.** A sticky header was the obvious answer to a
3,700px table, and it did nothing. Measured at 1500px into the table:

| wrapper `.peers__scroll` | `.provenance` | header top |
|---|---|---|
| `overflow-x: auto` | `overflow: hidden` | −1500px |
| `visible` | `overflow: hidden` | −3155px |
| `visible` | `overflow: clip` | **0px** |

The wrapper was the nearer cause and the surprising one: a non-`visible` overflow on one axis
forces the other from `visible` to `auto`, so the div that existed to scroll sideways was a
scroll container on both axes, and the header stuck faithfully to a box that never scrolls
vertically. Behind it, the card's `overflow: hidden` was a second scroll container doing the
same thing.

## Decision

**Declare the app dark, once.** `color-scheme: dark` on `html` in the reset — it is UA behaviour
normalisation, which is what a reset is for — plus `<meta name="color-scheme" content="dark">` in
each of the four heads so the first frame is not light. This alone fixes every native control,
not only scrollbars.

**Theme scrollbars with tokens, not with `::-webkit-scrollbar`.** Three semantic tokens
(`--scrollbar-thumb`, `--scrollbar-thumb-hover`, `--scrollbar-track`) applied through the
standard `scrollbar-color`. A hand-drawn scrollbar would diverge in Firefox, which has no
`::-webkit-scrollbar`, and the accessibility literature is consistent that a custom scrollbar
should earn its place. The thumb is 48% of `--starlight`; the track is transparent, so the thumb
is measured directly against whatever it sits on, which is the table above.

**Reserve the gutter.** `scrollbar-gutter: stable` on `:root`, which removes the layout shift the
three `ResizeObserver`s were compensating for. The observers stay — a window drag and an
orientation change still move the box — but their comments no longer name a cause that cannot
happen.

**Contain scroll chaining.** `overscroll-behavior` on the settings popover, the diagnostics log
and the card rail, so reaching the end of one stops rather than handing the wheel to the page
behind it.

**The swarm table never scrolls sideways.** Nine columns are always visible, at every width, with
no horizontal scrollbar anywhere:

- Two width budgets. The five columns holding a short unbreakable string get exact `rem` widths
  measured from their content; the four holding prose share `calc(100% - 26.5rem)` in fractions
  summing to 1. The total is identically 100% at every container width, so overflow is
  arithmetically impossible rather than merely unlikely. A single set of percentages could not do
  this: shrunk together, `Conectado` clipped at a 966px container — and with the scrollbar gone,
  clipped means gone, not scrolled past.
- Below 60rem, where nine columns fit no screen, each row becomes a card with all nine fields
  labelled. Nothing is dropped; dropping columns would hide the very rows and columns the page
  exists to show.
- The breakpoint is a **container** query, not a media query. The same table renders in two
  panels ~90px apart in width, and the viewport cannot tell them apart.
- A sticky header, on the `--z-sticky` token that had been declared and never used. This is what
  the two `overflow` changes above are in service of.

**Say the ARIA roles out loud.** The card layout lays table elements out as blocks, and an
element's implicit role is derived from the layout it gets, not the tag it has — so `display:
block` costs a table its `table`, `row` and `cell` roles. `ui/providerPeers.js` sets them
explicitly, which is inert in the wide layout and load-bearing in the narrow one. The `<thead>`
is `display: none` there rather than visually hidden: CSS generated content is exposed to
assistive technology and counts toward a cell's accessible name, so the `::before` label already
names each cell and an exposed column header would make it say so twice.

## Consequences

- **Retheming still means editing `tokens.css` only.** The scrollbar tokens follow the two-tier
  rule: the palette stays private to that file, components reach only for the semantic names.
  `prefers-contrast: more` raises the thumb to 80% along with the borders.
- **The gutter is reserved on pages that would not have scrolled.** Every page here scrolls, so
  nothing moves today; a future short page would show a 15px reserved strip. That is the trade for
  never shifting.
- **`overflow: clip` on `.provenance` must not be tidied back to `hidden`.** They look
  interchangeable — both crop to the radius — and only one of them leaves the sticky header
  working. The same trap is live in the other direction: adding `overflow-x: hidden` to
  `.peers__scroll` "to be safe" would make it a scroll container again and silently unstick the
  header.
- **The row cap stands.** 100 of 1,396 peers on the provenance page, 25 during a download. Fitting
  the columns does not change how many rows are worth rendering, and 1,396 rows would be a
  51,000px page.
- **No test covers any of this.** There is still no frontend runner (ADR-0011), so the contrast
  table and the sticky measurements above were taken by hand in the browser and are kept by
  review. The numbers are recorded here so the next person can re-take them rather than re-derive
  what they should be.
