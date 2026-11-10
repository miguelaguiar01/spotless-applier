package com.github.miguelaguiar01.spotless.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.miguelaguiar01.spotless.protocol.FormatStatus;
import com.github.miguelaguiar01.spotless.protocol.FrameCodec;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.Messages;
import com.github.miguelaguiar01.spotless.protocol.ProtocolVersion;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Spawns a real worker JVM and talks the protocol over its stdio. */
@Timeout(60)
class WorkerProcessTest {
    private Process process;
    private FrameCodec.Reader reader;
    private FrameCodec.Writer writer;
    private final List<Message.Log> logs = new ArrayList<>();
    private int nextId = 1;

    @BeforeEach
    void start() throws IOException {
        String java = System.getProperty("spotless.worker.test.java", System.getProperty("java.home") + "/bin/java");
        List<String> cmd = List.of(java, "-cp", System.getProperty("java.class.path"),
                WorkerMain.class.getName(), "--log-level=DEBUG");
        process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        reader = new FrameCodec.Reader(process.getInputStream());
        writer = new FrameCodec.Writer(process.getOutputStream());
        Message hello = next();
        assertInstanceOf(Message.Hello.class, hello);
        assertEquals(ProtocolVersion.CURRENT, ((Message.Hello) hello).protocolVersion());
        assertEquals("worker", ((Message.Hello) hello).info().get("role"));
        writer.writeFrame(new Message.Hello(ProtocolVersion.CURRENT, Map.of("role", "test")).encode());
    }

    @AfterEach
    void stop() throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Reads the next non-log message, collecting logs on the side. */
    private Message next() throws IOException {
        while (true) {
            String frame = reader.readFrame();
            assertNotNull(frame, "worker closed the channel");
            Message m = Messages.decode(frame);
            if (m instanceof Message.Log l) {
                logs.add(l);
                continue;
            }
            return m;
        }
    }

    private String id() {
        return String.valueOf(nextId++);
    }

    private SpotlessConfig config(Map<String, Object> extra) {
        return new SpotlessConfig(SpotlessConfig.Kind.BUILTIN_TEST, null, "/tmp/project", "/tmp/project/target",
                null, extra);
    }

    private String load(SpotlessConfig cfg) throws IOException {
        String id = id();
        writer.writeFrame(new Message.LoadConfig(id, cfg).encode());
        Message m = next();
        assertInstanceOf(Message.ConfigLoaded.class, m);
        Message.ConfigLoaded loaded = (Message.ConfigLoaded) m;
        assertEquals(id, loaded.id());
        assertTrue(loaded.isOk(), "load failed: " + loaded.error());
        assertEquals(cfg.configId(), loaded.configId());
        return loaded.configId();
    }

    private Message.FormatResult format(String configId, String text) throws IOException {
        String id = id();
        writer.writeFrame(new Message.Format(id, configId, "/tmp/project/src/A.java", text).encode());
        Message m = next();
        assertInstanceOf(Message.FormatResult.class, m, m.toString());
        assertEquals(id, m.id());
        return (Message.FormatResult) m;
    }

    @Test
    void pingPong() throws IOException {
        String id = id();
        writer.writeFrame(new Message.Ping(id).encode());
        assertEquals(new Message.Pong(id), next());
    }

    @Test
    void formatsAndReportsClean() throws IOException {
        String configId = load(config(Map.of("steps", "trimTrailingWhitespace,endWithNewline")));
        Message.FormatResult r = format(configId, "class A {   \n  int x;\t\n}");
        assertEquals(FormatStatus.FORMATTED, r.status());
        assertEquals("class A {\n  int x;\n}\n", r.text());

        Message.FormatResult clean = format(configId, "class A {\n  int x;\n}\n");
        assertEquals(FormatStatus.CLEAN, clean.status());
    }

    @Test
    void survivesFormatterClosingAndPrintingToStdout() throws IOException {
        String configId = load(config(Map.of("misbehave", "printStdout,closeStdout")));
        Message.FormatResult r = format(configId, "x \n");
        assertEquals(FormatStatus.FORMATTED, r.status());
        assertEquals("x\n", r.text());

        // The channel still works after System.out.close() ...
        Message.FormatResult again = format(configId, "y  \n");
        assertEquals("y\n", again.text());
        String id = id();
        writer.writeFrame(new Message.Ping(id).encode());
        assertEquals(new Message.Pong(id), next());

        // ... and the chatter arrived as log frames instead of corrupting it.
        assertTrue(logs.stream().anyMatch(l -> l.message().contains("[stdout] formatter chatter on stdout")), logs.toString());
        assertTrue(logs.stream().anyMatch(l -> l.message().contains("[stderr] formatter chatter on stderr")), logs.toString());
    }

    @Test
    void reportsStepFailuresAsErrorResults() throws IOException {
        String configId = load(config(Map.of("steps", "fail")));
        Message.FormatResult r = format(configId, "x");
        assertEquals(FormatStatus.ERROR, r.status());
        assertTrue(r.message().contains("always fails"), r.message());
    }

    @Test
    void unknownConfigIsAnError() throws IOException {
        Message.FormatResult r = format("nope", "x");
        assertEquals(FormatStatus.ERROR, r.status());
    }

    @Test
    void cancelDropsSlowRequestAndWorkerStaysResponsive() throws IOException {
        String configId = load(config(Map.of("misbehave", "sleepMs=20000")));
        String slow = id();
        writer.writeFrame(new Message.Format(slow, configId, "/tmp/project/x", "x ").encode());
        writer.writeFrame(new Message.Cancel(slow).encode());
        String ping = id();
        writer.writeFrame(new Message.Ping(ping).encode());
        Message m = next();
        assertEquals(new Message.Pong(ping), m, "expected pong, not the cancelled result");
    }

    @Test
    void invalidateThenShutdown() throws Exception {
        String configId = load(config(Map.of()));
        String inv = id();
        writer.writeFrame(new Message.Invalidate(inv, configId).encode());
        assertEquals(new Message.Ack(inv), next());
        Message.FormatResult r = format(configId, "x");
        assertEquals(FormatStatus.ERROR, r.status(), "config must be gone after invalidate");

        String sd = id();
        writer.writeFrame(new Message.Shutdown(sd).encode());
        assertEquals(new Message.Ack(sd), next());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "worker did not exit after shutdown");
        assertEquals(0, process.exitValue());
    }

    @Test
    void exitsWhenParentClosesStdin() throws Exception {
        process.getOutputStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "worker did not exit on stdin EOF");
        assertFalse(process.isAlive());
    }
}
