# Functional multi-stage image for Phase 11's CI docker-build stage.
# Full production hardening (resource limits, docker-compose.prod.yml wiring,
# healthcheck-driven orchestration) is Phase 12 — this stage already applies
# the baseline sane defaults (non-root, slim final image, no build tooling
# in the runtime layer) rather than deferring security hygiene entirely.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S nexusiq && adduser -S nexusiq -G nexusiq
WORKDIR /app
COPY --from=build /build/target/spring-api-*.jar app.jar
USER nexusiq
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
