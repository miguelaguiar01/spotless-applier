package com.github.miguelaguiar01.spotless.worker.maven;

import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.WorkerContext;
import com.github.miguelaguiar01.spotless.worker.WorkerLog;
import com.github.miguelaguiar01.spotless.worker.engine.FormatEngine;
import com.github.miguelaguiar01.spotless.worker.resolve.PluginClasspathResolver;
import com.github.miguelaguiar01.spotless.worker.resolve.ResolverEnvironment;
import com.github.miguelaguiar01.spotless.worker.spi.MavenAdapter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Maven mode: resolves the declared spotless-maven-plugin version, loads it in a child-first
 * classloader together with the adapter jar, and delegates to the adapter.
 */
public final class MavenEngine implements FormatEngine {
    private final SpotlessConfig config;
    private final WorkerContext context;
    private final WorkerLog log;

    private SpotlessClassLoader loader;
    private MavenAdapter adapter;
    private List<Message.FormatInfo> formats = List.of();

    public MavenEngine(SpotlessConfig config, WorkerContext context, WorkerLog log) {
        this.config = config;
        this.context = context;
        this.log = log;
    }

    @Override
    public List<String> load() throws Exception {
        SpotlessConfig.Maven maven = config.maven();
        if (maven == null) throw new UnsupportedConfigException("EXTRACTION_FAILED", "Maven config is missing");
        List<String> warnings = new ArrayList<>();

        ResolverEnvironment env = context.resolverEnvironment(maven, warnings::add);
        List<RemoteRepository> repos = env.remoteRepositories(maven.pluginRepositories().stream()
                .map(r -> new ResolverEnvironment.Repo(r.id(), r.url(), r.releases(), r.snapshots())).toList());

        List<Path> classpath;
        try {
            classpath = new PluginClasspathResolver(env, context.cacheDir(), log::info).resolve(config.spotlessVersion(), repos);
        } catch (DependencyResolutionException e) {
            throw new UnsupportedConfigException("WORKER_LOAD_FAILED",
                    "Cannot resolve spotless-maven-plugin " + config.spotlessVersion() + ": " + rootMessage(e));
        }

        List<URL> urls = new ArrayList<>();
        for (Path p : context.adapterJars()) urls.add(p.toUri().toURL());
        for (Path p : classpath) urls.add(p.toUri().toURL());
        loader = new SpotlessClassLoader(urls, MavenEngine.class.getClassLoader());

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class<?> impl = Class.forName(MavenAdapter.IMPLEMENTATION_CLASS, true, loader);
            adapter = (MavenAdapter) impl.getDeclaredConstructor().newInstance();
            MavenAdapter.Request request = new MavenAdapter.Request(
                    Path.of(config.projectDir()),
                    config.buildDir() != null ? Path.of(config.buildDir()) : Path.of(config.projectDir(), "target"),
                    maven.configurationXml(),
                    maven.properties(),
                    maven.sourceRoots(),
                    maven.testSourceRoots(),
                    env.system(),
                    env.session(),
                    repos,
                    log::info);
            MavenAdapter.Loaded loaded = adapter.load(request);
            formats = loaded.formats();
            warnings.addAll(loaded.warnings());
            return warnings;
        } catch (UnsupportedConfigException e) {
            throw e;
        } catch (Throwable t) {
            close();
            throw new UnsupportedConfigException("WORKER_LOAD_FAILED",
                    "Spotless " + config.spotlessVersion() + " could not be configured: " + rootMessage(t));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Override
    public List<Message.FormatInfo> formats() {
        return formats;
    }

    @Override
    public Message.FormatResult format(String requestId, String path, String text) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return adapter.format(requestId, Path.of(path), text);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (adapter != null) adapter.close();
        } finally {
            adapter = null;
            if (loader != null) loader.close();
            loader = null;
        }
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        return root == t ? String.valueOf(msg) : t.getMessage() + " (caused by " + root.getClass().getSimpleName() + ": " + msg + ")";
    }

    static List<Path> jarsIn(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
