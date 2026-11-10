package com.github.miguelaguiar01.spotless.worker.maven.adapter;

import com.diffplug.spotless.DirtyState;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.extra.GitRatchet;
import com.diffplug.spotless.maven.ArtifactResolver;
import com.diffplug.spotless.maven.FileLocator;
import com.diffplug.spotless.maven.FormatterConfig;
import com.diffplug.spotless.maven.FormatterFactory;
import com.diffplug.spotless.maven.FormatterStepFactory;
import com.diffplug.spotless.maven.MavenProvisioner;
import com.diffplug.spotless.maven.SpotlessApplyMojo;
import com.github.miguelaguiar01.spotless.protocol.FormatStatus;
import com.github.miguelaguiar01.spotless.protocol.Message;
import com.github.miguelaguiar01.spotless.worker.spi.MavenAdapter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.configurator.BasicComponentConfigurator;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.resource.DefaultResourceManager;
import org.codehaus.plexus.resource.ResourceManager;
import org.codehaus.plexus.resource.loader.FileResourceLoader;
import org.codehaus.plexus.resource.loader.ResourceLoader;
import org.codehaus.plexus.resource.loader.URLResourceLoader;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.MatchPatterns;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reproduces {@code AbstractSpotlessMojo}'s wiring without a Maven session: the real
 * {@link SpotlessApplyMojo} instance is configured by the Plexus component configurator (exactly
 * what Maven does), its configured {@link FormatterFactory}s are read back reflectively, and the
 * plugin's own {@code newFormatter} builds each {@link Formatter}.
 *
 * <p>Compiled against spotless-maven-plugin 3.11.0; runtime differences across supported versions
 * (2.44.x constructors, absence of lints) are handled reflectively.
 */
public final class MavenAdapterImpl implements MavenAdapter {
    private static final String[] FACTORY_ORDER = {
        // mojo 3.x order after <formats>
        "groovy", "java", "scala", "kotlin", "asciidoc", "cpp", "css", "typescript", "javascript", "antlr4", "pom",
        "sql", "python", "markdown", "json", "shell", "yaml", "gherkin", "go", "rdf", "protobuf", "tableTest", "toml"
    };

    private record Entry(String name, FormatterFactory factory, Formatter formatter,
                         List<String> includes, List<String> excludes,
                         MatchPatterns includePatterns, MatchPatterns excludePatterns) {}

    private Request request;
    private File baseDir;
    private File buildDir;
    private final List<Entry> entries = new ArrayList<>();
    private Optional<String> ratchetFrom = Optional.empty();
    private List<Object> lintSuppressions = List.of();
    private boolean lintsSupported;

