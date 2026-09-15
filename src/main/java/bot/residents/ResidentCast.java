package bot.residents;

import bot.planning.OkfDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed cast of residents.
 *
 * <p>{@code bot-memory/residents/<name>/profile.md} is the source of truth, so an operator can retune a
 * resident's personality or speciality by editing markdown. The jar carries a seed copy of each profile
 * ({@code residents/cast/}); a seed is written into {@code bot-memory} only when that resident has no
 * profile there yet, and never overwrites one. Without a {@code bot-memory} directory at all the seeds
 * are used directly, so the feature still works on a server that doesn't mount the bundle.
 */
public final class ResidentCast {
    private static final Logger log = LoggerFactory.getLogger(ResidentCast.class);
    private static final String SEED_DIR = "residents/cast/";

    private ResidentCast() {
    }

    public static List<ResidentProfile> load(Path memoryRoot, int castSize) {
        List<String> names = seedNames();
        List<ResidentProfile> cast = new ArrayList<>();
        boolean memory = memoryRoot != null && Files.isDirectory(memoryRoot);
        for (String name : names) {
            if (cast.size() >= castSize) {
                break;
            }
            try {
                String seed = readSeed(name);
                OkfDocument doc;
                if (memory) {
                    Path profile = memoryRoot.resolve("residents").resolve(name).resolve("profile.md");
                    if (!Files.isRegularFile(profile)) {
                        Files.createDirectories(profile.getParent());
                        Files.writeString(profile, seed, StandardCharsets.UTF_8);
                        log.info("Seeded resident profile {}", profile);
                    }
                    doc = OkfDocument.read(profile);
                } else {
                    doc = OkfDocument.parse(Path.of(SEED_DIR + name + ".md"), seed);
                }
                cast.add(ResidentProfile.fromOkf(doc));
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping resident {}: its profile couldn't be loaded", name, e);
            }
        }
        if (memory) {
            writeIndex(memoryRoot, cast);
        }
        return List.copyOf(cast);
    }

    static List<String> seedNames() {
        try {
            List<String> names = new ArrayList<>();
            for (String line : readResource(SEED_DIR + "index.txt").split("\n")) {
                String n = line.trim();
                if (!n.isEmpty() && !n.startsWith("#")) {
                    names.add(n);
                }
            }
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("resident seed index missing from the jar", e);
        }
    }

    static String readSeed(String name) throws IOException {
        return readResource(SEED_DIR + name + ".md");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = ResidentCast.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /** OKF wants an index per directory; this one is regenerated from the loaded cast each start. */
    private static void writeIndex(Path memoryRoot, List<ResidentProfile> cast) {
        StringBuilder sb = new StringBuilder("# residents\n\nThe Free Market resident traders. One directory per resident: "
                + "`profile.md` is its stable identity (hand-editable), `episodes.md` its recent wakes, compacted automatically.\n\n");
        for (ResidentProfile p : cast) {
            sb.append("* [").append(p.name()).append("](").append(p.name()).append("/profile.md) - ")
                    .append(p.focus()).append(", FM room ").append(p.roomMapId() - 910000000).append('\n');
        }
        try {
            Files.writeString(memoryRoot.resolve("residents").resolve("index.md"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Couldn't write bot-memory/residents/index.md", e);
        }
    }
}
