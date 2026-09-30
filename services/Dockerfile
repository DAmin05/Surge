# syntax=docker/dockerfile:1.7
# One Dockerfile for every Java service. Build context: repository root.
#   docker build -f services/Dockerfile --build-arg SERVICE=order .
#
# Behind a TLS-intercepting proxy, pass its CA as a build secret:
#   --secret id=extra_ca,src=/path/ca.crt   (HTTPS_PROXY is forwarded by Docker)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY proto proto
COPY libs libs
COPY services services
ARG SERVICE
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=cache,target=/root/.gradle,sharing=locked \
    set -eu; \
    if [ -s /run/secrets/extra_ca ]; then \
      awk '/BEGIN CERT/{n++} {print > ("/tmp/extra-ca-" n ".pem")}' /run/secrets/extra_ca; \
      for f in /tmp/extra-ca-*.pem; do \
        keytool -importcert -noprompt -cacerts -storepass changeit -alias "$(basename "$f" .pem)" -file "$f" >/dev/null; \
      done; \
    fi; \
    if [ -n "${HTTPS_PROXY:-}" ]; then \
      hp="${HTTPS_PROXY#*://}"; hp="${hp%/}"; \
      export GRADLE_OPTS="-Dhttps.proxyHost=${hp%:*} -Dhttps.proxyPort=${hp##*:}"; \
    fi; \
    ./gradlew --no-daemon --console=plain ":services:${SERVICE}:bootJar"; \
    cp "services/${SERVICE}/build/libs/${SERVICE}.jar" /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 surge && useradd --system --uid 10001 --gid surge surge
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER 10001
ENV PORT=8080
HEALTHCHECK --interval=3s --timeout=3s --retries=40 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/actuator/health/readiness" >/dev/null || exit 1
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
