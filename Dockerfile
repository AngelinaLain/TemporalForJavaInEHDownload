# ── 阶段 1：按目标模块构建（会同时构建其 Maven 依赖模块） ──
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

ARG MODULE=main-service

# 先复制全部 POM，使依赖下载层可被 Docker 缓存。
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY main-service/pom.xml main-service/pom.xml
COPY scraper-worker/pom.xml scraper-worker/pom.xml
COPY ai-service/pom.xml ai-service/pom.xml
RUN mvn -B -pl "$MODULE" -am dependency:go-offline

COPY common/src common/src
COPY main-service/src main-service/src
COPY scraper-worker/src scraper-worker/src
COPY ai-service/src ai-service/src
RUN mvn -B -pl "$MODULE" -am package -DskipTests

# ── 阶段 2：仅复制对应服务的可执行 JAR ──
FROM eclipse-temurin:17-jre
ARG MODULE=main-service
