# Production image (Phase 11 built it functional; Phase 12 hardens it):
# multi-stage, non-root, slim final image, no build tooling in the runtime
# layer, healthchecked.

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
# wget is BusyBox-provided on Alpine — no extra package needed.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
