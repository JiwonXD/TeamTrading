# 서버 이미지. 먼저 ./gradlew bootJar로 jar를 만든다(deploy/build.sh가 한 번에 한다)
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY build/libs/*.jar app.jar
USER app
# 8080 서비스, 8081 관리(health·prometheus). 8081은 외부에 열지 않는다
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
