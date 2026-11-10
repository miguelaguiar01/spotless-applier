package com.github.miguelaguiar01.spotless.worker.gradle;

import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.WorkerContext;
import com.github.miguelaguiar01.spotless.worker.WorkerLog;
import com.github.miguelaguiar01.spotless.worker.engine.FormatEngine;
import com.github.miguelaguiar01.spotless.worker.maven.SpotlessClassLoaders;
import com.github.miguelaguiar01.spotless.worker.spi.GradleAdapter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Gradle mode: the sync-time model already carries the serialized steps and the buildscript
 * classpath; load that classpath child-first with the adapter and deserialize there.
 */
public final class GradleEngine implements FormatEngine {
    private final SpotlessConfig config;
    private final WorkerContext context;
    private final WorkerLog log;
    private URLClassLoader loader;
    private GradleAdapter adapter;
    private List<Message.FormatInfo> formats = List.of();
    private final List<Message.FallbackFormat> fallback = new ArrayList<>();

    public GradleEngine(SpotlessConfig config, WorkerContext context, WorkerLog log) {
        this.config = config;
        this.context = context;
        this.log = log;
    }

    @Override
    public List<String> load() throws Exception {
        SpotlessConfig.Gradle gradle = config.gradle();
        if (gradle == null) throw new UnsupportedConfigException("EXTRACTION_FAILED", "Gradle config is missing");
        List<String> warnings = new ArrayList<>();
        List<GradleAdapter.Format> usable = new ArrayList<>();
        for (SpotlessConfig.GradleFormat f : gradle.formats()) {
            if (f.fallbackReason() != null || f.stepsBase64() == null || f.lineEndingPolicyBase64() == null) {
                warnings.add("Format '" + f.name() + "' is handled by the build tool: " + f.fallbackReason());
                fallback.add(new Message.FallbackFormat(f.name(), f.fallbackReason(), f.targetFiles()));
                continue;
            }
            usable.add(new GradleAdapter.Format(f.name(), Base64.getDecoder().decode(f.stepsBase64()),
                    Base64.getDecoder().decode(f.lineEndingPolicyBase64()), f.encoding(), f.targetFiles(), f.ratchetFrom()));
        }
        if (usable.isEmpty()) {
            throw new UnsupportedConfigException("GRADLE_STEP_NOT_SERIALIZABLE",
                    "No Spotless format of this Gradle project can be formatted natively: " + String.join("; ", warnings));
        }
        List<URL> urls = new ArrayList<>();
        for (Path p : context.adapterJars("worker-gradle-adapter")) urls.add(p.toUri().toURL());
        int missing = 0;
        for (String jar : gradle.classpath()) {
            Path p = Path.of(jar);
            if (Files.exists(p)) urls.add(p.toUri().toURL());
            else missing++;
        }
        if (missing > 0) warnings.add(missing + " buildscript classpath entries no longer exist; re-sync the Gradle project if loading fails");
        loader = SpotlessClassLoaders.childFirst(urls, GradleEngine.class.getClassLoader());

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class<?> impl = Class.forName(GradleAdapter.IMPLEMENTATION_CLASS, true, loader);
            adapter = (GradleAdapter) impl.getDeclaredConstructor().newInstance();
            GradleAdapter.Loaded loaded = adapter.load(new GradleAdapter.Request(Path.of(config.projectDir()), usable, log::info));
            formats = loaded.formats();
            fallback.addAll(loaded.fallback());
            warnings.addAll(loaded.warnings());
            return warnings;
        } catch (UnsupportedConfigException e) {
            throw e;
        } catch (Throwable t) {
            close();
            throw new UnsupportedConfigException("WORKER_LOAD_FAILED",
                    "Spotless Gradle steps could not be restored: " + rootMessage(t));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Override
    public List<Message.FormatInfo> formats() {
        return formats;
    }

    @Override
    public List<Message.FallbackFormat> fallbackFormats() {
        return fallback;
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

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root == t ? String.valueOf(t.getMessage()) : t.getMessage() + " (caused by " + root + ")";
    }
}
