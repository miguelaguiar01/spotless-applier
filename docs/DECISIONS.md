# Decisions (ADR log)

Format: one entry per decision. Status is `proposed` until confirmed at a
checkpoint, then `accepted`, later possibly `superseded`. ADR-001..014 were
accepted at the Phase 0 checkpoint on 2026-11-10 (ADR-003 conditionally: the
previous majors stay supported only if the parity corpus proves them).
"Verified against" lists the exact versions a claim was checked on.

---

## ADR-001: This repo is a fresh successor, not a fork

Status: accepted (fact)

The repository contained only `LICENSE` (MIT, (c) 2026 Miguel d'Aguiar) and a
one-line `README.md`. `lipiridi/spotless-applier` (MIT, 1.3.0, last commit
"Changelog update - 1.3.0", 2026-08) was cloned to a scratch dir for study
and will be re-implemented, not merged. Its license notice and author credit
are carried over.

## ADR-002: New plugin id and name

Status: accepted (checkpoint 2026-11-10)

Plugin id `com.github.miguelaguiar01.spotless-applier-native` (group
`com.github.miguelaguiar01`), display name **"Spotless Applier Native"**.
Rationale: a different Marketplace listing cannot reuse
`com.github.lipiridi.spotless-applier`. The id is a one-way door; please
confirm or propose another at the checkpoint.

## ADR-003: Supported Spotless versions

Status: accepted (checkpoint 2026-11-10)

| Artifact                 | Current major (verified) | Previous major (verified) | Note |
|--------------------------|--------------------------|---------------------------|------|
| `spotless-maven-plugin`  | 3.x (3.3.0 … 3.11.0, released 2026-11-02) | 2.44.x (2.44.0 … 2.44.5) | 2.x < 2.44 untested; fallback |
| `spotless-plugin-gradle` | 8.x (… 8.11.0)           | 7.0.x (7.0.0 … 7.0.4)     | Gradle mode gated by allow-list |
| `spotless-lib`           | 4.x (4.11.0)             | 3.x (3.1.2)               | transitively pulled |

Facts verified from the GitHub tags `maven/3.11.0`, `maven/2.44.5`,
`gradle/8.11.0` and Maven Central metadata (`maven-metadata.xml`,
`lastUpdated 20261102`). Note: search.maven.org's index is stale (shows 2.44.5
/ 7.0.4 as latest); the mission brief's "current major" must be read as 3.x /
8.x.

JVM floor: `spotless-lib` 4.x is compiled with `--release 17`
(`gradle/libs.versions.toml: jdk-release = "17"`), lib 3.x with
`VER_JAVA=11`. `AbstractSpotlessMojo` still says `MINIMUM_JRE = 11` but the
3.x bytecode is Java 17 and uses `String.formatted`. **Worker JVM must be ≥ 17
for Spotless 3.x/8.x, ≥ 11 for 2.44.x/7.0.x.**

## ADR-004: Facts from the brief, re-verified (what changed)

Status: accepted (fact), verified against maven/3.11.0, maven/2.44.5, gradle/8.11.0

Holds as stated:
- `Formatter.builder().lineEndingsPolicy().encoding().steps().build()`;
  `compute(String unix, File)`; `computeLineEndings(String unix, File)`.
- `FormatterStep extends Serializable, AutoCloseable`, with
  `create/createLazy(name, roundtripState, equalityFn, stepFn)`.
- `Provisioner.provisionWithTransitives(boolean, Collection<String>)`.
- `FormatterFactory.newFormatter(Supplier<Iterable<File>>, FormatterConfig)` is `public final`.
- `FormatterStepFactory { newFormatterStep(FormatterStepConfig); default init(RepositorySystemSession) }`.
- `FormatterStepConfig` 8-arg constructor exactly as listed (3.x).
- `ArtifactResolver(RepositorySystem, RepositorySystemSession, List<RemoteRepository>, Log)`;
  `MavenProvisioner.create(ArtifactResolver)`.
