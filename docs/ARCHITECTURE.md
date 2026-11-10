# Architecture

Status: Phase 0 design. Nothing here is implemented yet; see `PROGRESS.md`.

## Goal

Format a file with Spotless in milliseconds by calling the Spotless library
directly in a long-running worker JVM, using the exact Spotless version and
configuration declared in the user's `pom.xml` or `build.gradle(.kts)`.
Launching `mvn` / `mvnd` / `gradle` per format survives only as a fallback.

## What this repository is

A fresh repository (MIT, (c) 2026 Miguel d'Aguiar) that becomes the successor
of `lipiridi/spotless-applier` 1.3.0. It is **not** a git fork. The original
plugin's build-tool execution path, settings, on-save integration and
Dev Container (ijent/Eel) handling are re-implemented here as the fallback
engine and the IDE-integration baseline. The original author (Dimitrii
Lipiridi) is credited in the README and the MIT notice is kept.

Consequences:

- New plugin id and name (see `DECISIONS.md`, ADR-002).
- Same IDE compatibility baseline as the original: platform 2026.1,
  `sinceBuild = 261`, Java 21 toolchain, IntelliJ Platform Gradle Plugin 2.x.

## High-level picture

```
IDE process (plugin)                            Worker JVM (one per project x Spotless version)
──────────────────────────────                  ─────────────────────────────────────────────
ConfigExtractor                                 WorkerMain
  Maven:  IntelliJ MavenProject model             - protects stdout/stderr (see IPC)
  Gradle: sync-time ModelBuilderService           - length-prefixed JSON frames over stdin/stdout
     │ SpotlessConfig DTO (hash)                   - child ClassLoader per Spotless version
     ▼                                            - MavenMode: XML -> plugin config objects -> Formatter
WorkerManager  ──spawn/IPC──────────────────►     - GradleMode: deserialize FormatterStep[] -> Formatter
  lifecycle, health, restart, idle shutdown       - caches Formatter per configId
     ▲                                            - answers CLEAN | FORMATTED | DID_NOT_CONVERGE |
FormatService ── format(path, text) ──────────►              NOT_COVERED | ERROR
  reads Document, applies result in a
  WriteCommandAction (undoable)
FallbackRunner (build-tool path; used when native can't handle a config)
```

Principles (from the mission brief, kept as the design contract):

1. Never load formatters into the IDE JVM.
2. Version fidelity beats convenience: format with the build's Spotless version.
3. Format the in-memory `Document`, not the file on disk.
4. Always have a fallback, with a logged, user-visible reason.
5. Warm and cached: lazy Formatters, cache by config hash, keep worker alive.

## Module layout (proposed)

```
spotless-applier/
├── build.gradle.kts              root: IntelliJ Platform Gradle Plugin, bundles :worker and :protocol
├── settings.gradle.kts
├── protocol/                     plain Java 17, zero deps. Message DTOs, JSON codec, framing.
│                                 Shared by plugin and worker. Shipped in both.
├── worker/                       plain Java 17, no IntelliJ deps. Shipped as lib/worker-*.jar.
│   ├── bootstrap                 WorkerMain, stdio protection, framing loop, classloader builder
│   ├── resolve                   Maven Resolver (supplier-mvn3) wrapper: resolve plugin classpath,
│   │                             settings.xml (mirrors/proxies/servers/offline), per-version cache
│   ├── maven                     MavenMode: Plexus configurator -> FormatterFactory objects ->
│   │                             Formatter; includes/excludes matching; NOT_COVERED logic
│   └── gradle                    GradleMode: deserialize steps in build-classpath classloader
├── gradle-tooling/               (Phase 5) ModelBuilderService that runs inside the Gradle daemon
│                                 during IDE sync. Separate jar, Java 8-compatible bytecode? (verify
│                                 against the minimum Gradle version we support; Gradle 7+ runs on 8+)
├── src/main/kotlin|java          the IDE plugin
│   ├── engine/                   FormatEngine interface; NativeEngine; BuildToolEngine (fallback)
│   ├── config/maven              MavenConfigExtractor (MavenProjectsManager, MavenPlugin, settings)
│   ├── config/gradle             GradleConfigExtractor (reads DataNodes produced by resolver ext.)
│   ├── worker/                   WorkerManager (spawn via GeneralCommandLine or Eel), WorkerClient
│   ├── format/                   FormatService (Document <-> worker), batch project formatting
│   ├── actions/                  ReformatFileAction, ReformatProjectAction, on-save
│   ├── settings/                 settings state + UI (Engine, worker JDK, idle timeout, pre-warm...)
│   └── fallback/                 current build-tool runner, mvnd-safe (uses -DspotlessFiles)
├── fixtures/                     parity corpus: Maven/Gradle projects + golden outputs
└── docs/
```

## IPC protocol (v1)

Transport: the worker's **real** stdin/stdout file descriptors, captured
before any user code runs:

```java
// first statements in WorkerMain
var protocolIn  = new FileInputStream(FileDescriptor.in);
var protocolOut = new FileOutputStream(FileDescriptor.out);
System.setOut(new NonClosablePrintStream(logSink));   // forwards to worker log; close() is a no-op
System.setErr(new NonClosablePrintStream(logSink));
System.setIn(InputStream.nullInputStream());
```

Rationale: Spotless `IdeHook.performHook` (both Maven and Gradle) calls
`System.err.close(); System.out.close();` in a `finally` block and arbitrary
formatter code may print to stdout. With the capture above, nothing a step
does can corrupt the channel or kill the process. The log sink is forwarded to
the IDE as `log` frames (and to a rolling file next to the worker's temp dir)
and written to `idea.log` under the logger category
`#com.github.miguelaguiar.spotless.worker`.

Framing: `uint32 big-endian length` + UTF-8 JSON object. One frame = one
message. Every request has an `id`; responses echo it. The worker may send
unsolicited `log` and `progress` frames.

Messages (all carry `"type"`):

| Direction | type           | payload                                                                 |
|-----------|----------------|-------------------------------------------------------------------------|
| IDE→W     | `hello`        | `protocolVersion`, `ideBuild`, `pluginVersion`                          |
| W→IDE     | `hello`        | `protocolVersion`, `javaVersion`, `pid`, `capabilities[]`               |
| IDE→W     | `loadConfig`   | `configId`, `config: SpotlessConfig` (see below)                        |
| W→IDE     | `configLoaded` | `configId`, `formats[]` (name, includes, excludes), `warnings[]`, or `error` with `fallbackReason` |
| IDE→W     | `format`       | `id`, `configId`, `path` (absolute, worker-side), `text`, `encoding`?   |
| W→IDE     | `formatResult` | `id`, `status: CLEAN\|FORMATTED\|DID_NOT_CONVERGE\|NOT_COVERED\|ERROR`, `text?`, `message?`, `lints?[]` |
| IDE→W     | `cancel`       | `id` of an in-flight request                                            |
| IDE→W     | `invalidate`   | `configId` (closes Formatters and FormatterSteps)                       |
| IDE→W     | `ping` / W→IDE `pong` | heartbeat                                                        |
| IDE→W     | `shutdown`     | graceful exit; the IDE destroys the process after a grace period        |
| W→IDE     | `log`          | `level`, `message`, `throwable?`                                        |

`protocolVersion` is an integer. The plugin refuses to talk to a worker with a
different major and restarts it (the worker jar always ships with the plugin,
so this only matters across plugin upgrades while an old worker is alive).

`text` is sent with Unix line endings; the worker reports the result with
Unix line endings too. The IDE owns line-ending conversion of the `Document`.
The worker still needs the *configured* line-ending policy to decide
`CLEAN` vs `FORMATTED` exactly like `DirtyState.of(...)` would on disk, so the
worker computes `DirtyState` on bytes it re-encodes with the policy, and the
IDE compares only the Unix text. (Detail to settle in Phase 3; see
`DECISIONS.md` ADR-009.)

Cancellation: the worker runs each `format` on its own thread; `cancel`
interrupts it. Formatter steps are not guaranteed to be interruptible, so the
IDE also enforces a timeout and restarts the worker if it stops answering
`ping`.

## SpotlessConfig DTO

Serializable, content-hashed (`configId = sha256(canonical JSON)`).

```
SpotlessConfig
  kind: MAVEN | GRADLE
  spotlessVersion: "3.11.0"               # plugin version declared by the build
  projectDir: "/abs/module/dir"            # worker-side path (Eel-translated if remote)
  buildDir:   "/abs/module/dir/target"
  Maven:
    configurationXml: "<configuration>...</configuration>"   # effective, plugin-level + default-cli
    properties: {k: v}                     # project properties for ${...} evaluation
    sourceRoots: [...]; testSourceRoots: [...]   # for Java.defaultIncludes
    repositories: [{id,url,releases,snapshots}]  # project.remotePluginRepositories
    localRepository: "/Users/x/.m2/repository"
    userSettingsFile: ".../settings.xml"; globalSettingsFile?: "..."
    offline: bool
  Gradle:
    formats: [{ name, stepsRoundtrip: base64, lineEndingPolicy: base64, encoding, targetFiles | targetPatterns, ratchetFrom? }]
    buildscriptClasspath: [jar paths]
    fallbackOnly: [{name, reason}]
```

## Engine selection and fallback

`FormatEngine` has two implementations. `NativeEngine` is used when the
setting is `Native (recommended)` **and** config extraction succeeded **and**
the worker reported `configLoaded` without error. Otherwise `BuildToolEngine`
runs. Every fallback carries a `FallbackReason` (enum + detail string) that is
logged and shown once per project as a notification with a "Don't show again
for this project" action. Reasons foreseen:

- `SPOTLESS_VERSION_UNSUPPORTED` (outside the supported range)
- `NO_SPOTLESS_PLUGIN_FOUND`
- `EXTRACTION_FAILED` (unresolvable `${...}`, model not imported yet)
- `WORKER_LOAD_FAILED` (artifact resolution, classloading, step construction)
- `GRADLE_STEP_NOT_SERIALIZABLE` (e.g. `custom {}`)
- `RATCHET_FROM_UNSUPPORTED` (v1, if we decide to route ratchet to fallback)
- `WORKER_UNAVAILABLE` (no JDK >= required, process crashed repeatedly)
- `USER_CHOICE` (Engine = Build tool)

## Maven mode in the worker (core)

Mirrors `AbstractSpotlessMojo.execute()` without a `MavenSession`:

1. Resolve `com.diffplug.spotless:spotless-maven-plugin:<v>` + runtime deps with
   Maven Resolver (`maven-resolver-supplier-mvn3`), honoring settings.xml
   mirrors, proxies, servers, offline. Cache resolved classpath per version
   under the plugin's system dir.
2. Add the provided-scope Maven API jars the plugin compiles against but does
   not declare (`maven-plugin-api`, `maven-core`, `maven-model`, Aether API,
   `plexus-utils`, `org.eclipse.sisu.plexus`, `plexus-classworlds`,
   `plexus-resources`, `plexus-build-api`). The classes we touch at runtime:
   `MavenProject` (passed to `defaultIncludes`), `Log`, `RepositorySystem`,
   `RepositorySystemSession`, `RemoteRepository`, `ResourceManager`.
3. Build a child `URLClassLoader` (parent = platform/bootstrap loader so the
   worker's own classes never leak in; the protocol jar is not visible to it).
4. Read `META-INF/maven/plugin.xml` from the plugin jar to get the `apply`
   mojo's parameter defaults (`encoding=UTF-8`,
   `lineEndings=GIT_ATTRIBUTES_FAST_ALLSAME`, ...). Merge: descriptor defaults
   ← plugin-level `<configuration>` ← `default-cli` execution configuration.
5. Instantiate a **holder object** that mirrors the mojo's `@Parameter`
   fields (`encoding`, `lineEndings`, `ratchetFrom`, `licenseHeader`, `formats`,
   `java`, `kotlin`, ...), typed with the plugin's own classes loaded from the
   child loader, and configure it with Plexus
   `BasicComponentConfigurator.configureComponent(holder, XmlPlexusConfiguration, evaluator, realm)`.
   This is exactly what Maven does, including the `set*`/`add*` mapping
   (`<googleJavaFormat>` → `Java.addGoogleJavaFormat(...)`) and the
   "unrecognized step becomes null" behavior. The holder is generated via
   reflection against the loaded `AbstractSpotlessMojo` so it tracks new
   language blocks automatically.
6. Build `FormatterConfig` and the `Formatter` per `FormatterFactory` with the
   plugin's own `FormatterFactory.newFormatter(filesSupplier, config)`:
   `ArtifactResolver`+`MavenProvisioner` over our `RepositorySystem`,
   `P2Provisioner.createDefault()`, `FileLocator` over a Plexus
   `ResourceManager`, `userRatchetFrom = empty`, `lintSuppressions`, lazy
   `projectClasspathSupplier`. Constructor arity differs per major
   (2.x: 8/6 args, 3.x: 12/8 args) → one adapter per supported major.
7. `filesSupplier` = the same `FileUtils.getFiles(baseDir, includes, excludes)`
   scan the mojo does (needed for `GIT_ATTRIBUTES*` policies, which look at
   the *first* target file). Lazily computed and cached per format;
   invalidated with the config.
8. `format(path, text)`: find the first `FormatterFactory` whose
   includes/excludes match (`MatchPatterns`, same as `collectFilesFromGit`
   uses) — else `NOT_COVERED`. Then `DirtyState.of(formatter, file, bytes)` →
   `CLEAN` / `DID_NOT_CONVERGE` / `FORMATTED` + canonical bytes. Lints are
   returned as warnings (apply mode would fail the build on lints; the IDE
   shows them instead).

Note: a file can match several formats (e.g. `<java>` and a `<formats><format>`
with `**/*.java`). Maven applies them sequentially in declaration order
(`formats` first, then language blocks). The worker does the same: chain
every matching formatter in that order.

## Gradle mode (Phase 5)

During IntelliJ's Gradle sync a `ModelBuilderService` (running inside the
Gradle daemon, with access to the build's classpath) finds every
`SpotlessTaskImpl`, calls `getStepsInternalRoundtrip()` (a
`ConfigurationCacheHackList`, `Serializable`), `getLineEndingsPolicy().get()`,
`getEncoding()`, `getTarget().getFiles()`, `getRatchetFrom()`; serializes
them with Java serialization; records the buildscript classloader's jar
URLs. Failures to serialize mark that format fallback-only. The IDE ships the
bytes + classpath to the worker which deserializes them in a classloader over
that classpath and builds `Formatter`s with `Formatter.builder()`.

## IDE integration

- `FormatService.format(document)`: read text under read action → send off
  EDT (coroutine, cancellable, with progress) → apply in one
  `WriteCommandAction` named "Reformat with Spotless" (undoable) → no
  modification when `CLEAN`.
- On-save: `ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave`
  (`suspend fun updateDocument`) — the platform's intended hook for format-on-save
  with cancellable background progress. Keeps the file-type selection UI from
  the original plugin.
- Batch (project/module): stream files via the worker with bounded
  parallelism, then `VfsUtil.markDirtyAndRefresh`. Files open in editors go
  through their `Document`; others are written on disk by the worker.
- Opt-in: `AsyncDocumentFormattingService` so IDE "Reformat Code" calls
  Spotless (Phase 4, gated by a setting; off by default).
- Remote (ijent/Dev Containers): the worker must run where the files are.
  Spawn through the Eel exec API (`EelExecApi.spawnProcess`) when the
  project's `EelDescriptor` is not local; the worker jar is copied into the
  container. v1 decision pending measurement (see ADR-011); fallback to the
  build tool is always available.
