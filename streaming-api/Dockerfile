# syntax=docker/dockerfile:1

#
# Build context is the REPOSITORY ROOT, not this directory, matching
# deploy/docker-compose.yml. Build it with:
#   docker build -f streaming-api/Dockerfile .

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not
# re-download the world.
COPY streaming-api/pom.xml .
RUN mvn -B -q dependency:go-offline

COPY streaming-api/lombok.config .
COPY streaming-api/src ./src
RUN mvn -B -q clean package -DskipTests

# Split the fat jar into Spring Boot's layers: dependencies change rarely,
# application code changes every build.
RUN java -Djarmode=tools -jar target/streaming-api-*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# ffmpeg is a hard runtime dependency that nothing used to declare: the service
# shells out to it for every transcode. Debian's build includes libx264, which is
# the configured default encoder.
# curl is here for the HEALTHCHECK below, which the JRE image cannot satisfy on its own.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 aztcast
WORKDIR /app

COPY --from=build --chown=aztcast:aztcast /build/extracted/dependencies/ ./
COPY --from=build --chown=aztcast:aztcast /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=aztcast:aztcast /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=aztcast:aztcast /build/extracted/application/ ./

RUN mkdir -p /var/lib/aztcast/downloads /var/lib/aztcast/hls \
    && chown -R aztcast:aztcast /var/lib/aztcast
VOLUME ["/var/lib/aztcast"]

USER aztcast
EXPOSE 8080

# ffmpeg's memory lives OUTSIDE the JVM heap, so the default MaxRAMPercentage of
# 75 leaves nothing for it and the container is OOM-killed the moment an encode
# starts. That is exactly the "exit code 137" recorded in this project's history.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=docker

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# The layers above are extracted, not a jar: all four unpack into /app, so the
# loader classes sit at /app/org/... and the launcher runs off the classpath.
# Verified locally against the real extraction output.
ENTRYPOINT ["java", "-cp", ".", "org.springframework.boot.loader.launch.JarLauncher"]
