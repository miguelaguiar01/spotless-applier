package com.github.miguelaguiar01.spotless.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.miguelaguiar01.spotless.protocol.FormatStatus;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.protocol.SpotlessConfig;
import com.github.miguelaguiar01.spotless.worker.engine.EngineFactory;
import com.github.miguelaguiar01.spotless.worker.engine.FormatEngine;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The parity corpus: for every fixture project under {@code fixtures/maven}, run a real
 * {@code mvn spotless:apply} and check that the native engine produces byte-for-byte the same
 * result for every file (and reports CLEAN / NOT_COVERED for files Maven left alone).
 */
@Timeout(1200)
class MavenParityTest {
    private static Path fixtures;
    private static Path mavenHome;

    @TempDir
    static Path cacheDir;

    @BeforeAll
    static void locate() {
        fixtures = Path.of(System.getProperty("spotless.test.fixtures", "fixtures")).resolve("maven");
        mavenHome = Path.of(System.getProperty("spotless.test.mavenHome", ""));
        Assumptions.assumeTrue(Files.isDirectory(fixtures), "fixtures dir missing: " + fixtures);
        Assumptions.assumeTrue(Files.isExecutable(mavenHome.resolve("bin/mvn")), "Maven distribution missing: " + mavenHome);
    }

    static Stream<String> cases() throws IOException {
        Path dir = Path.of(System.getProperty("spotless.test.fixtures", "fixtures")).resolve("maven");
        if (!Files.isDirectory(dir)) return Stream.empty();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void nativeMatchesMaven(String caseName, @TempDir Path work) throws Exception {
        Path source = fixtures.resolve(caseName);
        if (caseName.equals("prettier")) {
            Assumptions.assumeTrue(onPath("npm"), "npm not on PATH; prettier fixture skipped");
        }
        Path mavenCopy = work.resolve("maven");
        Path nativeCopy = work.resolve("native");
        copyTree(source, mavenCopy);
        copyTree(source, nativeCopy);
        if (Files.exists(source.resolve("RATCHET"))) {
            // Commit the (unformatted) baseline on `main`, then overlay the edits: only dirty files are targets.
            for (Path copy : List.of(mavenCopy, nativeCopy)) {
                deleteTree(copy.resolve("_edits"));
                try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init().setDirectory(copy.toFile()).setInitialBranch("main").call()) {
                    git.add().addFilepattern(".").call();
                    git.commit().setAuthor("t", "t@t").setCommitter("t", "t@t").setMessage("baseline").call();
                }
                copyTree(source.resolve("_edits"), copy);
            }
        }

        Map<String, byte[]> before = snapshot(mavenCopy);
        runMaven(mavenCopy, "spotless:apply");
        Map<String, byte[]> after = snapshot(mavenCopy);

        PomInfo pom = PomInfo.read(nativeCopy.resolve("pom.xml"));
        SpotlessConfig config = config(nativeCopy, pom);
        WorkerLog log = new WorkerLog();
        log.attach(l -> System.out.println("[worker " + l.level() + "] " + l.message()));
        try (WorkerContext context = new WorkerContext(cacheDir, Path.of(System.getProperty("spotless.worker.test.adapterDir")), log);
             FormatEngine engine = EngineFactory.create(config, context, log)) {
            List<String> warnings = engine.load();
            warnings.forEach(w -> System.out.println("[warning] " + w));

            List<String> problems = new ArrayList<>();
            int formatted = 0;
            for (Map.Entry<String, byte[]> e : before.entrySet()) {
                String rel = e.getKey();
                byte[] mavenResult = after.get(rel);
                boolean mavenChanged = !java.util.Arrays.equals(e.getValue(), mavenResult);
                String original = new String(e.getValue(), StandardCharsets.UTF_8);
                String originalUnix = original.replace("\r\n", "\n");
                Message.FormatResult r = engine.format("1", nativeCopy.resolve(rel).toString(), originalUnix);
                if (mavenChanged) {
                    String expected = new String(mavenResult, StandardCharsets.UTF_8).replace("\r\n", "\n");
                    if (r.status() != FormatStatus.FORMATTED) {
                        problems.add(rel + ": maven changed it but native said " + r.status() + " (" + r.message() + ")");
                    } else if (!expected.equals(r.text())) {
                        problems.add(rel + ": output differs\n--- maven ---\n" + expected + "\n--- native ---\n" + r.text());
                    } else {
                        formatted++;
                    }
                } else {
                    if (r.status() == FormatStatus.FORMATTED) {
                        problems.add(rel + ": maven left it alone but native formatted it to:\n" + r.text());
                    } else if (r.status() == FormatStatus.ERROR || r.status() == FormatStatus.DID_NOT_CONVERGE) {
                        problems.add(rel + ": " + r.status() + " " + r.message());
                    }
                }
            }
            if (!problems.isEmpty()) {
                fail(caseName + ": " + problems.size() + " mismatch(es)\n" + String.join("\n\n", problems));
            }
            assertTrue(formatted > 0, caseName + ": the fixture should contain at least one file Maven reformats");
            System.out.println(caseName + ": " + formatted + " files byte-identical to mvn spotless:apply");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SpotlessConfig config(Path projectDir, PomInfo pom) {
        Map<String, String> props = new LinkedHashMap<>(pom.properties);
        props.put("project.basedir", projectDir.toString());
        props.put("basedir", projectDir.toString());
        props.put("project.build.directory", projectDir.resolve("target").toString());
        SpotlessConfig.Maven maven = new SpotlessConfig.Maven(
                pom.configurationXml, props,
                List.of(projectDir.resolve("src/main/java").toString()),
                List.of(projectDir.resolve("src/test/java").toString()),
                List.of(new SpotlessConfig.Repository("central", "https://repo.maven.apache.org/maven2", true, false)),
                null, null, null, false);
        return new SpotlessConfig(SpotlessConfig.Kind.MAVEN, pom.version, projectDir.toString(),
                projectDir.resolve("target").toString(), maven, Map.of());
    }

    private static void runMaven(Path dir, String... goals) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(mavenHome.resolve("bin/mvn").toString());
        cmd.add("-B");
        cmd.add("-q");
        cmd.add("-e");
        cmd.addAll(List.of(goals));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().put("MAVEN_OPTS", "--add-opens=java.base/java.lang=ALL-UNNAMED");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            fail("mvn timed out in " + dir);
        }
        if (p.exitValue() != 0) {
            fail("mvn " + String.join(" ", goals) + " failed (" + p.exitValue() + ") in " + dir + "\n" + out);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) return true;
        }
        return false;
    }

    /** Relative path → bytes for every regular file except build output. */
    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted().toList()) {
                if (!Files.isRegularFile(p)) continue;
                String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                if (rel.startsWith("target/") || rel.startsWith(".git/") || rel.equals("RATCHET") || rel.startsWith("_edits/")) continue;
                map.put(rel, Files.readAllBytes(p));
            }
        }
        return map;
    }

    /** Plugin-level spotless configuration, version and properties straight from the fixture POM. */
    record PomInfo(String version, String configurationXml, Map<String, String> properties) {
        static PomInfo read(Path pom) throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(Files.readAllBytes(pom)));
            Map<String, String> props = new LinkedHashMap<>();
            NodeList propsNodes = doc.getElementsByTagName("properties");
            for (int i = 0; i < propsNodes.getLength(); i++) {
                NodeList kids = propsNodes.item(i).getChildNodes();
                for (int j = 0; j < kids.getLength(); j++) {
                    if (kids.item(j) instanceof Element e) props.put(e.getTagName(), e.getTextContent().trim());
                }
            }
            NodeList plugins = doc.getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                Element plugin = (Element) plugins.item(i);
                if (!"spotless-maven-plugin".equals(childText(plugin, "artifactId"))) continue;
                String version = childText(plugin, "version");
                Element cfg = null;
                NodeList kids = plugin.getChildNodes();
                for (int j = 0; j < kids.getLength(); j++) {
                    if (kids.item(j) instanceof Element e && e.getTagName().equals("configuration")) cfg = e;
                }
                StringWriter sw = new StringWriter();
                var t = TransformerFactory.newInstance().newTransformer();
                t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                t.transform(new DOMSource(cfg != null ? cfg : doc.createElement("configuration")), new StreamResult(sw));
                return new PomInfo(version, sw.toString(), props);
            }
            throw new IllegalStateException("spotless-maven-plugin not declared in " + pom);
        }

        private static String childText(Element parent, String name) {
            NodeList kids = parent.getChildNodes();
            for (int j = 0; j < kids.getLength(); j++) {
                if (kids.item(j) instanceof Element e && e.getTagName().equals(name)) return e.getTextContent().trim();
            }
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static Node firstElement(NodeList list) {
        return list.getLength() == 0 ? null : list.item(0);
    }
}
