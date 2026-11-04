# PeerCraft 3.4.3 Beta release artifacts

32 client JARs built from the 3.4.2 release commit (aef204d7) plus the Fabric 1.16.5 Log4j binding fix and the version bump. Checksums in SHA256SUMS, CurseForge metadata in manifest.json, upload IDs in curseforge-uploads.json.

Bugfix release on top of 3.4.2.

- Fixed a crash on Fabric 1.16.5 during PeerCraft initialization: `NoSuchMethodError: org.apache.logging.log4j.spi.LoggerContextFactory.isClassLoaderDependent()`. The bundled SLF4J binding now matches the Log4j version shipped with Minecraft 1.16.5.

No other functional changes. Builds for other Minecraft versions and loaders are republished as 3.4.3 so that every version carries the same number.
