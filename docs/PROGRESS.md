# Progress

Running log of phase status. Newest entries at the top of each section.

## Phase status

| Phase | Title                                   | Status      |
|-------|-----------------------------------------|-------------|
| 0     | Recon and design                        | done (checkpoint confirmed 2026-11-10) |
| 1     | Worker skeleton and IPC                 | **done** |
| 2     | Maven config extraction (IDE side)      | **done** |
| 3     | Worker, Maven mode (core + parity)      | **done** (7/7 parity fixtures byte-identical) |
| 4     | IDE integration                         | **done** |
| 5     | Gradle support                          | **done** (3/3 parity fixtures, custom closure → fallback) |
| 6     | Fallback path hardening                 | **done** (remote worker deferred, see known gaps) |
| 7     | Polish and release                      | **done, awaiting checkpoint** (plugin verifier runs in CI) |

## Phase 7 — what was done (2026-11-10)

- `docs/BENCHMARKS.md` from `MavenBenchmarkTest` (p50 29 ms native vs 4.4 s build tool).
- README (how it works, settings, fallback reasons, troubleshooting), CHANGELOG 0.1.0,
  `.github/workflows/build.yml` (build + all tests incl. parity, plugin verifier job).
- `verifyPluginStructure` passes locally; `verifyPlugin` (IDE matrix) left to CI because of the
  IDE downloads.
- Worker JVM flags: dropped `-XX:TieredStopAtLevel=1` (hurt steady-state formatting latency).

### Definition of done (from the brief)
1. Maven native, no mvn process, byte-identical across the corpus — **yes** (7 fixtures).
2. Gradle within the allow-list, automatic fallback with reason — **yes** (3 fixtures; `custom {}` falls back).
3. Warm latency recorded, large improvement — **yes** (`docs/BENCHMARKS.md`).
4. File/project actions, on-save, optimize imports, Dev Containers — **yes** (Dev Containers via the build-tool path).
5. Docs current — **yes**.

### Manual `runIde` pass (2026-11-10, evening)
- Maven: a throwaway project (`~/Github/spotless-maven-demo`, spotless-maven-plugin 3.11.0 with
  google-java-format, license header, import order, toggleOffOn, sortPom and a custom `*.md` /
  properties format) was opened in the sandbox IDE. File action, project action, on-save,
  settings page and the build-tool fallback all worked as expected.
- Follow-ups from the idea.log of that session: worker stderr lines were forwarded at WARN even
  when slf4j printed them as INFO (fixed: the level in the line is honoured, blank lines dropped),
  and the JVM printed `Picked up JAVA_TOOL_OPTIONS:` because the launcher blanked the variable
  instead of removing it (fixed). Two "cannot execute background write action in 10 seconds"
  warnings were checked against the thread dumps: the EDT was parked in platform modal dialogs,
  not in plugin code.

### Not verified in this session
- Interactive `runIde` pass on a **Gradle** project (the Maven pass is above).
- Plugin verifier: first CI run (2026-11-10 evening) verified against IU-261.27258.48,
  IU-262.11879.100 and IU-263.7307.200. Compatible with 261; 262/263 reported one real
  compatibility problem (Kotlin-generated super-call stub for the removed two-argument
  `MavenProjectsTree.Listener.projectResolved`) and one internal-API use
  (`PluginManagerCore.getPlugin`, and its replacement `PluginManager.findEnabledPlugin` is
  internal on 262+ as well; the worker dir is now derived from the plugin jar via
  `PathManager.getJarPathForClass`). Both fixed (ADR-021). CI run 42898464393 (commit 61eb3d3):
  **Compatible** against IU-261.27258.48, IU-262.11879.100 and IU-263.7307.200, verifier job
  green. Remaining reports are deprecated / experimental API usages (Eel, `writeCommandAction`,
  `GradleUtil.findGradleModuleData`), which do not fail the build.
