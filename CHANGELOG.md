<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Spotless Applier Native Changelog

## [Unreleased]

### Added

- Native formatting engine: a worker JVM loads the build's own Spotless plugin and formats the
  in-memory document (no `mvn`/`gradle` process per file).
