package com.github.miguelaguiar01.spotless.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.miguelaguiar01.spotless.protocol.Message;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StdioGuardTest {

    @Test
    void stderrLinesKeepTheLevelSlf4jSimplePrinted() {
        WorkerLog log = new WorkerLog();
        log.setLevel(WorkerLog.Level.DEBUG);
        List<Message.Log> seen = new ArrayList<>();
        log.attach(seen::add);
        PrintStream err = new StdioGuard.NonClosablePrintStream(
                new StdioGuard.LineForwardingStream(log, WorkerLog.Level.WARN, "stderr"));

        err.println("[spotless-format] INFO com.diffplug.spotless.glue.pom.SortPomFormatterFunc - Sorting file x");
        err.println("[spotless-format] WARN com.diffplug.spotless.generic.LicenseHeaderStep - Can't parse year");
        err.println("DEBUG org.example.Thing - verbose");
        err.println("");
        err.println("Exception in thread \"main\" java.lang.RuntimeException: boom");
        err.close(); // must not close anything, only flush
        err.print("tail without newline");
        err.flush();

        assertEquals(5, seen.size(), seen.toString());
        assertEquals("INFO", seen.get(0).level());
        assertEquals("WARN", seen.get(1).level());
        assertEquals("DEBUG", seen.get(2).level());
        assertEquals("WARN", seen.get(3).level());
        assertEquals("[stderr] Exception in thread \"main\" java.lang.RuntimeException: boom", seen.get(3).message());
        assertEquals("[stderr] tail without newline", seen.get(4).message());
    }

    @Test
    void multiByteCharactersSurviveLineSplitting() {
        WorkerLog log = new WorkerLog();
        List<Message.Log> seen = new ArrayList<>();
        log.attach(seen::add);
        StdioGuard.LineForwardingStream out = new StdioGuard.LineForwardingStream(log, WorkerLog.Level.INFO, "stdout");
        byte[] bytes = "héllo wörld\n".getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) out.write(b);
        assertEquals("[stdout] héllo wörld", seen.get(0).message());
    }
}
