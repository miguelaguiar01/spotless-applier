# Progress

Running log of phase status. Newest entries at the top of each section.

## Phase status

| Phase | Title                                   | Status      |
|-------|-----------------------------------------|-------------|
| 0     | Recon and design                        | done (checkpoint confirmed 2026-11-10) |
| 1     | Worker skeleton and IPC                 | **done** |
| 2     | Maven config extraction (IDE side)      | **done** |
| 3     | Worker, Maven mode (core + parity)      | **core done**, parity corpus in progress |
| 4     | IDE integration                         | not started |
| 5     | Gradle support                          | not started |
| 6     | Fallback path hardening                 | not started |
| 7     | Polish and release                      | not started |

## Phase 3 — what was done so far (2026-11-10)

- `worker/resolve`: `MavenSettingsLoader` (settings.xml via
  `DefaultSettingsBuilderFactory`, decryption via sec-dispatcher),
  `ResolverEnvironment` (Resolver 2.0.25 `RepositorySystemSupplier` +
  `SessionBuilderSupplier`, local repo, offline, mirrors/proxies/servers,
  `newResolutionRepositories`), `PluginClasspathResolver` (runtime-scope
  transitive resolution of `spotless-maven-plugin:<v>`, JSON cache per
  version under `--cache-dir`, validated against the local repo).
- `worker/maven`: `SpotlessClassLoader` (child-first with parent-first
  allowlist), `MavenEngine` (resolve → classloader → adapter), `WorkerContext`
  (adapter jar discovery next to the worker jar, one resolver per
  settings/local-repo/offline tuple).
- `worker-maven-adapter`: `MavenAdapterImpl` (ADR-018/019), `XmlToPlexus`,
  `SimpleExpressionEvaluator`, `ConsumerLog`, `LintSupport`.
- IDE launcher passes `--cache-dir=<system>/spotless-applier-native`.
- Test `MavenModeTest`: real worker JVM resolves 3.11.0 from Central
  (28 jars, ~5 s cold / ~2.5 s warm on this machine), formats with
  google-java-format, custom `<formats>` block, `NOT_COVERED` for files
  outside includes or outside the module, cache file written, unknown step
  → `WORKER_LOAD_FAILED` with the same message Maven gives.

## Phase 2 — what was done (2026-11-10)

- `MavenSpotlessConfigExtractor`: finds `spotless-maven-plugin` in IntelliJ's
  effective `MavenProject` model (inherited/pluginManagement included),
  merges plugin-level `<configuration>` with the `default-cli` execution
  using a JDOM port of Maven's `Xpp3Dom.mergeXpp3Dom` (`XmlMerge`, incl.
  `combine.self/children/id/keys`), interpolates leftover `${...}` from
  project properties + built-ins (`project.basedir`, `project.build.directory`,
  `settings.localRepository`, env, system), and captures source roots,
  plugin repositories, effective local repo / settings files and offline
  mode into `SpotlessConfig`. Versions below 2.44.0 → `SPOTLESS_VERSION_UNSUPPORTED`.
- `SpotlessConfigService` (project service): caches per module, listens to
  `MavenProjectsTree.Listener` and `MavenImportListener`, re-extracts on
  import and invalidates only configIds that changed in the worker.
- Tests: 5 `XmlMerge`/interpolation unit tests; 6 `MavenImportingTestCase`
  tests importing fixture POMs through IntelliJ's real importer (single
  module with placeholders, parent inheritance, pluginManagement,
  default-cli vs phase-bound executions, no plugin / old version, service
  cache refresh on re-import). Placeholders come back resolved.
- Gotchas recorded: the test IDE only loads bundled plugins our descriptor
  depends on, and the Maven plugin needs `com.intellij.properties` and
  `org.jetbrains.idea.reposearch` → declared as optional dependencies
  (ADR-017). Maven import tests must run off the EDT
  (`runInDispatchThread() = false`). `MavenId` lives in the reposearch
  plugin and is not referenced.

## Phase 1 — what was done (2026-11-10)

- Gradle multi-project scaffold: root plugin (Kotlin, IPGP 2.10.5, platform
  2026.1), `protocol` (Java 17, zero deps) and `worker` (Java 17, depends on
  protocol only). Worker jars are copied to `lib/worker/` by every
  `PrepareSandboxTask` and end up in the distribution zip there (verified);
  they are not on the plugin classloader (ADR-015).
- `protocol`: hand-written JSON, 4-byte length-prefixed frames, typed
  `Message` records (`hello`, `ping/pong`, `loadConfig/configLoaded`,
  `format/formatResult`, `cancel`, `invalidate`, `ack`, `error`, `log`,
  `shutdown`), `SpotlessConfig` DTO with SHA-256 `configId`. 7 unit tests.
- `worker`: `WorkerMain` captures `FileDescriptor.in/out` first, replaces
  `System.out/err` with non-closable line-forwarding streams, nulls
  `System.in`; `WorkerServer` dispatches on a reader thread, formats on a
  pool, supports cancel, idle timeout (`--idle-timeout-ms`), exits on stdin
  EOF, `halt()`s at the end so stuck formatter threads cannot keep it alive.
  `BuiltinTestEngine` provides trivial steps and deliberate misbehavior.
  8 integration tests spawn a real JVM, including "formatter prints to and
  closes System.out, channel still works".
