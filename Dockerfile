ARG CODEX_RELEASE=0.150.1

FROM registry.access.redhat.com/ubi9/ubi:9.7 AS codex-cli

ARG CODEX_RELEASE
ENV CODEX_HOME=/opt/codex-install-home \
    CODEX_INSTALL_DIR=/opt/codex/bin \
    CODEX_NON_INTERACTIVE=true

RUN curl -fsSL https://chatgpt.com/codex/install.sh | \
       sh -s -- --release "${CODEX_RELEASE}" \
    && /opt/codex/bin/codex --version

FROM docker.io/library/eclipse-temurin:21-jre

ARG CODEX_RELEASE

COPY --from=codex-cli /opt/codex /opt/codex
COPY --from=codex-cli /opt/codex-install-home /opt/codex-install-home
COPY docker/entrypoint.sh /usr/local/bin/codex-creator-entrypoint

WORKDIR /deployments
COPY target/quarkus-app/lib/ /deployments/lib/
COPY target/quarkus-app/*.jar /deployments/
COPY target/quarkus-app/app/ /deployments/app/
COPY target/quarkus-app/quarkus/ /deployments/quarkus/

ENV PATH=/opt/codex/bin:/opt/codex-install-home/packages/standalone/current/codex-resources:${PATH} \
    HOME=/data/codex-creator \
    CODEX_HOME=/data/codex-creator/.codex

RUN mkdir -p /data/codex-creator/.codex /deployments/logs \
    && chown -R 10001:0 /data/codex-creator /deployments/logs \
    && chmod 0755 /usr/local/bin/codex-creator-entrypoint \
    && codex --version

USER 10001
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/codex-creator-entrypoint"]
CMD ["java", "-jar", "/deployments/quarkus-run.jar"]
