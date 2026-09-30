# Next.js standalone build: the runtime image holds only the traced server files.
FROM node:22-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=10001:10001 /src/.next/standalone ./
COPY --from=build --chown=10001:10001 /src/.next/static ./.next/static
USER 10001
EXPOSE 3000
HEALTHCHECK --interval=3s --timeout=2s --retries=30 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/healthz').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]
CMD ["node", "server.js"]