- Building on a corporate network (2026-11-10, user's workplace, WSL Ubuntu 24.04 + Windows
  IntelliJ 2026.3): `-PplatformLocalPath=<installed IDE>` compiles against a local IntelliJ when the
  web filter blocks JetBrains' hosts (classified "Generative AI"). Findings, all fixed on `main`:
  corporate root CA must be imported into the WSL JDK cacerts; `gradlew` arrived CRLF
  (`.gitattributes` now pins LF); the IDE install folder keeps its original name after in-place
  updates (a folder named 2025.2.4 held build 263); scanning the IDE over `/mnt/c` takes
  10-20 min (copying it into the Linux FS is faster); searchable options and code instrumentation
  are excluded in local mode (IDE not launchable from WSL, `java-compiler-ant-tasks` blocked);
  the daemon needs more than 512 MiB (`org.gradle.jvmargs` set); 2026.3 bundles Kotlin 2.4
  metadata (compiler moved to 2.4.21, apiVersion pinned 2.2) and Java 25 class files
  (`-PbuildJdk=25`, output still Java 21); `MavenProjectsTree.Listener.projectResolved(Pair)` no
  longer exists on 2026.3 (overridden without `@Override`).
- **2026-11-11, WSL support (ADR-022):** the work laptop's log showed every format had gone through
  the `mvn` fallback: the project lives in WSL, the Maven runner JRE is a Linux JDK, and the worker
  was started as a Windows process (`WORKER_UNAVAILABLE`). Added `WorkerTarget` (`LocalTarget` /
  `WslTarget`): worker launched inside the distribution via `WSLDistribution.patchCommandLine`,
  JDK candidates filtered to the distribution, `SpotlessConfig.mapPaths` + `WslPathMapper` for
  path translation, Gradle target sets mapped back for VFS lookups. Unit-tested mapper/config
  mapping; the launch itself is verified by the user on Windows (cannot run here).
- **Real-world check (2026-11-10 night):** plugin built against 2026.3 installed in the user's
  work IntelliJ 2026.3; native formatting works on a corporate Maven project ("It works!").
- Windows paths (parity ran on macOS only).

## Phase 5 — what was done (2026-11-10)

- `gradle-tooling` module: `SpotlessGradleModel` (+impl), `SpotlessTaskInspector`,
  `SpotlessModelBuilder` registered via `META-INF/services` (ADR-020).
- IDE: `SpotlessGradleProjectResolver` (EP `org.jetbrains.plugins.gradle.projectResolve`),
  `SpotlessGradleData` DataNode, `GradleSpotlessConfigExtractor` (allow-list
  7.x/8.x), `SpotlessConfigService.lookupGradle`, invalidation on sync.
- Worker: `GradleEngine`, `GradleAdapter` SPI, `worker-gradle-adapter`
  (deserialize + `Formatter.builder()` + ratchet + DirtyState).
- Protocol: `SpotlessConfig.Gradle` / `GradleFormat`.
- Tests: `SpotlessTaskInspectorTest` (TestKit + init script on the Kotlin
  DSL fixture), `GradleParityTest` on `fixtures/gradle/{kts,groovy,v7}`
  vs. `gradle spotlessApply` (Groovy `custom {}` expected to fall back).
- `ProjectFormatter` formats Gradle modules natively from the captured
  target set.

## Phase 6 — what was done (2026-11-10)

- mvnd: when the configured Maven home path contains `mvnd`, the fallback
  passes `-DspotlessFiles=\Q<abs path>\E` instead of `-DspotlessIdeHook`
  (IntelliJ itself never launches the mvnd daemon; see ADR-011).
- ijent/Dev Containers: the ported build-tool path keeps the Eel path
  translation; the native engine falls back for non-local files (ADR-011).

### Known gaps
- Worker inside Dev Containers (Eel-spawned) — deferred; native engine
  falls back to the build tool there.
- `spotlessSetLicenseHeaderYearsFromGitHistory` is a user property and is
  not set by the IDE (same as the original plugin).

## Phase 4 — what was done (2026-11-10)

