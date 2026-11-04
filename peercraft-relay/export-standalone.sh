#!/usr/bin/env sh
# Copies this module into a standalone directory (the public peercraft-relay repository),
# bringing along the wire-format codec that the monorepo shares with the mod.
set -eu
target=${1:?usage: ./export-standalone.sh TARGET_DIR}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$target"
(cd "$here" && tar --exclude=./build --exclude=./.gradle --exclude=./data --exclude=./relay.properties \
    --exclude=./export-standalone.sh -cf - .) | (cd "$target" && tar -xf -)
mkdir -p "$target/src/main/java/net/peercraft/network/ownrelay"
cp "$here/../peercraft/src/main/java/net/peercraft/network/ownrelay/OwnRelayProtocol.java" \
    "$target/src/main/java/net/peercraft/network/ownrelay/"
echo "Exported to $target"
