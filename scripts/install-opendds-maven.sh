#!/usr/bin/env bash
set -Eeuo pipefail

: "${DDS_ROOT:?Set DDS_ROOT to a locally built OpenDDS 3.34.0 directory}"
if [[ ! -f "${DDS_ROOT}/setenv.sh" ]]; then
  echo "error: DDS_ROOT does not contain an OpenDDS setenv.sh" >&2
  exit 1
fi

for artifact in \
  'opendds-dcps:OpenDDS_DCPS.jar' \
  'tao-java:tao_java.jar' \
  'i2jrt:i2jrt.jar' \
  'i2jrt-corba:i2jrt_corba.jar'; do
  artifact_id="${artifact%%:*}"
  jar_name="${artifact#*:}"
  jar_path="${DDS_ROOT}/lib/${jar_name}"
  if [[ ! -f "${jar_path}" ]]; then
    echo "error: missing ${jar_path}; build OpenDDS with Java support first" >&2
    exit 1
  fi
  mvn --batch-mode --no-transfer-progress \
    org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="${jar_path}" \
    -DgroupId=org.opendds \
    -DartifactId="${artifact_id}" \
    -Dversion=3.34.0 \
    -Dpackaging=jar \
    -DgeneratePom=true
done
