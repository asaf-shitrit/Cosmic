package bot.memory;

import bot.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonMemoryTest {
    private static final String BOT = "Marigold";

    /**
     * Memory is only available for a bot that already has a directory in the bundle, the same rule
     * {@code ResidentMemory} follows - a bot never invents bundle structure.
     */
    private static Path bundle(Path root) throws IOException {
        Files.createDirectories(root.resolve(BOT));
        return root;
    }

    @Test
    void firstEncounterIsRememberedAndStrangersAreNot(@TempDir Path tmp) throws IOException {
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, MutableClock.at("2026-09-10T18:00:00Z"));

        assertFalse(memory.knows("Shroomz"));
        assertEquals("", memory.recall("Shroomz"), "a stranger must recall nothing at all");

        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", PersonMemory.FRIENDLY, "Said hi.");

        assertTrue(memory.knows("Shroomz"));
        assertTrue(memory.recall("Shroomz").contains("met 1 time"), memory.recall("Shroomz"));
    }

    @Test
    void secondEncounterKeepsFirstMetAndCountsUp(@TempDir Path tmp) throws IOException {
        MutableClock clock = MutableClock.at("2026-09-10T18:00:00Z");
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, clock);
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", "", "");

        clock.advanceMs(4L * 24 * 3600 * 1000);           // four days later
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City PQ", "", "");

        PersonMemory.Entry entry = memory.get("Shroomz").orElseThrow();
        assertEquals(2, entry.metCount());
        assertEquals("2026-09-10T18:00:00Z", entry.firstMet().toString(), "first_met must never move");
        assertEquals("2026-09-14T18:00:00Z", entry.lastSeen().toString());
    }

    @Test
    void recallReadsLikeSomethingABotWouldSay(@TempDir Path tmp) throws IOException {
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, MutableClock.at("2026-09-10T18:00:00Z"));
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City PQ", PersonMemory.FREQUENT_PARTY,
                "Reliable partier, shares drops.");

        String recall = memory.recall("Shroomz");

        assertTrue(recall.startsWith("met 1 time (first 2026-09-10, last 2026-09-10)"), recall);
        assertTrue(recall.contains("around Kerning City PQ"), recall);
        assertTrue(recall.contains("frequent-party"), recall);
        assertTrue(recall.contains("you noted: Reliable partier, shares drops."), recall);
    }

    @Test
    void learningNothingNewDoesNotEraseWhatWeKnew(@TempDir Path tmp) throws IOException {
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, MutableClock.at("2026-09-10T18:00:00Z"));
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", PersonMemory.FRIENDLY, "Plays a priest.");
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, null, null, "   ");

        PersonMemory.Entry entry = memory.get("Shroomz").orElseThrow();
        assertEquals("Plays a priest.", entry.notes());
        assertEquals(PersonMemory.FRIENDLY, entry.relationship());
        assertEquals(1, entry.locations().size(), "a blank location must not be recorded");
    }

    @Test
    void locationsKeepTheRecentOnesAndNeverRepeatTheLatest(@TempDir Path tmp) throws IOException {
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, MutableClock.at("2026-09-10T18:00:00Z"));
        for (String place : new String[]{"Henesys", "Henesys", "Ellinia", "Perion", "Ludibrium", "Orbis", "El Nath"}) {
            memory.record("Shroomz", PersonMemory.KIND_PLAYER, place, "", "");
        }

        PersonMemory.Entry entry = memory.get("Shroomz").orElseThrow();
        assertEquals(5, entry.locations().size(), entry.locations().toString());
        assertEquals("Orbis", entry.locations().get(3), entry.locations().toString());
        assertEquals("El Nath", entry.locations().get(4), "newest location is kept");
        assertFalse(entry.locations().contains("Henesys"), "oldest locations are dropped first");
    }

    @Test
    void anUnreadableEntryIsSkippedWithoutLosingTheOthers(@TempDir Path tmp) throws IOException {
        Path root = bundle(tmp);
        PersonMemory memory = new PersonMemory(root, BOT, MutableClock.at("2026-09-10T18:00:00Z"));
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", "", "");

        Path file = root.resolve(BOT).resolve("acquaintances.md");
        String damaged = Files.readString(file, StandardCharsets.UTF_8)
                + "### Broken (player)\n- first_met: not-a-date\n- last_seen: nope\n- met_count: x\n- locations: []\n"
                + "- relationship: \n- notes: \n\n";
        Files.writeString(file, damaged, StandardCharsets.UTF_8);

        assertTrue(memory.knows("Shroomz"), "a hand-edited entry must not cost the bot its other memories");
        assertFalse(memory.knows("Broken"));
    }

    @Test
    void memorySurvivesAReopen(@TempDir Path tmp) throws IOException {
        Path root = bundle(tmp);
        new PersonMemory(root, BOT, MutableClock.at("2026-09-10T18:00:00Z"))
                .record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", PersonMemory.AVOID, "Kses mobs.");

        PersonMemory reopened = new PersonMemory(root, BOT, MutableClock.at("2026-09-11T09:00:00Z"));

        assertTrue(reopened.knows("shroomz"), "the game treats names case-insensitively, so memory should too");
        assertEquals("Kses mobs.", reopened.get("Shroomz").orElseThrow().notes());
    }

    @Test
    void theWrittenFileMatchesTheBundleTypeDefinition(@TempDir Path tmp) throws IOException {
        Path root = bundle(tmp);
        new PersonMemory(root, BOT, MutableClock.at("2026-09-10T18:00:00Z"))
                .record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", PersonMemory.FRIENDLY, "Said hi.");

        String text = Files.readString(root.resolve(BOT).resolve("acquaintances.md"), StandardCharsets.UTF_8);

        assertTrue(text.contains("type: Acquaintance Log"), text);
        assertTrue(text.contains("bot_id: " + BOT), text);
        assertTrue(text.contains("### Shroomz (player)"), text);
        assertTrue(text.contains("- first_met: 2026-09-10T18:00:00Z"), text);
        assertTrue(text.contains("- met_count: 1"), text);
        assertTrue(text.contains("- locations: [Kerning City]"), text);
        assertTrue(text.contains("- relationship: friendly"), text);
        assertTrue(text.contains("- notes: Said hi."), text);
        assertFalse(Files.exists(root.resolve(BOT).resolve("acquaintances.md.tmp")), "no temporary file is left behind");
    }

    @Test
    void aNoteIsAlwaysOneLineSoTheNextReadCanParseIt(@TempDir Path tmp) throws IOException {
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, MutableClock.at("2026-09-10T18:00:00Z"));
        memory.record("Shroomz", PersonMemory.KIND_PLAYER, "Kerning City", "", "Told me about\nthe Ant Tunnel.\n");

        assertEquals("Told me about the Ant Tunnel.", memory.get("Shroomz").orElseThrow().notes());
    }

    @Test
    void namesThatCouldNotBeCharactersAreRefused(@TempDir Path tmp) throws IOException {
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, MutableClock.at("2026-09-10T18:00:00Z"));

        assertTrue(memory.record("ab", PersonMemory.KIND_PLAYER, "x", "", "").isEmpty(), "too short to be a name here");
        assertTrue(memory.record("thirteenchars", PersonMemory.KIND_PLAYER, "x", "", "").isEmpty(), "over 12 characters");
        assertTrue(memory.record("has space", PersonMemory.KIND_PLAYER, "x", "", "").isEmpty());
        assertTrue(memory.record(null, PersonMemory.KIND_PLAYER, "x", "", "").isEmpty());
    }

    @Test
    void aBotIdCannotEscapeTheBundle(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("bot-memory"));

        PersonMemory escaping = new PersonMemory(tmp.resolve("bot-memory"), "../evil", MutableClock.at("2026-09-10T18:00:00Z"));
        assertFalse(escaping.available());
        assertTrue(escaping.record("Shroomz", PersonMemory.KIND_PLAYER, "x", "", "").isEmpty());
        assertEquals("", escaping.recall("Shroomz"));
    }

    @Test
    void aServerWithNoBundleSimplyHasNoMemory(@TempDir Path tmp) {
        PersonMemory none = new PersonMemory(null, BOT, MutableClock.at("2026-09-10T18:00:00Z"));
        PersonMemory missing = new PersonMemory(tmp.resolve("nowhere"), BOT, MutableClock.at("2026-09-10T18:00:00Z"));

        assertFalse(none.available());
        assertFalse(missing.available());
        assertEquals("", none.recall("Shroomz"));
        assertTrue(missing.record("Shroomz", PersonMemory.KIND_PLAYER, "x", "", "").isEmpty());
        assertEquals(Optional.empty(), missing.get("Shroomz"));
    }

    @Test
    void theOldestAcquaintanceIsEvictedOnceMemoryIsFull(@TempDir Path tmp) throws IOException {
        MutableClock clock = MutableClock.at("2026-09-10T18:00:00Z");
        PersonMemory memory = new PersonMemory(bundle(tmp), BOT, clock);
        for (int i = 0; i < PersonMemory.MAX_ENTRIES; i++) {
            memory.record("Player" + String.format("%03d", i), PersonMemory.KIND_PLAYER, "Henesys", "", "");
            clock.advanceMs(60_000);
        }

        memory.record("Newcomer", PersonMemory.KIND_PLAYER, "Henesys", "", "");

        assertTrue(memory.knows("Newcomer"));
        assertFalse(memory.knows("Player000"), "the longest-unseen entry makes room for the new one");
        assertEquals(PersonMemory.MAX_ENTRIES, Files.readAllLines(tmp.resolve(BOT).resolve("acquaintances.md"))
                .stream().filter(l -> l.startsWith("### ")).count());
    }
}
