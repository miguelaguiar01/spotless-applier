package com.github.miguelaguiar01.spotless.worker;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Entry point of the worker JVM.
 *
 * <p>The very first thing it does is take ownership of the process's real stdin/stdout file
 * descriptors for the protocol and replace {@link System#out}/{@link System#err} with streams that
 * forward to the worker log and ignore {@code close()}. Spotless' own IDE hook and arbitrary
 * formatter code may print to or even close {@code System.out}; none of that must be able to
 * corrupt the protocol channel (see docs/DECISIONS.md ADR-008).
 *
 * <p>Arguments (all optional): {@code --idle-timeout-ms=N} (exit after N ms without requests;
 * 0 disables), {@code --log-level=DEBUG|INFO|WARN|ERROR}.
 */
public final class WorkerMain {
    private WorkerMain() {}

    public static void main(String[] args) {
        InputStream protocolIn = new FileInputStream(FileDescriptor.in);
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        WorkerLog log = new WorkerLog();
        StdioGuard.install(log);

        long idleTimeoutMs = 0;
        for (String arg : args) {
            if (arg.startsWith("--idle-timeout-ms=")) {
                idleTimeoutMs = Long.parseLong(arg.substring("--idle-timeout-ms=".length()));
            } else if (arg.startsWith("--log-level=")) {
                log.setLevel(WorkerLog.Level.valueOf(arg.substring("--log-level=".length()).toUpperCase()));
            }
        }

        int exitCode;
        try {
            WorkerServer server = new WorkerServer(protocolIn, protocolOut, log, idleTimeoutMs);
            exitCode = server.run();
        } catch (Throwable t) {
            // Last resort: the protocol channel may be gone; the parent sees the exit code.
            exitCode = 2;
        }
        // Formatter threads may be non-daemon and stuck; never let them keep the worker alive.
        Runtime.getRuntime().halt(exitCode);
    }
}
