package com.github.miguelaguiar01.spotless.worker.maven.adapter;

import java.util.function.Consumer;
import org.apache.maven.plugin.logging.Log;

/** Maven plugin {@link Log} that forwards to the worker log. */
final class ConsumerLog implements Log {
    private final Consumer<String> sink;

    ConsumerLog(Consumer<String> sink) {
        this.sink = sink;
    }

    private void emit(String level, CharSequence content, Throwable error) {
        String msg = "[maven " + level + "] " + content;
        if (error != null) msg += " (" + error + ")";
        sink.accept(msg);
    }

    @Override public boolean isDebugEnabled() { return false; }
    @Override public void debug(CharSequence content) { }
    @Override public void debug(CharSequence content, Throwable error) { }
    @Override public void debug(Throwable error) { }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public void info(CharSequence content) { emit("info", content, null); }
    @Override public void info(CharSequence content, Throwable error) { emit("info", content, error); }
    @Override public void info(Throwable error) { emit("info", "", error); }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public void warn(CharSequence content) { emit("warn", content, null); }
    @Override public void warn(CharSequence content, Throwable error) { emit("warn", content, error); }
    @Override public void warn(Throwable error) { emit("warn", "", error); }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public void error(CharSequence content) { emit("error", content, null); }
    @Override public void error(CharSequence content, Throwable error) { emit("error", content, error); }
    @Override public void error(Throwable error) { emit("error", "", error); }
}
