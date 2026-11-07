FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home participant && mkdir -p /workspace/data && chown participant /workspace/data
WORKDIR /workspace
COPY --from=build /build/target/application.jar ./application.jar
COPY scripts/start.sh ./scripts/start.sh
USER participant
ENV DATA_DIR=/workspace/data
EXPOSE 8000
CMD ["sh", "scripts/start.sh"]
