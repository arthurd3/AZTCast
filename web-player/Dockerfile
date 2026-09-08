# syntax=docker/dockerfile:1
#
# Build context is the REPOSITORY ROOT, not this directory, so the nginx config
# can be copied from deploy/. Build it with:
#   docker build -f web-player/Dockerfile .
# or let deploy/docker-compose.yml do it.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# npm ci needs only these two, and they change far less often than src/.
COPY web-player/package.json web-player/package-lock.json ./
RUN npm ci --no-fund --no-audit

COPY web-player/ ./
# Empty base URL: every request is relative, and nginx proxies /api to the API.
ENV VITE_API_BASE_URL=""
RUN npm run build

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
