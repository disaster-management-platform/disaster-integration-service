#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac --release 21 --add-modules jdk.httpserver -d "$classes" \
  src/main/java/platform/integration/domain/OperationIdentity.java \
  src/main/java/platform/integration/domain/ExternalReportSource.java \
  src/main/java/platform/integration/integration/http/BoundedTransport.java \
  tests/LocalChecks.java
java --add-modules jdk.httpserver -cp "$classes" LocalChecks
