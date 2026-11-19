# syntax=docker/dockerfile:1.7
ARG BUILD_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG SERVER_IMAGE=quay.io/wildfly/wildfly:42.0.0.Final-jdk21
FROM ${BUILD_IMAGE} AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline
COPY src/ src/
RUN mvn -B -ntp package && \
    mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
      -Dartifact=org.postgresql:postgresql:42.7.7 -DoutputDirectory=/driver
FROM ${SERVER_IMAGE} AS runtime
COPY --chown=jboss:root --from=build /driver/postgresql-42.7.7.jar /tmp/postgresql-42.7.7.jar
COPY --chown=jboss:root server/datasource.cli /tmp/datasource.cli
COPY --chown=jboss:root --from=build /src/target/integration-service.war /tmp/integration-service.war
RUN printf '%s\n' 'embed-server --server-config=standalone.xml --admin-only=true --std-out=echo' \
    'deploy /tmp/postgresql-42.7.7.jar' \
    >/tmp/configure.cli && \
    cat /tmp/datasource.cli >>/tmp/configure.cli && \
    printf '\n%s\n' 'deploy /tmp/integration-service.war' 'stop-embedded-server' >>/tmp/configure.cli && \
    $JBOSS_HOME/bin/jboss-cli.sh --file=/tmp/configure.cli && \
    rm /tmp/configure.cli /tmp/datasource.cli /tmp/postgresql-42.7.7.jar /tmp/integration-service.war
EXPOSE 8080
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-c", "standalone.xml", "-b", "0.0.0.0"]
