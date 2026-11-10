package com.github.miguelaguiar01.spotless.protocol;

import java.util.Map;

/** Public entry points for decoding (the sealed interface keeps its statics package-private). */
public final class Messages {
    private Messages() {}

    public static Message decode(String json) {
        return Message.decode(json);
    }

    public static Message fromJson(Map<String, Object> json) {
        return Message.fromJson(json);
    }
}
