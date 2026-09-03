# syntax=docker/dockerfile:1

# --- Node toolchain -------------------------------------------------------
# Pinned by tag AND digest so the build is reproducible and the image content
# is verified. This replaces the previous
#   curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
# which piped an unpinned, unverified remote script into a root shell at build
# time - whatever that URL served on the day of the build became part of the
# image, with no way to notice a change. The official Node image is a
# content-addressed artifact instead.
#
# To bump: pick a new tag, then record its digest with
#   docker buildx imagetools inspect node:<tag>
# Node 24, not 22: vaadin-maven-plugin has a minimum Node version and silently
# downloads its own from nodejs.org into ~/.vaadin when the one on PATH is older
# - which is what happened with Node 22, making the system install dead weight
# and leaving an unpinned download in the build after all. Keep this at or above
# the version Vaadin asks for, or the pin stops meaning anything.
FROM node:24.19.0-bookworm-slim@sha256:3638d9a6fe4030bd716be989438248074489337ba3275657f93595428be4fc03 AS node

# --- Build stage ---------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

# Node.js is required by Vaadin's production-mode frontend build: this app
# ships a custom theme under src/main/frontend/themes/calendarsync/, so Vaadin
# builds its own frontend bundle rather than falling back to the prebuilt
# vaadin-prod-bundle dependency. That build runs npm, so the image build needs
# network access to the npm registry.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates maven \
    && rm -rf /var/lib/apt/lists/*

# Node ships as a self-contained tree under /usr/local; copying it from the
# official image avoids adding a third-party apt repository to this one. The
# npm/npx entrypoints are symlinks in the source image, so they are recreated
# here rather than copied.
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version && npm --version

WORKDIR /build

# Cache dependency resolution in its own layer.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -Pprod dependency:go-offline

# The Checkstyle ruleset is bound to the validate phase, so `package` reads it
# and the build fails without it. Copied alongside pom.xml rather than with src
# because it changes far less often, keeping the source layer's cache intact.
COPY config ./config

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -Pprod -DskipTests package \
    && mv target/calendarsync-*.jar target/app.jar

# --- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

RUN useradd --system --create-home --home-dir /app calendarsync \
    && mkdir -p /data && chown calendarsync:calendarsync /data
WORKDIR /app
USER calendarsync

COPY --from=build --chown=calendarsync:calendarsync /build/target/app.jar app.jar

# /data is created and chowned above BEFORE this VOLUME line so the
# non-root user can actually write to it - Docker preserves an existing
# directory's ownership into the volume on first use, but a VOLUME
# declared with no prior directory gets created root-owned instead, which
# a non-root ENTRYPOINT can't write into.
VOLUME ["/data"]
EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod \
    CALENDARSYNC_DB_PATH=/data/calendarsync.db

# CALCLEANER_DB_KEY, CALENDARSYNC_BASE_URL, and the OAuth client id/secret
# env vars are deliberately NOT set here - see README/docker-compose.yml.
ENTRYPOINT ["java", "-jar", "app.jar"]
