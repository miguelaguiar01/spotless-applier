# Benchmarks

Measured 2026-11-10 on a MacBook (Apple silicon, Temurin 21 x86_64 under Rosetta for the test
JVM, JBR-less), fixture `fixtures/maven/gjf-full` (googleJavaFormat 1.33 + licenseHeader +
importOrder + removeUnusedImports + formatAnnotations + toggleOffOn), file `Messy.java`
(~30 lines). Harness: `worker/src/test/java/.../MavenBenchmarkTest.java`, run with

```
./gradlew :worker:test --tests '*MavenBenchmarkTest*' -Dspotless.bench=true
```

Native numbers are **full IPC round trips** through a real worker process (frame encode →
worker → DirtyState/PaddedCell → frame decode), 100 iterations after 5 warm-ups. The build-tool
number is one `mvn -q spotless:apply -DspotlessIdeHook=<file>` per iteration (5 iterations, warm
local repository, Maven 3.10.0, JDK 21), which is what the previous plugin did per file.

| Path | p50 | p95 | min | max |
|------|-----|-----|-----|-----|
| Native worker, dirty file (reformatted) | **29 ms** | 161 ms | 15 ms | 215 ms |
| Native worker, clean file (no change) | **4 ms** | 13 ms | 2 ms | 93 ms |
| `mvn spotless:apply -DspotlessIdeHook` | **4 398 ms** | 5 088 ms | 4 359 ms | 5 088 ms |

One-off costs (native): worker JVM start + handshake ≈ 0.3 s; first `loadConfig` for a Spotless
version with a warm local repository ≈ 3.4 s (resolution from the cache file, classloader,
Plexus configuration, google-java-format jar loading); cold (artifact download) several seconds
more depending on the network. These are paid once per project open (or once at project open with
"pre-warm" enabled), not per file.

Speed-up at p50: ~150× for a file that needs changes, ~1000× for a clean file. The build-tool
path additionally rewrites the file on disk and goes through a VFS refresh, which the native path
avoids by editing the Document directly.

Caveats: numbers vary with the formatter (ktfmt and Eclipse JDT are heavier than
google-java-format), the first format after a worker start includes JIT warm-up (the max column),
and Gradle's `spotlessApply` baseline was not measured here (configuration-cache misses with
`-PspotlessIdeHook` make it comparable to or slower than Maven).
