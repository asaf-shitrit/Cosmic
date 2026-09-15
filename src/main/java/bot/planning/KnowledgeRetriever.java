package bot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieval over {@code bot-knowledge}, the OKF v0.2 bundle, by walking its {@code index.md} tree and
 * filtering on frontmatter - no embeddings. The corpus is ~50 small, hand-curated pages whose
 * frontmatter already says which levels, jobs and maps each one is about, so an exact filter is
 * instant, free and debuggable (README section 8 records why vector search was rejected).
 *
 * <p>Scoring, highest first: the page names the bot's current map (+5), its level range contains the
 * bot's level (+3), its {@code jobs} list names the bot's job line or {@code all} (+2), it is
 * {@code verified} against server data (+1). A page with a level range that excludes the bot is dropped
 * outright (training for the wrong level is wrong, not merely less relevant), and a page must earn
 * more than the verified point alone to be returned. Only a few trimmed excerpts go into a prompt.
 *
 * <p>Pages are parsed once and cached; the bundle is static at runtime.
 */
public final class KnowledgeRetriever {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    public record Query(String jobLine, int level, int mapId, Set<String> sections) {
        public static Query of(String jobLine, int level, int mapId) {
            return new Query(jobLine, level, mapId, Set.of());
        }
    }

    public record Excerpt(String path, String title, String type, boolean verified, int score, List<Integer> mapIds,
                          String text) {}

    private final Path root;
    private final Map<Path, OkfDocument> documents = new ConcurrentHashMap<>();
    private volatile List<Path> pageOrder;

    public KnowledgeRetriever(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public boolean available() {
        return Files.isRegularFile(root.resolve("index.md"));
    }

    /**
     * @param maxPages        at most this many excerpts
     * @param maxCharsPerPage body text is cut here, at a line boundary where possible
     */
    public List<Excerpt> retrieve(Query query, int maxPages, int maxCharsPerPage) {
        if (!available()) {
            return List.of();
        }
        List<Excerpt> scored = new ArrayList<>();
        for (Path page : pages()) {
            OkfDocument doc = documents.get(page);
            String relative = root.relativize(page).toString().replace('\\', '/');
            String section = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "";
            if (!query.sections().isEmpty() && !query.sections().contains(section)) {
                continue;
            }
            int score = score(doc, query);
            if (score <= 1) {
                continue;
            }
            scored.add(new Excerpt(relative, doc.string("title", relative), doc.string("type", ""), doc.verified(), score,
                    mapIds(doc), trim(doc.body(), maxCharsPerPage)));
        }
        scored.sort(Comparator.comparingInt(Excerpt::score).reversed()
                .thenComparing(Excerpt::verified, Comparator.reverseOrder())
                .thenComparing(Excerpt::path));
        return scored.size() > maxPages ? List.copyOf(scored.subList(0, maxPages)) : List.copyOf(scored);
    }

    static int score(OkfDocument doc, Query query) {
        int score = 0;
        Integer min = doc.integer("level_min");
        Integer max = doc.integer("level_max");
        if (min != null || max != null) {
            int lo = min == null ? 0 : min;
            int hi = max == null ? Integer.MAX_VALUE : max;
            if (query.level() < lo || query.level() > hi) {
                return 0;
            }
            score += 3;
        }
        List<String> jobs = doc.list("jobs");
        if (jobs.contains("all") || (query.jobLine() != null && jobs.contains(query.jobLine()))) {
            score += 2;
        }
        if (query.mapId() > 0 && mapIds(doc).contains(query.mapId())) {
            score += 5;
        }
        if (doc.verified()) {
            score += 1;
        }
        return score;
    }

    private static List<Integer> mapIds(OkfDocument doc) {
        List<Integer> ids = new ArrayList<>();
        for (String s : doc.list("map_ids")) {
            try {
                ids.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // a malformed id in one page shouldn't hide the page
            }
        }
        return ids;
    }

    /**
     * Content pages reachable from the root {@code index.md} through index links. A linked
     * {@code index.md} is descended into; any other linked page is a leaf. Parsed once.
     */
    private List<Path> pages() {
        List<Path> cached = pageOrder;
        if (cached != null) {
            return cached;
        }
        List<Path> result = new ArrayList<>();
        walk(root.resolve("index.md"), new HashSet<>(), result, 0);
        pageOrder = List.copyOf(result);
        log.info("Knowledge bundle at {}: {} pages indexed", root, result.size());
        return pageOrder;
    }

    private void walk(Path index, Set<Path> seen, List<Path> out, int depth) {
        Path normalized = index.toAbsolutePath().normalize();
        if (depth > 6 || !normalized.startsWith(root) || !seen.add(normalized) || !Files.isRegularFile(normalized)) {
            return;
        }
        OkfDocument doc = load(normalized);
        if (doc == null) {
            return;
        }
        for (String link : doc.links()) {
            Path target = normalized.getParent().resolve(link).normalize();
            if (!target.startsWith(root)) {
                continue;       // e.g. ../bot-memory/index.md - another bundle
            }
            if (target.getFileName().toString().equals("index.md")) {
                walk(target, seen, out, depth + 1);
            } else if (seen.add(target) && Files.isRegularFile(target) && load(target) != null) {
                out.add(target);
            }
        }
    }

    private OkfDocument load(Path path) {
        OkfDocument doc = documents.get(path);
        if (doc != null) {
            return doc;
        }
        try {
            doc = OkfDocument.read(path);
            documents.put(path, doc);
            return doc;
        } catch (IOException e) {
            log.warn("Couldn't read knowledge page {}", path, e);
            return null;
        }
    }

    private static String trim(String body, int maxChars) {
        String text = body.strip();
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf('\n', maxChars);
        return (cut > maxChars / 2 ? text.substring(0, cut) : text.substring(0, maxChars)) + "\n...";
    }
}