- `FormatService` (project service): native path = config lookup →
  worker → one undoable `writeCommandAction` that replaces only the differing
  middle range (caret/folds outside survive); `CLEAN` never touches the
  document; `NOT_COVERED` / `DID_NOT_CONVERGE` / `ERROR` / lints reported;
  every "can't" returns `FellBack(reason, detail)` and callers run the
  build-tool engine. Remote (ijent) files fall back in this version.
- Build-tool engine: the original plugin's `ReformatProcessor`,
  `TaskUnderProgress`, `ToolEnvExternalSystemUtil`, `ModuleInfo`, `BuildTool`
  ported verbatim (package rename, settings class) under `buildtool/`;
  optimize-imports (`SynchronousOptimizeImportsProcessor`), on-save options
  UI and module-selection dialog ported likewise.
- Actions: `ReformatFileAction` (optimize imports on EDT, then background
  native format, fallback on EDT), `ReformatProjectAction` (module dialog as
  before; `ProjectFormatter` streams candidate files with parallelism 4 under
  a cancellable background progress, edits open documents, writes others
  with the policy's line ending, refreshes VFS; modules without native config
  go to the build tool), `ReformatActionOnSave` now implements
  `DocumentUpdatingActionOnSave` (cancellable, off-EDT) with the same
  file-type selection UI.
- Settings UI (Kotlin UI DSL): Engine, show fallback reasons, pre-warm,
  Reformat Code integration (opt-in), worker JDK/idle timeout/heap,
  optimize imports (+asterisk), Gradle configuration-cache flag.
- `SpotlessFormattingService` (`AsyncDocumentFormattingService`): opt-in via
  settings; `canFormat` only for files with a native config.
- `SpotlessNotifications`: status-bar successes, balloon errors, fallback
  reason once per project with a "Don't show" action.
- Startup: on-save options warm-up off EDT (original ijent fix) + optional
  pre-warm of every Maven module's config.
- Tests: `FormatServiceTest` (minimal replacement keeps caret, one undo
  restores, CLEAN leaves the document untouched).
- Not done yet: `docs/BENCHMARKS.md` numbers (needs an interactive `runIde`
  session or a scripted measurement; worker-side timings are logged).

## Phase 3 — parity corpus (2026-11-10)

`fixtures/maven/*` + `MavenParityTest`: each fixture is copied twice; a real
Apache Maven 3.10.0 (downloaded by Gradle as `org.apache.maven:apache-maven:bin@zip`)
runs `spotless:apply` on one copy, the native engine formats the original
content of every file in the other, and results must be byte-identical
(Maven-unchanged files must come back `CLEAN` or `NOT_COVERED`). All 7 pass:

| fixture    | Spotless | steps |
|------------|----------|-------|
| gjf-full   | 3.11.0   | toggleOffOn, licenseHeader(file, `${project.basedir}`), removeUnusedImports, importOrder, googleJavaFormat(reflow, `${gjf.version}`), formatAnnotations; `<formats>` with includes/excludes, trimTrailingWhitespace, endWithNewline, indent |
| palantir   | 3.10.4   | palantirJavaFormat, removeUnusedImports, licenseHeader(content), `<pom><sortPom>`, `lineEndings=UNIX` |
| eclipse    | 3.11.0   | eclipse JDT with settings file (P2 provisioning) |
| ktfmt      | 3.11.0   | kotlin ktfmt + licenseHeader |
| generic    | 2.44.5   | formats: trim/endWithNewline/indent(tabs)/replaceRegex; json gson (previous major) |
| prettier   | 3.11.0   | prettier 3.3.3 via npm with config (skipped when npm is absent) |
| ratchet    | 3.11.0   | ratchetFrom=main (JGit-created repo, edits overlaid) + googleJavaFormat + licenseHeader |

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

## Next steps (after the checkpoint)

- Manual `runIde` pass on a real Gradle project (Maven done 2026-11-10).
- Worker inside Dev Containers through the Eel exec API.
- Windows CI leg for the parity corpus.
