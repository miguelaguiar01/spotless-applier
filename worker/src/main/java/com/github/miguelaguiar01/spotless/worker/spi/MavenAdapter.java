package com.github.miguelaguiar01.spotless.worker.spi;

import com.github.miguelaguiar01.spotless.protocol.Message;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Boundary between the worker (parent classloader) and the adapter that is compiled against the
 * Spotless Maven plugin API and loaded next to the user's Spotless jars (child classloader).
 * Everything crossing this boundary must be a JDK, protocol, Maven-API or Resolver-API type, which
 * the child loader delegates to the parent.
 */
public interface MavenAdapter extends AutoCloseable {

    /** Fully qualified name of the implementation inside the adapter jar. */
    String IMPLEMENTATION_CLASS = "com.github.miguelaguiar01.spotless.worker.maven.adapter.MavenAdapterImpl";

    record Request(
            Path baseDir,
            Path buildDir,
            /** The effective {@code <configuration>} element of the apply goal. */
            String configurationXml,
            /** Properties for {@code ${...}} evaluation that the IDE could not resolve. */
            Map<String, String> properties,
            List<String> sourceRoots,
            List<String> testSourceRoots,
            RepositorySystem repositorySystem,
            RepositorySystemSession session,
            List<RemoteRepository> repositories,
            /** Receives Maven-plugin log lines (info/warn). */
            Consumer<String> log) {}

    record Loaded(List<Message.FormatInfo> formats, List<String> warnings) {}

    /** Configures the plugin's config objects from XML and builds the Formatters. */
    Loaded load(Request request) throws Exception;

    /** {@code unixText} uses {@code \n}; the returned text does too. */
    Message.FormatResult format(String requestId, Path file, String unixText) throws Exception;

    @Override
    void close() throws Exception;
}
