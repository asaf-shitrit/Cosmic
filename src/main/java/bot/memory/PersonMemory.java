package bot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One bot's acquaintances: the players and other bots it has met, in
 * {@code bot-memory/<bot_id>/acquaintances.md}. This is the {@code Acquaintance} type from
 * {@code bot-memory/_types/acquaintance.md}, which the bundle calls the highest-value type in the whole
 * memory model - a bot that greets a returning player by name is worth more to how the world feels than
 * any amount of planning sophistication.
 *
 * <p><b>Only what the game does not model.</b> Never a level, job, map, inventory or meso count: those
 * live in the server's database and are always ground truth. This file holds what the database cannot -
 * that this player was met, where, how often, and what the bot thought of them.
 *
 * <p><b>Writes are atomic.</b> The bundle is read back to build the next prompt, and a half-written file
 * would poison every wake after it; {@link #write} therefore lands a temporary file and moves it into
 * place. One thread owns one bot's file, so no locking is needed beyond that.
 *
 * <p><b>Unavailable is a normal state, not an error.</b> A server with no {@code bot-memory} directory,
 * or a bot whose id cannot be a path segment, simply has no memory: every call here degrades to "knows
 * nobody". A bot's mission must never fail because its memory could not be written.
 *
 * <p>{@code bot.residents.ResidentMemory} implements the same idea for Free Market residents, with a
 * narrower field set and its own {@code residents/} layout. It predates this class; folding it onto this
 * one is a follow-up, deliberately not done here so the verified resident path is left untouched.
 */
public final class PersonMemory {
    private static final Logger log = LoggerFactory.getLogger(PersonMemory.class);

    static final String FILE = "acquaintances.md";

    /** Entries beyond this are evicted least-recently-seen first, so a long-lived bot's file stays bounded. */
    public static final int MAX_ENTRIES = 60;
    /** Locations kept per entry, newest kept, oldest dropped. */
    static final int KEEP_LOCATIONS = 5;
    static final int MAX_NOTE_CHARS = 200;

    public static final String KIND_PLAYER = "player";
    public static final String KIND_BOT = "bot";

    /** The whole relationship vocabulary. Kept small on purpose: planners match on these strings. */
    public static final String FRIENDLY = "friendly";
    public static final String FREQUENT_PARTY = "frequent-party";
    public static final String TRADE_PARTNER = "trade-partner";
    public static final String AVOID = "avoid";

    /** MapleStory's own limit; anything longer could not be a character name on this server. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9]{3,12}");
    /** A bot id becomes a directory name, so it may not contain separators or traversal. */
    private static final Pattern VALID_BOT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final Pattern ENTRY = Pattern.compile(
            "(?m)^### (\\S+) \\((player|bot)\\)\\n"
                    + "- first_met: (\\S+)\\n"
                    + "- last_seen: (\\S+)\\n"
                    + "- met_count: (\\d+)\\n"
                    + "- locations: \\[(.*)]\\n"
                    + "- relationship: (\\S*)\\n"
                    + "- notes: (.*)$");

    /**
     * @param name         the other character's name, as {@code SPAWN_PLAYER} spelled it
     * @param kind         {@link #KIND_PLAYER} or {@link #KIND_BOT}
     * @param firstMet     when this bot first recorded them - never moves once set
     * @param lastSeen     the last encounter
     * @param metCount     encounters, incremented by every {@link #record}
     * @param locations    recent places met, newest last
     * @param relationship one of the {@code FRIENDLY}/{@code FREQUENT_PARTY}/{@code TRADE_PARTNER}/
     *                     {@code AVOID} vocabulary, or the empty string when unknown
     * @param notes        a sentence or two of what the bot would actually remember, never a transcript
     */
    public record Entry(String name, String kind, Instant firstMet, Instant lastSeen, int metCount,
                        List<String> locations, String relationship, String notes) {}

    private final Path dir;
    private final String botId;
    private final Clock clock;

    /**
     * @param memoryRoot the {@code bot-memory} bundle directory, or null when the server has none
     * @param botId      this bot's directory within the bundle; invalid ids disable memory rather than
     *                   escaping the bundle
     */
    public PersonMemory(Path memoryRoot, String botId, Clock clock) {
        this.botId = botId;
        this.clock = clock;
        boolean usable = memoryRoot != null && botId != null && VALID_BOT_ID.matcher(botId).matches();
        this.dir = usable ? memoryRoot.resolve(botId) : null;
    }

    /** Whether this bot can read and write memory at all. False is normal and never an error. */
    public boolean available() {
        return dir != null && Files.isDirectory(dir);
    }

    /** The bundle directory backing this memory, for logs and tests; null when unavailable. */
    public Optional<Path> directory() {
        return Optional.ofNullable(dir);
    }

    /** Cheap "have I met this one before" check - the exact query the type definition asks callers to make cheap. */
    public boolean knows(String name) {
        return find(name) != null;
    }

    public Optional<Entry> get(String name) {
        return Optional.ofNullable(find(name));
    }

    /**
     * A one-line, prompt-ready recollection of {@code name}, or the empty string for a stranger.
     *
     * <p>Written to be dropped straight into a model prompt, so it is short, factual and free of
     * markdown: the bot reading it is deciding whether to greet someone like an old friend.
     */
    public String recall(String name) {
        Entry e = find(name);
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("met ").append(e.metCount()).append(e.metCount() == 1 ? " time" : " times")
                .append(" (first ").append(day(e.firstMet())).append(", last ").append(day(e.lastSeen())).append(')');
        if (!e.locations().isEmpty()) {
            sb.append(", around ").append(String.join(", ", e.locations()));
        }
        if (!e.relationship().isEmpty()) {
            sb.append(", ").append(e.relationship());
        }
        if (!e.notes().isEmpty()) {
            sb.append("; you noted: ").append(e.notes());
        }
        return sb.toString();
    }

    /**
     * Records an encounter, creating the entry if this is the first one. {@code firstMet} is preserved,
     * {@code metCount} always increments, and {@code note} replaces what was remembered only when it says
     * something - a caller that learned nothing new this time must not erase what it knew.
     *
     * @return the entry as it was stored, or empty when memory is unavailable or {@code name} could not
     * be a character name on this server
     */
    public Optional<Entry> record(String name, String kind, String location, String relationship, String note) {
        if (name == null || !VALID_NAME.matcher(name).matches() || !available()) {
            return Optional.empty();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Entry> entries = readAll();
        Entry old = entries.get(name.toLowerCase(Locale.ROOT));
        Entry updated = new Entry(
                old == null ? name : old.name(),
                KIND_BOT.equals(kind) ? KIND_BOT : KIND_PLAYER,
                old == null ? now : old.firstMet(),
                now,
                old == null ? 1 : old.metCount() + 1,
                locations(old, location),
                sanitizeRelationship(relationship, old),
                sanitizeNote(note, old));
        entries.put(updated.name().toLowerCase(Locale.ROOT), updated);
        evictLongestUnseen(entries);
        write(entries);
        return Optional.of(updated);
    }

    private static List<String> locations(Entry old, String location) {
        List<String> kept = old == null ? new ArrayList<>() : new ArrayList<>(old.locations());
        if (location == null || location.isBlank()) {
            return kept;
        }
        String place = location.strip();
        // Same place twice in a row is the common case (a bot laps the same town); don't spend the
        // entry's whole location budget on it.
        if (!kept.isEmpty() && kept.get(kept.size() - 1).equals(place)) {
            return kept;
        }
        kept.add(place);
        while (kept.size() > KEEP_LOCATIONS) {
            kept.remove(0);
        }
        return kept;
    }

    private static String sanitizeRelationship(String relationship, Entry old) {
        if (relationship == null || relationship.isBlank()) {
            return old == null ? "" : old.relationship();
        }
        String value = relationship.strip();
        return switch (value) {
            case FRIENDLY, FREQUENT_PARTY, TRADE_PARTNER, AVOID -> value;
            default -> old == null ? "" : old.relationship();
        };
    }

    private static String sanitizeNote(String note, Entry old) {
        if (note == null || note.isBlank()) {
            return old == null ? "" : old.notes();
        }
        // One line, always: the entry parser is line-oriented, and a multi-line note would be silently
        // truncated at the next field on the following read.
        String collapsed = note.replaceAll("\\s+", " ").strip();
        if (collapsed.length() <= MAX_NOTE_CHARS) {
            return collapsed;
        }
        int cut = collapsed.lastIndexOf(' ', MAX_NOTE_CHARS);
        return collapsed.substring(0, cut > 40 ? cut : MAX_NOTE_CHARS).strip();
    }

    private static void evictLongestUnseen(Map<String, Entry> entries) {
        while (entries.size() > MAX_ENTRIES) {
            String oldestKey = null;
            Instant oldest = null;
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                if (oldest == null || e.getValue().lastSeen().isBefore(oldest)) {
                    oldest = e.getValue().lastSeen();
                    oldestKey = e.getKey();
                }
            }
            entries.remove(oldestKey);
        }
    }

    private Entry find(String name) {
        if (name == null) {
            return null;
        }
        return readAll().get(name.toLowerCase(Locale.ROOT));
    }

    /** Entries as stored, keyed by lower-cased name so lookups are case-insensitive like the game's own are. */
    private Map<String, Entry> readAll() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (!available()) {
            return entries;
        }
        String text;
        try {
            text = Files.readString(dir.resolve(FILE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return entries;             // no file yet is the normal first-run case
        }
        Matcher m = ENTRY.matcher(text);
        while (m.find()) {
            try {
                String name = m.group(1);
                entries.put(name.toLowerCase(Locale.ROOT), new Entry(
                        name,
                        m.group(2),
                        Instant.parse(m.group(3)),
                        Instant.parse(m.group(4)),
                        Integer.parseInt(m.group(5)),
                        splitLocations(m.group(6)),
                        m.group(7),
                        m.group(8)));
            } catch (RuntimeException e) {
                // A hand-edited or half-written entry: skip that one, keep the rest. Losing one
                // acquaintance beats losing a bot's whole memory.
                log.warn("Skipping unreadable acquaintance entry for bot {}: {}", botId, m.group(1), e);
            }
        }
        return entries;
    }

    private static List<String> splitLocations(String raw) {
        List<String> places = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                places.add(part.strip());
            }
        }
        return places;
    }

    private void write(Map<String, Entry> entries) {
        if (!available()) {
            return;
        }
        Path file = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        try {
            Files.writeString(tmp, render(entries), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Couldn't write {}", file, e);
        }
    }

    private String render(Map<String, Entry> entries) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        StringBuilder sb = new StringBuilder();
        sb.append("---\ntype: Acquaintance Log\ntitle: Acquaintances - ").append(botId)
                .append("\ndescription: Players and bots this bot has met, and what it remembers about them.\n")
                .append("tags: [acquaintances, memory, social]\ntimestamp: ").append(now)
                .append("\ngenerated: { by: \"process:person-memory\", at: ").append(now)
                .append(" }\nstatus: stable\nbot_id: ").append(botId)
                .append("\n---\n\n# Acquaintances - ").append(botId).append("\n\n");
        for (Entry e : entries.values()) {
            sb.append("### ").append(e.name()).append(" (").append(e.kind()).append(")\n")
                    .append("- first_met: ").append(e.firstMet()).append('\n')
                    .append("- last_seen: ").append(e.lastSeen()).append('\n')
                    .append("- met_count: ").append(e.metCount()).append('\n')
                    .append("- locations: [").append(String.join(", ", e.locations())).append("]\n")
                    .append("- relationship: ").append(e.relationship()).append('\n')
                    .append("- notes: ").append(e.notes()).append("\n\n");
        }
        return sb.toString();
    }

    /** Dates, not instants: a bot recalling a friend says "last week", not a timestamp. */
    private static String day(Instant instant) {
        return instant.truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10);
    }
}
