#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA="${RPC_TOOLCHAINS:-/workspace/toolchains}/jdk8u462-b08/bin/java"
cd "$ROOT"
case "${1:-}" in
 server) MODULE=example-server; MAIN=NettyServerMain ;;
 client) MODULE=example-client; MAIN=github.javaguide.NettyClientMain ;;
 *) echo 'Usage: scripts/example.sh server|client' >&2; exit 2 ;;
esac
[[ -f "$MODULE/target/classpath.txt" ]] || { echo 'Run scripts/mvn.sh install dependency:build-classpath -Dmdep.outputFile=target/classpath.txt first' >&2; exit 1; }
exec "$JAVA" -ea -XX:ActiveProcessorCount=4 -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$MODULE/target/classes:$(cat "$MODULE/target/classpath.txt")" "$MAIN"
