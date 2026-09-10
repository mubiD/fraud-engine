# Runtime image only: the JAR is pre-built on the host by deploy.sh before this runs.
#
# Why not a multi-stage Maven build here?
# The Confluent Schema Registry Maven repository (packages.confluent.io/maven) now
# requires authentication, so `mvn dependency:go-offline` fails with 403 inside a
# build container that has no local cache. In a proper CI/CD pipeline with a corporate
# Nexus/Artifactory mirror or Confluent credentials injected via build secrets, the
# original multi-stage approach would be used instead:
#
#   FROM maven:3.9.6-amazoncorretto-21 AS build
#   WORKDIR /app
#   COPY pom.xml .
#   RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
#       mvn dependency:go-offline -q
#   COPY src ./src
#   RUN mvn package -DskipTests -q
#
FROM amazoncorretto:21-alpine
WORKDIR /app

# libstdc++: required by RocksDB's native JNI library (org.rocksdb:rocksdbjni, pulled in via
# kafka-streams' RocksDBStore, com.fraudengine.streams). Alpine's musl-based minimal image
# doesn't include it by default; without it, every Kafka Streams state-store initialization
# fails with UnsatisfiedLinkError ("libstdc++.so.6: No such file or directory") and the
# customer-activity-store topology (VelocityStreamsTopologyConfig) can never come up. Found
# live 2026-09-08. EvaluationContextBuilder's Postgres fallback masks this as a silent
# performance degradation rather than a startup failure, which is why it went unnoticed:
# dev/local (the only previously-verified profiles) never activate Kafka Streams at all.
RUN apk add --no-cache libstdc++

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY target/fraud-rule-engine-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
