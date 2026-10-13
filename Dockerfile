# Package only a jar already produced by ./mvnw clean verify; this image build is not a test gate.
ARG JAVA_IMAGE=eclipse-temurin:17-jdk-jammy@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda
ARG PYTHON_IMAGE=python@sha256:6f7ffcbabc08539252eacc4b5f5982dc83f71ce53dff009c29f91ab4f8dd0ecf
ARG POSTGRES_IMAGE=postgres:17.11@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3

FROM ${JAVA_IMAGE} AS app
WORKDIR /app
RUN groupadd --gid 10001 notification && useradd --uid 10001 --gid 10001 --no-create-home notification
COPY --chown=10001:10001 target/notification-service-0.1.0-SNAPSHOT.jar /app/notification-service.jar
COPY --chown=10001:10001 deploy/application-compose.yml /app/config/application-compose.yml
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx384m -Xss512k -XX:MaxDirectMemorySize=64m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/notification-service.jar"]

FROM ${PYTHON_IMAGE} AS mock
WORKDIR /app
COPY scripts/perf /app/scripts/perf
USER 10001:10001
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 9090
CMD ["python3", "/app/scripts/perf/mock_server.py", "--host", "0.0.0.0", "--port", "9090"]

FROM ${POSTGRES_IMAGE} AS postgres
# Keep the official PostgreSQL entrypoint and privilege drop; only add the isolated demo initializer.
COPY --chmod=755 deploy/postgres/init-roles.sh /docker-entrypoint-initdb.d/10-roles.sh
