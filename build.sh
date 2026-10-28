#!/bin/sh
set -eu

case "${1:-}" in
    --help|-h)
        cat <<'USAGE'
Usage: sh build.sh [Maven options]

Requires JDK 8 and Maven 3.6.3 or newer on PATH.
Runs: mvn -B clean package -DskipTests [Maven options]
WARNING: clean removes the project's target directory.

Examples:
  sh build.sh
  sh build.sh "-Dbundled.java.home=/opt/delivery/jdk8"
  sh build.sh -o

Choose a bundled Java runtime matching the delivery OS and CPU architecture.
USAGE
        exit 0
        ;;
esac

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

if ! command -v mvn >/dev/null 2>&1; then
    echo "[ERROR] Maven was not found. Install Maven 3.6.3 or newer and add its bin directory to PATH." >&2
    echo "[ERROR] Use JDK 8 for the build and configure JAVA_HOME if required by Maven." >&2
    exit 1
fi

echo "[INFO] Building Light DB Migration in \"$SCRIPT_DIR\""
echo "[INFO] Maven will create the executable JAR and delivery ZIP, including the Java runtime."
if mvn -B clean package -DskipTests "$@"; then
    echo "[INFO] Build completed."
    echo "[INFO] JAR: $SCRIPT_DIR/target/light-db-migration.jar"
    echo "[INFO] ZIP: $SCRIPT_DIR/target/light-db-migration-*.zip"
else
    BUILD_EXIT_CODE=$?
    echo "[ERROR] Maven build failed. Exit code: $BUILD_EXIT_CODE" >&2
    exit "$BUILD_EXIT_CODE"
fi
