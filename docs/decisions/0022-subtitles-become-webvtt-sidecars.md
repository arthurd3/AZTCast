# Subtitles become WebVTT sidecars

## Status

Accepted

Supersedes the "no captions button" part of
[ADR-0012](0012-custom-player-controls.md). Everything else in that record — the custom controls,
the layer order, the accessibility commitments — stands.

## Context

The ladder command mapped `0:v:0` and one audio stream. Every subtitle track in the source was
therefore dropped, in silence, at the point of transcode. The release this pipeline was tested
against carries four — English, English SDH, Spanish (Latin American) and Portuguese (Brazilian) —
all of them present on disk, all of them transcoded into nothing.

[ADR-0012](0012-custom-player-controls.md) decided against a captions control on the grounds that
there was nothing to caption with. That was true, and it was true because of this.

The same record commits this project to accessibility as a contract. An SDH track that arrives in
the source and is thrown away is the clearest possible case of that contract going unmet.

## Decision

Extract every **text** subtitle track to WebVTT, publish each as its own single-segment media
playlist, and advertise them as an `EXT-X-MEDIA` subtitle group.

**Not through the ladder command.** `-var_stream_map` accepts an `sgroup:` key, and using it makes
one `hlsenc` instance emit fMP4 video segments and WebVTT text segments simultaneously — the least
exercised corner of that muxer. The ladder is the one command that must not become fragile. Each
track gets its own short invocation instead, reading a text stream measured in kilobytes.

**One segment per track.** HLS wants subtitles segmented alongside the media in the general case.
For video on demand a single-segment playlist is legal and universally supported, and it avoids
inventing cue-splitting logic to solve a problem — seeking into a caption track without downloading
all of it — that does not exist at the size these files are.

**Text only.** PGS and VobSub are bitmaps; converting them needs OCR rather than a muxer, and
asking ffmpeg to try fails the command. `ProbedSubtitle.isText()` gates the attempt.

**`DEFAULT=NO` on every track, `AUTOSELECT=YES` on every track.** A player that honours DEFAULT
turns subtitles on without being asked, and burning captions onto a viewer who did not ask for
them is the kind of helpfulness people uninstall software over. AUTOSELECT turns nothing on; it
says the player may pick this rendition when the viewer's own language or accessibility
preferences match. `CHARACTERISTICS` is written only for SDH, and omitted rather than emitted
empty for everything else.

**A failure here never fails the ingestion.** A track that will not extract is not published, so
it is never advertised — advertised-and-missing is worse than absent, because the picker offers a
track that 404s. A track that converts to an empty file is dropped for the same reason.

The player gains a "Legendas" group in the existing settings menu, which appears only when the
stream carries tracks. hls.js exposes them as `subtitleTracks`; Safari's native HLS path surfaces
the same renditions as `video.textTracks`, and `createHlsPlayer` normalises the two.

## Consequences

- **Four tracks published on the reference release**, named `English`, `English (SDH)`,
  `Spanish (Latin American)` and `Portuguese (Brazilian)` — the labels come from the container's
  own language and title tags.

- **Language tags are translated, not passed through.** Matroska tags streams with ISO 639-2
  (`por`); the HLS `LANGUAGE` attribute is RFC 5646 (`pt`). Getting this wrong does not break
  playback — it silently stops the right track from ever being auto-selected, which is worse,
  because nothing reports it. `LanguageTag` derives the table from the JDK's own ISO data rather
  than typing it out, plus the bibliographic codes (`ger`, `fre`, `dut`, …) that Matroska writers
  use about as often and the JDK does not index.

- **`text/vtt` had to be added to `HlsMediaTypes`.** A browser will not parse a cue file served as
  anything else, and the failure is silent: the track appears in the picker and shows nothing.

- **One extra ffmpeg invocation per text track.** They run after the ladder, against a stream of a
  few kilobytes, and finish in well under a second. The extraction has its own two-minute deadline
  rather than the ladder's thirty, so a pathological track cannot hold a transcoding thread.

- **The `\d+p` rendition pattern in `VideoCatalog` still counts rungs correctly.** Neither
  `audio.m3u8` nor `sub_pt-3.m3u8` matches it, which is the behaviour that was wanted and is now
  load-bearing.
