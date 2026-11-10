# Progress

Running log of phase status. Newest entries at the top of each section.

## Phase status

| Phase | Title                                   | Status      |
|-------|-----------------------------------------|-------------|
| 0     | Recon and design                        | **done, awaiting checkpoint confirmation** |
| 1     | Worker skeleton and IPC                 | not started |
| 2     | Maven config extraction (IDE side)      | not started |
| 3     | Worker, Maven mode (core + parity)      | not started |
| 4     | IDE integration                         | not started |
| 5     | Gradle support                          | not started |
| 6     | Fallback path hardening                 | not started |
| 7     | Polish and release                      | not started |

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

## Next steps (Phase 1, pending confirmation)

- Scaffold the multi-project Gradle build (`protocol`, `worker`, root plugin)
  from the original's working configuration.
- Implement `WorkerMain` with stdio capture, framing, `hello/ping/format/shutdown`,
  a hardcoded trim-trailing-whitespace step, and the integration test that
  survives `System.out.close()`.
- Implement `WorkerManager` in the plugin with lifecycle, JDK selection,
  logging to `idea.log`.
