# syntax=docker/dockerfile:1.7
# Build context: gateway/. For a TLS-intercepting proxy see services/Dockerfile.

FROM rust:1.94-slim-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src src
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    set -eu; \
    if [ -s /run/secrets/extra_ca ]; then \
      cat /run/secrets/extra_ca >> /etc/ssl/certs/ca-certificates.crt; \
      export CARGO_HTTP_CAINFO=/etc/ssl/certs/ca-certificates.crt; \
    fi; \
    cargo build --release --locked; \
    cp target/release/surge-gateway target/release/wsbench /

FROM debian:bookworm-slim
RUN groupadd --system --gid 10001 surge && useradd --system --uid 10001 --gid surge surge
COPY --from=build /surge-gateway /wsbench /usr/local/bin/
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=3s --timeout=3s --retries=20 CMD ["surge-gateway", "healthcheck"]
ENTRYPOINT ["surge-gateway"]
