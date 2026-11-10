package com.github.miguelaguiar01.spotless.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.miguelaguiar01.spotless.protocol.FormatStatus;
import com.github.miguelaguiar01.spotless.protocol.Json;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.engine.EngineFactory;
import com.github.miguelaguiar01.spotless.worker.engine.FormatEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Gradle parity: {@code gradle spotlessApply} (TestKit) on one copy vs. the native Gradle engine fed
 * with the sync-time model of the other copy. Files owned only by fallback formats (e.g. a Groovy
 * {@code custom {}} step) are expected to be NOT_COVERED natively.
 */
@Timeout(1800)
class GradleParityTest {
    @TempDir
    static Path cacheDir;

    static Stream<String> cases() throws IOException {
        Path dir = Path.of(System.getProperty("spotless.test.fixtures", "fixtures")).resolve("gradle");
        if (!Files.isDirectory(dir)) return Stream.empty();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @SuppressWarnings("unchecked")
    void nativeMatchesGradle(String caseName, @TempDir Path work) throws Exception {
        Assumptions.assumeTrue(System.getProperty("spotless.test.toolingJar") != null, "tooling jar property missing");
        Path source = Path.of(System.getProperty("spotless.test.fixtures", "fixtures")).resolve("gradle").resolve(caseName);
        Path gradleCopy = work.resolve("gradle");
        Path nativeCopy = work.resolve("native");
        copyTree(source, gradleCopy);
        copyTree(source, nativeCopy);

        Map<String, Object> model = GradleTestKitSupport.dumpModel(nativeCopy, work.resolve("scratch"));
        assertNotNull(model.get("formats"), "model: " + model);
        String version = Json.str(model, "pluginVersion");
        assertNotNull(version, "plugin version from classpath");
        List<SpotlessConfig.GradleFormat> formats = new ArrayList<>();
        Set<String> fallbackOnlyTargets = new HashSet<>();
        Set<String> nativeTargets = new HashSet<>();
        for (Object o : Json.list(model, "formats")) {
            SpotlessConfig.GradleFormat f = SpotlessConfig.GradleFormat.fromJson((Map<String, Object>) o);
            formats.add(f);
            for (String t : f.targetFiles()) {
                String real = Files.exists(Path.of(t)) ? Path.of(t).toRealPath().toString() : t;
                (f.fallbackReason() == null ? nativeTargets : fallbackOnlyTargets).add(real);
            }
        }
        fallbackOnlyTargets.removeAll(nativeTargets);
        SpotlessConfig config = new SpotlessConfig(SpotlessConfig.Kind.GRADLE, version, nativeCopy.toString(),
                nativeCopy.resolve("build").toString(), null,
                new SpotlessConfig.Gradle(Json.strList(model, "classpath"), formats), Map.of());

        Map<String, byte[]> before = snapshot(gradleCopy);
        GradleTestKitSupport.run(gradleCopy, "spotlessApply", "-q");
        Map<String, byte[]> after = snapshot(gradleCopy);

        WorkerLog log = new WorkerLog();
        log.attach(l -> System.out.println("[worker " + l.level() + "] " + l.message()));
        try (WorkerContext context = new WorkerContext(cacheDir, Path.of(System.getProperty("spotless.worker.test.adapterDir")), log);
             FormatEngine engine = EngineFactory.create(config, context, log)) {
            List<String> warnings = engine.load();
            warnings.forEach(w -> System.out.println("[warning] " + w));
            // Formats restored natively vs. formats the engine handed to the build tool (known only after load).
            Set<String> fallbackNames = new HashSet<>();
            for (Message.FallbackFormat f : engine.fallbackFormats()) fallbackNames.add(f.name());
            nativeTargets.clear();
            fallbackOnlyTargets.clear();
            for (SpotlessConfig.GradleFormat f : formats) {
                for (String t : f.targetFiles()) {
                    String real = Files.exists(Path.of(t)) ? Path.of(t).toRealPath().toString() : t;
                    (fallbackNames.contains(f.name()) ? fallbackOnlyTargets : nativeTargets).add(real);
                }
            }
            fallbackOnlyTargets.removeAll(nativeTargets);
            if (caseName.equals("groovy")) {
                assertTrue(engine.fallbackFormats().stream().anyMatch(f -> f.name().equals("shout")),
                        "custom closure format must fall back: " + warnings);
            }

            List<String> problems = new ArrayList<>();
            int formatted = 0;
            for (Map.Entry<String, byte[]> e : before.entrySet()) {
                String rel = e.getKey();
                byte[] gradleResult = after.get(rel);
                boolean gradleChanged = !java.util.Arrays.equals(e.getValue(), gradleResult);
                Path nativeFile = nativeCopy.resolve(rel);
                String originalUnix = new String(e.getValue(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                Message.FormatResult r = engine.format("1", nativeFile.toString(), originalUnix);
                boolean fallbackOnly = fallbackOnlyTargets.contains(nativeFile.toRealPath().toString());
                if (fallbackOnly) {
                    if (r.status() != FormatStatus.NOT_COVERED) problems.add(rel + ": fallback-only file must be NOT_COVERED, was " + r.status());
                    continue;
                }
                if (gradleChanged) {
                    String expected = new String(gradleResult, StandardCharsets.UTF_8).replace("\r\n", "\n");
                    if (r.status() != FormatStatus.FORMATTED) {
                        problems.add(rel + ": gradle changed it but native said " + r.status() + " (" + r.message() + ")");
                    } else if (!expected.equals(r.text())) {
                        problems.add(rel + ": output differs\n--- gradle ---\n" + expected + "\n--- native ---\n" + r.text());
                    } else {
                        formatted++;
                    }
                } else if (r.status() == FormatStatus.FORMATTED) {
                    problems.add(rel + ": gradle left it alone but native formatted it to:\n" + r.text());
                } else if (r.status() == FormatStatus.ERROR || r.status() == FormatStatus.DID_NOT_CONVERGE) {
                    problems.add(rel + ": " + r.status() + " " + r.message());
                }
            }
            if (!problems.isEmpty()) fail(caseName + ": " + problems.size() + " mismatch(es)\n" + String.join("\n\n", problems));
            assertTrue(formatted > 0, caseName + ": expected at least one natively reformatted file");
            System.out.println(caseName + ": " + formatted + " files byte-identical to gradle spotlessApply");
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted().toList()) {
                if (!Files.isRegularFile(p)) continue;
                String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                if (rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.endsWith(".init.gradle")) continue;
                map.put(rel, Files.readAllBytes(p));
            }
        }
        return map;
    }
}
