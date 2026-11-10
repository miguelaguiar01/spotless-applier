package com.github.miguelaguiar01.spotless.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal JSON reader/writer. Supports objects (as {@code Map<String, Object>}), arrays (as
 * {@code List<Object>}), strings, numbers ({@link Long} when integral and in range, otherwise
 * {@link Double}), booleans and {@code null}. No dependencies, deliberately small: it is shared by
 * the IDE plugin and the worker (see ADR-016).
 */
public final class Json {
    private Json() {}

    /** Serializes with insertion order of maps preserved. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, false);
        return sb.toString();
    }

    /** Serializes with map keys sorted, for stable hashing. */
    public static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder sb, boolean canonical) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Cannot serialize non-finite number: " + d);
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map<?, ?> m) {
            Map<String, Object> map = canonical ? new TreeMap<>((Map<String, Object>) m) : (Map<String, Object>) m;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb, canonical);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb, canonical);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            write(List.of(arr), sb, canonical);
        } else if (value instanceof Enum<?> en) {
            writeString(en.name(), sb);
        } else {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getName());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object read(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("Trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object v = read(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            if (pos >= s.length()) throw error("Unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("Expected " + word);
            pos += word.length();
            return value;
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("Expected string key");
                String key = string();
                skipWs();
                if (peek() != ':') throw error("Expected ':'");
                pos++;
                skipWs();
                map.put(key, value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        char peek() {
            if (pos >= s.length()) throw error("Unexpected end");
            return s.charAt(pos);
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw error("Unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw error("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Number number() {
            int start = pos;
            if (peek() == '-') pos++;
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, pos);
            try {
                if (!floating) {
                    return Long.parseLong(text);
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ex2) {
                    throw error("Bad number '" + text + "'");
                }
            }
        }
    }

    // ---- small typed accessors used by message decoding ----

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static String requireStr(Map<String, Object> m, String key) {
        String v = str(m, key);
        if (v == null) throw new IllegalArgumentException("Missing field '" + key + "'");
        return v;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    public static long num(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : dflt;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key)) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> strMap(Map<String, Object> m, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        Object v = m.get(key);
        if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                out.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
            }
        }
        return out;
    }
}
