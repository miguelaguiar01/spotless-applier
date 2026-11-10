package com.github.miguelaguiar01.spotless.worker.maven.adapter;

import java.io.File;
import java.util.Map;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.TypeAwareExpressionEvaluator;

/**
 * Stand-in for Maven's {@code PluginParameterExpressionEvaluator}, which needs a MavenSession.
 * Resolves {@code ${key}} from the properties captured on the IDE side; unknown expressions are
 * returned unchanged (Maven would do the same for unknown {@code ${...}} inside literal text).
 */
final class SimpleExpressionEvaluator implements TypeAwareExpressionEvaluator {
    private final Map<String, String> properties;
    private final File baseDir;

    SimpleExpressionEvaluator(Map<String, String> properties, File baseDir) {
        this.properties = properties;
        this.baseDir = baseDir;
    }

    @Override
    public Object evaluate(String expression) throws ExpressionEvaluationException {
        return evaluate(expression, null);
    }

    @Override
    public Object evaluate(String expression, Class<?> type) throws ExpressionEvaluationException {
        if (expression == null) return null;
        String value = expression;
        for (int guard = 0; guard < 10 && value.contains("${"); guard++) {
            String before = value;
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < value.length()) {
                int start = value.indexOf("${", i);
                if (start < 0) {
                    sb.append(value, i, value.length());
                    break;
                }
                int end = value.indexOf('}', start);
                if (end < 0) {
                    sb.append(value, i, value.length());
                    break;
                }
                sb.append(value, i, start);
                String key = value.substring(start + 2, end);
                String replacement = lookup(key);
                sb.append(replacement != null ? replacement : value.substring(start, end + 1));
                i = end + 1;
            }
            value = sb.toString();
            if (value.equals(before)) break;
        }
        return value;
    }

    private String lookup(String key) {
        String v = properties.get(key);
        if (v != null) return v;
        return switch (key) {
            case "basedir", "project.basedir", "pom.basedir" -> baseDir.getAbsolutePath();
            default -> System.getProperty(key);
        };
    }

    @Override
    public File alignToBaseDirectory(File path) {
        if (path == null) return null;
        if (path.isAbsolute()) return path;
        return new File(baseDir, path.getPath()).getAbsoluteFile();
    }
}
