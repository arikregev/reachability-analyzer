# Scanner image (architecture.md 3.4, 7.1, D-043): the scan Job runner with the Java toolchains it builds with.
# Base: Gradle 9.8.2 on Temurin 25 and UBI 10, pinned by digest. Maven 3.9.16 comes from Maven Central, checked
# against its published SHA-512. The build context is scanner/target (after `mvn package`), so only the runner's
# fast-jar goes in. Runs as an arbitrary UID with a read-only root filesystem; /work, /tmp and $HOME are emptyDir
# volumes, and the scanner keeps the Maven and Gradle caches under /work.
FROM docker.io/library/gradle:9.8.2-jdk25-ubi@sha256:5c3b9933708f919c537a64a33c9db463599eafe227a638252f1933d7f03de8a3

ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6

USER root
RUN microdnf install -y --nodocs zstd \
    && microdnf clean all \
    && curl -fsSLo /tmp/maven.tar.gz \
       "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - \
    && mkdir -p /opt/maven && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 && rm /tmp/maven.tar.gz \
    && ln -s /opt/maven/bin/mvn /usr/local/bin/mvn \
    && mkdir -p /work /home/reach /opt/reach && chgrp -R 0 /work /home/reach && chmod -R g=u /work /home/reach

COPY quarkus-app/ /opt/reach/scanner/

ENV HOME=/home/reach \
    MAVEN_HOME=/opt/maven \
    GRADLE_USER_HOME=/home/reach/.gradle \
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
USER 1001
WORKDIR /work
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS_APPEND -jar /opt/reach/scanner/quarkus-run.jar"]
