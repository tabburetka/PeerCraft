# Modrinth downloads on Java 8

Minecraft 1.7.10, 1.12.2 and 1.16.5 share `ModrinthCatalogClient` and
`ModrinthDownloader` from `peercraft/src/modsync-java8`. Their client adapters
resolve an exact SHA-512 match to an HTTPS URL on `cdn.modrinth.com`.
Only listed versions receive the existing public catalog label. That label is
not a security verdict, and SHA-512 verifies byte identity, not mod safety.

The downloader uses the JVM's default TLS certificate and hostname validation.
It rejects redirects, other hosts, HTTP URLs, unsuccessful responses, declared
length mismatches, truncated files, excess bytes and hash mismatches. Connect
and read timeouts are 15 seconds; streaming also has a ten-minute deadline.
Failed and cancelled attempts remove their partial file. Cancellation is checked
between reads, so an outstanding network read can take up to its timeout.

Catalog errors retain the P2P plan. Download and verification failures request
the original host file over P2P, unless the user cancelled. Every completed file
is checked against the host manifest's size and SHA-512 again before installation.

## Reproducible checks

Run from the repository root, with a JDK 8 and a Java 8 compatible Gson jar:

```bash
export JAVA8_HOME=/path/to/jdk8
export GSON_JAR=/path/to/gson.jar
bash peercraft/tests/modrinth-java8/run.sh
```

The standalone probe installs a process-local fake HTTPS transport for 34 checks
of catalog selection, transfer integrity, failure cleanup and cancellation.
It runs without Minecraft. To test a separate Java 8 JRE, set `JAVA8_BIN` to that
JRE's `bin/java`; compilation still uses `JAVA8_HOME`.

For a live check, obtain a listed file's size and SHA-512 from Modrinth and run:

```bash
bash peercraft/tests/modrinth-java8/run.sh live "$FILE_SIZE" "$FILE_SHA512"
```

This mode uses the production catalog lookup and downloader with real HTTPS,
and deletes the downloaded temporary JAR after verification.

Verified on 2026-10-19:

| Runtime | Offline checks | Real catalog and CDN, default TLS validation |
| --- | --- | --- |
| Temurin 1.8.0_504-b01 | 34 passed | Passed |
| Oracle 1.8.0_202-b08 (Prism `jre-legacy`) | 34 passed | Passed |

The live file was Fabric API `0.42.0+1.16`, 1,090,414 bytes. Its size and SHA-512
matched the manifest supplied to the probe. Compile checks passed for Forge
1.7.10, Forge 1.12.2 and Fabric 1.16.5. The existing 1.7.10 mod-sync test suite
passed with 16 tests executed and one skipped.

These checks do not exercise a running Minecraft client. In each client family,
verify the confirmation source, download progress, cancel button and restart
installation; also block CDN access and confirm that the host's P2P transfer
finishes. Older Java 8 builds and different trust stores require their own live
TLS check; no certificate validation bypass is provided.