- IDE side: `SpotlessApplierSettings` (complete state, UI later),
  `WorkerJdkResolver` (settings → Maven runner JRE → Gradle JVM → project
  SDK → IDE runtime, version-checked via `JdkVersionDetector`),
  `WorkerLauncher` (JVM flags per ADR-014, `-cp` over `lib/worker/*.jar`,
  override property `spotless.applier.worker.dir`), `WorkerConnection`
  (reader thread, pending-request map, cancellation → `cancel` frame,
  death propagation), `WorkerManager` project service (lazy start,
  handshake, restart with backoff: 3 failures → 1 min cooldown, configs
  re-sent after restart, graceful shutdown on dispose, worker logs under
  `#com.github.miguelaguiar01.spotless.worker`). 3 platform tests.
- Not done / deferred: periodic health ping (request timeouts + process exit
  detection cover it for now), Eel/remote spawning (Phase 6), `runIde`
  manual check (headless session; sandbox layout verified on disk instead).

## Phase 0 — what was done (2026-11-10)

- Determined the repo is a **fresh successor**, not a fork (ADR-001).
- Cloned `lipiridi/spotless-applier` 1.3.0 to scratch, read all ~20 source
  files, `plugin.xml`, build files and CI. Built it with its own wrapper
  (Gradle 9.2.1, IntelliJ Platform Gradle Plugin 2.10.5, platform 2026.1,
  Java 21): `buildPlugin test` → BUILD SUCCESSFUL in 43s (warm cache),
  `spotless-applier-1.3.0.zip` produced. The original has **no tests**
  (`:test NO-SOURCE`). The platform `IU-261.22158.277` is now in the Gradle
  cache. `runIde` was not exercised (headless session); `buildSearchableOptions`
  did launch the IDE headless successfully, which covers the same classpath.
- Cloned `diffplug/spotless`, checked out `maven/3.11.0` and `maven/2.44.5`;
  read `lib`, `lib-extra`, `plugin-maven`, `plugin-gradle` sources.
  Downloaded `spotless-maven-plugin-3.11.0.jar` and inspected
  `META-INF/maven/plugin.xml`. Re-verified every fact from the brief
  (ADR-004).
- Sparse-cloned `intellij-community` branch `261` (Maven, Gradle, code-style,
  actions-on-save, Eel) and verified the IntelliJ APIs (ADR-013).
- Verified the mvnd 1.0.6 NPE mechanism in mvnd sources (ADR-008).
- Checked Maven Central for the resolver/Plexus artifacts the worker will
  use (ADR-005, ADR-007) and read Sisu-Plexus 1.1.2 sources to confirm the
  `set*`/`add*` property mapping.
- Wrote `ARCHITECTURE.md`, `DECISIONS.md`, this file.

## Open questions for the checkpoint

1. Plugin id/name (ADR-002).
2. Supported range: Maven 3.x + 2.44.x, Gradle 8.x + 7.0.x (ADR-003). Older
   versions fall back to the build tool.
3. v1 scope for `ratchetFrom` (native via JGit, with fallback on failure) and
   for remote/ijent projects (worker via Eel exec, fallback otherwise)
   (ADR-011).
4. Lints shown as warnings, formatting still applied (ADR-010).
5. Order of work: Phase 1 (worker + IPC) first, then Phase 2 and 3 (Maven
   end to end) before any IDE polish, as the brief requests.

## Riskiest unknowns

- **Whether IntelliJ's `MavenProject.plugins` configuration is fully
  interpolated** (`${project.basedir}`, custom properties). If not, the
  extractor interpolates with `MavenProject.properties` + built-ins. To be
  settled by a fixture in Phase 2.
- **Parity of `GIT_ATTRIBUTES_FAST_ALLSAME`**: depends on the first file of
  Maven's `FileUtils.getFiles` scan order. The worker reproduces the same scan
  (same `plexus-utils` version from the plugin's own classpath), but file
  system ordering differences between platforms are a possible source of
  divergence. Mitigation: policy evaluated per format on the same machine the
  build runs on; parity tests compare against a real `mvn` run on the same
  machine.
- **Plexus configurator outside Maven**: `BasicComponentConfigurator` needs a
  `ClassRealm` and an `ExpressionEvaluator`; enum conversion
  (`LineEnding`), `File` conversion relative to basedir, and `List<Format>`
  with `implementation` hints must behave as under Maven. Covered by the
  Phase 3 parity corpus.
- **Spotless 2.44.x adapter**: different constructor arities and no
  `P2Provisioner`; Eclipse steps there use a different provisioning path.
  Scope decision: support 2.44.x for the common steps and route Eclipse-based
  steps on 2.x to the fallback if they fail to construct.
- **Gradle mode relies on internals** (`getStepsInternalRoundtrip`, Java
  serialization of steps across classloaders). Allow-list gating and a
  per-format fallback are mandatory; expect churn across Spotless releases.
- **Remote/ijent worker**: spawning a JVM inside the container via Eel and
  shipping the worker jar is untested; the JDK inside the container may be
  missing or too old → fallback.
- **Worker JDK on JDK 25+**: Spotless' reflective `ModuleHelper` hack may be
  blocked; we pass explicit `--add-opens/--add-exports` (ADR-014). Needs a
  test on the bundled JBR 25.

## Next steps (Phase 3)

- Worker: Maven Resolver (supplier-mvn3) with settings.xml, resolve the
  plugin classpath per version, child-first classloader, adapter module
  compiled against the Spotless Maven plugin API that configures
  `SpotlessApplyMojo` via Plexus and builds Formatters; `DirtyState`-based
  format; includes/excludes → `NOT_COVERED`.
- Parity corpus + golden files generated with a real Maven.