    @Override
    public Loaded load(Request req) throws Exception {
        this.request = req;
        this.baseDir = req.baseDir().toFile().getAbsoluteFile();
        this.buildDir = req.buildDir().toFile().getAbsoluteFile();
        List<String> warnings = new ArrayList<>();

        SpotlessApplyMojo mojo = new SpotlessApplyMojo();
        DefaultPlexusConfiguration configuration = XmlToPlexus.parse(req.configurationXml());
        applyDescriptorDefaults(configuration, warnings);
        ClassRealm realm = new ClassWorld().newRealm("spotless-" + Integer.toHexString(System.identityHashCode(this)),
                getClass().getClassLoader());
        new BasicComponentConfigurator().configureComponent(mojo, configuration,
                new SimpleExpressionEvaluator(req.properties(), baseDir), realm);

        String encoding = Objects.requireNonNullElse(field(mojo, "encoding", String.class), "UTF-8");
        LineEnding lineEndings = Objects.requireNonNullElse(field(mojo, "lineEndings", LineEnding.class),
                LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME);
        String ratchet = field(mojo, "ratchetFrom", String.class);
        ratchetFrom = Optional.ofNullable(ratchet).filter(r -> !"NONE".equals(r));
        FormatterStepFactory licenseHeader = field(mojo, "licenseHeader", FormatterStepFactory.class);
        String yearsFromGit = field(mojo, "setLicenseHeaderYearsFromGitHistory", String.class);
        List<?> suppressions = field(mojo, "lintSuppressions", List.class);
        lintSuppressions = suppressions == null ? List.of() : new ArrayList<>(suppressions);
        lintsSupported = classPresent("com.diffplug.spotless.LintState");

        List<FormatterFactory> factories = formatterFactories(mojo);
        for (FormatterFactory f : factories) {
            try {
                Method init = FormatterFactory.class.getMethod("init", org.eclipse.aether.RepositorySystemSession.class);
                init.invoke(f, req.session());
            } catch (NoSuchMethodException ignored) {
                // 2.x without init
            }
        }

        Log log = new ConsumerLog(req.log());
        ArtifactResolver resolver = new ArtifactResolver(req.repositorySystem(), req.session(), req.repositories(), log);
        Provisioner provisioner = MavenProvisioner.create(resolver);
        FileLocator fileLocator = new FileLocator(resourceManager(), baseDir, buildDir);
        List<FormatterStepFactory> globalSteps = licenseHeader == null ? List.of() : List.of(licenseHeader);
        FormatterConfig config = formatterConfig(encoding, lineEndings, ratchetFrom, provisioner, fileLocator, globalSteps,
                Optional.ofNullable(yearsFromGit), lintSuppressions);

        MavenProject project = mavenProject();
        Set<String> defaultExcludes = new HashSet<>(FileUtils.getDefaultExcludesAsList());
        defaultExcludes.add(withTrailingSeparator(buildDir.toString()));

        List<Message.FormatInfo> infos = new ArrayList<>();
        for (FormatterFactory factory : factories) {
            String name = factory.getClass().getSimpleName();
            Set<String> includes = factory.includes().isEmpty() ? factory.defaultIncludes(project) : factory.includes();
            if (includes.isEmpty()) {
                throw new IllegalArgumentException(name + ": you must specify some files to include, such as "
                        + "'<includes><include>src/**/*.blah</include></includes>'");
            }
            Set<String> excludes = new LinkedHashSet<>(defaultExcludes);
            excludes.addAll(factory.excludes());
            List<String> includeList = new ArrayList<>(includes);
            List<String> excludeList = new ArrayList<>(excludes);
            Supplier<Iterable<File>> files = lazyFiles(includeList, excludeList);
            Formatter formatter;
            try {
                formatter = factory.newFormatter(files, config);
            } catch (RuntimeException e) {
                closeAll();
                throw new IllegalStateException("Cannot build formatter '" + name + "': " + rootMessage(e), e);
            }
            entries.add(new Entry(name, factory, formatter, includeList, excludeList,
                    MatchPatterns.from(normalized(includeList)), MatchPatterns.from(normalized(excludeList))));
            infos.add(new Message.FormatInfo(name, includeList, excludeList));
        }
        if (entries.isEmpty()) {
            warnings.add("The Spotless configuration declares no formats; every file will be NOT_COVERED");
        }
        return new Loaded(infos, warnings);
    }

    @Override
    public Message.FormatResult format(String requestId, Path file, String unixText) throws Exception {
        File target = file.toFile().getAbsoluteFile();
        String relative = relativize(target);
        if (relative == null) {
            return new Message.FormatResult(requestId, FormatStatus.NOT_COVERED, null,
                    target + " is outside " + baseDir, List.of());
        }
        List<Entry> matching = entries.stream().filter(e -> covers(e, relative)).toList();
        if (matching.isEmpty()) {
            return new Message.FormatResult(requestId, FormatStatus.NOT_COVERED, null,
                    relative + " matches no <includes> of any format", List.of());
        }
        if (ratchetFrom.isPresent() && isCleanPerRatchet(target)) {
            return Message.FormatResult.clean(requestId);
        }

        String current = unixText;
        List<Message.Lint> lints = new ArrayList<>();
        for (Entry entry : matching) {
            Formatter formatter = entry.formatter();
            Charset charset = formatter.getEncoding();
            byte[] raw = current.getBytes(charset);
            DirtyState dirty;
            if (lintsSupported) {
                dirty = LintSupport.dirtyStateCollectingLints(formatter, target, raw, relative, lintSuppressions, lints, entry.name());
            } else {
                dirty = DirtyState.of(formatter, target, raw);
            }
            if (dirty.didNotConverge()) {
                return new Message.FormatResult(requestId, FormatStatus.DID_NOT_CONVERGE, null,
                        entry.name() + " did not converge on " + relative
                                + " (see https://github.com/diffplug/spotless/blob/main/PADDEDCELL.md)", lints);
            }
            if (!dirty.isClean()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                dirty.writeCanonicalTo(out);
                current = LineEnding.toUnix(out.toString(charset));
            }
        }
        if (current.equals(unixText)) {
            return new Message.FormatResult(requestId, FormatStatus.CLEAN, null, null, lints);
        }
        return new Message.FormatResult(requestId, FormatStatus.FORMATTED, current, null, lints);
    }

