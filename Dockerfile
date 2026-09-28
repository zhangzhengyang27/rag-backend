# rag-knowledge-hub（Spring Boot 3 + Java 17）多阶段构建
# 关键点：
# - Maven 构建走阿里云镜像（NAS 出口直连 maven central 极慢/超时）
# - 嵌入模型 bge-small-zh-v15 内置于 langchain4j jar，无需外挂；
#   重排模型（bge-reranker INT8 ONNX）由 volume 挂载 /models 提供
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY settings-aliyun.xml /root/.m2/settings.xml
COPY pom.xml .
# 先缓存依赖（pom 不变时命中缓存）
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/lostsystem-1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
