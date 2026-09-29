# Build
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests package

# Run — non-root, JRE only
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 yoon
USER yoon
COPY --from=build /src/yoon-server/target/yoon-server-*.jar /app/yoon.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/yoon.jar"]
