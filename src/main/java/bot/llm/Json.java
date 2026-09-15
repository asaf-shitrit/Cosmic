package bot.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader/writer for the handful of small documents the LLM layer exchanges: the
 * OpenRouter request/response bodies and the objective the model returns. The server has no JSON
 * library on its classpath and one small, strict parser is cheaper than a new dependency in a fat jar.
 *
 * <p>Values map to {@code Map<String,Object>} (insertion-ordered), {@code List<Object>}, {@code String},
 * {@code Double}/{@code Long}, {@code Boolean} and {@code null}. Anything malformed throws
 * {@link IllegalArgumentException}; callers treat that exactly like an invalid objective and fall back.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue(0);
        json.skipWhitespace();
        if (json.pos != text.length()) {
            throw new IllegalArgumentException("trailing content at " + json.pos);
        }
        return value;
    }

    /**
     * Models often wrap JSON in a markdown fence or add a sentence around it despite being told not
     * to. Parses the outermost {@code {...}} span instead of failing the whole plan over that.
     */
    public static Map<String, Object> parseObjectLenient(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no content");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in content");
        }
        Object value = parse(text.substring(start, end + 1));
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static final int MAX_DEPTH = 32;

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting too deep");
        }
        if (pos >= text.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("expected key at " + pos);
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or } at " + (pos - 1));
            }
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or ] at " + (pos - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String token = text.substring(start, pos);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("unexpected character at " + start);
        }
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number " + token);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw new IllegalArgumentException("unexpected token at " + pos);
        }
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("expected " + c + " at " + (pos - 1));
        }
    }

    // ---- writing ----

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Double d -> sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
            case Float f -> sb.append(f.isNaN() || f.isInfinite() ? "null" : f.toString());
            case Number n -> sb.append(n.longValue());
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            }
            default -> writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- typed access helpers for validation code ----

    public static long asLong(Object value, String field) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d)) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return n.longValue();
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    public static double asDouble(Object value, String field) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    public static String asString(Object value, String field) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(field + " must be a string");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value, String field) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(field + " must be an object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value, String field) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException(field + " must be an array");
    }
}
