# 1. Frontend: React bundle
FROM node:20-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# vite.config.ts writes to ../backend/src/main/resources/static
RUN npm run build

# 2. Backend: jar with the bundle in static/ (the ONNX model ships inside the LangChain4j dependency)
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/backend/src/main/resources/static ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# 3. Runtime: glibc-based image, because onnxruntime's bundled native library does not run on musl/Alpine
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=backend /build/backend/target/app.jar app.jar
USER app

# Sized for a small host (512 MB RAM)
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
-XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