    @Override
    public void close() throws Exception {
        closeAll();
    }

    // ------------------------------------------------------------------ wiring helpers

    private void closeAll() {
        Exception error = null;
        for (Entry e : entries) {
            try {
                e.formatter().close();
            } catch (Exception ex) {
                if (error == null) error = ex;
                else error.addSuppressed(ex);
            }
        }
        entries.clear();
        if (error != null) throw new IllegalStateException("Closing formatters failed", error);
    }

    /** Mirrors {@code getFormatterFactories()}: {@code <formats>} first, then language blocks in mojo order. */
    private static List<FormatterFactory> formatterFactories(SpotlessApplyMojo mojo) throws Exception {
        List<FormatterFactory> result = new ArrayList<>();
        List<?> formats = field(mojo, "formats", List.class);
        if (formats != null) {
            for (Object f : formats) if (f instanceof FormatterFactory ff) result.add(ff);
        }
        Set<String> seen = new HashSet<>();
        for (String name : FACTORY_ORDER) {
            Field f = findField(mojo.getClass(), name);
            if (f == null) continue;
            seen.add(name);
            Object value = f.get(mojo);
            if (value instanceof FormatterFactory ff) result.add(ff);
        }
        // Language blocks added in newer plugin versions than this adapter knows about.
        for (Class<?> c = mojo.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (seen.contains(f.getName()) || !FormatterFactory.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object value = f.get(mojo);
                if (value instanceof FormatterFactory ff) result.add(ff);
            }
        }
        return result;
    }

    private FormatterConfig formatterConfig(String encoding, LineEnding lineEndings, Optional<String> ratchetFrom,
                                            Provisioner provisioner, FileLocator fileLocator,
                                            List<FormatterStepFactory> globalSteps, Optional<String> yearsFromGit,
                                            List<Object> suppressions) throws Exception {
        Constructor<?> ctor = FormatterConfig.class.getConstructors()[0];
        int n = ctor.getParameterCount();
        if (n == 12) {
            // 3.x: baseDir, encoding, lineEndings, userRatchetFrom, ratchetFrom, provisioner, p2Provisioner,
            //      fileLocator, globalStepFactories, setLicenseHeaderYearsFromGitHistory, lintSuppressions, projectClasspathSupplier
            Object p2 = Class.forName("com.diffplug.spotless.extra.P2Provisioner").getMethod("createDefault").invoke(null);
            Supplier<Set<File>> classpath = this::projectClasspath;
            return (FormatterConfig) ctor.newInstance(baseDir, encoding, lineEndings, Optional.empty(), ratchetFrom,
                    provisioner, p2, fileLocator, globalSteps, yearsFromGit, suppressions, Optional.of(classpath));
        }
        if (n == 8) {
            // 2.44.x: baseDir, encoding, lineEndings, ratchetFrom, provisioner, fileLocator, globalStepFactories,
            //         setLicenseHeaderYearsFromGitHistory
            return (FormatterConfig) ctor.newInstance(baseDir, encoding, lineEndings, ratchetFrom, provisioner, fileLocator,
                    globalSteps, yearsFromGit);
        }
        throw new IllegalStateException("Unsupported FormatterConfig constructor with " + n + " parameters "
                + "(Spotless version not supported by this adapter)");
    }

    /** Source roots only; dependency jars would need a full project resolution (expandWildcardImports is rare). */
    private Set<File> projectClasspath() {
        Set<File> cp = new LinkedHashSet<>();
        for (String s : request.sourceRoots()) if (new File(s).isDirectory()) cp.add(new File(s));
        for (String s : request.testSourceRoots()) if (new File(s).isDirectory()) cp.add(new File(s));
        request.log().accept("expandWildcardImports: using source roots only as type-solver classpath");
        return cp;
    }

    private MavenProject mavenProject() {
        Model model = new Model();
        Build build = new Build();
        build.setSourceDirectory(request.sourceRoots().isEmpty() ? new File(baseDir, "src/main/java").getPath() : request.sourceRoots().get(0));
        build.setTestSourceDirectory(request.testSourceRoots().isEmpty() ? new File(baseDir, "src/test/java").getPath() : request.testSourceRoots().get(0));
        build.setDirectory(buildDir.getPath());
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(new File(baseDir, "pom.xml"));
        return project;
    }

