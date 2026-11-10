package com.github.miguelaguiar01.spotless.gradle.tooling;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;

/**
 * Pure Gradle-API inspection of a configured project: finds every Spotless task, serializes its
 * steps/policy (the same representation Spotless uses for Gradle's configuration cache) and records
 * the classpath needed to deserialize them. Uses reflection only, so it never links against
 * Spotless itself.
 */
public final class SpotlessTaskInspector {
    static final String SPOTLESS_TASK_CLASS = "com.diffplug.gradle.spotless.SpotlessTask";
    private static final Pattern PLUGIN_JAR = Pattern.compile("spotless-plugin-gradle-([0-9][^/\\\\]*?)\\.jar$");

    private SpotlessTaskInspector() {}

    /** Null when the project has no Spotless tasks. */
    public static SpotlessGradleModelImpl inspect(Project project) {
        List<SpotlessGradleModel.SpotlessFormatModel> formats = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> classpath = new LinkedHashSet<>();
        for (Task task : project.getTasks()) {
            Class<?> spotlessTaskClass = spotlessTaskSuperclass(task.getClass());
            if (spotlessTaskClass == null) continue;
            String name = task.getName();
            if (name.endsWith("IndependentHelper")) continue;
            collectClasspath(task.getClass().getClassLoader(), classpath);
            formats.add(inspectTask(task, spotlessTaskClass, warnings));
        }
        if (formats.isEmpty()) return null;
        try {
            Configuration buildscript = project.getBuildscript().getConfigurations().findByName("classpath");
            if (buildscript != null) {
                for (File f : buildscript.resolve()) classpath.add(f.getAbsolutePath());
            }
        } catch (RuntimeException e) {
            warnings.add("buildscript classpath not resolvable: " + e.getMessage());
        }
        return new SpotlessGradleModelImpl(pluginVersion(classpath), new ArrayList<>(classpath), formats, warnings);
    }

    private static SpotlessGradleModel.SpotlessFormatModel inspectTask(Task task, Class<?> spotlessTaskClass, List<String> warnings) {
        String taskName = task.getName();
        String formatName = formatName(taskName);
        String encoding = "UTF-8";
        List<String> targets = new ArrayList<>();
        String ratchetFrom = null;
        String steps = null;
        String policy = null;
        String fallback = null;
        try {
            encoding = String.valueOf(invoke(task, "getEncoding"));
            Object target = invoke(task, "getTarget");
            if (target instanceof FileCollection) {
                for (File f : ((FileCollection) target).getFiles()) targets.add(f.getAbsolutePath());
            }
            Field ratchetField = findField(spotlessTaskClass, "ratchetFrom");
            if (ratchetField != null) {
                ratchetField.setAccessible(true);
                Object v = ratchetField.get(task);
                ratchetFrom = v == null || v.toString().trim().isEmpty() ? null : v.toString();
            }
            Object policyProvider = invoke(task, "getLineEndingsPolicy");
            Object policyValue = policyProvider instanceof Provider ? ((Provider<?>) policyProvider).get() : policyProvider;
            policy = serialize(policyValue);
            Object stepsList = invoke(task, "getStepsInternalRoundtrip");
            steps = serialize(stepsList);
        } catch (Throwable t) {
            fallback = "GRADLE_STEP_NOT_SERIALIZABLE: " + taskName + ": " + rootMessage(t);
            warnings.add(fallback);
        }
        return new SpotlessGradleModelImpl.FormatImpl(formatName, taskName, steps, policy, encoding, targets, ratchetFrom, fallback);
    }

    static String formatName(String taskName) {
        if (taskName.startsWith("spotless") && taskName.length() > "spotless".length()) {
            return taskName.substring("spotless".length()).toLowerCase(Locale.ROOT);
        }
        return taskName;
    }

    private static String serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = findMethod(target.getClass(), method);
        if (m == null) throw new NoSuchMethodException(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static Class<?> spotlessTaskSuperclass(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (SPOTLESS_TASK_CLASS.equals(c.getName())) return c;
        }
        return null;
    }

    private static void collectClasspath(ClassLoader loader, Set<String> out) {
        ClassLoader stop = Project.class.getClassLoader();
        for (ClassLoader cl = loader; cl != null && cl != stop; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) cl).getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        try {
                            out.add(new File(url.toURI()).getAbsolutePath());
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    private static String pluginVersion(Set<String> classpath) {
        for (String path : classpath) {
            Matcher m = PLUGIN_JAR.matcher(path);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
