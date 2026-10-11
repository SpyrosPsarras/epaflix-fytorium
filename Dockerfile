FROM ghcr.io/amacneil/dbmate:2.28.0 AS migrations

FROM node:22-bookworm-slim AS tools
COPY --from=migrations /usr/local/bin/dbmate /usr/local/bin/dbmate
ENV PNPM_HOME=/pnpm
ENV PATH=$PNPM_HOME:$PATH
RUN corepack enable && corepack prepare pnpm@10.18.3 --activate
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*

FROM tools AS dependencies
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM dependencies AS build
COPY . .
RUN pnpm build
RUN pnpm prune --prod

FROM node:22-alpine AS runtime
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000
WORKDIR /app
COPY --from=migrations /usr/local/bin/dbmate /usr/local/bin/dbmate
COPY --from=build --chown=node:node /app/package.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/build ./build
COPY --chown=node:node migrations ./migrations
COPY --chown=node:node scripts/start.mjs ./scripts/start.mjs
USER node
EXPOSE 3000
CMD ["node", "scripts/start.mjs"]
