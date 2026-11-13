# 多阶段构建：控制台静态资源打入网关 JAR，单容器即可提供 API + UI。

FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -DskipTests package \
    && mv target/federation-test-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 federation \
    && mkdir -p /app/data /app/docker/trino/catalog /app/docker/trino/kafka \
    && chown -R federation:federation /app
USER federation
COPY --from=build /build/app.jar /app/app.jar
EXPOSE 18080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:18080/api/v1/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
