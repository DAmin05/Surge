# syntax=docker/dockerfile:1.7
# One Dockerfile for every Python service. Build context: the service directory.
#   docker build -f infra/docker/python.Dockerfile --build-arg PACKAGE=surge_payment payment/
# For a TLS-intercepting proxy see services/Dockerfile.

FROM python:3.12-slim AS build
ENV UV_PYTHON_DOWNLOADS=never UV_PYTHON=/usr/local/bin/python3 \
    UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /src
COPY pyproject.toml uv.lock ./
COPY src src
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=cache,target=/root/.cache \
    set -eu; \
    if [ -s /run/secrets/extra_ca ]; then \
      cat /run/secrets/extra_ca >> /etc/ssl/certs/ca-certificates.crt; \
      export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt PIP_CERT=/etc/ssl/certs/ca-certificates.crt; \
    fi; \
    pip install --no-cache-dir uv==0.8.17; \
    uv sync --frozen --no-dev --no-editable

FROM python:3.12-slim
RUN groupadd --system --gid 10001 surge && useradd --system --uid 10001 --gid surge surge
COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 PORT=8000
ARG PACKAGE
ENV APP_MODULE=${PACKAGE}.app:app
USER 10001
HEALTHCHECK --interval=3s --timeout=3s --retries=20 \
  CMD ["python", "-c", "import os,urllib.request; urllib.request.urlopen(f'http://127.0.0.1:{os.environ[\"PORT\"]}/health', timeout=2)"]
CMD ["sh", "-c", "exec uvicorn \"$APP_MODULE\" --host 0.0.0.0 --port \"$PORT\""]
