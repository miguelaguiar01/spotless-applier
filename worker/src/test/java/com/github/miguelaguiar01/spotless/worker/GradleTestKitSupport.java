package com.github.miguelaguiar01.spotless.worker;

import com.github.miguelaguiar01.spotless.protocol.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/** Runs fixture builds through Gradle TestKit and dumps the sync-time Spotless model via an init script. */
final class GradleTestKitSupport {
    private GradleTestKitSupport() {}

    static BuildResult run(Path projectDir, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--stacktrace");
        GradleRunner runner = GradleRunner.create();
        String home = System.getProperty("spotless.test.gradleHome");
        if (home != null) runner = runner.withGradleInstallation(new java.io.File(home));
        return runner
                .withProjectDir(projectDir.toFile())
                .withArguments(all)
                .forwardOutput()
                .build();
    }

    /** Writes an init script that registers {@code spotlessApplierDump}, writing the model as JSON to {@code out}. */
    static Path writeDumpInitScript(Path dir, Path out) throws IOException {
        String toolingJar = System.getProperty("spotless.test.toolingJar");
        String protocolJar = System.getProperty("spotless.test.protocolJar");
        String script = """
                initscript {
                    dependencies {
                        classpath files('%s', '%s')
                    }
                }
                allprojects {
                    tasks.register('spotlessApplierDump') {
                        notCompatibleWithConfigurationCache('test helper')
                        doLast {
                            def model = com.github.miguelaguiar01.spotless.gradle.tooling.SpotlessTaskInspector.inspect(project)
                            def map = [:]
                            if (model != null) {
                                map.pluginVersion = model.pluginVersion
                                map.classpath = model.classpath
                                map.warnings = model.warnings
                                map.formats = model.formats.collect { f ->
                                    [name: f.name, taskName: f.taskName, stepsBase64: f.stepsBase64,
                                     lineEndingPolicyBase64: f.lineEndingPolicyBase64, encoding: f.encoding,
                                     targetFiles: f.targetFiles, ratchetFrom: f.ratchetFrom, fallbackReason: f.fallbackReason]
                                }
                            }
                            new File('%s').text = com.github.miguelaguiar01.spotless.protocol.Json.write(map)
                        }
                    }
                }
                """.formatted(toolingJar.replace("\\", "\\\\"), protocolJar.replace("\\", "\\\\"), out.toString().replace("\\", "\\\\"));
        Path init = dir.resolve("spotless-applier-dump.init.gradle");
        Files.writeString(init, script, StandardCharsets.UTF_8);
        return init;
    }

    static Map<String, Object> dumpModel(Path projectDir, Path scratch) throws IOException {
        Files.createDirectories(scratch);
        Path out = scratch.resolve("spotless-model.json");
        Path init = writeDumpInitScript(scratch, out);
        run(projectDir, "spotlessApplierDump", "-I", init.toString(), "-q");
        return Json.readObject(Files.readString(out, StandardCharsets.UTF_8));
    }
}
