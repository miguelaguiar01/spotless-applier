package com.github.miguelaguiar01.spotless.gradle.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.miguelaguiar01.spotless.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Runs the inspector inside a real Gradle build (TestKit + init script) on the Kotlin-DSL fixture. */
@Timeout(900)
class SpotlessTaskInspectorTest {

    @Test
    @SuppressWarnings("unchecked")
    void capturesFormatsClasspathAndVersion(@TempDir Path work) throws Exception {
        Path source = Path.of(System.getProperty("spotless.test.fixtures")).resolve("gradle/kts");
        Path project = work.resolve("project");
        try (Stream<Path> s = Files.walk(source)) {
            for (Path p : s.toList()) {
                Path t = project.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
        Path out = work.resolve("model.json");
        Path init = work.resolve("dump.init.gradle");
        Files.writeString(init, """
                initscript { dependencies { classpath files('%s', '%s') } }
                allprojects {
                    tasks.register('spotlessApplierDump') {
                        notCompatibleWithConfigurationCache('test helper')
                        doLast {
                            def model = com.github.miguelaguiar01.spotless.gradle.tooling.SpotlessTaskInspector.inspect(project)
                            def map = [pluginVersion: model.pluginVersion, classpath: model.classpath, warnings: model.warnings,
                                formats: model.formats.collect { f -> [name: f.name, taskName: f.taskName, hasSteps: f.stepsBase64 != null,
                                    hasPolicy: f.lineEndingPolicyBase64 != null, encoding: f.encoding, targetFiles: f.targetFiles,
                                    fallbackReason: f.fallbackReason] }]
                            new File('%s').text = com.github.miguelaguiar01.spotless.protocol.Json.write(map)
                        }
                    }
                }
                """.formatted(System.getProperty("spotless.test.toolingJar"), System.getProperty("spotless.test.protocolJar"), out), StandardCharsets.UTF_8);
        GradleRunner.create().withGradleInstallation(new java.io.File(System.getProperty("spotless.test.gradleHome")))
                .withProjectDir(project.toFile())
                .withArguments("spotlessApplierDump", "-I", init.toString(), "-q", "--stacktrace")
                .forwardOutput().build();

        Map<String, Object> model = Json.readObject(Files.readString(out));
        assertEquals("8.11.0", Json.str(model, "pluginVersion"));
        assertTrue(Json.strList(model, "classpath").stream().anyMatch(p -> p.contains("spotless-lib-")), "lib on classpath");
        List<Object> formats = Json.list(model, "formats");
        assertEquals(2, formats.size(), model.toString());
        Map<String, Object> java = (Map<String, Object>) formats.stream().filter(f -> "java".equals(Json.str((Map<String, Object>) f, "name"))).findFirst().orElseThrow();
        assertEquals("spotlessJava", Json.str(java, "taskName"));
        assertEquals(Boolean.TRUE, java.get("hasSteps"));
        assertEquals(Boolean.TRUE, java.get("hasPolicy"));
        assertNull(Json.str(java, "fallbackReason"));
        assertEquals(2, Json.strList(java, "targetFiles").size(), "Messy.java and Clean.java");
        Map<String, Object> misc = (Map<String, Object>) formats.stream().filter(f -> "misc".equals(Json.str((Map<String, Object>) f, "name"))).findFirst().orElseThrow();
        assertNotNull(misc);
        assertTrue(Json.strList(misc, "targetFiles").stream().noneMatch(p -> p.endsWith("IGNORED.md")));
    }
}
