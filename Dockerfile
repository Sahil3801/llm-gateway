# ---------- Stage 1: build ----------
# Full JDK + Maven, used only to compile. None of this ends up in the final image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml first and download dependencies on their own layer.
# Docker caches this layer, so code-only changes don't re-download every dependency.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- Stage 2: run ----------
# Small JRE-only image: smaller download, smaller attack surface.
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is used by the HEALTHCHECK below; then run as a non-root user.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 gateway
USER gateway

COPY --from=build /app/target/llm-gateway.jar app.jar

EXPOSE 8080

# Let the JVM size its heap from the container's memory limit instead of the host's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# API keys are NOT baked into the image. Pass them at runtime, e.g.
#   docker run --env-file .env -p 8080:8080 llm-gateway
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
