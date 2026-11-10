package com.github.miguelaguiar01.spotless.worker.engine;

import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.WorkerLog;
import java.util.List;
import java.util.Map;

/**
 * Tiny formatter used by tests and smoke checks. Steps are selected via {@code extra.steps}
 * (comma separated): {@code trimTrailingWhitespace}, {@code endWithNewline}. {@code extra.misbehave}
 * makes the step do what badly behaved formatter code does: {@code closeStdout} closes
 * {@code System.out}/{@code System.err}, {@code printStdout} prints to them, {@code sleepMs=N} sleeps.
 */
final class BuiltinTestEngine implements FormatEngine {
    private final SpotlessConfig config;
    private final WorkerLog log;
    private List<String> steps = List.of("trimTrailingWhitespace");
    private String misbehave = "";

    BuiltinTestEngine(SpotlessConfig config, WorkerLog log) {
        this.config = config;
        this.log = log;
    }

    @Override
    public List<String> load() {
        Map<String, Object> extra = config.extra();
        Object s = extra.get("steps");
        if (s != null) steps = List.of(s.toString().split(","));
        Object m = extra.get("misbehave");
        if (m != null) misbehave = m.toString();
        log.info("Builtin test engine loaded with steps " + steps);
        return List.of();
    }

    @Override
    public List<Message.FormatInfo> formats() {
        return List.of(new Message.FormatInfo("builtin", List.of("**/*"), List.of()));
    }

    @Override
    public Message.FormatResult format(String requestId, String path, String text) throws Exception {
        if (misbehave.startsWith("sleepMs=")) {
            Thread.sleep(Long.parseLong(misbehave.substring("sleepMs=".length())));
        }
        if (misbehave.contains("printStdout")) {
            System.out.println("formatter chatter on stdout for " + path);
            System.err.println("formatter chatter on stderr");
        }
        if (misbehave.contains("closeStdout")) {
            System.out.close();
            System.err.close();
        }
        String result = text;
        for (String step : steps) {
            switch (step.trim()) {
                case "trimTrailingWhitespace" -> result = result.replaceAll("[ \\t]+(?=\\n|$)", "");
                case "endWithNewline" -> result = result.endsWith("\n") ? result : result + "\n";
                case "fail" -> throw new IllegalStateException("step 'fail' always fails");
                default -> throw new IllegalArgumentException("Unknown builtin step '" + step + "'");
            }
        }
        if (result.equals(text)) {
            return Message.FormatResult.clean(requestId);
        }
        return Message.FormatResult.formatted(requestId, result, List.of());
    }

    @Override
    public void close() {
        log.debug("Builtin test engine closed");
    }
}
