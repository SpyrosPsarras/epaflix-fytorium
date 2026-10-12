FROM ghcr.io/amacneil/dbmate:2.28.0 AS migrations

FROM node:22-bookworm-slim AS tools-base
COPY --from=migrations /usr/local/bin/dbmate /usr/local/bin/dbmate
ENV PNPM_HOME=/pnpm
ENV PATH=$PNPM_HOME:$PATH
RUN corepack enable && corepack prepare pnpm@10.18.3 --activate
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*

# Caller identities belong only in the interactive tools image, not the app build.
# An existing UID (node, root) already resolves; docker run sets the group and HOME.
FROM tools-base AS tools
ARG UID=1000
ARG GID=1000
RUN set -eu; \
    if ! getent group "$GID" >/dev/null; then groupadd --gid "$GID" "tools-$GID"; fi; \
    if ! getent passwd "$UID" >/dev/null; then \
      useradd --uid "$UID" --gid "$GID" --home-dir /tmp --no-create-home --no-log-init "tools-$UID"; \
    fi

FROM tools-base AS dependencies
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM dependencies AS build
COPY . .
RUN pnpm build

# Install native runtime dependencies on musl, not in the Debian build stage.
FROM node:22-alpine AS production-dependencies
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10.18.3 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --prod --frozen-lockfile

FROM node:22-alpine AS runtime
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000 BODY_SIZE_LIMIT=41M
WORKDIR /app
COPY --from=migrations /usr/local/bin/dbmate /usr/local/bin/dbmate
COPY --from=build --chown=node:node /app/package.json ./
COPY --from=production-dependencies --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/build ./build
COPY --chown=node:node migrations ./migrations
COPY --chown=node:node scripts/start.mjs ./scripts/start.mjs
USER node
EXPOSE 3000
CMD ["node", "scripts/start.mjs"]
