# Decisions (ADR log)

Format: one entry per decision. Status is `proposed` until the Phase 0
checkpoint is confirmed, then `accepted`, later possibly `superseded`.
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

Status: proposed

Plugin id `com.github.miguelaguiar01.spotless-applier-native` (group
`com.github.miguelaguiar01`), display name **"Spotless Applier Native"**.
Rationale: a different Marketplace listing cannot reuse
`com.github.lipiridi.spotless-applier`. The id is a one-way door; please
confirm or propose another at the checkpoint.

## ADR-003: Supported Spotless versions

Status: proposed

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

Status: proposed, verified against `org.eclipse.sisu.plexus` 1.1.2 sources

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

Status: proposed

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

Status: proposed

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

Status: proposed

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

Status: proposed

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

## ADR-010: Lints are warnings in the IDE

Status: proposed

`spotless:apply` throws after formatting when unsuppressed lints exist. The
IDE applies the formatted text and shows lints as a warning notification
(step name, line, message). The user can still see the build fail in CI as
intended; blocking the editor on lints is not useful.

## ADR-011: ratchetFrom, license-header years, remote projects (v1 scope)

Status: proposed

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
- Remote/ijent: v1 spawns the worker through the Eel exec API when the
  project is not local, copying the worker jar into the target with Eel's
  file API; if that fails → build-tool fallback (which already works in
  ijent). This is listed as a risk; measure in Phase 6.

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

Status: proposed

Spotless opens `jdk.compiler` packages itself at runtime
(`ModuleHelper.doOpenInternalPackagesIfRequired`, used by google-java-format,
palantir and cleanthat) via `Unsafe` + `IMPL_LOOKUP`; its own test JVMs add
`--add-opens=java.base/java.lang=ALL-UNNAMED`. The worker is launched with
`--add-opens=java.base/java.lang=ALL-UNNAMED` plus explicit
`--add-exports jdk.compiler/com.sun.tools.javac.{api,file,parser,tree,util}=ALL-UNNAMED`
so formatting works even where the reflective hack is blocked (JDK 25+
tightens `Unsafe`). Also `-Xss` default, `-Xmx` from a setting (default 1g),
`-Dfile.encoding=UTF-8`, `-Djava.awt.headless=true`.
