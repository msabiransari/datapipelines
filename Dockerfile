FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S datapipelines && adduser -S datapipelines -G datapipelines

COPY modules/app/build/libs/datapipelines-*.jar /app/datapipelines.jar

USER datapipelines
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/datapipelines.jar"]
