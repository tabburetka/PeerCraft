#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${JAVA8_HOME:?Set JAVA8_HOME to a real JDK 8}"
: "${GSON_JAR:?Set GSON_JAR to a Java 8 compatible Gson jar}"
probe_dir=$(mktemp -d)
trap 'rm -rf "$probe_dir"' EXIT
"$JAVA8_HOME/bin/javac" -cp "$GSON_JAR" -sourcepath mod/src/core/java -d "$probe_dir" \
 mod/src/core/java/net/peercraft/network/modsync/{ModEntry,ModSyncPlan,ModrinthCatalogClient,ModrinthDownloader}.java \
 mod/tests/modrinth-java8/ModrinthJava8Probe.java
"${JAVA8_BIN:-$JAVA8_HOME/bin/java}" -cp "$probe_dir:$GSON_JAR" net.peercraft.network.modsync.ModrinthJava8Probe "$@"
