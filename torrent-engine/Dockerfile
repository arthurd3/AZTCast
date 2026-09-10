# Built where it runs. libtorrent-rasterbar is packaged on Debian and not on the Fedora this was
# written on, and a torrent engine is not a thing to build from source on every developer's laptop
# to satisfy a monorepo's symmetry.
FROM debian:trixie-slim AS build

RUN apt-get -qq update && apt-get -qq install -y --no-install-recommends \
        g++ cmake ninja-build pkg-config \
        libtorrent-rasterbar-dev nlohmann-json3-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -G Ninja -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo . && cmake --build build

FROM debian:trixie-slim

# Runtime only: the engine, the BitTorrent library and libstdc++. No compiler, no shell utilities
# beyond what the base carries, nothing this process would ever have a reason to execute.
RUN apt-get -qq update && apt-get -qq install -y --no-install-recommends \
        libtorrent-rasterbar2.0t64 \
    && rm -rf /var/lib/apt/lists/*

# Its own user, and one it cannot escape. Numeric so the compose file can grant the API's container
# the same group on the socket without either side guessing at name resolution.
RUN groupadd -g 10001 aztcast && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin aztcast

# Created here, owned here. Docker seeds a fresh named volume from whatever the image has at the
# mount point, ownership included -- so this is what lets an unprivileged engine create its socket
# in a volume it did not make. Without it the container starts as uid 10001 against a root-owned
# volume and cannot bind, which fails as "Permission denied" from a process that looks like it
# should have every right to be there.
RUN mkdir -p /run/aztcast && chown 10001:10001 /run/aztcast

COPY --from=build /src/build/aztcast-torrent-engine /usr/local/bin/aztcast-torrent-engine

USER 10001:10001
ENTRYPOINT ["/usr/local/bin/aztcast-torrent-engine"]
CMD ["/run/aztcast/engine.sock"]
