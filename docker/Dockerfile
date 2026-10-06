# syntax=docker/dockerfile:1.7
#
# Expects a pre-built JAR in target/fraud-rule-engine-*.jar (built on host with Maven).
# This avoids Docker network issues reaching Maven Central. The deploy.sh script handles
# building the JAR locally before invoking docker build.
#
# If you need to build without a pre-existing JAR, replace this FROM/COPY with a full
# multi-stage build. See git history for the original Maven-in-Docker approach.

FROM amazoncorretto:25 AS build-deps
WORKDIR /app

# unzip: needed for Maven wrapper (kept for reference if reverting to Maven-in-Docker).
RUN yum install -y libstdc++ || true && yum clean all || true


FROM amazoncorretto:25
WORKDIR /app

# libstdc++: required by RocksDB's native JNI library (org.rocksdb:rocksdbjni, pulled in via
# kafka-streams' RocksDBStore, com.fraudengine.streams). Without it, every Kafka Streams
# state-store initialization fails with UnsatisfiedLinkError ("libstdc++.so.6: No such file
# or directory") and the customer-activity-store topology (VelocityStreamsTopologyConfig) can
# never come up. Found live 2026-09-08. EvaluationContextBuilder's Postgres fallback masks
# this as a silent performance degradation rather than a startup failure, which is why it went
# unnoticed: dev/local (the only previously-verified profiles) never activate Kafka Streams.
RUN yum install -y libstdc++ || true && yum clean all || true

# Copy pre-built JAR from host (built by deploy.sh before docker build)
COPY target/fraud-rule-engine-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
