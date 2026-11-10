package com.github.miguelaguiar01.spotless.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

    private static SpotlessConfig sampleConfig(String version) {
        SpotlessConfig.Maven maven = new SpotlessConfig.Maven(
                "<configuration><java><googleJavaFormat/></java></configuration>",
                Map.of("project.basedir", "/p"),
                List.of("/p/src/main/java"), List.of("/p/src/test/java"),
                List.of(new SpotlessConfig.Repository("central", "https://repo.maven.apache.org/maven2", true, false)),
                "/home/u/.m2/repository", "/home/u/.m2/settings.xml", null, false);
        return new SpotlessConfig(SpotlessConfig.Kind.MAVEN, version, "/p", "/p/target", maven, Map.of());
    }

    @Test
    void everyMessageRoundTrips() {
        List<Message> all = List.of(
                new Message.Hello(ProtocolVersion.CURRENT, Map.of("role", "ide")),
                new Message.Ping("1"), new Message.Pong("1"), new Message.Shutdown("2"), new Message.Ack("2"),
                new Message.Error("3", "boom"), new Message.Log("WARN", "msg", "trace"),
                new Message.LoadConfig("4", sampleConfig("3.11.0")),
                new Message.ConfigLoaded("4", "abc", List.of(new Message.FormatInfo("java", List.of("**/*.java"), List.of())),
                        List.of("w"), null, null, List.of(new Message.FallbackFormat("shout", "closure", List.of("/p/notes.txt")))),
                new Message.Invalidate("5", "abc"),
                new Message.Format("6", "abc", "/p/A.java", "class A{}\n"),
                new Message.FormatResult("6", FormatStatus.FORMATTED, "class A {}\n", null,
                        List.of(new Message.Lint("step", 3, "lint")), "\r\n"),
                Message.FormatResult.clean("7"), Message.FormatResult.error("8", "bad"),
                new Message.Cancel("9"));
        for (Message m : all) {
            Message back = Messages.decode(m.encode());
            assertEquals(m, back, m.type());
        }
    }

    @Test
    void gradleConfigRoundTrips() {
        SpotlessConfig.Gradle gradle = new SpotlessConfig.Gradle(List.of("/a.jar", "/b.jar"), List.of(
                new SpotlessConfig.GradleFormat("java", "spotlessJava", "AAAA", "BBBB", "UTF-8", List.of("/p/A.java"), "main", null),
                new SpotlessConfig.GradleFormat("misc", "spotlessMisc", null, null, "UTF-8", List.of(), null, "GRADLE_STEP_NOT_SERIALIZABLE")));
        SpotlessConfig cfg = new SpotlessConfig(SpotlessConfig.Kind.GRADLE, "8.11.0", "/p", "/p/build", null, gradle, Map.of());
        Message back = Messages.decode(new Message.LoadConfig("1", cfg).encode());
        assertEquals(cfg, ((Message.LoadConfig) back).config());
    }

    @Test
    void configIdIsStableAndContentSensitive() {
        assertEquals(sampleConfig("3.11.0").configId(), sampleConfig("3.11.0").configId());
        assertNotEquals(sampleConfig("3.11.0").configId(), sampleConfig("3.10.4").configId());
        assertEquals(64, sampleConfig("3.11.0").configId().length());
    }

    @Test
    void framesRoundTripAndEndCleanly() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameCodec.Writer w = new FrameCodec.Writer(bytes);
        w.writeFrame(new Message.Ping("a").encode());
        w.writeFrame(new Message.Format("b", "c", "/x", "héllo\n").encode());
        FrameCodec.Reader r = new FrameCodec.Reader(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(new Message.Ping("a"), Messages.decode(r.readFrame()));
        assertEquals(new Message.Format("b", "c", "/x", "héllo\n"), Messages.decode(r.readFrame()));
        assertNull(r.readFrame());
    }
}
