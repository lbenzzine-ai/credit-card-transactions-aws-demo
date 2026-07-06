#!/usr/bin/env bash
#
# Runs the CLI demo (com.cardco.Demo) in its own forked JVM process, so it
# shuts down cleanly with no lingering-thread warnings from Maven.
#
# Usage:
#   ./run-demo.sh                                   # normal (KMS mode, whatever setup.sh last wrote)
#   EXTRA_JAVA_OPTS="-Dkms.enabled=false" ./run-demo.sh   # force no-KMS mode for this run
#
# (Only works if that mode's AWS resources actually exist — see
#  infrastructure/setup.sh's ENABLE_KMS option.)

set -euo pipefail

EXTRA_JAVA_OPTS="${EXTRA_JAVA_OPTS:-}"

mvn compile exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="${EXTRA_JAVA_OPTS} -classpath %classpath com.cardco.Demo"
