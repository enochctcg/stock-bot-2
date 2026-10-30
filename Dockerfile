# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Taipei
COPY --from=build /src/target/stock-bot-*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
