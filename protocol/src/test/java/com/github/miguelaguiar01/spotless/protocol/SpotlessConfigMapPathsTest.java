package com.github.miguelaguiar01.spotless.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpotlessConfigMapPathsTest {

    @Test
    void mapsEveryPathAndTheXml() {
        SpotlessConfig.Maven maven = new SpotlessConfig.Maven(
                "<java><licenseHeader><file>W:/p/h.txt</file></licenseHeader></java>",
                Map.of("project.basedir", "W:/p", "other", "value"),
                List.of("W:/p/src/main/java"), List.of("W:/p/src/test/java"),
                List.of(new SpotlessConfig.Repository("central", "https://repo", true, false)),
                "W:/m2", "W:/settings.xml", null, false);
        SpotlessConfig config = new SpotlessConfig(SpotlessConfig.Kind.MAVEN, "3.11.0", "W:/p", "W:/p/target", maven, Map.of());

        SpotlessConfig mapped = config.mapPaths(
                s -> s.startsWith("W:/") ? "/mnt/w/" + s.substring(3) : s,
                s -> s.replace("W:/", "/mnt/w/"));

        assertEquals("/mnt/w/p", mapped.projectDir());
        assertEquals("/mnt/w/p/target", mapped.buildDir());
        assertEquals("<java><licenseHeader><file>/mnt/w/p/h.txt</file></licenseHeader></java>", mapped.maven().configurationXml());
        assertEquals("/mnt/w/p", mapped.maven().properties().get("project.basedir"));
        assertEquals("value", mapped.maven().properties().get("other"));
        assertEquals(List.of("/mnt/w/p/src/main/java"), mapped.maven().sourceRoots());
        assertEquals(List.of("/mnt/w/p/src/test/java"), mapped.maven().testSourceRoots());
        assertEquals("/mnt/w/m2", mapped.maven().localRepository());
        assertEquals("/mnt/w/settings.xml", mapped.maven().userSettingsFile());
        assertEquals(config.maven().pluginRepositories(), mapped.maven().pluginRepositories());
        assertNotEquals(config.configId(), mapped.configId());
        // identity mapping keeps the id
        assertEquals(config.configId(), config.mapPaths(s -> s, s -> s).configId());
    }

    @Test
    void mapsGradleClasspathAndTargets() {
        SpotlessConfig.GradleFormat f = new SpotlessConfig.GradleFormat("java", "spotlessJava", "AAA=", "BBB=", "UTF-8",
                List.of("W:/p/A.java"), null, null);
        SpotlessConfig config = new SpotlessConfig(SpotlessConfig.Kind.GRADLE, "8.11.0", "W:/p", null, null,
                new SpotlessConfig.Gradle(List.of("W:/jar.jar"), List.of(f)), Map.of());
        SpotlessConfig mapped = config.mapPaths(s -> s.replace("W:/", "/mnt/w/"), s -> s);
        assertEquals(List.of("/mnt/w/jar.jar"), mapped.gradle().classpath());
        assertEquals(List.of("/mnt/w/p/A.java"), mapped.gradle().formats().get(0).targetFiles());
        assertEquals("AAA=", mapped.gradle().formats().get(0).stepsBase64());
    }
}
