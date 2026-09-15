package bot.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KnowledgeRetrieverTest {

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Path fixture(Path dir) throws IOException {
        write(dir.resolve("index.md"), "# kb\n* [training](training/index.md)\n* [towns](locations/index.md)\n"
                + "* [elsewhere](../bot-memory/index.md)\n");
        write(dir.resolve("training/index.md"), "* [10-20](training-10-20.md)\n* [20-30](training-20-30.md)\n* [draft](draft-20-30.md)\n");
        write(dir.resolve("training/training-10-20.md"), """
                ---
                type: Training Route
                title: Training levels 10-20
                verified:
                  - { by: "process:server-script-crosscheck", at: 2026-09-14T00:00:00Z }
                level_min: 10
                level_max: 20
                jobs: [all]
                map_ids: [100040000, 101010100]
                ---
                # Training 10-20
                Pigs and slimes.
                """);
        write(dir.resolve("training/training-20-30.md"), """
                ---
                type: Training Route
                title: Training levels 20-30
                verified:
                  - { by: "process:server-script-crosscheck", at: 2026-09-14T00:00:00Z }
                level_min: 20
                level_max: 30
                jobs: [all]
                map_ids: [105050000, 101030400]
                ---
                # Training 20-30
                Ant Tunnel.
                """);
        write(dir.resolve("training/draft-20-30.md"), """
                ---
                type: Training Route
                title: Unverified 20-30 notes
                level_min: 20
                level_max: 30
                jobs: [all]
                map_ids: [999]
                ---
                draft
                """);
        write(dir.resolve("locations/index.md"), "* [Perion](town-perion.md)\n* [missing](nope.md)\n");
        write(dir.resolve("locations/town-perion.md"), """
                ---
                type: Location
                title: Perion
                verified:
                  - { by: "process:server-wz-crosscheck", at: 2026-09-14T00:00:00Z }
                map_ids: [102000000]
                jobs: [warrior]
                ---
                Warrior town.
                """);
        return dir;
    }

    @Test
    void filtersByLevelAndPrefersVerifiedPages(@TempDir Path dir) throws IOException {
        KnowledgeRetriever kr = new KnowledgeRetriever(fixture(dir));
        List<KnowledgeRetriever.Excerpt> hits = kr.retrieve(KnowledgeRetriever.Query.of("thief", 25, 0), 5, 400);
        assertEquals(List.of("training/training-20-30.md", "training/draft-20-30.md"), hits.stream().map(KnowledgeRetriever.Excerpt::path).toList());
        assertTrue(hits.get(0).verified());
    }

    @Test
    void currentMapAndJobPullInLocationPages(@TempDir Path dir) throws IOException {
        KnowledgeRetriever kr = new KnowledgeRetriever(fixture(dir));
        List<KnowledgeRetriever.Excerpt> hits = kr.retrieve(KnowledgeRetriever.Query.of("warrior", 25, 102000000), 5, 400);
        assertEquals("locations/town-perion.md", hits.get(0).path(), "map match outranks everything");
        List<KnowledgeRetriever.Excerpt> thief = kr.retrieve(KnowledgeRetriever.Query.of("thief", 25, 100000000), 5, 400);
        assertFalse(thief.stream().anyMatch(e -> e.path().contains("perion")), "wrong job and wrong map: not relevant");
    }

    @Test
    void sectionFilterAndLinksOutsideTheBundleAreIgnored(@TempDir Path dir) throws IOException {
        KnowledgeRetriever kr = new KnowledgeRetriever(fixture(dir));
        List<KnowledgeRetriever.Excerpt> hits = kr.retrieve(new KnowledgeRetriever.Query("warrior", 15, 102000000, Set.of("training")), 5, 400);
        assertEquals(List.of("training/training-10-20.md"), hits.stream().map(KnowledgeRetriever.Excerpt::path).toList());
        assertEquals(List.of(100040000, 101010100), hits.get(0).mapIds());
    }

    @Test
    void missingBundleIsSimplyEmpty(@TempDir Path dir) {
        assertTrue(new KnowledgeRetriever(dir.resolve("absent")).retrieve(KnowledgeRetriever.Query.of("all", 10, 0), 3, 100).isEmpty());
    }

    /** Against the real corpus when it is checked out next to the server, e.g. on the dev machine. */
    @Test
    void realBundleReturnsTheRightTrainingPage() {
        Path real = Path.of("..", "bot-knowledge");
        if (!Files.isDirectory(real)) {
            real = Path.of("bot-knowledge");
        }
        assumeTrue(Files.isRegularFile(real.resolve("index.md")), "bot-knowledge not available here");
        KnowledgeRetriever kr = new KnowledgeRetriever(real);
        List<KnowledgeRetriever.Excerpt> hits = kr.retrieve(new KnowledgeRetriever.Query("warrior", 25, 105050000, Set.of("training")), 3, 300);
        assertEquals("training/training-20-30.md", hits.get(0).path());
        assertTrue(hits.get(0).verified());
        assertTrue(hits.get(0).mapIds().contains(105050000));
    }
}
