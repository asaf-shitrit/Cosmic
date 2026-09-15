package bot.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void roundTripsNestedValues() {
        Map<String, Object> value = Map.of("a", List.of(1L, 2.5, "x\"y\n"), "b", true);
        Object parsed = Json.parse(Json.write(value));
        assertEquals(value, parsed);
    }

    @Test
    void lenientParseDigsTheObjectOutOfAMarkdownFence() {
        Map<String, Object> m = Json.parseObjectLenient("Sure!\n```json\n{\"plans\":{\"Mira\":{\"objective\":\"Idle\"}}}\n```");
        assertEquals("Idle", Json.asObject(Json.asObject(m.get("plans"), "p").get("Mira"), "m").get("objective"));
    }

    @Test
    void malformedInputThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObjectLenient("no json here"));
        assertThrows(IllegalArgumentException.class, () -> Json.asLong(1.5, "n"));
    }

    @Test
    void unicodeEscapesDecode() {
        assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
    }
}
