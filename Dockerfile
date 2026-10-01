FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data && chown -R 1000:1000 /app
COPY --from=build /build/target/rag-service-1.0.0.jar /app/rag-service.jar
USER 1000:1000
ENV RAG_HOST=0.0.0.0
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/rag-service.jar"]
