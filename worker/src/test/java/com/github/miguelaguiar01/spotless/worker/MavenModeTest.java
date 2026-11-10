package com.github.miguelaguiar01.spotless.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end Maven mode: a real worker JVM resolves spotless-maven-plugin from Maven Central (into
 * the user's local repository, so repeated runs are warm) and formats with google-java-format.
 * Needs network on the first run.
 */
@Timeout(600)
class MavenModeTest {
    private static final String SPOTLESS_VERSION = "3.11.0";

    @TempDir
    Path projectDir;
    @TempDir
    Path cacheDir;

    private Process process;
    private FrameCodec.Reader reader;
    private FrameCodec.Writer writer;
    private final List<Message.Log> logs = new ArrayList<>();
    private int nextId = 1;

    @BeforeEach
    void start() throws IOException {
        String java = System.getProperty("spotless.worker.test.java", System.getProperty("java.home") + "/bin/java");
        String adapterDir = System.getProperty("spotless.worker.test.adapterDir");
        assertNotNull(adapterDir, "spotless.worker.test.adapterDir must be set by the build");
        List<String> cmd = List.of(java,
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                WorkerMain.class.getName(), "--log-level=DEBUG",
                "--cache-dir=" + cacheDir, "--adapter-dir=" + adapterDir);
        process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        reader = new FrameCodec.Reader(process.getInputStream());
        writer = new FrameCodec.Writer(process.getOutputStream());
        assertInstanceOf(Message.Hello.class, next());
        writer.writeFrame(new Message.Hello(ProtocolVersion.CURRENT, Map.of("role", "test")).encode());
    }

    @AfterEach
    void stop() throws Exception {
        if (process.isAlive()) {
            writer.writeFrame(new Message.Shutdown(id()).encode());
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private Message next() throws IOException {
        while (true) {
            String frame = reader.readFrame();
            assertNotNull(frame, "worker closed the channel; logs: " + logs);
            Message m = Messages.decode(frame);
            if (m instanceof Message.Log l) {
                logs.add(l);
                System.out.println("[worker " + l.level() + "] " + l.message());
                continue;
            }
            return m;
        }
    }

    private String id() {
        return String.valueOf(nextId++);
    }

    private SpotlessConfig mavenConfig(String configurationXml) {
        SpotlessConfig.Maven maven = new SpotlessConfig.Maven(
                configurationXml,
                Map.of(),
                List.of(projectDir.resolve("src/main/java").toString()),
                List.of(projectDir.resolve("src/test/java").toString()),
                List.of(new SpotlessConfig.Repository("central", "https://repo.maven.apache.org/maven2", true, false)),
                null, null, null, false);
        return new SpotlessConfig(SpotlessConfig.Kind.MAVEN, SPOTLESS_VERSION, projectDir.toString(),
                projectDir.resolve("target").toString(), maven, Map.of());
    }

    private Message.ConfigLoaded load(SpotlessConfig cfg) throws IOException {
        String id = id();
        writer.writeFrame(new Message.LoadConfig(id, cfg).encode());
        Message m = next();
        assertInstanceOf(Message.ConfigLoaded.class, m, m.toString());
        return (Message.ConfigLoaded) m;
    }

    private Message.FormatResult format(String configId, Path file, String text) throws IOException {
        String id = id();
        writer.writeFrame(new Message.Format(id, configId, file.toString(), text).encode());
        Message m = next();
        assertInstanceOf(Message.FormatResult.class, m, m.toString());
        return (Message.FormatResult) m;
    }

    @Test
    void formatsJavaWithGoogleJavaFormatAndCustomFormat() throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.writeString(projectDir.resolve("src/main/java/A.java"), "class A {}\n");
        Files.writeString(projectDir.resolve("README.md"), "# hi  \n");

        SpotlessConfig cfg = mavenConfig("""
                <configuration>
                  <java>
                    <googleJavaFormat/>
                  </java>
                  <formats>
                    <format>
                      <includes><include>*.md</include></includes>
                      <trimTrailingWhitespace/>
                      <endWithNewline/>
                    </format>
                  </formats>
                </configuration>
                """);
        Message.ConfigLoaded loaded = load(cfg);
        assertTrue(loaded.isOk(), "load failed: " + loaded.error() + " / " + loaded.fallbackReason() + "\nlogs: " + logs);
        assertEquals(List.of("Format", "Java"), loaded.formats().stream().map(Message.FormatInfo::name).toList());

        Message.FormatResult java = format(loaded.configId(), projectDir.resolve("src/main/java/A.java"),
                "package p;\nimport java.util.*;\npublic class A { private   int x ;\n  void f(){System.out.println( 1 );}}\n");
        assertEquals(FormatStatus.FORMATTED, java.status(), java.message());
        // google-java-format alone keeps unused imports (only removeUnusedImports would drop them).
        assertEquals("""
                package p;

                import java.util.*;

                public class A {
                  private int x;

                  void f() {
                    System.out.println(1);
                  }
                }
                """, java.text());

        Message.FormatResult clean = format(loaded.configId(), projectDir.resolve("src/main/java/A.java"), java.text());
        assertEquals(FormatStatus.CLEAN, clean.status(), clean.message());

        Message.FormatResult md = format(loaded.configId(), projectDir.resolve("README.md"), "# hi  \n\ntext   ");
        assertEquals(FormatStatus.FORMATTED, md.status(), md.message());
        assertEquals("# hi\n\ntext\n", md.text());

        Message.FormatResult notCovered = format(loaded.configId(), projectDir.resolve("src/main/resources/x.txt"), "x");
        assertEquals(FormatStatus.NOT_COVERED, notCovered.status(), notCovered.message());

        Message.FormatResult outside = format(loaded.configId(), projectDir.getParent().resolve("elsewhere.java"), "x");
        assertEquals(FormatStatus.NOT_COVERED, outside.status(), outside.message());

        // Loading the same config again is a no-op and the classpath cache file exists.
        assertTrue(load(cfg).isOk());
        assertTrue(Files.list(cacheDir).anyMatch(p -> p.getFileName().toString().startsWith("classpath-")), "cache written");
    }

    @Test
    void unknownStepIsReportedAsLoadFailure() throws Exception {
        SpotlessConfig cfg = mavenConfig("<configuration><java><noSuchStep/></java></configuration>");
        Message.ConfigLoaded loaded = load(cfg);
        // Same as Maven: the component configurator rejects unknown parameters.
        assertTrue(!loaded.isOk(), loaded.toString());
        assertEquals("WORKER_LOAD_FAILED", loaded.fallbackReason());
        assertTrue(loaded.error().contains("noSuchStep"), loaded.error());
    }
}
