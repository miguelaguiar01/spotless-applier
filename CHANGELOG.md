<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Spotless Applier Native Changelog

## [Unreleased]

## [0.1.0] - 2026-11-10

### Added

- Native formatting engine: a worker JVM loads the build's own Spotless plugin and formats the
  in-memory document; no `mvn` / `gradle` process per file.
  - Maven: `spotless-maven-plugin` 2.44.x and 3.x, configuration read from IntelliJ's imported model.
  - Gradle: `com.diffplug.spotless` 7.x and 8.x, steps captured during Gradle sync.
- Per-file results are byte-for-byte identical to `spotless:apply` / `spotlessApply`
  (parity corpus in `fixtures/`).
- Settings: engine selection, worker JDK / idle timeout / heap, pre-warm on project open,
  optional integration with `Code | Reformat Code`, fallback-reason notifications.
- Reformat Project runs natively per module with bounded parallelism and a cancellable progress.
- On-save action now runs off the EDT with cancellable progress.

### Changed

- New plugin id `com.github.miguelaguiar01.spotless-applier-native` (successor of
  `com.github.lipiridi.spotless-applier`).
- Build-tool fallback uses `-DspotlessFiles` when the Maven home is an mvnd distribution
  (works around apache/maven-mvnd#1271).
