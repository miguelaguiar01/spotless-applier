package com.github.miguelaguiar01.spotless.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the worker needs to build Formatters for one module, extracted on the IDE side.
 * Serializable to JSON; {@link #configId()} is the SHA-256 of the canonical JSON and is the cache
 * key on both sides.
 */
public record SpotlessConfig(
        Kind kind,
        /** Version of spotless-maven-plugin / spotless-plugin-gradle declared by the build. */
        String spotlessVersion,
        /** Module base directory, as seen by the worker process. */
        String projectDir,
        /** Module build directory (Maven {@code target/}), as seen by the worker process. */
        String buildDir,
        Maven maven,
        Gradle gradle,
        /** Free-form extras; used by {@link Kind#BUILTIN_TEST}. */
        Map<String, Object> extra) {

    /** Convenience for Maven/test configs. */
    public SpotlessConfig(Kind kind, String spotlessVersion, String projectDir, String buildDir, Maven maven, Map<String, Object> extra) {
        this(kind, spotlessVersion, projectDir, buildDir, maven, null, extra);
    }

    public enum Kind {
        /** Worker-internal trivial steps; used by tests and smoke checks. */
        BUILTIN_TEST,
        MAVEN,
        GRADLE
    }

    public record Repository(String id, String url, boolean releases, boolean snapshots) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("url", url);
            m.put("releases", releases);
            m.put("snapshots", snapshots);
            return m;
        }

        public static Repository fromJson(Map<String, Object> m) {
            return new Repository(Json.str(m, "id"), Json.requireStr(m, "url"),
                    Json.bool(m, "releases", true), Json.bool(m, "snapshots", false));
        }
    }

    public record Maven(
            /** Effective {@code <configuration>} of spotless-maven-plugin for a CLI {@code spotless:apply}. */
            String configurationXml,
            /** Project properties (plus built-ins) for {@code ${...}} evaluation. */
            Map<String, String> properties,
            List<String> sourceRoots,
            List<String> testSourceRoots,
            List<Repository> pluginRepositories,
            String localRepository,
            String userSettingsFile,
            String globalSettingsFile,
            boolean offline) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configurationXml", configurationXml);
            m.put("properties", properties);
            m.put("sourceRoots", sourceRoots);
            m.put("testSourceRoots", testSourceRoots);
            List<Object> repos = new ArrayList<>();
            for (Repository r : pluginRepositories) repos.add(r.toJson());
            m.put("pluginRepositories", repos);
            m.put("localRepository", localRepository);
            m.put("userSettingsFile", userSettingsFile);
            m.put("globalSettingsFile", globalSettingsFile);
            m.put("offline", offline);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Maven fromJson(Map<String, Object> m) {
            List<Repository> repos = new ArrayList<>();
            for (Object o : Json.list(m, "pluginRepositories")) repos.add(Repository.fromJson((Map<String, Object>) o));
            return new Maven(
                    Json.requireStr(m, "configurationXml"),
                    Json.strMap(m, "properties"),
                    Json.strList(m, "sourceRoots"),
                    Json.strList(m, "testSourceRoots"),
                    repos,
                    Json.str(m, "localRepository"),
                    Json.str(m, "userSettingsFile"),
                    Json.str(m, "globalSettingsFile"),
                    Json.bool(m, "offline", false));
        }
    }

    public record GradleFormat(
            String name,
            String taskName,
            /** Base64 of the Java-serialized ConfigurationCacheHackList (roundtrip). */
            String stepsBase64,
            String lineEndingPolicyBase64,
            String encoding,
            List<String> targetFiles,
            String ratchetFrom,
            /** Non-null: this format must go through the build tool. */
            String fallbackReason) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("taskName", taskName);
            m.put("stepsBase64", stepsBase64);
            m.put("lineEndingPolicyBase64", lineEndingPolicyBase64);
            m.put("encoding", encoding);
            m.put("targetFiles", targetFiles);
            m.put("ratchetFrom", ratchetFrom);
            m.put("fallbackReason", fallbackReason);
            return m;
        }

        public static GradleFormat fromJson(Map<String, Object> m) {
            return new GradleFormat(Json.requireStr(m, "name"), Json.str(m, "taskName"), Json.str(m, "stepsBase64"),
                    Json.str(m, "lineEndingPolicyBase64"), Json.str(m, "encoding"), Json.strList(m, "targetFiles"),
                    Json.str(m, "ratchetFrom"), Json.str(m, "fallbackReason"));
        }
    }

    public record Gradle(List<String> classpath, List<GradleFormat> formats) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("classpath", classpath);
            List<Object> fs = new ArrayList<>();
            for (GradleFormat f : formats) fs.add(f.toJson());
            m.put("formats", fs);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Gradle fromJson(Map<String, Object> m) {
            List<GradleFormat> fs = new ArrayList<>();
            for (Object o : Json.list(m, "formats")) fs.add(GradleFormat.fromJson((Map<String, Object>) o));
            return new Gradle(Json.strList(m, "classpath"), fs);
        }
    }

    public SpotlessConfig {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(projectDir, "projectDir");
        extra = extra == null ? Map.of() : extra;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("spotlessVersion", spotlessVersion);
        m.put("projectDir", projectDir);
        m.put("buildDir", buildDir);
        m.put("maven", maven == null ? null : maven.toJson());
        m.put("gradle", gradle == null ? null : gradle.toJson());
        m.put("extra", extra);
        return m;
    }

    public static SpotlessConfig fromJson(Map<String, Object> m) {
        Map<String, Object> maven = Json.obj(m, "maven");
        Map<String, Object> gradle = Json.obj(m, "gradle");
        Map<String, Object> extra = Json.obj(m, "extra");
        return new SpotlessConfig(
                Kind.valueOf(Json.requireStr(m, "kind")),
                Json.str(m, "spotlessVersion"),
                Json.requireStr(m, "projectDir"),
                Json.str(m, "buildDir"),
                maven == null ? null : Maven.fromJson(maven),
                gradle == null ? null : Gradle.fromJson(gradle),
                extra == null ? Map.of() : extra);
    }

    /** Stable content hash: the cache key for Formatters in the worker and for the IDE-side cache. */
    public String configId() {
        String canonical = Json.writeCanonical(toJson());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
