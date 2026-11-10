package com.github.miguelaguiar01.spotless.gradle.tooling;

import java.util.List;

/**
 * What the IDE needs from a Gradle project that applies Spotless, captured during sync inside the
 * Gradle daemon. Getter-only interface so Gradle's tooling API can proxy it; implemented by
 * {@link SpotlessGradleModelImpl}.
 */
public interface SpotlessGradleModel {
    /** Version of com.diffplug.spotless as found on the buildscript classpath, or null. */
    String getPluginVersion();

    /** Absolute jar paths needed to deserialize the steps (buildscript classpath incl. Spotless). */
    List<String> getClasspath();

    List<SpotlessFormatModel> getFormats();

    List<String> getWarnings();

    interface SpotlessFormatModel {
        /** Format name as Spotless calls it ({@code java}, {@code kotlin}, {@code misc}…). */
        String getName();

        String getTaskName();

        /** Base64 of the Java-serialized {@code ConfigurationCacheHackList} (roundtrip variant). */
        String getStepsBase64();

        /** Base64 of the Java-serialized {@code LineEnding.Policy}. */
        String getLineEndingPolicyBase64();

        String getEncoding();

        /** Absolute paths of the task's target files at sync time. */
        List<String> getTargetFiles();

        /** {@code ratchetFrom} reference or null. */
        String getRatchetFrom();

        /** Non-null when this format cannot be handled natively (e.g. non-serializable custom step). */
        String getFallbackReason();
    }
}
