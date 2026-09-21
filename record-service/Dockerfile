# ============================================================
# record-service 多阶段镜像（F20 服务容器化）
# 用法：构建上下文必须是仓库根（compose 中 context: .），
#       因 parent pom 与 common/api 依赖模块位于仓库根下。
# ============================================================

# ---- 构建阶段：Maven + JDK 21 打出可执行 jar ----
# 离线/私有仓库场景由构建者在 docker build 时自行挂载 settings 与本地仓库缓存
# （如 docker build 内 -v 不可用，可在外层先 mvn package 再改 COPY 预打 jar）；默认走公共中央仓库
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Parent pom declares all 6 modules: Maven must see the whole reactor, so copying
# one module at a time fails while reading the pom. Use the full build context and
# keep it small/secret-free via the repo-root .dockerignore.
COPY . .
RUN mvn -B -pl record-service -am -DskipTests package

# ---- 运行阶段：仅 JRE，非 root 运行 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /workspace/record-service/target/*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
