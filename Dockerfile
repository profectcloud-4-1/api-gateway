FROM bellsoft/liberica-runtime-container:jdk-17-musl AS builder
WORKDIR /workspace/app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon dependencies || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---------------- Runtime ----------------
FROM bellsoft/liberica-runtime-container:jre-17-musl

WORKDIR /app

COPY --from=builder /workspace/app/build/libs/*.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=dev,secret
ENV TZ=Asia/Seoul

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Duser.timezone=$TZ -jar /app/app.jar"]