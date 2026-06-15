# Stage 1: Build
FROM maven:3.9.6-amazoncorretto-21 AS build
WORKDIR /app

# Force wagon HTTP transport so SSL-bypass flags take effect during POM resolution
ENV MAVEN_OPTS="-Dmaven.wagon.http.ssl.insecure=true \
                -Dmaven.wagon.http.ssl.allowall=true \
                -Dmaven.wagon.http.ssl.ignore.validity.dates=true"

# Cache dependencies separately from source — layer invalidated only on pom.xml change
COPY pom.xml .
RUN mvn dependency:go-offline -q -Dmaven.resolver.transport=wagon

COPY src ./src
RUN mvn package -DskipTests -q -Dmaven.resolver.transport=wagon

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
