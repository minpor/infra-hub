#!/usr/bin/env bash
# Build distributable files locally; does not start or update a server.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :backend:bootJar --no-daemon
npm --prefix frontend ci
npm --prefix frontend run build
mkdir -p deploy/artifacts
cp backend/build/libs/infra-hub.jar deploy/artifacts/app.jar
rm -rf deploy/artifacts/dist
cp -R frontend/dist deploy/artifacts/dist
printf '%s\n' 'Release files prepared in deploy/artifacts. See docs/self-hosting.md.'
