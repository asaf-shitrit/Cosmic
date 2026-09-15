package bot.planning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One OKF v0.2 markdown file: YAML frontmatter between {@code ---} fences, then a markdown body.
 *
 * <p>Only the frontmatter shapes the bundles actually use are understood - {@code key: scalar},
 * {@code key: [a, b]}, an inline {@code key: { ... }} kept as raw text, and a block list of
 * {@code - item} lines under a bare {@code key:}. That is enough for retrieval filters
 * ({@code level_min}, {@code jobs}, {@code map_ids}, {@code verified}) and for resident profiles, and
 * avoids a YAML library choking on one odd value in a 50-file corpus.
 */
public final class OkfDocument {
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s#]+\\.md)\\)");

    private final Path path;
    private final Map<String, Object> frontmatter;
    private final String body;

    private OkfDocument(Path path, Map<String, Object> frontmatter, String body) {
        this.path = path;
        this.frontmatter = frontmatter;
        this.body = body;
    }

    public static OkfDocument read(Path path) throws IOException {
        return parse(path, Files.readString(path, StandardCharsets.UTF_8));
    }

    public static OkfDocument parse(Path path, String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new OkfDocument(path, Map.of(), normalized);
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return new OkfDocument(path, Map.of(), normalized);
        }
        String yaml = normalized.substring(4, end);
        int bodyStart = normalized.indexOf('\n', end + 4);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
        return new OkfDocument(path, parseFrontmatter(yaml), body);
    }

    private static Map<String, Object> parseFrontmatter(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        String currentListKey = null;
        for (String line : yaml.split("\n")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            String trimmed = line.trim();
            if (indented || trimmed.startsWith("- ")) {
                if (currentListKey != null && trimmed.startsWith("- ")) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) result.get(currentListKey);
                    list.add(unquote(trimmed.substring(2).trim()));
                }
                continue;       // nested map lines under a key (e.g. sources entries) are not needed
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                result.put(key, new ArrayList<>());
                currentListKey = key;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<Object> list = new ArrayList<>();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        list.add(unquote(part.trim()));
                    }
                }
                result.put(key, list);
                currentListKey = null;
            } else {
                result.put(key, unquote(value));
                currentListKey = null;
            }
        }
        return result;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public Path path() {
        return path;
    }

    public String body() {
        return body;
    }

    public String string(String key, String fallback) {
        Object v = frontmatter.get(key);
        return v instanceof String s ? s : fallback;
    }

    public Integer integer(String key) {
        Object v = frontmatter.get(key);
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public List<String> list(String key) {
        Object v = frontmatter.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    /** OKF marks a page cross-checked by a non-empty {@code verified} list. */
    public boolean verified() {
        return !list("verified").isEmpty();
    }

    /** Relative {@code .md} link targets in the body, in order - how an {@code index.md} names its children. */
    public List<String> links() {
        List<String> out = new ArrayList<>();
        Matcher m = LINK.matcher(body);
        while (m.find()) {
            String target = m.group(1);
            if (!target.startsWith("http") && !out.contains(target)) {
                out.add(target);
            }
        }
        return out;
    }
}
