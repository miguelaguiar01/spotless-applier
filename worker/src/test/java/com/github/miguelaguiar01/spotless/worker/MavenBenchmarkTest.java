package com.github.miguelaguiar01.spotless.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.miguelaguiar01.spotless.protocol.FormatStatus;
import com.github.miguelaguiar01.spotless.protocol.FrameCodec;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.Messages;
import com.github.miguelaguiar01.spotless.protocol.ProtocolVersion;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in ({@code -Dspotless.bench=true}): warm per-file latency through a real worker process vs.
 * {@code mvn spotless:apply -DspotlessIdeHook=<file>} on the gjf-full fixture. Prints the numbers
 * recorded in docs/BENCHMARKS.md.
 */
@Timeout(1800)
class MavenBenchmarkTest {
    private static final int ITERATIONS = 100;

    @Test
    void workerVsMaven(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("spotless.bench"), "enable with -Dspotless.bench=true");
        Path source = Path.of(System.getProperty("spotless.test.fixtures")).resolve("maven/gjf-full");
        Path project = work.resolve("project");
        try (Stream<Path> s = Files.walk(source)) {
            for (Path p : s.toList()) {
                Path t = project.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
        Path messy = project.resolve("src/main/java/com/example/Messy.java");
        String messyText = Files.readString(messy, StandardCharsets.UTF_8);
        Path clean = project.resolve("src/main/java/com/example/Clean.java");
        String cleanText = Files.readString(clean, StandardCharsets.UTF_8);

        // ---- worker process (IPC round trip included)
        String java = System.getProperty("spotless.worker.test.java");
        Process process = new ProcessBuilder(java, "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), WorkerMain.class.getName(),
                "--cache-dir=" + work.resolve("cache"), "--adapter-dir=" + System.getProperty("spotless.worker.test.adapterDir"))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        FrameCodec.Reader reader = new FrameCodec.Reader(process.getInputStream());
        FrameCodec.Writer writer = new FrameCodec.Writer(process.getOutputStream());
        Messages.decode(reader.readFrame());
        writer.writeFrame(new Message.Hello(ProtocolVersion.CURRENT, Map.of("role", "bench")).encode());
        MavenParityTest.PomInfo pom = MavenParityTest.PomInfo.read(project.resolve("pom.xml"));
        Map<String, String> props = new java.util.LinkedHashMap<>(pom.properties());
        props.put("project.basedir", project.toString());
        SpotlessConfig config = new SpotlessConfig(SpotlessConfig.Kind.MAVEN, pom.version(), project.toString(),
                project.resolve("target").toString(), new SpotlessConfig.Maven(pom.configurationXml(), props,
                List.of(project.resolve("src/main/java").toString()), List.of(project.resolve("src/test/java").toString()),
                List.of(new SpotlessConfig.Repository("central", "https://repo.maven.apache.org/maven2", true, false)),
                null, null, null, false), Map.of());
        long loadStart = System.nanoTime();
        writer.writeFrame(new Message.LoadConfig("load", config).encode());
        Message.ConfigLoaded loaded = (Message.ConfigLoaded) nextNonLog(reader);
        long loadMs = (System.nanoTime() - loadStart) / 1_000_000;
        assertEquals(true, loaded.isOk(), loaded.error());

        List<Long> dirty = new ArrayList<>();
        List<Long> cleanTimes = new ArrayList<>();
        for (int i = 0; i < ITERATIONS + 5; i++) {
            long t0 = System.nanoTime();
            writer.writeFrame(new Message.Format("d" + i, config.configId(), messy.toString(), messyText).encode());
            Message.FormatResult r = (Message.FormatResult) nextNonLog(reader);
            long d = (System.nanoTime() - t0) / 1_000;
            assertEquals(FormatStatus.FORMATTED, r.status(), r.message());
            t0 = System.nanoTime();
            writer.writeFrame(new Message.Format("c" + i, config.configId(), clean.toString(), cleanText).encode());
            Message.FormatResult c = (Message.FormatResult) nextNonLog(reader);
            long cd = (System.nanoTime() - t0) / 1_000;
            assertEquals(FormatStatus.CLEAN, c.status(), c.message());
            if (i >= 5) { // warm-up excluded
                dirty.add(d);
                cleanTimes.add(cd);
            }
        }
        writer.writeFrame(new Message.Shutdown("bye").encode());
        process.waitFor(10, TimeUnit.SECONDS);

        // ---- mvn spotless:apply -DspotlessIdeHook (what the old plugin does per file)
        Path mavenHome = Path.of(System.getProperty("spotless.test.mavenHome"));
        List<Long> mvnTimes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Files.writeString(messy, messyText, StandardCharsets.UTF_8);
            long t0 = System.nanoTime();
            Process mvn = new ProcessBuilder(mavenHome.resolve("bin/mvn").toString(), "-B", "-q", "spotless:apply",
                    "-DspotlessIdeHook=" + messy).directory(project.toFile()).redirectErrorStream(true).start();
            mvn.getInputStream().readAllBytes();
            mvn.waitFor(10, TimeUnit.MINUTES);
            mvnTimes.add((System.nanoTime() - t0) / 1_000);
            assertEquals(0, mvn.exitValue());
        }

        System.out.println("BENCH config load (worker, warm local repo): " + loadMs + " ms");
        System.out.println("BENCH native worker, dirty file (round trip, us): " + stats(dirty));
        System.out.println("BENCH native worker, clean file (round trip, us): " + stats(cleanTimes));
        System.out.println("BENCH mvn spotless:apply -DspotlessIdeHook (us): " + stats(mvnTimes));
    }

    private static Message nextNonLog(FrameCodec.Reader reader) throws Exception {
        while (true) {
            Message m = Messages.decode(reader.readFrame());
            if (!(m instanceof Message.Log)) return m;
        }
    }

    private static String stats(List<Long> us) {
        long[] a = us.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = a[a.length / 2];
        long p95 = a[Math.min(a.length - 1, (int) Math.ceil(a.length * 0.95) - 1)];
        return "n=" + a.length + " p50=" + p50 + " p95=" + p95 + " min=" + a[0] + " max=" + a[a.length - 1] + " all=" + Arrays.toString(a);
    }
}
