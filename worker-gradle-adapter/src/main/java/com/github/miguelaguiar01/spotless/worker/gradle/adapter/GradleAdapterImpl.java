package com.github.miguelaguiar01.spotless.worker.gradle.adapter;

import com.diffplug.spotless.ConfigurationCacheHackList;
import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.extra.GitRatchet;
import com.github.miguelaguiar01.spotless.protocol.FormatStatus;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.worker.spi.GradleAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds the Gradle plugin's Formatters from the configuration-cache representation of each
 * SpotlessTask (steps + line-ending policy serialized at sync time) and formats like
 * {@code SpotlessTaskImpl}/its IDE hook would: target set membership, ratchet, DirtyState.
 */
public final class GradleAdapterImpl implements GradleAdapter {
    private record Entry(String name, Formatter formatter, Set<String> targets, String ratchetFrom) {}

    private final List<Entry> entries = new ArrayList<>();
    private Path projectDir;

    @Override
    public Loaded load(Request request) throws Exception {
        this.projectDir = request.projectDir();
        List<String> warnings = new ArrayList<>();
        List<Message.FormatInfo> infos = new ArrayList<>();
        List<Message.FallbackFormat> fallback = new ArrayList<>();
        for (Format f : request.formats()) {
            try {
                ConfigurationCacheHackList steps = (ConfigurationCacheHackList) deserialize(f.steps());
                LineEnding.Policy policy = (LineEnding.Policy) deserialize(f.lineEndingPolicy());
                List<FormatterStep> stepList = steps.getSteps();
                Formatter formatter = Formatter.builder()
                        .encoding(Charset.forName(f.encoding() == null ? "UTF-8" : f.encoding()))
                        .lineEndingsPolicy(policy)
                        .steps(stepList)
                        .build();
                Set<String> targets = new HashSet<>();
                for (String t : f.targetFiles()) targets.add(normalize(Path.of(t)));
                entries.add(new Entry(f.name(), formatter, targets, f.ratchetFrom()));
                infos.add(new Message.FormatInfo(f.name(), List.of("<" + f.targetFiles().size() + " target files>"), List.of()));
            } catch (Throwable t) {
                String reason = "GRADLE_STEP_NOT_SERIALIZABLE: " + rootMessage(t);
                warnings.add("Format '" + f.name() + "' could not be restored and is handled by the build tool: " + rootMessage(t));
                List<String> targets = new ArrayList<>();
                for (String target : f.targetFiles()) targets.add(normalize(Path.of(target)));
                fallback.add(new Message.FallbackFormat(f.name(), reason, targets));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("No format could be restored: " + String.join("; ", warnings));
        }
        return new Loaded(infos, warnings, fallback);
    }

    @Override
    public Message.FormatResult format(String requestId, Path file, String unixText) throws Exception {
        String key = normalize(file);
        List<Entry> matching = entries.stream().filter(e -> e.targets().contains(key)).toList();
        if (matching.isEmpty()) {
            return new Message.FormatResult(requestId, FormatStatus.NOT_COVERED, null,
                    file + " is not a target of any natively handled Spotless format (re-sync after adding files)", List.of(), null);
        }
        File target = file.toFile();
        String current = unixText;
        String lineEnding = null;
        for (Entry entry : matching) {
            if (entry.ratchetFrom() != null && !entry.ratchetFrom().isBlank() && isCleanPerRatchet(entry.ratchetFrom(), target)) continue;
            Formatter formatter = entry.formatter();
            Charset charset = formatter.getEncoding();
            lineEnding = formatter.getLineEndingsPolicy().getEndingFor(target);
            DirtyState dirty = DirtyState.of(formatter, target, current.getBytes(charset));
            if (dirty.didNotConverge()) {
                return new Message.FormatResult(requestId, FormatStatus.DID_NOT_CONVERGE, null,
                        entry.name() + " did not converge on " + file, List.of(), lineEnding);
            }
            if (!dirty.isClean()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                dirty.writeCanonicalTo(out);
                current = LineEnding.toUnix(out.toString(charset));
            }
        }
        if (current.equals(unixText)) {
            return new Message.FormatResult(requestId, FormatStatus.CLEAN, null, null, List.of(), lineEnding);
        }
        return new Message.FormatResult(requestId, FormatStatus.FORMATTED, current, null, List.of(), lineEnding);
    }

    @Override
    public void close() throws Exception {
        Exception error = null;
        for (Entry e : entries) {
            try {
                e.formatter().close();
            } catch (Exception ex) {
                if (error == null) error = ex;
                else error.addSuppressed(ex);
            }
        }
        entries.clear();
        if (error != null) throw error;
    }

    private Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        ClassLoader loader = getClass().getClassLoader();
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                try {
                    return Class.forName(desc.getName(), false, loader);
                } catch (ClassNotFoundException e) {
                    return super.resolveClass(desc);
                }
            }
        }) {
            return in.readObject();
        }
    }

    private boolean isCleanPerRatchet(String ratchetFrom, File target) throws IOException {
        File dir = projectDir.toFile();
        try (GitRatchet<File> ratchet = new GitRatchet<>() {
            @Override
            protected File getDir(File project) {
                return project;
            }

            @Override
            protected File getParent(File project) {
                return project.getParentFile();
            }
        }) {
            return ratchet.isClean(dir, ratchet.rootTreeShaOf(dir, ratchetFrom), target);
        } catch (Exception e) {
            throw new IOException("ratchetFrom=" + ratchetFrom + " could not be evaluated: " + rootMessage(e), e);
        }
    }

    /** Real path when the file exists (macOS /var vs /private/var, symlinked checkouts), else absolute+normalized. */
    private static String normalize(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
