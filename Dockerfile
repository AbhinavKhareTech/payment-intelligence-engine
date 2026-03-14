# ---- Build Stage ----
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Download dependencies first (layer caching)
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# Build (skip tests -- they run in CI)
RUN mvn clean package -DskipTests -B

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Abhinav Khare <khare.abhinav@gmail.com>"
LABEL description="Payment Intelligence Engine - GenAI-augmented transaction risk scoring"

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/target/payment-intelligence-engine-*.jar app.jar

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:+UseStringDeduplication \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

USER appuser

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
