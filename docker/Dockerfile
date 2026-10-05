# syntax=docker/dockerfile:1.7
#
# Multi-stage build: the JAR is compiled inside the build stage, so an image can be produced
# from a clean checkout with only Docker installed (no host JDK/Maven, same result on a laptop
# and in CI).
#
# Dependency resolution:
#   - The container does NOT inherit the host's ~/.m2/settings.xml. Without one, Maven resolves
#     straight from Maven Central and packages.confluent.io/maven, which works on an open
#     network but NOT behind a corporate proxy that blocks artifact downloads or requires a
#     mirror (observed: packages.confluent.io JAR downloads return 403 behind Zscaler).
#   - In that case supply the same settings.xml your host Maven uses (mirror + credentials) as
#     the BuildKit secret `maven_settings`. It is mounted only for the Maven RUN step, never
#     written to an image layer or visible in `docker history`:
#       docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -f docker/Dockerfile .
#
# Build args:
#   MAVEN_PROFILES  Maven profiles to activate (default: confluent, the Protobuf/Schema Registry
#                   serialisers used outside the local/standalone profiles).
#
# Corporate TLS inspection (e.g. Zscaler): containers don't inherit the host's trusted CAs, so
# yum/Maven downloads fail with "unable to get local issuer certificate". Supply the proxy's root
# CA (PEM) as the optional BuildKit secret `corp_ca`; it is trusted for the build only and is
# removed again from the runtime image:
#   docker build --secret id=corp_ca,src=/path/to/corp-ca.pem -f docker/Dockerfile .

FROM amazoncorretto:25 AS build
WORKDIR /app

# unzip: the Maven wrapper needs it to unpack the distribution it downloads.
RUN --mount=type=secret,id=corp_ca,target=/tmp/corp-ca.pem,required=false \
    if [ -s /tmp/corp-ca.pem ]; then \
      cp /tmp/corp-ca.pem /etc/pki/ca-trust/source/anchors/corp-ca.pem && update-ca-trust; \
    fi \
 && yum install -y unzip && yum clean all

ARG MAVEN_PROFILES=confluent

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src

# The cache mount keeps ~/.m2/repository across builds (a layer-cache miss no longer means
# re-downloading ~400 MB). Tests run in CI as a separate gate, so they're skipped here.
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=false \
    --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -B -q package -DskipTests -P"${MAVEN_PROFILES}"


FROM amazoncorretto:25
WORKDIR /app

# libstdc++: required by RocksDB's native JNI library (org.rocksdb:rocksdbjni, pulled in via
# kafka-streams' RocksDBStore, com.fraudengine.streams). Without it, every Kafka Streams
# state-store initialization fails with UnsatisfiedLinkError ("libstdc++.so.6: No such file
# or directory") and the customer-activity-store topology (VelocityStreamsTopologyConfig) can
# never come up. Found live 2026-09-08. EvaluationContextBuilder's Postgres fallback masks
# this as a silent performance degradation rather than a startup failure, which is why it went
# unnoticed: dev/local (the only previously-verified profiles) never activate Kafka Streams.
RUN --mount=type=secret,id=corp_ca,target=/tmp/corp-ca.pem,required=false \
    if [ -s /tmp/corp-ca.pem ]; then \
      cp /tmp/corp-ca.pem /etc/pki/ca-trust/source/anchors/corp-ca.pem && update-ca-trust; \
    fi \
 && yum install -y libstdc++ && yum clean all \
 && command -v curl >/dev/null \
 && rm -f /etc/pki/ca-trust/source/anchors/corp-ca.pem && update-ca-trust

COPY --from=build /app/target/fraud-rule-engine-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
