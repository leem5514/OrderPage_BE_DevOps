# Jenkins 테스트와 빌드/실행 JDK의 주 버전을 동일하게 유지한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN sh gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
# wildcard 대신 실행 가능한 bootJar만 복사한다.
COPY --from=build /app/build/libs/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
