package com.github.miguelaguiar01.spotless.worker.engine;

import com.github.miguelaguiar01.spotless.protocol.Message;
import java.util.List;

/** One loaded configuration: knows which files it covers and how to format them. */
public interface FormatEngine extends AutoCloseable {

    /** Builds everything needed to format; may be slow. Returns non-fatal warnings. */
    List<String> load() throws Exception;

    List<Message.FormatInfo> formats();

    /** Formats of this config that the build tool must handle (their targets are NOT_COVERED here). */
    default List<Message.FallbackFormat> fallbackFormats() {
        return List.of();
    }

    /** {@code text} has Unix line endings; the result text must too. */
    Message.FormatResult format(String requestId, String path, String text) throws Exception;

    @Override
    void close() throws Exception;

    /** Thrown by {@link #load()} when the configuration must be handled by the build-tool fallback. */
    final class UnsupportedConfigException extends Exception {
        private final String reason;

        public UnsupportedConfigException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** Stable code, e.g. {@code SPOTLESS_VERSION_UNSUPPORTED}. */
        public String reason() {
            return reason;
        }
    }
}
