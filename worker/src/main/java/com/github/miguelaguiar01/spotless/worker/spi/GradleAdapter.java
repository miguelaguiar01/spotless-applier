package com.github.miguelaguiar01.spotless.worker.spi;

import com.github.miguelaguiar01.spotless.protocol.Message;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Parent-first boundary for the Gradle adapter: deserializes the steps captured at sync time and
 * formats with them. Only JDK and protocol types cross.
 */
public interface GradleAdapter extends AutoCloseable {
    String IMPLEMENTATION_CLASS = "com.github.miguelaguiar01.spotless.worker.gradle.adapter.GradleAdapterImpl";

    record Format(String name, byte[] steps, byte[] lineEndingPolicy, String encoding, List<String> targetFiles,
                  String ratchetFrom) {}

    record Request(Path projectDir, List<Format> formats, Consumer<String> log) {}

    record Loaded(List<Message.FormatInfo> formats, List<String> warnings, List<Message.FallbackFormat> fallback) {}

    Loaded load(Request request) throws Exception;

    Message.FormatResult format(String requestId, Path file, String unixText) throws Exception;

    @Override
    void close() throws Exception;
}
