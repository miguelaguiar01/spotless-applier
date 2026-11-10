package com.github.miguelaguiar01.spotless.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protocol messages. Each is a JSON object with a {@code type} field; requests carry an {@code id}
 * that the response echoes. See docs/ARCHITECTURE.md, "IPC protocol".
 */
public sealed interface Message {

    String type();

    Map<String, Object> toJson();

    default String encode() {
        return Json.write(toJson());
    }

    /** Request id, or {@code null} for unsolicited messages. */
    default String id() {
        return null;
    }

    // ------------------------------------------------------------------ handshake / lifecycle

    record Hello(int protocolVersion, Map<String, String> info) implements Message {
        public static final String TYPE = "hello";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, null);
            m.put("protocolVersion", protocolVersion);
            m.put("info", info);
            return m;
        }
    }

    record Ping(String id) implements Message {
        public static final String TYPE = "ping";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            return base(TYPE, id);
        }
    }

    record Pong(String id) implements Message {
        public static final String TYPE = "pong";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            return base(TYPE, id);
        }
    }

    record Shutdown(String id) implements Message {
        public static final String TYPE = "shutdown";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            return base(TYPE, id);
        }
    }

    /** Generic positive acknowledgement for requests without a dedicated response. */
    record Ack(String id) implements Message {
        public static final String TYPE = "ack";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            return base(TYPE, id);
        }
    }

    /** Protocol-level failure (unknown type, bad payload, internal error) for request {@code id}. */
    record Error(String id, String message) implements Message {
        public static final String TYPE = "error";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, id);
            m.put("message", message);
            return m;
        }
    }

    record Log(String level, String message, String throwable) implements Message {
        public static final String TYPE = "log";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, null);
            m.put("level", level);
            m.put("message", message);
            m.put("throwable", throwable);
            return m;
        }
    }

    // ------------------------------------------------------------------ configuration

    record LoadConfig(String id, SpotlessConfig config) implements Message {
        public static final String TYPE = "loadConfig";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, id);
            m.put("configId", config.configId());
            m.put("config", config.toJson());
            return m;
        }
    }

    record FormatInfo(String name, List<String> includes, List<String> excludes) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("includes", includes);
            m.put("excludes", excludes);
            return m;
        }

        static FormatInfo fromJson(Map<String, Object> m) {
            return new FormatInfo(Json.requireStr(m, "name"), Json.strList(m, "includes"), Json.strList(m, "excludes"));
        }
    }

    /** A format the worker cannot run; its target files must go through the build tool. */
    record FallbackFormat(String name, String reason, List<String> targetFiles) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("reason", reason);
            m.put("targetFiles", targetFiles);
            return m;
        }

        static FallbackFormat fromJson(Map<String, Object> m) {
            return new FallbackFormat(Json.requireStr(m, "name"), Json.str(m, "reason"), Json.strList(m, "targetFiles"));
        }
    }

    /**
     * Result of {@link LoadConfig}. If {@code error} is non-null the config is unusable and the IDE
     * must fall back; {@code fallbackReason} is a stable machine-readable code. {@code fallbackFormats}
     * lists formats of an otherwise usable config that the build tool must handle.
     */
    record ConfigLoaded(String id, String configId, List<FormatInfo> formats, List<String> warnings,
                        String error, String fallbackReason, List<FallbackFormat> fallbackFormats) implements Message {
        public static final String TYPE = "configLoaded";

        public ConfigLoaded(String id, String configId, List<FormatInfo> formats, List<String> warnings,
                            String error, String fallbackReason) {
            this(id, configId, formats, warnings, error, fallbackReason, List.of());
        }

        @Override
        public String type() {
            return TYPE;
        }

        public boolean isOk() {
            return error == null;
        }

        /** True when {@code path} is a target of a format the build tool must handle. */
        public boolean needsBuildTool(String path) {
            for (FallbackFormat f : fallbackFormats) {
                if (f.targetFiles().contains(path)) return true;
            }
            return false;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, id);
            m.put("configId", configId);
            List<Object> fs = new ArrayList<>();
            for (FormatInfo f : formats) fs.add(f.toJson());
            m.put("formats", fs);
            m.put("warnings", warnings);
            m.put("error", error);
            m.put("fallbackReason", fallbackReason);
            List<Object> fb = new ArrayList<>();
            for (FallbackFormat f : fallbackFormats) fb.add(f.toJson());
            m.put("fallbackFormats", fb);
            return m;
        }
    }

    record Invalidate(String id, String configId) implements Message {
        public static final String TYPE = "invalidate";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, id);
            m.put("configId", configId);
            return m;
        }
    }

    // ------------------------------------------------------------------ formatting

    /**
     * Format {@code text} (Unix line endings) as if it were the content of {@code path} (absolute,
     * worker-side path).
     */
    record Format(String id, String configId, String path, String text) implements Message {
        public static final String TYPE = "format";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, id);
            m.put("configId", configId);
            m.put("path", path);
            m.put("text", text);
            return m;
        }
    }

    record Lint(String step, long line, String message) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step);
            m.put("line", line);
            m.put("message", message);
            return m;
        }

        static Lint fromJson(Map<String, Object> m) {
            return new Lint(Json.str(m, "step"), Json.num(m, "line", -1), Json.str(m, "message"));
        }
    }

    /**
     * {@code text} always uses {@code \n}; {@code lineEnding} is the separator the formatter's
     * line-ending policy would write for this file ({@code "\n"}, {@code "\r\n"}, …) or null if unknown.
     */
    record FormatResult(String id, FormatStatus status, String text, String message, List<Lint> lints, String lineEnding)
            implements Message {
        public static final String TYPE = "formatResult";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = base(TYPE, id);
            m.put("status", status.name());
            m.put("text", text);
            m.put("message", message);
            List<Object> ls = new ArrayList<>();
            for (Lint l : lints) ls.add(l.toJson());
            m.put("lints", ls);
            m.put("lineEnding", lineEnding);
            return m;
        }

        public FormatResult(String id, FormatStatus status, String text, String message, List<Lint> lints) {
            this(id, status, text, message, lints, null);
        }

        public static FormatResult clean(String id) {
            return new FormatResult(id, FormatStatus.CLEAN, null, null, List.of(), null);
        }

        public static FormatResult formatted(String id, String text, List<Lint> lints) {
            return new FormatResult(id, FormatStatus.FORMATTED, text, null, lints, null);
        }

        public static FormatResult error(String id, String message) {
            return new FormatResult(id, FormatStatus.ERROR, null, message, List.of(), null);
        }
    }

    record Cancel(String id) implements Message {
        public static final String TYPE = "cancel";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Map<String, Object> toJson() {
            return base(TYPE, id);
        }
    }

    // ------------------------------------------------------------------ codec

    private static Map<String, Object> base(String type, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (id != null) m.put("id", id);
        return m;
    }

    static Message decode(String json) {
        return fromJson(Json.readObject(json));
    }

    @SuppressWarnings("unchecked")
    static Message fromJson(Map<String, Object> m) {
        String type = Json.requireStr(m, "type");
        String id = Json.str(m, "id");
        switch (type) {
            case Hello.TYPE:
                return new Hello((int) Json.num(m, "protocolVersion", -1), Json.strMap(m, "info"));
            case Ping.TYPE:
                return new Ping(id);
            case Pong.TYPE:
                return new Pong(id);
            case Shutdown.TYPE:
                return new Shutdown(id);
            case Ack.TYPE:
                return new Ack(id);
            case Error.TYPE:
                return new Error(id, Json.str(m, "message"));
            case Log.TYPE:
                return new Log(Json.str(m, "level"), Json.str(m, "message"), Json.str(m, "throwable"));
            case LoadConfig.TYPE: {
                Map<String, Object> cfg = Json.obj(m, "config");
                if (cfg == null) throw new IllegalArgumentException("loadConfig without config");
                return new LoadConfig(id, SpotlessConfig.fromJson(cfg));
            }
            case ConfigLoaded.TYPE: {
                List<FormatInfo> formats = new ArrayList<>();
                for (Object o : Json.list(m, "formats")) formats.add(FormatInfo.fromJson((Map<String, Object>) o));
                List<FallbackFormat> fallback = new ArrayList<>();
                for (Object o : Json.list(m, "fallbackFormats")) fallback.add(FallbackFormat.fromJson((Map<String, Object>) o));
                return new ConfigLoaded(id, Json.str(m, "configId"), formats, Json.strList(m, "warnings"),
                        Json.str(m, "error"), Json.str(m, "fallbackReason"), fallback);
            }
            case Invalidate.TYPE:
                return new Invalidate(id, Json.requireStr(m, "configId"));
            case Format.TYPE:
                return new Format(id, Json.requireStr(m, "configId"), Json.requireStr(m, "path"),
                        Json.requireStr(m, "text"));
            case FormatResult.TYPE: {
                List<Lint> lints = new ArrayList<>();
                for (Object o : Json.list(m, "lints")) lints.add(Lint.fromJson((Map<String, Object>) o));
                return new FormatResult(id, FormatStatus.valueOf(Json.requireStr(m, "status")), Json.str(m, "text"),
                        Json.str(m, "message"), lints, Json.str(m, "lineEnding"));
            }
            case Cancel.TYPE:
                return new Cancel(id);
            default:
                throw new IllegalArgumentException("Unknown message type '" + type + "'");
        }
    }
}