- `SpotlessTask.setSteps(List)`, `getStepsInternalRoundtrip()/Equality()` →
  `ConfigurationCacheHackList` (lives in `com.diffplug.spotless`, lib, not plugin-gradle).
- `IdeHook.performHook` closes `System.err` and `System.out` in `finally`
  (both Maven and Gradle variants).

Differs / missing from the brief:
- `spotlessIdeHook*` are parameters of `SpotlessApplyMojo`, not
  `AbstractSpotlessMojo`.
- `FormatterConfig` (3.x) takes 12 args: `baseDir, encoding, lineEndings,
  userRatchetFrom, ratchetFrom, provisioner, p2Provisioner, fileLocator,
  globalStepFactories, setLicenseHeaderYearsFromGitHistory, lintSuppressions,
  projectClasspathSupplier`. In 2.44.5 it takes 8 (no userRatchetFrom,
  p2Provisioner, lintSuppressions, projectClasspathSupplier) and
  `FormatterStepConfig` takes 6. → one adapter per major.
- Lints exist (3.x): `LintState`, `LintSuppression`, `Formatter.computeWithLint`.
  `spotless:apply` fails the build when unsuppressed lints remain. The worker
  returns them as warnings instead (ADR-010).
- Default line endings are `GIT_ATTRIBUTES_FAST_ALLSAME`, implemented in
  lib-extra `GitAttributesLineEndings.createFastAllSame(projectDir, filesSupplier)`:
  it reads the git attributes of the **first target file** and assumes all
  files share them. So the worker needs the real target file set (or at least
  its first element in Maven's scan order) to be byte-for-byte compatible.
- The published plugin POM declares only runtime deps (`spotless-lib`,
  `spotless-lib-extra`, durian, `plexus-resources`, `plexus-utils` 4.1.0,
  JGit 7.8.0, `plexus-build-api`). Maven API jars are `compileOnly` pinned to
  **Maven 3.0** / Aether 1.1.0 (`gradle/pinned-libs.versions.toml`). The worker
  supplies its own Maven 3.9.x / Resolver 2.0.x jars.
- `Java.defaultIncludes(MavenProject)` uses
  `project.getBuild().getSourceDirectory()/getTestSourceDirectory()`; the
  worker must construct a minimal `MavenProject` (or `Model`/`Build`) with
  those values, taken from IntelliJ's model (`MavenProject.sources/testSources`).
- `FormatterFactory.init(RepositorySystemSession)` is used by `Eclipse` steps
  to default the P2 cache to `<localRepo>/dev/equo/p2-data`. The worker must
  pass a real `RepositorySystemSession` (we have one from the resolver).
- `FileLocator.findDataDir()` derives a data dir from the plugin jar's
  location assuming `~/.m2/repository/.../spotless-maven-plugin/<v>/x.jar`.
  Resolving through the local repository keeps that layout.

## ADR-005: Reuse Plexus to turn XML into Spotless config objects

Status: accepted (checkpoint 2026-11-10), verified against `org.eclipse.sisu.plexus` 1.1.2 sources

`org.eclipse.sisu.plexus.CompositeBeanHelper.setProperty` looks for
`set<Name>` then `add<Name>` then a field — which is how
`<java><googleJavaFormat>` reaches `Java.addGoogleJavaFormat(GoogleJavaFormat)`.
`BasicComponentConfigurator.configureComponent(bean, PlexusConfiguration,
ExpressionEvaluator, ClassRealm)` is public. We will use it on a generated
holder object whose fields mirror the `apply` mojo's parameters, with
defaults read from the plugin jar's `META-INF/maven/plugin.xml`
(`encoding=UTF-8`, `lineEndings=GIT_ATTRIBUTES_FAST_ALLSAME`, ...). Expression
evaluation (`${project.basedir}`, custom properties) is done by a small
evaluator over the properties captured IDE-side, as the Maven
`PluginParameterExpressionEvaluator` requires a `MavenSession`.
Dependencies added to the worker only: `org.eclipse.sisu.plexus`,
`org.eclipse.sisu.inject`, `plexus-classworlds`, `plexus-utils`, `javax.inject`.

## ADR-006: Which configuration applies — mirror `mvn spotless:apply`

Status: accepted (checkpoint 2026-11-10)

The parity target is the CLI invocation `mvn spotless:apply`, which uses the
plugin-level `<configuration>` merged with the `default-cli` execution (if
present). Configurations inside phase-bound `<execution>`s are **not**
applied by a CLI goal invocation and are therefore ignored by the native path
too. IntelliJ's `MavenPlugin` exposes `getConfigurationElement()` and
`getExecutions()` separately; the extractor merges them the way Maven does
(`Xpp3Dom.mergeXpp3Dom`, child overrides parent).

IntelliJ's `MavenProject.plugins` comes from the server-side
`Maven3ModelConverter.convertPlugins(mavenProject.getModel(), …)` — the
**effective** model after inheritance and `pluginManagement` injection.
Whether `${…}` inside plugin configuration is already interpolated there is
to be confirmed with a fixture in Phase 2 (Maven's model interpolator does
interpolate plugin configuration DOMs, but IntelliJ's quick "read" phase may
produce a less complete model before resolution finishes).

## ADR-007: Artifact resolution in the worker

Status: accepted (checkpoint 2026-11-10)

Use `org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.25`
(`RepositorySystemSupplier`, `SessionBuilderSupplier`) with
`maven-resolver-provider`/`maven-model-builder` 3.9.x, plus
`maven-settings-builder` to read `settings.xml` (mirrors, proxies, servers,
offline, local repo). `maven-resolver-supplier` (unsuffixed) is dead at
2.0.0-alpha-8; the `-mvn3` artifact is the maintained one. Maven 4 (`-mvn4`)
is out of scope for v1. The resolved classpath for
`spotless-maven-plugin:<v>` is cached per version in the plugin's system dir
(`PathManager.getSystemPath()/spotless-applier/classpaths/<v>.json`).

## ADR-008: Worker transport is captured stdio, not sockets

Status: accepted (checkpoint 2026-11-10)

`FileDescriptor.in/out` are wrapped **before** any Spotless class loads;
`System.out/err` are replaced by non-closable streams forwarding to the log.
Verified motivation: mvnd 1.0.6 `DaemonMavenCli` lines 497-498 install
`LoggingOutputStream.printStream()` and lines 853-854 call
`LoggingOutputStream.forceFlush(System.out/err)`; `LoggingPrintStream.forceFlush`
does `((LoggingOutputStream) out).forceFlush()`, and `PrintStream.close()`
nulls `out` → NPE after Spotless' `IdeHook` closes the streams
(apache/maven-mvnd#1271, still open). Sockets would also work but add
port/permission handling for no benefit; revisit only if stdio throughput
becomes a bottleneck (it won't for source files).

## ADR-009: Line endings and `CLEAN` detection

Status: accepted (checkpoint 2026-11-10)

The IDE sends Unix text; the worker converts with the configured
`LineEnding.Policy` and runs `DirtyState.of(formatter, file, bytes)` exactly
as `IdeHook` does, so `CLEAN`/`DID_NOT_CONVERGE`/`FORMATTED` and the
PaddedCell behavior match Spotless bit-for-bit. The worker returns canonical
bytes decoded with the formatter encoding; the IDE strips the policy's line
ending back to `\n` for the `Document` (IntelliJ documents are always `\n`)
and keeps the file's `LineSeparator` untouched. Edge case to test: a policy
that would *change* the file's line separator (e.g. `WINDOWS` on an LF file)
— on disk Spotless would rewrite separators; in the IDE we will set the
`Document`'s detected line separator accordingly via
`LoadTextUtil.changeLineSeparators` only in the batch/on-disk path, and log
in the document path.

## ADR-010: Lints do not block formatting; shown as errors

Status: accepted (checkpoint 2026-11-10)

`spotless:apply` writes the formatted file and *then* fails the build when
unsuppressed lints exist, so the file is formatted either way. The IDE
mirrors that: it applies the formatted text and shows lints as an
**error-level** notification (step name, line, message). Blocking the
editor would diverge from what the command line does to the file. Revisit
if users ask for a "block on lints" option.

## ADR-011: ratchetFrom, license-header years, remote projects (v1 scope)

Status: accepted (checkpoint 2026-11-10)

- `ratchetFrom`: affects (a) which files are targets (only files dirty vs.
  the ref) and (b) license-header year mode. v1 implements (b) natively
  (just a flag into `FormatterStepConfig`) and (a) via lib-extra's public
  `GitRatchet` (`isClean(project, treeSha, file)`), the same primitive the
  Gradle IdeHook uses — a clean file → `CLEAN`, a dirty file → format. If
  JGit fails (worktrees, submodules, shallow clones) the config falls back
  with `RATCHET_FROM_UNSUPPORTED`.
- `spotlessSetLicenseHeaderYearsFromGitHistory` is a user property
  (`-D…=true`), not POM config. Not set by the IDE; same as the current
  plugin's behavior.
- Remote/ijent: **revised in Phase 6** — v1 falls back to the build tool
  for files whose `EelDescriptor` is not local (the ported build-tool path
  keeps the original's Eel path translation, so Dev Containers keep
  working as before). Spawning the worker inside the container through the
  Eel exec API, shipping the worker jars and resolving artifacts there is
  deferred to a follow-up (tracked in PROGRESS.md "known gaps"); the native
  engine would otherwise format against the host's view of the files and
  the host's local repository, which is wrong for a container build.
- mvnd: IntelliJ never runs the mvnd daemon itself (its Maven runner uses
  the classworlds launcher), so the NPE only reaches users whose Maven home
  points into an mvnd distribution. In that case the fallback passes
  `-DspotlessFiles=\Q<absolute path>\E` instead of `-DspotlessIdeHook`.

## ADR-012: Build and toolchain baseline

Status: accepted (fact)

Copied from the original's working configuration and verified by building
it locally on this machine: Gradle 9.2.1 (wrapper), Kotlin 2.2.21, IntelliJ
Platform Gradle Plugin 2.10.5, `intellijIdea("2026.1")` (unified IDEA
distribution; local build downloaded `IU-261.22158.277`, bundled JBR is JDK
25.0.2), `sinceBuild = 261`, no `untilBuild`, Java 21 toolchain, bundled
plugins `com.intellij.java`, `com.intellij.gradle`, `org.jetbrains.idea.maven`.
Local machine: Temurin 11/17/21 available; no `mvn`/`gradle` on PATH (the
wrapper is used; parity tests will download a Maven distribution into the
Gradle build dir).

## ADR-013: IntelliJ APIs verified on branch `261`

Status: accepted (fact), verified against `JetBrains/intellij-community` branch `261` sources

- `MavenProjectsManager.getInstance(project)`, `findProject(VirtualFile|Module)`,
  `findContainingProject(VirtualFile)`, `getGeneralSettings()`,
  `getLocalRepository()`, `getProjects()`.
- `MavenProject` (Kotlin): `findPlugin(groupId, artifactId, explicitlyDeclaredOnly=false)`,
  `getPluginConfiguration(g, a)`, `getPluginGoalConfiguration(g, a, goal)`,
  `plugins`/`declaredPlugins`, `properties`, `directory`, `buildDirectory`,
  `sources`, `testSources`, `remotePluginRepositories`, `localRepository`, `file`.
- `org.jetbrains.idea.maven.model.MavenPlugin` (in `plugins/maven-server-api`):
  `getVersion()`, `getConfigurationElement()` (JDOM), `getExecutions()`,
  `getGoalConfiguration(goal)`, `getExecutionConfiguration(id)`.
- Listeners: project-level `MavenImportListener.TOPIC`
  (`importFinished`, `projectResolutionFinished`) and app-level
  `MavenSyncListener.TOPIC`; `MavenProjectsManager.addProjectsTreeListener`.
- Settings: `MavenGeneralSettings.getUserSettingsFile()/getLocalRepository()/isWorkOffline()/getMavenHomeType()`;
  `MavenSettingsCache.getInstance(project).getEffectiveUserSettingsFile()/getEffectiveUserLocalRepo()/getEffectiveGlobalSettingsFile()`.
- Maven JDK: `MavenRunner.getInstance(project).getState().getJreName()` →
  `MavenExternalParameters.getJdk(project, jreName, isGlobal)` handles
  `USE_PROJECT_JDK`/`USE_INTERNAL_JAVA`/`USE_JAVA_HOME`.
- Gradle: `GradleProjectResolverExtension` has `getModelProviders()`
  (`ProjectImportModelProvider.populateModels(BuildController, builds, consumer)`),
  `getToolingExtensionsClasses()`, `getExtraProjectModelClasses()`;
  tooling side `org.jetbrains.plugins.gradle.tooling.ModelBuilderService`
  (`canBuild(modelName)`, `buildAll(modelName, Project)`, `Ex.buildAll(…, ModelBuilderContext)`),
  registered via `META-INF/services`. `GradleProjectSettings.getGradleJvm()`.
- Editing: `ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave`
  (`abstract suspend fun updateDocument(project, document)`, cancellable
  background progress) is the recommended hook for format-on-save; the
  original used the older `ActionOnSave.processDocuments` (EDT).
- `AsyncDocumentFormattingService` (`platform/code-style-api`):
  `createFormattingTask(AsyncFormattingRequest)`, `getNotificationGroupId()`,
  `getName()`, `getFeatures()`; EP `com.intellij.formattingService`.
- Eel: `EelExecApi.spawnProcess/execute(ExecuteProcessOptions)`,
  `EelDescriptor.toEelApi()` (suspend) / `toEelApiBlocking()`,
  `Path.asEelPath()`, `EelPath.asNioPath()`, `EelProviderUtil.getEelDescriptor(Path)`.

## ADR-014: Worker JVM flags

Status: accepted (checkpoint 2026-11-10)

Spotless opens `jdk.compiler` packages itself at runtime
(`ModuleHelper.doOpenInternalPackagesIfRequired`, used by google-java-format,
palantir and cleanthat) via `Unsafe` + `IMPL_LOOKUP`; its own test JVMs add
`--add-opens=java.base/java.lang=ALL-UNNAMED`. The worker is launched with
`--add-opens=java.base/java.lang=ALL-UNNAMED` plus explicit
`--add-exports jdk.compiler/com.sun.tools.javac.{api,file,parser,tree,util}=ALL-UNNAMED`
so formatting works even where the reflective hack is blocked (JDK 25+
tightens `Unsafe`). Also `-Xss` default, `-Xmx` from a setting (default 1g),
`-Dfile.encoding=UTF-8`, `-Djava.awt.headless=true`, `-XX:+UseSerialGC`.
`-XX:TieredStopAtLevel=1` was tried and dropped: it shortens start-up but
caps the JIT, and the worker is long-lived (Phase 7 benchmark).

## ADR-015: Worker jars live in `lib/worker/`, outside the plugin classloader

Status: accepted

IntelliJ loads only the jars directly under a plugin's `lib/` directory.
The worker and its dependencies (Maven Resolver, Sisu-Plexus, …) are copied
to `lib/worker/` by `prepareSandbox`, so none of them are visible to the IDE
classloader; the plugin only needs the small `protocol` jar. The worker is
launched with an explicit `-cp` listing every jar in that directory.

## ADR-016: Zero-dependency protocol module, hand-written JSON

Status: accepted

`protocol` is plain Java 17 with no dependencies (a ~300-line JSON
reader/writer). Reason: it is shared by the IDE plugin and the worker, must
not pull Gson/Jackson into the worker (which later hosts arbitrary Spotless
classpaths), and the messages are small. Java records give typed messages;
`SpotlessConfig.configId()` is the SHA-256 of the canonical (sorted-key)
JSON.

## ADR-017: Test IDE plugin loading

Status: accepted

The IntelliJ test framework loads only the bundled plugins the plugin under
test depends on. The Maven plugin (`org.jetbrains.idea.maven`) has content
modules provided by `com.intellij.properties` and
`org.jetbrains.idea.reposearch`; without them it silently fails to load and
Maven tests die with "Missing extension point". Both are declared as
*optional* dependencies with empty config files so tests load them while the
plugin keeps no hard requirement (every IDEA has them bundled anyway). The
`MavenId` class lives in the reposearch plugin and is deliberately not used.

## ADR-018: Adapter module compiled against the Spotless Maven plugin API

Status: accepted

Rather than driving Spotless through reflection, `worker-maven-adapter` is a
small jar compiled with `compileOnly` dependencies on spotless-maven-plugin
3.11.0 (plus lib, lib-extra, Maven/Resolver APIs). Nothing of Spotless is
bundled: at runtime the adapter jar is loaded by the worker's child-first
`SpotlessClassLoader` together with the user's resolved plugin classpath, so
it links against *their* version. The boundary with the worker is the
`MavenAdapter` SPI (parent-first package) whose signatures use only JDK,
protocol, Maven-API and Resolver-API types. Version differences inside the
supported range (2.44.x: 8-arg `FormatterConfig`, no `P2Provisioner`, no
lints) are handled reflectively in the adapter. The adapter configures a real
`SpotlessApplyMojo` instance with `BasicComponentConfigurator`, applies the
descriptor's `default-value`s from the jar's `META-INF/maven/plugin.xml`, and
reads the configured `FormatterFactory`s back by reflection.

## ADR-019: Multiple matching formats and ratchetFrom in a long-lived process

Status: accepted

- A file matching several formats is formatted by each in the mojo's order
  (`<formats>` first, then the language blocks in `getFormatterFactories()`
  order), feeding each result into the next, which is what sequential
  `process()` calls do on disk.
- `ratchetFrom`: the build only targets files dirty relative to the ref.
  Spotless's `GitRatchetMaven` caches the dirty set per JVM, which is wrong
  in a worker that lives for hours, so the adapter creates a fresh
  lib-extra `GitRatchet` per request and uses `isClean(baseDir, treeSha,
  file)`; clean files are reported `CLEAN` without formatting. JGit failures
  surface as `ERROR` for that file (the IDE then falls back).
- Lints (3.x) are collected via `LintState.of(formatter, file, bytes)` with
  `<lintSuppressions>` applied, and returned alongside the result (ADR-010).

## ADR-020: Gradle mode = sync-time serialization of SpotlessTask state

Status: accepted (verified against spotless-plugin-gradle 8.11.0 and 7.0.4)

- `gradle-tooling` (Java 11 bytecode, Gradle API + IntelliJ tooling-extension
  API only) ships inside the plugin and is sent to the Gradle daemon by
  `SpotlessGradleProjectResolver.getToolingExtensionsClasses()`. Its
  `SpotlessModelBuilder` runs for every project during IntelliJ's sync and,
  by reflection only, finds tasks extending
  `com.diffplug.gradle.spotless.SpotlessTask`, serializes
  `getStepsInternalRoundtrip()` (the `ConfigurationCacheHackList` Spotless
  itself uses for Gradle's configuration cache) and
  `getLineEndingsPolicy().get()` with Java serialization, and records
  encoding, target files, `ratchetFrom` and the buildscript classpath
  (classloader URLs + resolved `buildscript.classpath`).
- The model travels through the normal external-system DataNode graph
  (`SpotlessGradleData` under the module node, persisted in the project
  data cache) so no extra Gradle invocation is needed.
- The worker loads that classpath child-first together with
  `worker-gradle-adapter` (compiled against spotless-lib 4.11.0, which the
  user's lib replaces at runtime), deserializes with a classloader-aware
  `ObjectInputStream`, and builds `Formatter.builder()` exactly as
  `SpotlessTask.buildFormatter()` does. Coverage is the explicit target set
  captured at sync time (new files need a re-sync; the IDE then falls back
  for them with `NOT_COVERED`).
- Groovy `custom {}` closures serialize but reference the build-script
  class, which only exists in Gradle's script classloader: deserialization
  fails → that format is marked fallback-only; other formats of the project
  stay native. Files owned only by fallback formats are `NOT_COVERED`.
- Allow-list: plugin majors 7 and 8 (parity-tested on 7.0.4 and 8.11.0);
  anything else → `SPOTLESS_VERSION_UNSUPPORTED`.
- Gradle sync completion (`ProjectDataImportListener`) drops cached Gradle
  configs and invalidates their worker formatters.

## ADR-021: Platform listener interfaces with default methods are implemented in Java

**Status:** accepted (2026-11-10, after the first plugin-verifier run)

**Context.** `MavenProjectsTree.Listener` is a Kotlin interface compiled with JVM default
methods. When a Kotlin `object` implements it, the Kotlin compiler emits a bridge stub for
*every* default method of the interface, each doing `invokespecial` on the interface default.
2026.2 removed the deprecated two-argument `projectResolved`, so the stub for it became an
unresolved method and the verifier reported a `NoSuchMethodError` risk even though our source
never mentioned that overload.

**Decision.** Listeners for platform interfaces of this kind live in Java
(`config/maven/MavenTreeListener.java`). A Java class only references the methods it overrides,
so dropped defaults cannot break it. A bytecode audit (`javap` for
`invokespecial ... InterfaceMethod com/intellij|org/jetbrains`) found no other Kotlin class with
such stubs; the audit is cheap and should be repeated whenever a new platform listener is added.

**Also.** `PluginManagerCore.getPlugin` is `@ApiStatus.Internal`; the worker directory lookup now
uses `PluginManager.getInstance().findEnabledPlugin(id)`.

## ADR-022: Projects inside WSL run the worker inside the distribution

**Status:** accepted (2026-11-11, after the first real-world use at the user's workplace)

**Context.** On Windows with the project in a WSL distribution (`\\wsl.localhost\<distro>\...`),
the IDE sees ordinary paths and the Eel descriptor of such a file is still the local one, so the
plugin tried to start the worker as a Windows process using the Maven runner JRE, which is a
*Linux* JDK inside the distribution. That cannot start, and every format went through the
`mvn` fallback (reason `WORKER_UNAVAILABLE`), silently after the first notification.

**Decision.** A `WorkerTarget` abstraction decides where the worker runs, chosen once per project
from its base path:
- `LocalTarget`: unchanged behaviour.
- `WslTarget`: the worker is started inside the distribution through the platform's stable WSL
  API (`WSLDistribution.patchCommandLine`, i.e. `wsl.exe`), using a JDK that lives inside the
  distribution (Maven runner JRE, Gradle JVM, project SDK or the distribution's `JAVA_HOME`;
  Windows JDKs and the IDE runtime are rejected). The plugin's worker jars and the resolved-classpath
  cache are reached through `/mnt/<drive>/...`; every path in the `SpotlessConfig`
  (`SpotlessConfig.mapPaths`) and in each format request is translated to its Linux form by
  `WslPathMapper`; worker-side paths (Gradle target sets) are translated back for VFS lookups.
  The IDE-side config cache keeps IDE paths and ids; `WorkerManager` owns the mapping.
- The stdio-framed protocol is unchanged: `wsl.exe` relays the Linux process's stdin/stdout bytes.

**Not covered.** Dev Containers and other Eel-backed remotes still fall back to the build tool
(`NOT_IMPLEMENTED`); they would need the Eel exec API rather than `wsl.exe`. Reading the worker
jars through `/mnt/c` makes the first start slower than local; copying them into the distribution
once is a possible follow-up.

**Verification.** Unit tests for the mapper and the config mapping; the WSL launch itself cannot
be exercised on macOS/CI and was verified on the user's Windows + WSL machine.

