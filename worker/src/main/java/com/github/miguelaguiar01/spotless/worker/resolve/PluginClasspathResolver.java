package com.github.miguelaguiar01.spotless.worker.resolve;

import com.github.miguelaguiar01.spotless.protocol.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/**
 * Resolves {@code com.diffplug.spotless:spotless-maven-plugin:<version>} with its runtime
 * dependencies. Results are cached as JSON per version under the worker cache dir so warm starts
 * never touch the network (the files are verified to exist before the cache is trusted).
 */
public final class PluginClasspathResolver {
    public static final String GROUP = "com.diffplug.spotless";
    public static final String ARTIFACT = "spotless-maven-plugin";

    private final ResolverEnvironment env;
    private final Path cacheDir;
    private final Consumer<String> log;

    public PluginClasspathResolver(ResolverEnvironment env, Path cacheDir, Consumer<String> log) {
        this.env = env;
        this.cacheDir = cacheDir;
        this.log = log;
    }

    public List<Path> resolve(String version, List<RemoteRepository> repositories) throws DependencyResolutionException {
        List<Path> cached = readCache(version);
        if (cached != null) {
            log.accept("Spotless " + version + " classpath from cache (" + cached.size() + " jars)");
            return cached;
        }
        long start = System.nanoTime();
        Dependency root = new Dependency(new DefaultArtifact(GROUP, ARTIFACT, "jar", version), JavaScopes.RUNTIME);
        CollectRequest collect = new CollectRequest(root, repositories);
        DependencyRequest request = new DependencyRequest(collect, DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
        DependencyResult result = env.system().resolveDependencies(env.session(), request);
        List<Path> paths = new ArrayList<>();
        for (ArtifactResult ar : result.getArtifactResults()) {
            if (ar.getArtifact() != null && ar.getArtifact().getPath() != null) paths.add(ar.getArtifact().getPath());
        }
        log.accept("Resolved spotless-maven-plugin " + version + ": " + paths.size() + " jars in "
                + (System.nanoTime() - start) / 1_000_000 + " ms");
        writeCache(version, paths);
        return paths;
    }

    private Path cacheFile(String version) {
        return cacheDir == null ? null : cacheDir.resolve("classpath-" + ARTIFACT + "-" + version + ".json");
    }

    private List<Path> readCache(String version) {
        Path file = cacheFile(version);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            Map<String, Object> m = Json.readObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!version.equals(Json.str(m, "version"))) return null;
            List<Path> paths = new ArrayList<>();
            for (String s : Json.strList(m, "jars")) {
                Path p = Path.of(s);
                if (!Files.isRegularFile(p)) return null; // local repo was cleaned; re-resolve
                paths.add(p);
            }
            return paths.isEmpty() ? null : paths;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeCache(String version, List<Path> paths) {
        Path file = cacheFile(version);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version);
            List<String> jars = new ArrayList<>();
            for (Path p : paths) jars.add(p.toAbsolutePath().toString());
            m.put("jars", jars);
            Files.writeString(file, Json.write(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.accept("Could not write classpath cache " + file + ": " + e.getMessage());
        }
    }
}
