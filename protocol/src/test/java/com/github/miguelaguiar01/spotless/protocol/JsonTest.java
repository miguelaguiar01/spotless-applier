package com.github.miguelaguiar01.spotless.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedStructures() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "tab\t quote\" backslash\\ newline\n unicode \u2028 é");
        m.put("n", 42L);
        m.put("d", 1.5);
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of(1L, "two", List.of(), Map.of()));
        String json = Json.write(m);
        assertEquals(m, Json.readObject(json));
    }

    @Test
    void canonicalSortsKeys() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", Map.of("y", 2L, "x", 3L));
        assertEquals("{\"a\":{\"x\":3,\"y\":2},\"b\":1}", Json.writeCanonical(m));
    }

    @Test
    void parsesNumbersAndLiterals() {
        assertEquals(-12L, Json.read("-12"));
        assertEquals(1.0e3, Json.read("1e3"));
        assertEquals(Boolean.FALSE, Json.read(" false "));
        assertNull(Json.read("null"));
        assertEquals("\u00e9", Json.read("\"\\u00e9\""));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.read("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("\"unterminated"));
    }
}
