# 多阶段构建：Spring Boot 后端应用
FROM maven:3.9-eclipse-temurin-21 AS builder

# 设置工作目录
WORKDIR /app

# 复制 pom.xml
COPY pom.xml .

# 下载依赖（利用Docker缓存层）
RUN mvn dependency:go-offline -B

# 复制源代码
COPY src ./src

# 构建应用（跳过测试）
RUN mvn clean package -DskipTests

# 生产阶段：使用轻量级JRE
FROM eclipse-temurin:21-jre-alpine

# 设置工作目录
WORKDIR /app

# 安装必要的系统工具
RUN apk add --no-cache curl

# 从构建阶段复制jar包
COPY --from=builder /app/target/backend.jar app.jar

# 创建必要的目录
RUN mkdir -p /app/logs /app/uploads /app/data

# 设置环境变量（可覆盖）
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SPRING_PROFILES_ACTIVE=production
ENV SERVER_PORT=8080

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
