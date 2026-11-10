# Spotless Applier Native

[![Build](https://github.com/miguelaguiar01/spotless-applier/workflows/Build/badge.svg)](https://github.com/miguelaguiar01/spotless-applier/actions)

<!-- Plugin description -->
**Spotless Applier Native** applies [Spotless](https://github.com/diffplug/spotless) formatting
inside IntelliJ IDEA in milliseconds, using the exact Spotless version and configuration declared
in your `pom.xml` or `build.gradle(.kts)`, without launching Maven or Gradle for every file.

A long-running worker JVM loads your build's own Spotless plugin and formats the in-memory document
(unsaved edits included, one undo step). When a configuration cannot be handled natively, the plugin
falls back to running the build tool and tells you why, once per project.

### Features

| Actions                                                     | Shortcuts (Windows)                                          | Shortcuts (macOS)                                              |
|-------------------------------------------------------------|--------------------------------------------------------------|----------------------------------------------------------------|
| <kbd>Code</kbd> > <kbd>Reformat File With Spotless</kbd>    | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>;</kbd>                  | <kbd>⌘Cmd</kbd>+<kbd>⌥Opt</kbd>+<kbd>;</kbd>                   |
| <kbd>Code</kbd> > <kbd>Reformat Project With Spotless</kbd> | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>;</kbd> | <kbd>⌘Cmd</kbd>+<kbd>⌥Opt</kbd>+<kbd>⇧Shift</kbd>+<kbd>;</kbd> |

- Native engine for **Maven** (`spotless-maven-plugin` 2.44+ / 3.x) and **Gradle**
  (`com.diffplug.spotless` 7.x / 8.x), byte-for-byte identical to `spotless:apply` / `spotlessApply`.
- Run on save (`Settings` > `Tools` > `Actions on Save` > `Run Spotless`), with file-type selection.
- Optional: run Spotless from the IDE's own `Reformat Code` (Settings > Tools > Spotless Applier Native).
- Multi-module projects, module selection dialog, "optimize imports before applying".
- Build-tool fallback for everything else, including Dev Containers (ijent).
<!-- Plugin description end -->

This plugin is the successor of [Spotless Applier](https://github.com/lipiridi/spotless-applier)
by Dimitrii Lipiridi (MIT). The build-tool execution path, settings and on-save integration started
from that work. Thank you.

## How native mode works

1. **Config extraction (IDE side).** For Maven, the plugin reads IntelliJ's imported effective model:
   the `spotless-maven-plugin` version and its plugin-level `<configuration>` merged with the
   `default-cli` execution, exactly what `mvn spotless:apply` uses. For Gradle, a small tooling
   extension runs during the normal Gradle sync and serializes each `spotless*` task's steps the way
   Spotless does for Gradle's configuration cache.
2. **Worker JVM.** One process per project (see `lib/worker/` in the plugin), started lazily on the
   project's JDK (Maven runner JRE → Gradle JVM → project SDK → IDE runtime; Java 17+ for Spotless
   3.x / 8.x). It resolves `spotless-maven-plugin:<version>` with Maven Resolver (honouring your
   `settings.xml`: mirrors, proxies, servers, offline) or uses the Gradle buildscript classpath, loads
   it in an isolated classloader and builds the real Spotless `Formatter`s. Formatters are cached per
   configuration hash; the worker exits after an idle timeout or when the project closes.
3. **Formatting.** The editor's text is sent to the worker; the result is applied as a single
   undoable edit that only replaces the changed range. `CLEAN` never touches the document.
   Line endings follow the configuration's policy (`GIT_ATTRIBUTES_FAST_ALLSAME` by default).

Nothing of Spotless is bundled: every formatter jar comes from your repositories, at your version.

## Settings

`Settings` > `Tools` > `Spotless Applier Native`

| Setting | Meaning |
|---------|---------|
| Engine | `Native (recommended)` or `Build tool` (always run `mvn` / `gradle`). |
| Show why the build tool was used | One notification per project and reason. |
| Start the worker when a project opens | Pre-warms the formatters. |
| Also run Spotless from Code \| Reformat Code | Experimental: registers Spotless as the formatter for covered files. |
| JDK home / idle timeout / max heap | Worker JVM. |
| Optimize imports before applying (+ no asterisk) | Runs IntelliJ's optimize imports first, as before. |
| Prohibit Gradle configuration cache | Fallback only. |

## Limitations and fallback reasons

The native engine hands a file to the build tool when it cannot guarantee identical output. The
reason is logged under `#com.github.miguelaguiar01.spotless` and shown once per project:

| Reason | Meaning |
|--------|---------|
| `NO_MAVEN_PROJECT` | The file is not in an imported Maven or linked Gradle module. |
| `NO_SPOTLESS_PLUGIN_FOUND` | No Spotless plugin declared (Maven), or no Spotless tasks seen during the last Gradle sync. |
| `SPOTLESS_VERSION_UNSUPPORTED` | Maven plugin < 2.44.0 or Gradle plugin outside 7.x / 8.x. |
| `EXTRACTION_FAILED` | Plugin version or configuration could not be read (e.g. unresolved `${property}`). |
| `WORKER_LOAD_FAILED` | Artifact resolution or formatter construction failed; details in the log. |
| `GRADLE_STEP_NOT_SERIALIZABLE` | A Gradle format (e.g. a Groovy `custom {}` step) cannot be restored outside Gradle. Other formats of the project stay native. |
| `NO_SUITABLE_JDK` / `WORKER_UNAVAILABLE` | No JDK ≥ 17 found, or the worker crashed repeatedly. |
| `NOT_IMPLEMENTED` | Remote (Dev Container) projects use the build tool in this version. |
| `USER_CHOICE` | Engine set to Build tool. |

Other known gaps: Gradle target sets are captured at sync time (new files need a re-sync, the IDE
falls back for them); `spotlessSetLicenseHeaderYearsFromGitHistory` is a command-line property and
is not set; lints (Spotless 3.x) are shown as an error notification but formatting is still applied.

## Troubleshooting

- Enable debug logging: `Help` > `Diagnostic Tools` > `Debug Log Settings` →
  `#com.github.miguelaguiar01.spotless`. Worker output (including formatter stdout/stderr) appears in
  `idea.log` with the `[worker]` prefix.
- The resolved classpath cache lives in `<IDE system dir>/spotless-applier-native/`; delete it after
  cleaning your local Maven repository.
- Formatting a file for the first time after opening a project takes a few seconds (worker start +
  formatter download/initialization); afterwards it is milliseconds. Enable pre-warming to hide it.

## Building

Requirements: a JDK 21 installed locally (the build uses a Gradle toolchain but does not
auto-download one; set `JAVA_HOME` to it or make sure Gradle can find it), and network access to
Maven Central and JetBrains' repositories (the first build downloads IntelliJ 2026.1, about 1.5 GB).

```
./gradlew buildPlugin    # -> build/distributions/spotless-applier-<version>.zip (no tests)
./gradlew build          # all tests except the parity corpus need no network
./gradlew :worker:test   # parity: runs real Maven/Gradle on fixtures/ (downloads formatters)
./gradlew runIde         # sandbox IDE with the plugin installed
```

Install the zip with Settings | Plugins | ⚙ | Install Plugin from Disk… (IntelliJ 2026.1+).

If your network blocks JetBrains' download hosts, compile against an installed IDE (2026.1 or
newer) instead; the plugin is still built for 2026.1+:

```
./gradlew buildPlugin -PplatformLocalPath="/path/to/IntelliJ IDEA"
# WSL against the Windows install:
./gradlew buildPlugin -PplatformLocalPath="/mnt/c/Program Files/JetBrains/IntelliJ IDEA 2026.3"
```

Maven Central and plugins.gradle.org must still be reachable (Kotlin, Gradle plugins, worker deps).
IntelliJ 2026.3 and newer ship Java 25 class files, which javac 21 cannot read: install a JDK 25
and add `-PbuildJdk=25` (the plugin is still compiled to Java 21 bytecode). If Gradle does not
auto-detect that JDK, add `-Porg.gradle.java.installations.paths=/path/to/jdk-25`.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DECISIONS.md](docs/DECISIONS.md),
[docs/BENCHMARKS.md](docs/BENCHMARKS.md) and [docs/PROGRESS.md](docs/PROGRESS.md).

## License

MIT, see [LICENSE](LICENSE). Spotless is Apache-2.0 and is loaded at runtime from your
repositories; it is not bundled.
