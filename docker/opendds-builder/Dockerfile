FROM eclipse-temurin:25.0.4_7-jdk-noble

ARG OPENDDS_VERSION=3.34.0
ARG MAVEN_VERSION=3.9.16

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION} \
    DDS_ROOT=/opt/OpenDDS-${OPENDDS_VERSION} \
    JAVA_HOME=/opt/java/openjdk
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl g++ make perl \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    archive="apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    base_url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries"; \
    curl --fail --location --silent --show-error "${base_url}/${archive}" --output "/tmp/${archive}"; \
    curl --fail --location --silent --show-error "${base_url}/${archive}.sha512" --output "/tmp/${archive}.sha512"; \
    expected="$(awk '{print $1}' "/tmp/${archive}.sha512")"; \
    printf '%s  %s\n' "${expected}" "/tmp/${archive}" | sha512sum --check --status; \
    tar -xzf "/tmp/${archive}" -C /opt; \
    rm "/tmp/${archive}" "/tmp/${archive}.sha512"

WORKDIR /opt
RUN set -eux; \
    curl --fail --location --silent --show-error \
      "https://github.com/OpenDDS/OpenDDS/releases/download/v${OPENDDS_VERSION}/OpenDDS-${OPENDDS_VERSION}.tar.gz" \
      --output "OpenDDS-${OPENDDS_VERSION}.tar.gz"; \
    tar -xzf "OpenDDS-${OPENDDS_VERSION}.tar.gz"; \
    rm "OpenDDS-${OPENDDS_VERSION}.tar.gz"; \
    cd "OpenDDS-${OPENDDS_VERSION}"; \
    ./configure --java; \
    make -j2

ENV ACE_ROOT=${DDS_ROOT}/ACE_wrappers \
    TAO_ROOT=${DDS_ROOT}/ACE_wrappers/TAO \
    MPC_ROOT=${DDS_ROOT}/ACE_wrappers/MPC \
    LD_LIBRARY_PATH=${DDS_ROOT}/ACE_wrappers/lib:${DDS_ROOT}/lib

RUN set -eux; \
    for artifact in \
      "opendds-dcps:OpenDDS_DCPS.jar" \
      "tao-java:tao_java.jar" \
      "i2jrt:i2jrt.jar" \
      "i2jrt-corba:i2jrt_corba.jar"; do \
      artifact_id="${artifact%%:*}"; \
      jar_name="${artifact#*:}"; \
      mvn --batch-mode --no-transfer-progress \
        org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
        -Dfile="${DDS_ROOT}/lib/${jar_name}" \
        -DgroupId=org.opendds \
        -DartifactId="${artifact_id}" \
        -Dversion="${OPENDDS_VERSION}" \
        -Dpackaging=jar \
        -DgeneratePom=true; \
    done

WORKDIR ${DDS_ROOT}
CMD ["bash"]
