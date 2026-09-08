# Deprecate the legacy download endpoint rather than change it

## Status

Accepted

## Context

`POST /api/v1/video/download` returns `202` with a plain-text Portuguese
sentence:

```
Download iniciado. O stream estará disponível em: /api/v1/stream/{videoId}/master.m3u8
```

The videoId appears **only** inside that prose. There is no JSON field and never
was.

Its consumer is an out-of-repo Python bot. Nothing in this repository documents
it; the evidence is a commit titled *"feat: more adjust in python bot"* whose
diff contains no Python. The web player never calls this endpoint.

If the bot extracts the id with a regex — the only thing it can do — then
changing the body hands it a `202` it cannot parse: an apparent success with
nothing extracted, failing silently, in somebody else's repository.

## Decision

Add `POST /api/v1/videos` returning JSON with a `videoId` field and a `Location`
header, plus `GET /api/v1/videos/{id}` for progress.

Keep the old endpoint working, with its body **byte-for-byte identical**,
accents included. `LegacyVideoController` is marked `@Deprecated(forRemoval)`
and a test compares the raw response bytes against the literal recovered from
the pre-refactor commit.

Additions that cannot break a text parser: a `Location` header pointing at the
new resource, and RFC 8594 `Deprecation` / `Sunset` headers with a
`successor-version` `Link`.

Rejected: changing the body to JSON and telling the bot's maintainer. The
failure would be silent and remote — the worst combination.

Rejected: deleting it. Same, but immediate.

## Consequences

- Two ingestion endpoints until the bot migrates.
- The Portuguese string is now a tested contract. Do not "fix" its language,
  spacing or punctuation.
- Removal is gated on the bot migrating, not on a date; the advertised `Sunset`
  is a signal, not a commitment.
