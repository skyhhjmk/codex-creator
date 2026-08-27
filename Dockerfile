FROM eclipse-temurin:21-jre

WORKDIR /deployments
COPY target/quarkus-app/lib/ /deployments/lib/
COPY target/quarkus-app/*.jar /deployments/
COPY target/quarkus-app/app/ /deployments/app/
COPY target/quarkus-app/quarkus/ /deployments/quarkus/

RUN mkdir -p /data/codex-creator /deployments/logs
USER 10001
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
