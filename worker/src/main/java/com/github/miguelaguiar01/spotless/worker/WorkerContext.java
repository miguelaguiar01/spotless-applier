package com.github.miguelaguiar01.spotless.worker;

import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.resolve.ResolverEnvironment;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Process-wide state shared by engines: cache dir, adapter jars, resolver environments. */
public final class WorkerContext implements AutoCloseable {
    private final Path cacheDir;
    private final Path adapterDir;
    private final WorkerLog log;
    private final Map<String, ResolverEnvironment> environments = new LinkedHashMap<>();

    public WorkerContext(Path cacheDir, Path adapterDir, WorkerLog log) {
        this.cacheDir = cacheDir;
        this.adapterDir = adapterDir != null ? adapterDir : defaultAdapterDir();
        this.log = log;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    /** The adapter jars whose file name starts with {@code prefix}, shipped next to the worker under {@code adapter/}. */
    public List<Path> adapterJars(String prefix) {
        if (adapterDir == null || !Files.isDirectory(adapterDir)) {
            throw new IllegalStateException("Adapter directory not found: " + adapterDir);
        }
        try (Stream<Path> s = Files.list(adapterDir)) {
            List<Path> jars = new ArrayList<>(s.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith(".jar")).sorted().toList());
            if (jars.isEmpty()) throw new IllegalStateException("No " + prefix + "*.jar in " + adapterDir);
            return jars;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot list " + adapterDir, e);
        }
    }

    /** One resolver environment per (local repo, settings, offline) combination. */
    public synchronized ResolverEnvironment resolverEnvironment(SpotlessConfig.Maven maven, Consumer<String> warn) {
        String key = String.join("|", String.valueOf(maven.localRepository()), String.valueOf(maven.userSettingsFile()),
                String.valueOf(maven.globalSettingsFile()), String.valueOf(maven.offline()));
        return environments.computeIfAbsent(key, k -> {
            log.info("Creating Maven resolver (localRepo=" + maven.localRepository() + ", settings=" + maven.userSettingsFile()
                    + ", offline=" + maven.offline() + ")");
            return ResolverEnvironment.create(
                    maven.localRepository() != null ? Path.of(maven.localRepository()) : null,
                    maven.userSettingsFile() != null ? Path.of(maven.userSettingsFile()) : null,
                    maven.globalSettingsFile() != null ? Path.of(maven.globalSettingsFile()) : null,
                    maven.offline(), warn);
        });
    }

    /** {@code <dir of worker jar>/adapter}, i.e. {@code lib/worker/adapter} in the plugin distribution. */
    private static Path defaultAdapterDir() {
        try {
            CodeSource cs = WorkerContext.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            Path self = Path.of(cs.getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            return dir == null ? null : dir.resolve("adapter");
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        for (ResolverEnvironment env : environments.values()) {
            try {
                env.close();
            } catch (RuntimeException ignored) {
            }
        }
        environments.clear();
    }

    static Path pathArg(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath();
    }

    static boolean sameFile(Path a, Path b) {
        return Objects.equals(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
    }

    static File file(Path p) {
        return p == null ? null : p.toFile();
    }
}
