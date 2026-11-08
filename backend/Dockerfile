# 서버 이미지. 빌드도 컨테이너 안에서 하므로 서버 PC에는 Docker만 있으면 된다
# Gradle이 든 공식 이미지로 빌드한다. 래퍼(gradlew)는 빌드할 때마다 GitHub에서 Gradle 배포본을 받아야 해서
# 그쪽이 504·시간 초과를 내면 빌드가 통째로 실패했다. 버전은 gradle/wrapper/gradle-wrapper.properties와 맞춘다
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
RUN gradle --no-daemon -q dependencies > /dev/null
COPY src src
RUN gradle --no-daemon -q bootJar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
# 8080 서비스, 8081 관리(health·prometheus). 8081은 외부에 열지 않는다
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
