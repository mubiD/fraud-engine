# Stage 1: Build
FROM maven:3.9.6-amazoncorretto-21 AS build
WORKDIR /app

# Cache dependencies separately from source — layer invalidated only on pom.xml change
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime — slim JRE only, no build tools or source
FROM amazoncorretto:21-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/fraud-rule-engine-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
