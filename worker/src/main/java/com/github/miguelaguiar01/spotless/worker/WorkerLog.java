package com.github.miguelaguiar01.spotless.worker;

import com.github.miguelaguiar01.spotless.protocol.Message;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

/**
 * Worker-side log. Messages are forwarded to the IDE as {@code log} frames once the protocol writer
 * is attached; anything logged before that (or after the channel broke) is dropped after being
 * written to the original stderr is no longer possible — so we keep it simple and drop it.
 */
public final class WorkerLog {
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private volatile Level level = Level.INFO;
    private volatile Consumer<Message.Log> sink = msg -> {};

    void setLevel(Level level) {
        this.level = level;
    }

    void attach(Consumer<Message.Log> sink) {
        this.sink = sink;
    }

    public boolean isDebug() {
        return level == Level.DEBUG;
    }

    public void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    public void info(String msg) {
        log(Level.INFO, msg, null);
    }

    public void warn(String msg) {
        log(Level.WARN, msg, null);
    }

    public void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    public void log(Level lvl, String msg, Throwable t) {
        if (lvl.ordinal() < level.ordinal()) return;
        String trace = null;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            trace = sw.toString();
        }
        try {
            sink.accept(new Message.Log(lvl.name(), msg, trace));
        } catch (RuntimeException ignored) {
            // the channel is gone; nothing sensible left to do
        }
    }
}
