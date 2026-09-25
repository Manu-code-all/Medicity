# ---- build stage -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are copied and resolved before the sources. Docker caches this
# layer, so editing a Java file does not re-download the whole dependency tree
# on every rebuild — it cuts a typical incremental image build from minutes to
# seconds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests need a Docker daemon (Testcontainers) which is not available inside the
# build container. CI runs them separately, before the image is built.
RUN mvn -B clean package -DskipTests

# ---- runtime stage -----------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Running as root inside a container means a container escape starts with root
# on the host. Cheap to avoid, so avoid it.
RUN addgroup -S medicity && adduser -S medicity -G medicity
USER medicity

COPY --from=build /build/target/medicity-api-*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then respects whatever
# memory limit the orchestrator imposes instead of ignoring it and being OOM-killed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
