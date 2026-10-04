# syntax=docker/dockerfile:1

# Build stage. Dependencies are resolved before the sources are copied so that a
# code-only change does not re-download the world.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# The reactor needs the parent pom and the two library modules it depends on, so the
# build context must be the repository root, not this service directory.
COPY pom.xml ./
COPY services/contracts/pom.xml services/contracts/
COPY services/messaging/pom.xml services/messaging/
COPY services/graph-compiler/pom.xml services/graph-compiler/

RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY services/contracts services/contracts
COPY services/messaging services/messaging
COPY services/graph-compiler services/graph-compiler

RUN mvn -B -q -pl services/contracts,services/messaging,services/graph-compiler \
        -am package -DskipTests

# Runtime stage. No Maven, no sources, no shell.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Never run as root.
RUN addgroup -S archforge && adduser -S -G archforge -h /app archforge

WORKDIR /app

# finalName is set to the artifactId in the pom, so this path is version-independent.
COPY --from=build /build/services/graph-compiler/target/graph-compiler.jar app.jar
RUN chown -R archforge:archforge /app

USER archforge

# Match ARCHFORGE_LOG_DIR in archforge-logback.xml: a non-writable path would make
# logback fail at startup and abort the context.
ENV ARCHFORGE_LOG_DIR=/var/log/archforge \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

RUN mkdir -p /var/log/archforge && chown -R archforge:archforge /var/log/archforge

EXPOSE 8081

# Liveness/readiness are permitted without credentials by SecurityConfig; requiring
# them here would make the container fail its own health gate.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -q -O- http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
