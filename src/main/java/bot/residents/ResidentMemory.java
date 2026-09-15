package bot.residents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A resident's history in {@code bot-memory/residents/<name>/}: an {@code episodes.md} Episode Log
 * (one entry per wake - what sold, what it bought, who talked to it) and an {@code acquaintances.md}
 * of players it has spoken with. Only what the game does not model; never stock, mesos or level.
 *
 * <p>Follows the bundle's compaction policy: the most recent {@link #KEEP_EPISODES} episodes stay
 * verbatim, older ones are folded into a one-line gist at the top, so the file (and the planner prompt
 * built from it) stays small no matter how long the server runs.
 *
 * <p>Writes happen on the resident's thread, once per wake; each resident owns its own files.
 */
final class ResidentMemory {
    private static final Logger log = LoggerFactory.getLogger(ResidentMemory.class);
    static final int KEEP_EPISODES = 20;
    private static final Pattern ENTRY = Pattern.compile("(?m)^### (\\S+) - (\\S+)\\n((?:(?!### ).*\\n?)*)");
    private static final Pattern FOLDED = Pattern.compile("Folded (\\d+) earlier episodes? from (\\S+) to (\\S+)");

    private final Path dir;
    private final String name;

    ResidentMemory(Path memoryRoot, String name) {
        this.dir = memoryRoot == null ? null : memoryRoot.resolve("residents").resolve(name);
        this.name = name;
    }

    record Episode(String timestamp, String kind, String summary) {}

    boolean available() {
        return dir != null && Files.isDirectory(dir);
    }

    /** The newest {@code n} episode summaries, newest first - for the planner's context. */
    List<String> recent(int n) {
        List<Episode> episodes = readEpisodes();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, episodes.size()); i++) {
            out.add(episodes.get(i).timestamp() + " " + episodes.get(i).summary());
        }
        return out;
    }

    void append(Instant at, String kind, String summary) {
        if (!available()) {
            return;
        }
        List<Episode> episodes = readEpisodes();
        episodes.add(0, new Episode(at.truncatedTo(ChronoUnit.SECONDS).toString(), kind, summary.replace('\n', ' ').trim()));
        String gist = readGist();
        if (episodes.size() > KEEP_EPISODES) {
            List<Episode> folded = episodes.subList(KEEP_EPISODES, episodes.size());
            gist = fold(gist, folded);
            episodes = new ArrayList<>(episodes.subList(0, KEEP_EPISODES));
        }
        write("episodes.md", render(episodes, gist));
    }

    /** Folds old episodes into the running gist: how many, over what span. Summaries of folded wakes are dropped. */
    static String fold(String gist, List<Episode> folded) {
        int count = folded.size();
        String from = folded.get(folded.size() - 1).timestamp();
        String to = folded.get(0).timestamp();
        Matcher m = gist == null ? null : FOLDED.matcher(gist);
        if (m != null && m.find()) {
            count += Integer.parseInt(m.group(1));
            from = m.group(2);
        }
        return "Folded " + count + " earlier episode" + (count == 1 ? "" : "s") + " from " + from + " to " + to
                + ": routine wakes tending the shop.";
    }

    private List<Episode> readEpisodes() {
        List<Episode> list = new ArrayList<>();
        String text = read("episodes.md");
        if (text == null) {
            return list;
        }
        int recent = text.indexOf("## Recent episodes");
        Matcher m = ENTRY.matcher(recent < 0 ? text : text.substring(recent));
        while (m.find()) {
            list.add(new Episode(m.group(1), m.group(2), m.group(3).trim()));
        }
        return list;
    }

    private String readGist() {
        String text = read("episodes.md");
        if (text == null) {
            return null;
        }
        Matcher m = FOLDED.matcher(text);
        return m.find() ? text.substring(m.start(), text.indexOf('\n', m.start()) < 0 ? text.length() : text.indexOf('\n', m.start())) : null;
    }

    private String render(List<Episode> episodes, String gist) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\ntype: Episode Log\ntitle: Episodes - ").append(name)
                .append("\ndescription: What resident ").append(name).append(" did on its recent wakes in the Free Market.\n")
                .append("tags: [episodes, resident]\ntimestamp: ").append(episodes.isEmpty() ? Instant.now() : episodes.get(0).timestamp())
                .append("\ngenerated: { by: \"process:resident-session\", at: ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS))
                .append(" }\nstatus: stable\nbot_id: ").append(name).append("\n---\n\n# Episodes - ").append(name).append("\n\n")
                .append("## Summary of compacted history\n\n").append(gist == null ? "Nothing compacted yet." : gist).append("\n\n")
                .append("## Recent episodes (most recent first, kept verbatim)\n\n");
        for (Episode e : episodes) {
            sb.append("### ").append(e.timestamp()).append(" - ").append(e.kind()).append('\n').append(e.summary()).append("\n\n");
        }
        return sb.toString();
    }

    /** Records that {@code player} talked to this resident. Returns what it already remembered, for a reply prompt. */
    String metPlayer(String player, Instant at) {
        if (!available() || !player.matches("[A-Za-z0-9]{3,12}")) {
            return "";
        }
        String text = read("acquaintances.md");
        List<String[]> rows = new ArrayList<>();   // name, first_met, last_seen, count
        if (text != null) {
            Matcher m = Pattern.compile("(?m)^### (\\S+) \\(player\\)\\n- first_met: (\\S+)\\n- last_seen: (\\S+)\\n- met_count: (\\d+)").matcher(text);
            while (m.find()) {
                rows.add(new String[]{m.group(1), m.group(2), m.group(3), m.group(4)});
            }
        }
        String now = at.truncatedTo(ChronoUnit.SECONDS).toString();
        String remembered = "";
        String[] row = rows.stream().filter(r -> r[0].equalsIgnoreCase(player)).findFirst().orElse(null);
        if (row == null) {
            rows.add(new String[]{player, now, now, "1"});
        } else {
            remembered = "has talked to you " + row[3] + " time(s) before, first on " + row[1];
            row[2] = now;
            row[3] = String.valueOf(Integer.parseInt(row[3]) + 1);
        }
        StringBuilder sb = new StringBuilder("---\ntype: Acquaintance Log\ntitle: Acquaintances - " + name
                + "\ndescription: Players who have talked to resident " + name + ".\ntags: [acquaintances, resident]\ntimestamp: " + now
                + "\nstatus: stable\nbot_id: " + name + "\n---\n\n# Acquaintances - " + name + "\n\n");
        for (String[] r : rows) {
            sb.append("### ").append(r[0]).append(" (player)\n- first_met: ").append(r[1]).append("\n- last_seen: ").append(r[2])
                    .append("\n- met_count: ").append(r[3]).append("\n\n");
        }
        write("acquaintances.md", sb.toString());
        return remembered;
    }

    private String read(String file) {
        if (!available()) {
            return null;
        }
        Path p = dir.resolve(file);
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
        } catch (IOException e) {
            log.warn("Couldn't read {}", p, e);
            return null;
        }
    }

    private void write(String file, String content) {
        try {
            Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Couldn't write {} for resident {}", file, name, e);
        }
    }
}
