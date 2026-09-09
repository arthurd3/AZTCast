# Two applications in one repository

## Status

Accepted

## Context

The repository held `streamingData/` (Spring Boot) and `front-end/` (three
static files). `streamingData` is camelCase, which violates Maven's rule that an
artifactId contains only lowercase letters, digits and hyphens, and neither name
says what the thing is. There was no home for documentation, deployment
configuration or scripts.

A third component already exists in practice: an out-of-repo Python bot that
POSTs magnet links to the API. The layout should not pretend otherwise.

## Decision

Two application directories at the root, named for what they are, plus
directories for the concerns that had nowhere to live:

```
streaming-api/   web-player/   deploy/   docs/   scripts/   .github/
```

`streaming-api` doubles as the Maven artifactId. `web-player` names the app:
it is the playback half of the product, not "the frontend of" the backend — it
never calls the ingestion endpoint at all.

Rejected: an `apps/` level. It is the Turborepo/Nx convention and would leave
the root showing only categories, but it adds a directory level for two
deployables and no shared code. Revisit if the Python bot moves in-repo.

Also rejected: a `packages/` directory and a root aggregator `pom.xml`. There is
nothing shared between a Java service and a 200-line web app, and one Maven
module needs no parent.

## Consequences

- Directory names match artifact and image names.
- Documentation, deployment and scripts have obvious homes.
- Adding a third application means another root-level directory; if that happens
  more than once, reconsider `apps/`.
