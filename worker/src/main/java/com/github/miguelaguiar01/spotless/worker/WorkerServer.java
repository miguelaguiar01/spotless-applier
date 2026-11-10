package com.github.miguelaguiar01.spotless.worker;

import com.github.miguelaguiar01.spotless.protocol.FrameCodec;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.Messages;
import com.github.miguelaguiar01.spotless.protocol.ProtocolVersion;
import com.github.miguelaguiar01.spotless.worker.engine.EngineFactory;
import com.github.miguelaguiar01.spotless.worker.engine.FormatEngine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads frames from the protocol input, dispatches requests, writes responses. Formatting runs on
 * a dedicated pool so {@code ping}, {@code cancel} and {@code shutdown} stay responsive while a
 * step is busy.
 */
final class WorkerServer {
    private final FrameCodec.Reader in;
    private final FrameCodec.Writer out;
    private final WorkerLog log;
    private final long idleTimeoutMs;
    private final WorkerContext context;

    private final Map<String, FormatEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService formatPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "spotless-format");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spotless-idle-timer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private volatile ScheduledFuture<?> idleCheck;
    private volatile int exitCode = 0;
    private volatile boolean stop;

    WorkerServer(InputStream protocolIn, OutputStream protocolOut, WorkerLog log, long idleTimeoutMs, WorkerContext context) {
        this.in = new FrameCodec.Reader(protocolIn);
        this.out = new FrameCodec.Writer(protocolOut);
        this.log = log;
        this.idleTimeoutMs = idleTimeoutMs;
        this.context = context;
    }

    int run() {
        log.attach(this::send);
        Map<String, String> info = new LinkedHashMap<>();
        info.put("role", "worker");
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaHome", System.getProperty("java.home"));
        info.put("pid", String.valueOf(ProcessHandle.current().pid()));
        send(new Message.Hello(ProtocolVersion.CURRENT, info));

        if (idleTimeoutMs > 0) {
            long period = Math.max(1000, idleTimeoutMs / 4);
            idleCheck = timer.scheduleAtFixedRate(this::checkIdle, period, period, TimeUnit.MILLISECONDS);
        }

        try {
            while (!stop) {
                String frame = in.readFrame();
                if (frame == null) {
                    log.info("Protocol input closed; exiting");
                    break;
                }
                lastActivity.set(System.nanoTime());
                Message msg;
                try {
                    msg = Messages.decode(frame);
                } catch (RuntimeException e) {
                    log.warn("Undecodable frame: " + e.getMessage());
                    send(new Message.Error(null, "Undecodable frame: " + e.getMessage()));
                    continue;
                }
                dispatch(msg);
            }
        } catch (IOException e) {
            // Parent died or pipe broke: exit quietly.
            exitCode = 0;
        } finally {
            closeAll();
            formatPool.shutdownNow();
            timer.shutdownNow();
        }
        return exitCode;
    }

    private void dispatch(Message msg) {
        if (msg instanceof Message.Ping p) {
            send(new Message.Pong(p.id()));
        } else if (msg instanceof Message.Hello h) {
            if (h.protocolVersion() != ProtocolVersion.CURRENT) {
                log.warn("IDE speaks protocol " + h.protocolVersion() + ", worker speaks " + ProtocolVersion.CURRENT);
            }
        } else if (msg instanceof Message.Shutdown s) {
            send(new Message.Ack(s.id()));
            stop = true;
        } else if (msg instanceof Message.LoadConfig lc) {
            // Loading can be slow (artifact resolution); keep the reader loop free.
            formatPool.submit(() -> loadConfig(lc));
        } else if (msg instanceof Message.Invalidate inv) {
            FormatEngine e = engines.remove(inv.configId());
            if (e != null) closeQuietly(e, inv.configId());
            send(new Message.Ack(inv.id()));
        } else if (msg instanceof Message.Format f) {
            Future<?> future = formatPool.submit(() -> format(f));
            inFlight.put(f.id(), future);
        } else if (msg instanceof Message.Cancel c) {
            Future<?> future = inFlight.remove(c.id());
            if (future != null) future.cancel(true);
        } else {
            send(new Message.Error(msg.id(), "Unexpected message type '" + msg.type() + "'"));
        }
    }

    private void loadConfig(Message.LoadConfig lc) {
        String configId = lc.config().configId();
        try {
            FormatEngine previous = engines.get(configId);
            if (previous != null) {
                send(new Message.ConfigLoaded(lc.id(), configId, previous.formats(), List.of(), null, null, previous.fallbackFormats()));
                return;
            }
            FormatEngine engine = EngineFactory.create(lc.config(), context, log);
            List<String> warnings = engine.load();
            engines.put(configId, engine);
            send(new Message.ConfigLoaded(lc.id(), configId, engine.formats(), warnings, null, null, engine.fallbackFormats()));
        } catch (FormatEngine.UnsupportedConfigException e) {
            log.warn("Config " + configId + " not supported natively: " + e.getMessage());
            send(new Message.ConfigLoaded(lc.id(), configId, List.of(), List.of(), e.getMessage(), e.reason()));
        } catch (Throwable t) {
            log.error("Failed to load config " + configId, t);
            send(new Message.ConfigLoaded(lc.id(), configId, List.of(), List.of(),
                    t.getClass().getSimpleName() + ": " + t.getMessage(), "WORKER_LOAD_FAILED"));
        }
    }

    private void format(Message.Format f) {
        try {
            FormatEngine engine = engines.get(f.configId());
            if (engine == null) {
                send(Message.FormatResult.error(f.id(), "Unknown configId " + f.configId() + " (call loadConfig first)"));
                return;
            }
            Message.FormatResult result = engine.format(f.id(), f.path(), f.text());
            if (!Thread.currentThread().isInterrupted()) {
                send(result);
            }
        } catch (Throwable t) {
            if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                return; // cancelled
            }
            log.error("Formatting " + f.path() + " failed", t);
            send(Message.FormatResult.error(f.id(), t.toString()));
        } finally {
            inFlight.remove(f.id());
            lastActivity.set(System.nanoTime());
        }
    }

    private void checkIdle() {
        if (!inFlight.isEmpty()) return;
        long idleMs = (System.nanoTime() - lastActivity.get()) / 1_000_000;
        if (idleMs >= idleTimeoutMs) {
            log.info("Idle for " + idleMs + " ms; shutting down");
            stop = true;
            // Unblock the reader: closing our end of stdin is not possible portably, so halt.
            closeAll();
            Runtime.getRuntime().halt(0);
        }
    }

    private void closeAll() {
        for (Map.Entry<String, FormatEngine> e : engines.entrySet()) {
            closeQuietly(e.getValue(), e.getKey());
        }
        engines.clear();
    }

    private void closeQuietly(FormatEngine e, String configId) {
        try {
            e.close();
        } catch (Exception ex) {
            log.warn("Closing engine " + configId + " failed: " + ex);
        }
    }

    private void send(Message msg) {
        try {
            out.writeFrame(msg.encode());
        } catch (IOException e) {
            // Channel is gone: stop the loop; nothing else can be reported.
            stop = true;
        }
    }
}
