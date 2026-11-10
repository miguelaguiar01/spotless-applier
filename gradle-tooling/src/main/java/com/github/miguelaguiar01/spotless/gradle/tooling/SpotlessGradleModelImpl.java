package com.github.miguelaguiar01.spotless.gradle.tooling;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class SpotlessGradleModelImpl implements SpotlessGradleModel, Serializable {
    private static final long serialVersionUID = 1L;

    private final String pluginVersion;
    private final List<String> classpath;
    private final List<SpotlessFormatModel> formats;
    private final List<String> warnings;

    public SpotlessGradleModelImpl(String pluginVersion, List<String> classpath, List<SpotlessFormatModel> formats, List<String> warnings) {
        this.pluginVersion = pluginVersion;
        this.classpath = new ArrayList<>(classpath);
        this.formats = new ArrayList<>(formats);
        this.warnings = new ArrayList<>(warnings);
    }

    @Override public String getPluginVersion() { return pluginVersion; }
    @Override public List<String> getClasspath() { return classpath; }
    @Override public List<SpotlessFormatModel> getFormats() { return formats; }
    @Override public List<String> getWarnings() { return warnings; }

    public static final class FormatImpl implements SpotlessFormatModel, Serializable {
        private static final long serialVersionUID = 1L;

        private final String name;
        private final String taskName;
        private final String stepsBase64;
        private final String lineEndingPolicyBase64;
        private final String encoding;
        private final List<String> targetFiles;
        private final String ratchetFrom;
        private final String fallbackReason;

        public FormatImpl(String name, String taskName, String stepsBase64, String lineEndingPolicyBase64, String encoding,
                          List<String> targetFiles, String ratchetFrom, String fallbackReason) {
            this.name = name;
            this.taskName = taskName;
            this.stepsBase64 = stepsBase64;
            this.lineEndingPolicyBase64 = lineEndingPolicyBase64;
            this.encoding = encoding;
            this.targetFiles = new ArrayList<>(targetFiles);
            this.ratchetFrom = ratchetFrom;
            this.fallbackReason = fallbackReason;
        }

        @Override public String getName() { return name; }
        @Override public String getTaskName() { return taskName; }
        @Override public String getStepsBase64() { return stepsBase64; }
        @Override public String getLineEndingPolicyBase64() { return lineEndingPolicyBase64; }
        @Override public String getEncoding() { return encoding; }
        @Override public List<String> getTargetFiles() { return targetFiles; }
        @Override public String getRatchetFrom() { return ratchetFrom; }
        @Override public String getFallbackReason() { return fallbackReason; }
    }
}
