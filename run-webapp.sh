#!/usr/bin/env bash
#
# Runs the web dashboard (com.cardco.WebApp) in its own forked JVM process.
#
# Usage:
#   ./run-webapp.sh                                        # http://localhost:7000
#   PORT=8080 ./run-webapp.sh                               # custom port
#   EXTRA_JAVA_OPTS="-Dkms.enabled=false" ./run-webapp.sh    # force no-KMS mode for this run
#
# Ctrl+C stops it cleanly (shuts down the fraud-scoring worker, the web
# server, and all AWS SDK clients via the shutdown hook in WebApp.java).

set -euo pipefail

EXTRA_JAVA_OPTS="${EXTRA_JAVA_OPTS:-}"
PORT="${PORT:-7000}"

mvn compile exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="${EXTRA_JAVA_OPTS} -Dserver.port=${PORT} -classpath %classpath com.cardco.WebApp"
