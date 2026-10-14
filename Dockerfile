# Build stage: compile and package (tests run in CI, not in the image build).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

# Runtime: the validator shells out to Maven to run the sample repository's tests,
# so the runtime image keeps Maven and a JDK (not just a JRE).
FROM maven:3.9-eclipse-temurin-21
# One shared local repository for the warm-up below and for validations at runtime (Maven 3.9 reads MAVEN_ARGS).
ENV MAVEN_ARGS="-Dmaven.repo.local=/opt/m2"
RUN useradd --create-home --uid 10001 incidentforge
WORKDIR /app
COPY --from=build /src/target/incidentforge-agent.jar app.jar
COPY sample-repo sample-repo
# Warm the sample repository's dependencies so validation does not need network access later,
# then hand everything to the unprivileged runtime user.
RUN cd sample-repo/orders-consumer && mvn -B -q test && rm -rf target \
    && chown -R incidentforge /app /opt/m2
USER incidentforge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