    private static ResourceManager resourceManager() {
        Map<String, ResourceLoader> loaders = new LinkedHashMap<>();
        loaders.put(FileResourceLoader.ID, new FileResourceLoader());
        loaders.put(URLResourceLoader.ID, new URLResourceLoader());
        return new DefaultResourceManager(loaders);
    }

    private Supplier<Iterable<File>> lazyFiles(List<String> includes, List<String> excludes) {
        return new Supplier<>() {
            private List<File> cached;

            @Override
            public synchronized Iterable<File> get() {
                if (cached == null) {
                    try {
                        cached = FileUtils.getFiles(baseDir, String.join(",", includes), String.join(",", excludes));
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to scan file tree rooted at " + baseDir, e);
                    }
                }
                return cached;
            }
        };
    }

    /** Applies the {@code default-value}s of the apply mojo's descriptor the way Maven merges them. */
    private void applyDescriptorDefaults(DefaultPlexusConfiguration configuration, List<String> warnings) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/maven/plugin.xml")) {
            if (in == null) {
                warnings.add("plugin descriptor META-INF/maven/plugin.xml not found; using built-in defaults");
                addDefault(configuration, "encoding", "UTF-8");
                addDefault(configuration, "lineEndings", "GIT_ATTRIBUTES_FAST_ALLSAME");
                return;
            }
            Document doc = XmlToPlexus.parseDocument(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            NodeList mojos = doc.getElementsByTagName("mojo");
            for (int i = 0; i < mojos.getLength(); i++) {
                Element mojo = (Element) mojos.item(i);
                if (!"apply".equals(text(mojo, "goal"))) continue;
                NodeList cfgs = mojo.getElementsByTagName("configuration");
                if (cfgs.getLength() == 0) break;
                NodeList params = cfgs.item(0).getChildNodes();
                for (int j = 0; j < params.getLength(); j++) {
                    if (!(params.item(j) instanceof Element p)) continue;
                    String dflt = p.getAttribute("default-value");
                    if (dflt == null || dflt.isEmpty() || dflt.contains("${")) continue;
                    addDefault(configuration, p.getTagName(), dflt);
                }
                break;
            }
        } catch (Exception e) {
            warnings.add("Could not read plugin descriptor defaults: " + e.getMessage());
        }
    }

    private static void addDefault(DefaultPlexusConfiguration configuration, String name, String value) {
        if (configuration.getChild(name, false) == null) {
            configuration.addChild(name, value);
        }
    }

    private static String text(Element parent, String child) {
        NodeList nl = parent.getElementsByTagName(child);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent().trim();
    }

    // ------------------------------------------------------------------ matching

    private String relativize(File target) {
        Path base = baseDir.toPath().toAbsolutePath().normalize();
        Path t = target.toPath().toAbsolutePath().normalize();
        if (!t.startsWith(base)) return null;
        return base.relativize(t).toString();
    }

    private static boolean covers(Entry e, String relative) {
        String normalized = relative.replace('/', File.separatorChar).replace('\\', File.separatorChar);
        return e.includePatterns().matches(normalized, true) && !e.excludePatterns().matches(normalized, true);
    }

    private static List<String> normalized(List<String> patterns) {
        return patterns.stream()
                .map(p -> p.replace('/', File.separatorChar).replace('\\', File.separatorChar))
                .collect(Collectors.toList());
    }

    private static String withTrailingSeparator(String path) {
        return path.endsWith(File.separator) ? path : path + File.separator;
    }

    /** {@code ratchetFrom}: a file unchanged since the ref is not a target, exactly like the build. */
    private boolean isCleanPerRatchet(File target) throws IOException {
        try (GitRatchet<File> ratchet = new GitRatchet<>() {
            @Override
            protected File getDir(File project) {
                return project;
            }

            @Override
            protected File getParent(File project) {
                return project.getParentFile();
            }
        }) {
            var sha = ratchet.rootTreeShaOf(baseDir, ratchetFrom.get());
            return ratchet.isClean(baseDir, sha, target);
        } catch (Exception e) {
            throw new IOException("ratchetFrom=" + ratchetFrom.get() + " could not be evaluated: " + rootMessage(e), e);
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field f = findField(target.getClass(), name);
        if (f == null) return null;
        Object v = f.get(target);
        return v == null ? null : type.cast(v);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, MavenAdapterImpl.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root == t ? String.valueOf(t.getMessage()) : t.getMessage() + " (caused by " + root + ")";
    }

    @SuppressWarnings("unused")
    private static List<String> unmodifiable(Collection<String> c) {
        return Collections.unmodifiableList(new ArrayList<>(c));
    }
}
