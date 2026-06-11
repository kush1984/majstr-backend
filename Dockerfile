do # syntax=docker/dockerfile:1
#
# Explicit multi-stage build so the deploy controls both compile and run —
# Railway's Nixpacks mis-guessed the jar path ("*/build/libs/*jar" not found)
# and fed garbage to `java`. With this Dockerfile present, Railway uses it
# instead of Nixpacks.

# ---- Build stage: compile the executable Spring Boot jar with JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Wrapper + build scripts first so the dependency-download layer is cached and
# only re-runs when the build config changes — not on every source edit.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true

# Then the source, then build. We run bootJar (the executable Spring Boot jar),
# not `build`, and skip checks/tests — those run in CI/locally, not on deploy.
# `clean` wipes build/ and bootJar does NOT invoke the plain `jar` task, so
# build/libs ends up with exactly one *.jar (majstr-backend-0.0.1-SNAPSHOT.jar)
# — the glob in the runtime stage resolves to a single file, unambiguously.
COPY . .
RUN ./gradlew clean bootJar -x check -x test --no-daemon

# ---- Runtime stage: slim JRE 21, non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN useradd --system --no-create-home --uid 10001 appuser
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser

# EXPOSE is documentation only — Railway routes to $PORT, which Spring binds via
# server.port=${PORT:8080}.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
