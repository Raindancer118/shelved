# Build
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Run
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S shelved && adduser -S shelved -G shelved && mkdir -p /data && chown shelved:shelved /data
WORKDIR /app
COPY --from=build /src/target/shelved-*.jar app.jar
USER shelved
ENV SHELVED_DATA_DIR=/data SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
