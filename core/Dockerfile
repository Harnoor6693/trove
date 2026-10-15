# trove-core — the one application image (collapse plan §1.1).
#
# Not multi-stage, matching the existing service images: the jar is built by Maven on the host
# or in CI (`./mvnw --batch-mode clean package -pl core -am`) and copied in. Build context is the
# repository root:
#     docker build -f core/Dockerfile -t trove-core:dev .
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S trove && adduser -S trove -G trove

WORKDIR /app

COPY core/target/*-exec.jar app.jar

# The data volume (receipts/, exports/, uploads/). StorageConfig creates the sub-directories on
# boot and fails fast if they are not writable, so the mount must be owned by the trove user.
RUN mkdir -p /data && chown -R trove:trove /data /app
VOLUME ["/data"]

USER trove

# 8080 application (context path /api), 9090 management. Neither is published by compose:
# Traefik is the only container that publishes ports, and it never routes 9090 (§1.4).
EXPOSE 8080 9090

ENV SPRING_PROFILES_ACTIVE=prod \
    TROVE_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-Xmx1536m -XX:+UseZGC"

# `exec` form, so PID 1 is the JVM and receives SIGTERM directly — that is what makes
# server.shutdown=graceful and compose's stop_grace_period: 30s effective (gap P1-2).
ENTRYPOINT ["java", "-jar", "app.jar"]

# Readiness, not /actuator/health: it is the probe that reports db + flyway + the receipts
# volume, so it distinguishes "still migrating" from "database down" (gap P1-1).
# start-period 240s keeps a slow ARM box out of a restart loop; start-interval 5s means a fast
# box is still marked healthy in seconds.
HEALTHCHECK --interval=15s --timeout=10s --start-period=240s --start-interval=5s --retries=3 \
  CMD wget -qO- http://localhost:9090/actuator/health/readiness | grep -q '"status":"UP"'
