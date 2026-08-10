#!/usr/bin/env sh
set -e

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LOCAL_MAVEN="$SCRIPT_DIR/.tools/maven/apache-maven-3.9.9/bin/mvn"
if [ -x "$LOCAL_MAVEN" ]; then
  exec "$LOCAL_MAVEN" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

echo "Maven was not found. Install Maven or place it under .tools/maven/apache-maven-3.9.9/bin/mvn." >&2
exit 1
