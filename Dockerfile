# ---- Build
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests -pl yoon-server -am package

# ---- Run — JRE only, non-root
FROM eclipse-temurin:25-jre
ARG VERSION=dev
LABEL org.opencontainers.image.title="Yoon" \
      org.opencontainers.image.description="Self-hosted payment gateway for African payment providers" \
      org.opencontainers.image.source="https://github.com/yoonpay/yoon" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.version="${VERSION}"

RUN useradd --system --uid 10001 yoon
USER yoon
COPY --from=build /src/yoon-server/target/yoon-server-*.jar /app/yoon.jar

# Size the heap from the container's memory limit, not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Readiness over plain bash (/dev/tcp): the JRE image ships no curl or wget.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'

ENTRYPOINT ["java", "-jar", "/app/yoon.jar"]
