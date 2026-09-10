# Custom player controls replace the native ones

## Status

Accepted

Extends [ADR-0011](0011-the-library-replaces-manual-id-entry.md), which split the
library and the watch page. This one is about what those pages look like and, on
the watch page, what the viewer actually presses.

## Context

The interface was the CSS a prototype leaves behind. `tokens.css` named it
honestly — "the hand-rolled dark theme from the original inline `<style>`" — and
it was `#111` on `#222` with Bootstrap's `#007bff`, Arial, and `text-align:
center` on `<body>` that every block downstream then had to undo. That last
detail is why `.steps` was `display: inline-block` and why `.library` needed
`text-align: left`: they were both working around a rule that should not have
been there.

The bigger gap was the player itself. `<video controls>` hands over the browser's
own chrome, which cannot be styled beyond a couple of vendor pseudo-elements, and
which has no idea this application exists. Three things it will not do:

- **Show what is buffered.** On an adaptive stream the distance between the
  playhead and the end of the buffer is the difference between "fine" and "about
  to stall", and `video.buffered` is a *list* of ranges precisely because seeking
  leaves holes in it.
- **Offer the ladder.** Quality selection is an hls.js concept. Native chrome has
  no idea the levels exist, which is why a `<select>` was bolted on beside the
  video.
- **Belong to the page.** It is Chrome's grey, in the middle of everything else.

## Decision

**A design system, in native CSS.** `@layer reset, tokens, base, components,
utilities` declared once, so a component never out-specifies the base and a
utility never needs `!important`. Colours are OKLCH because every hover, tint and
border is a `color-mix()` of a base and those mixes drift in brightness in sRGB.
Two tiers: a palette that names pigments and is used only in `tokens.css`, and
semantic tokens that name jobs and are all a component may reach for.

**The controls are ours.** `player/controls.js` and the five modules under it
replace the native chrome and the `<select>`: a seek bar that draws
`video.buffered` and commits on release rather than seeking through a drag, a
volume slider that remembers its level, a settings popover for quality and speed,
and the keyboard map every video site has converged on.

**`controls` stays in the markup.** `controls.js` removes the attribute as its
first act. A bundle that fails to load therefore leaves a working native player
rather than a black rectangle, and the ordering is the only thing that makes that
true — it is commented in both the HTML and the JS for that reason.

**Accessibility is now a contract we hold.** This is the real cost of the
decision. Native chrome gave keyboard operation, focus, and screen-reader
semantics for free; replacing it means owning all three. Every control is a real
`<button>` with a state-dependent `aria-label`; the seek and volume bars are
`role="slider"` with bounds and an `aria-valuetext` in words, because a screen
reader saying "four oh seven" for `4:07` is reading a time of day. The auto-hide
never fires while focus is inside the bar, and never while `readyState` is below
`HAVE_FUTURE_DATA` — `paused` goes false the instant `play()` is called, long
before a frame exists, so hiding on that alone made the controls vanish during the
initial buffer and during every stall.

**Search, sort and resume are client-side.** `GET /api/v1/videos` already returns
everything on disk and the retention window keeps that small, so filtering happens
over the array in memory rather than as a request per keystroke. Playheads live in
`localStorage` under `aztcast:v1:progress`. No endpoint changed.

**No captions button.** The transcoding pipeline produces no text tracks. A CC
control that never has anything to toggle is worse than its absence.

**One new dependency**: `@fontsource-variable/space-grotesk`, self-hosted through
npm. The `unicode-range` in its own `@font-face` means a pt-BR page downloads only
the latin subset. It is not loaded from a CDN, for the same reason hls.js is not.

## Consequences

- **The watch page owns its accessibility forever.** The contract above is not
  covered by any test — there is still no frontend runner (ADR-0011) — so it is
  kept by review and by the manual pass in the README: tab through the bar, and
  listen to it with a screen reader.
- **iOS takes fullscreen for itself.** `playsinline` keeps playback inline, but
  in fullscreen on an iPhone the native UI replaces all of this. Accepted, not a
  bug.
- **Safari's native-HLS path has no quality menu.** Without hls.js there is no
  level list, so `settingsMenu.js` omits the group rather than rendering it empty.
  Speed still works, because it acts on the element.
- **The diagnostics page's segment check ran for the first time.** `new URL(child,
  base)` throws on a relative base, and `masterPlaylistUrl()` is root-relative
  whenever `VITE_API_BASE_URL` is unset — the default, and what production runs.
  The rejection was unhandled, so every inspection died silently after the codec
  check and left the status on "Inspecionando…". The one check the page exists
  for — is a `.m4s` being served as `application/octet-stream`, which
  [troubleshooting-hls.md](../troubleshooting-hls.md) sends you here to find — had
  never run. Fixed by resolving against `window.location.href`.
- **There is client-side state now**, where there was none: playhead per video and
  one volume level. Both are conveniences, both are wrapped, and both are pruned;
  losing them costs nothing.
- **The starfield has a budget.** `ui/cosmos.js` animates only `transform` and
  `opacity`, stops when hidden or off-screen, paints one static frame under
  `prefers-reduced-motion`, and the watch page stops it outright while video is
  playing — the GPU belongs to the decoder.
- `prefers-reduced-transparency` and `prefers-contrast` are honoured as well as
  `prefers-reduced-motion`, by swapping tokens rather than by rewriting components.
