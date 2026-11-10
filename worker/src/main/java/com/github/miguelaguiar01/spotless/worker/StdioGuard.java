package com.github.miguelaguiar01.spotless.worker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces {@code System.out/err/in} so user code cannot touch the protocol channel. */
final class StdioGuard {
    private StdioGuard() {}

    static void install(WorkerLog log) {
        System.setOut(new NonClosablePrintStream(new LineForwardingStream(log, WorkerLog.Level.INFO, "stdout")));
        System.setErr(new NonClosablePrintStream(new LineForwardingStream(log, WorkerLog.Level.WARN, "stderr")));
        System.setIn(InputStream.nullInputStream());
    }

    /** {@link PrintStream} whose {@code close()} only flushes. */
    static final class NonClosablePrintStream extends PrintStream {
        NonClosablePrintStream(OutputStream out) {
            super(out, true, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            flush();
        }
    }

    /** Buffers bytes until a newline and emits each line as a log message. */
    static final class LineForwardingStream extends OutputStream {
        private final WorkerLog log;
        private final WorkerLog.Level level;
        private final String prefix;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        LineForwardingStream(WorkerLog log, WorkerLog.Level level, String prefix) {
            this.log = log;
            this.level = level;
            this.prefix = prefix;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else if (b != '\r') {
                buf.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) write(b[i]);
        }

        @Override
        public synchronized void flush() {
            if (buf.size() > 0) emit();
        }

        @Override
        public void close() {
            flush();
        }

        private void emit() {
            String line = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (line.isBlank()) return;
            log.log(levelOf(line, level), "[" + prefix + "] " + line, null);
        }

        /**
         * Formatter libraries log through slf4j-simple onto this stream as {@code [thread] LEVEL logger - msg}.
         * Honour that level instead of flagging every stderr line as a warning.
         */
        static WorkerLog.Level levelOf(String line, WorkerLog.Level fallback) {
            Matcher m = SLF4J_SIMPLE.matcher(line);
            if (!m.find()) return fallback;
            switch (m.group(1)) {
                case "TRACE":
                case "DEBUG":
                    return WorkerLog.Level.DEBUG;
                case "INFO":
                    return WorkerLog.Level.INFO;
                case "WARN":
                    return WorkerLog.Level.WARN;
                default:
                    return WorkerLog.Level.ERROR;
            }
        }

        private static final Pattern SLF4J_SIMPLE =
                Pattern.compile("^(?:\\[[^\\]]*\\] )?(TRACE|DEBUG|INFO|WARN|ERROR) \\S+ - ");
    }
}
