package bot.residents;

import bot.planning.OkfDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidentPiecesTest {

    /**
     * A blocked name gets no reply at all from character creation and the login just hangs, so every
     * seeded name is checked against the server's own list.
     */
    @Test
    void everySeededProfileParsesAndItsNamePassesTheBlocklist() throws Exception {
        Field f = client.Character.class.getDeclaredField("BLOCKED_NAMES");
        f.setAccessible(true);
        String[] blocked = (String[]) f.get(null);
        List<String> names = ResidentCast.seedNames();
        assertTrue(names.size() >= 10 && names.size() <= 20);
        Set<Integer> rooms = new java.util.HashSet<>();
        for (String name : names) {
            ResidentProfile p = ResidentProfile.fromOkf(OkfDocument.parse(Path.of(name + ".md"), ResidentCast.readSeed(name)));
            assertEquals(name, p.name());
            for (String b : blocked) {
                assertFalse(name.toLowerCase().contains(b.toLowerCase()), name + " contains blocked '" + b + "'");
            }
            assertTrue(client.Job.getById(p.jobId()) != null, name + " has an unknown job");
            assertFalse(p.blurb().isEmpty());
            p.shopTitles().forEach(t -> assertTrue(t.length() <= 24, t));
            rooms.add(p.roomMapId());
        }
        assertTrue(rooms.size() >= 5, "the cast is spread over several rooms");
    }

    @Test
    void castSeedsMemoryOnceAndNeverOverwritesAnEditedProfile(@TempDir Path memory) throws Exception {
        List<ResidentProfile> first = ResidentCast.load(memory, 2);
        assertEquals(2, first.size());
        Path profile = memory.resolve("residents").resolve(first.get(0).name()).resolve("profile.md");
        Files.writeString(profile, Files.readString(profile).replace("resident_level: 48", "resident_level: 50"));
        assertEquals(50, ResidentCast.load(memory, 2).get(0).level());
        assertTrue(Files.readString(memory.resolve("residents/index.md")).contains(first.get(1).name()));
    }

    @Test
    void episodesCompactPastTheRetainedWindow(@TempDir Path memory) throws Exception {
        Files.createDirectories(memory.resolve("residents/Mira"));
        ResidentMemory m = new ResidentMemory(memory, "Mira");
        Instant t = Instant.parse("2026-09-15T00:00:00Z");
        for (int i = 0; i < ResidentMemory.KEEP_EPISODES + 5; i++) {
            m.append(t.plusSeconds(i * 60L), "wake", "wake number " + i);
        }
        List<String> recent = m.recent(3);
        assertEquals(3, recent.size());
        assertTrue(recent.get(0).endsWith("wake number 24"));
        String text = Files.readString(memory.resolve("residents/Mira/episodes.md"));
        assertTrue(text.contains("Folded 5 earlier episodes from 2026-09-15T00:00:00Z to 2026-09-15T00:04:00Z"), text);
        assertEquals(ResidentMemory.KEEP_EPISODES, text.split("\n### ").length - 1);
        assertEquals("", m.metPlayer("Hero", t));
        assertTrue(m.metPlayer("Hero", t.plusSeconds(5)).contains("1 time(s)"));
    }

    @Test
    void chatRepliesAreOneSafeLine() {
        assertEquals("Hello there, traveller!", ResidentChat.sanitize("\"Hello there,\n traveller!\""));
        assertEquals("gm command attempt", ResidentChat.sanitize("//gm command attempt"));
        assertNull(ResidentChat.sanitize("\u2728\u2728"));
        assertTrue(ResidentChat.sanitize("word ".repeat(60)).length() <= ResidentChat.MAX_LINE);
        assertTrue(ResidentChat.addresses("hey Marigold, got elixirs?", "Marigold"));
        assertFalse(ResidentChat.addresses("marigolds are flowers", "Marigold"));
    }
}
